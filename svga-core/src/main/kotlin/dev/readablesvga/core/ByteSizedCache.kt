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
    private val dispose: (V) -> Unit,
) : AutoCloseable {
    private class Entry<V : Any>(val owner: ResourceLease<V>, val bytes: Long)

    private val lock = Any()
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    private var cachedBytes = 0L
    private var closed = false

    init {
        require(maxBytes >= 0) { "Cache capacity must be nonnegative" }
    }

    fun acquire(key: K): ResourceLease<V>? = synchronized(lock) {
        checkOpen()
        entries[key]?.owner?.fork()
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
                return@synchronized existing.owner.fork()
            }
            val owner = ResourceLease.own(value, dispose)
            val acquired = owner.fork()
            entries.remove(key)?.let { previous ->
                cachedBytes -= previous.bytes
                retired += previous.owner
            }
            if (maxBytes > 0 && bytes <= maxBytes) {
                // Make room before addition so Long byte accounting cannot overflow.
                retireToSize(maxBytes - bytes, retired)
                entries[key] = Entry(owner, bytes)
                cachedBytes += bytes
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

    fun clear() = trimToSize(0)

    /** Drops cache leases to this byte target. This does not change configured capacity. */
    fun trimToSize(targetBytes: Long) {
        require(targetBytes >= 0) { "Trim target must be nonnegative" }
        val retired = ArrayList<ResourceLease<V>>()
        synchronized(lock) {
            retireToSize(targetBytes, retired)
            // Zero-sized entries should also leave a cleared cache.
            if (targetBytes == 0L) {
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
