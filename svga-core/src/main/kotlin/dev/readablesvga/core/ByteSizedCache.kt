// SPDX-License-Identifier: Apache-2.0
package dev.readablesvga.core

/** A snapshot of cache-owned bytes; live client leases are not included after eviction. */
data class CacheStats(val cachedBytes: Long, val maxBytes: Long, val entryCount: Int)

/**
 * An LRU cache that owns one lease per entry and returns a separate client lease.
 * Eviction relinquishes cache ownership without invalidating active clients.
 *
 * [sizeOf] is evaluated once per insertion; resources and their size must stay
 * stable while cached. Charge at least one byte if zero-cost entries must be
 * bounded. [maxBytes] bounds cache ownership, not all memory retained by clients.
 * [dispose] must not throw; it runs outside cache/reference locks.
 */
class ByteSizedCache<K : Any, V : Any>(
    private val maxBytes: Long,
    private val sizeOf: (V) -> Long,
    private val retainActive: Boolean,
    private val maxIdleBytes: Long = maxBytes,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onIdle: () -> Unit = {},
    private val dispose: (V) -> Unit,
) : AutoCloseable {
    constructor(maxBytes: Long, sizeOf: (V) -> Long, dispose: (V) -> Unit) :
        this(maxBytes, sizeOf, false, dispose = dispose)

    private class Entry<V : Any>(val owner: ResourceLease<V>, val bytes: Long)

    private val lock = Any()
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    private val idle = LinkedHashMap<K, Long>()
    private var activeTrimTarget = maxBytes
    private var cachedBytes = 0L
    private var closed = false

    init {
        require(maxIdleBytes >= 0)
        require(maxBytes >= 0) { "Cache capacity must be nonnegative" }
    }

    fun acquire(key: K): ResourceLease<V>? = synchronized(lock) {
        checkOpen()
        entries[key]?.owner?.fork()?.also { idle.remove(key) }
    }

    /**
     * Transfers a new value to cache/client ownership. Validation or a closed
     * cache rejects the value without disposing it. A value too large to cache
     * still has a valid returned client lease. Do not transfer a resource that
     * is already owned by another cache or lease.
     */
    fun putAndAcquire(key: K, value: V): ResourceLease<V> {
        val bytes = sizeOf(value)
        require(bytes >= 0) { "Resource size must be nonnegative" }
        val retired = ArrayList<ResourceLease<V>>()
        val client = synchronized(lock) {
            checkOpen()
            val existing = entries[key]
            if (existing != null && existing.owner.value === value) {
                idle.remove(key)
                return@synchronized existing.owner.fork()
            }
            val owner = if (retainActive) ResourceLease.observed(value, dispose) { becameIdle(key, value) }
            else ResourceLease.own(value, dispose)
            val acquired = owner.fork()
            idle.remove(key)
            entries.remove(key)?.let { previous ->
                cachedBytes -= previous.bytes
                retired += previous.owner
            }
            activeTrimTarget = maxBytes
            val pinnedBytes = if (retainActive) cachedBytes - idle.keys.sumOf { entries.getValue(it).bytes } else 0L
            if (maxBytes > 0 && bytes <= maxBytes - pinnedBytes) {
                // Make room before addition so Long byte accounting cannot overflow.
                retireToSize(maxBytes - bytes, retired)
                if (cachedBytes <= Long.MAX_VALUE - bytes) {
                    entries[key] = Entry(owner, bytes)
                    cachedBytes += bytes
                } else retired += owner
            } else {
                retired += owner
            }
            acquired
        }
        try {
            closeAll(retired)
        } catch (failure: Throwable) {
            // Even a broken disposer must not strand an unreturned client lease.
            try {
                client.close()
            } catch (cleanupFailure: Throwable) {
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
        return client
    }

    fun clear() {
        val retired = synchronized(lock) {
            checkOpen()
            entries.values.map { it.owner }.also {
                entries.clear()
                idle.clear()
                cachedBytes = 0
            }
        }
        closeAll(retired)
    }

    private fun becameIdle(key: K, value: V) {
        val retired = ArrayList<ResourceLease<V>>()
        synchronized(lock) {
            val entry = entries[key] ?: return
            if (entry.owner.value !== value || !entry.owner.isSoleOwner()) return
            idle.putIfAbsent(key, clockMillis())
            retireToSize(activeTrimTarget, retired)
        }
        closeAll(retired)
        if (synchronized(lock) { idle.isNotEmpty() }) onIdle()
    }

    /** Removes only entries idle for at least [ageMillis]; returns whether idle entries remain. */
    fun expireIdle(ageMillis: Long): Boolean {
        require(ageMillis >= 0)
        val retired = ArrayList<ResourceLease<V>>()
        val remains = synchronized(lock) {
            val now = clockMillis()
            val iterator = idle.entries.iterator()
            while (iterator.hasNext()) {
                val (key, since) = iterator.next()
                if (now - since < ageMillis) continue
                val entry = entries.remove(key)!!
                iterator.remove()
                cachedBytes -= entry.bytes
                retired += entry.owner
            }
            idle.isNotEmpty()
        }
        closeAll(retired)
        return remains
    }

    /** Drops cache leases to this byte target. This does not change configured capacity. */
    fun trimToSize(targetBytes: Long) {
        require(targetBytes >= 0) { "Trim target must be nonnegative" }
        val retired = ArrayList<ResourceLease<V>>()
        synchronized(lock) {
            activeTrimTarget = minOf(targetBytes, maxBytes)
            retireToSize(targetBytes, retired)
            // Zero-sized entries should also leave a cleared cache.
            if (!retainActive && targetBytes == 0L) {
                retired += entries.values.map { it.owner }
                entries.clear()
            }
        }
        closeAll(retired)
    }

    fun stats(): CacheStats = synchronized(lock) {
        CacheStats(cachedBytes, maxBytes, entries.size)
    }

    override fun close() {
        val retired = synchronized(lock) {
            if (closed) return
            closed = true
            entries.values.map { it.owner }.also {
                entries.clear()
                idle.clear()
                cachedBytes = 0
            }
        }
        closeAll(retired)
    }

    private fun checkOpen() {
        check(!closed) { "Cache is closed" }
    }

    /** Must be called while holding [lock]. It only detaches cache ownership. */
    private fun retireToSize(targetBytes: Long, retired: MutableList<ResourceLease<V>>) {
        if (retainActive) {
            var idleBytes = idle.keys.sumOf { entries.getValue(it).bytes }
            val iterator = idle.keys.iterator()
            while ((idleBytes > maxIdleBytes || cachedBytes > targetBytes || targetBytes == 0L && activeTrimTarget == 0L) && iterator.hasNext()) {
                val key = iterator.next()
                val entry = checkNotNull(entries.remove(key))
                iterator.remove()
                cachedBytes -= entry.bytes
                idleBytes -= entry.bytes
                retired += entry.owner
            }
            return
        }
        val iterator = entries.entries.iterator()
        while (cachedBytes > targetBytes && iterator.hasNext()) {
            val entry = iterator.next().value
            cachedBytes -= entry.bytes
            retired += entry.owner
            iterator.remove()
        }
    }

    private fun closeAll(leases: List<ResourceLease<V>>) {
        var failure: Throwable? = null
        for (lease in leases) {
            try {
                lease.close()
            } catch (next: Throwable) {
                if (failure == null) failure = next
                else if (failure !== next) failure.addSuppressed(next)
            }
        }
        failure?.let { throw it }
    }
}
