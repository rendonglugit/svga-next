// SPDX-License-Identifier: Apache-2.0
package dev.readablesvga.runtime

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.readablesvga.core.ByteSizedCache
import dev.readablesvga.core.CacheStats
import dev.readablesvga.core.ResourceLease
import dev.readablesvga.player.SVGAVideoEntity

/**
 * Loads prepared animations with size-aware caching and in-flight request sharing.
 *
 * Each successful callback receives its own lease and must close it after detaching
 * the drawable. Cache eviction and loader close never revoke delivered leases.
 * The cache budget measures cache ownership, not total native or leased memory.
 */
class SvgaLoader internal constructor(
    context: Context,
    config: SvgaConfig,
    private val decoder: VideoDecoder,
    private val dispose: (SVGAVideoEntity) -> Unit = { it.clear() },
) : AutoCloseable, ComponentCallbacks2 {
    constructor(context: Context, config: SvgaConfig = SvgaConfig()) :
        this(context, config, ParserVideoDecoder(context))

    private val application = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var closed = false
    private val flights = HashMap<String, Flight>()
    private val pending = HashSet<Waiter>()
    private val cache = ByteSizedCache<String, SVGAVideoEntity>(
        config.memoryCacheBytes,
        { it.estimatedMemoryBytes.coerceAtLeast(1L) },
        ::releaseVideo,
    )

    init {
        application.registerComponentCallbacks(this)
    }

    /** Callback delivery always occurs on the main Looper, including a cache hit or failure. */
    fun load(request: SvgaRequest, callback: (Result<ResourceLease<SVGAVideoEntity>>) -> Unit): CancelHandle {
        val waiter = Waiter(callback)
        val accepted = synchronized(lock) {
            if (closed) false
            else {
                pending.add(waiter)
                true
            }
        }
        if (!accepted) {
            waiter.enqueue(Result.failure(IllegalStateException("SvgaLoader is closed")))
            return waiter
        }
        val key = try {
            request.cacheKey
        } catch (error: Exception) {
            waiter.enqueue(Result.failure(error))
            return waiter
        }
        var start: Flight? = null
        var ready: Result<ResourceLease<SVGAVideoEntity>>? = null
        synchronized(lock) {
            if (closed) {
                ready = Result.failure(IllegalStateException("SvgaLoader is closed"))
            } else {
                val cached = cache.acquire(key)
                if (cached != null) {
                    ready = Result.success(cached)
                } else {
                    val flight = flights[key] ?: Flight(key, request).also {
                        flights[key] = it
                        start = it
                    }
                    flight.waiters.add(waiter)
                    waiter.flight = flight
                }
            }
        }
        ready?.let(waiter::enqueue)
        start?.let(::startDecode)
        return waiter
    }

    private fun startDecode(flight: Flight) {
        synchronized(lock) {
            if (closed || flight.abandoned) return
        }
        val cancel = try {
            decoder.decode(flight.request) { complete(flight, it) }
        } catch (error: Exception) {
            complete(flight, Result.failure(error))
            return
        }
        val cancelNow = synchronized(lock) {
            if (closed || flight.abandoned) true
            else {
                if (!flight.completed) flight.cancelDecode = cancel
                false
            }
        }
        if (cancelNow) cancel.cancel()
    }

    private fun complete(flight: Flight, decoded: Result<SVGAVideoEntity>) {
        val deliveries = ArrayList<Pair<Waiter, Result<ResourceLease<SVGAVideoEntity>>>>()
        var discard: SVGAVideoEntity? = null
        synchronized(lock) {
            if (flight.completed) {
                // A duplicate callback must not clear the already shared first value.
                discard = decoded.getOrNull()?.takeUnless { it === flight.completedValue }
            } else {
                flight.completed = true
                flight.completedValue = decoded.getOrNull()
                if (closed || flight.abandoned || flights[flight.key] !== flight) {
                    discard = decoded.getOrNull()
                } else {
                    flights.remove(flight.key)
                    val listeners = flight.waiters.toList()
                    flight.waiters.clear()
                    listeners.forEach { it.flight = null }
                    decoded.fold(
                        onSuccess = { entity ->
                            try {
                                val owner = cache.putAndAcquire(flight.key, entity)
                                try {
                                    listeners.forEach { deliveries.add(it to Result.success(owner.fork())) }
                                } finally {
                                    owner.close()
                                }
                            } catch (error: Exception) {
                                // A failed put does not consume the candidate's ownership.
                                discard = entity
                                listeners.forEach { deliveries.add(it to Result.failure(error)) }
                            }
                        },
                        onFailure = { error -> listeners.forEach { deliveries.add(it to Result.failure(error)) } },
                    )
                }
            }
        }
        discard?.let(::releaseVideo)
        deliveries.forEach { (waiter, result) -> waiter.enqueue(result) }
    }

    // A platform cleanup failure cannot invalidate the new owner committed by
    // the cache. Entity.clear itself releases its Java state in a finally block.
    private fun releaseVideo(entity: SVGAVideoEntity) {
        try {
            dispose(entity)
        } catch (error: Exception) {
            Log.e("SvgaLoader", "Failed to release SVGA playback resources", error)
        }
    }

    fun clearMemoryCache() = cache.clear()

    /** Trimming relinquishes cache ownership; currently displayed animations keep their leases. */
    fun trimMemory(level: Int) {
        val budget = cache.stats().maxBytes
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> cache.clear()
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> cache.clear()
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> cache.trimToSize(budget / 2)
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> cache.trimToSize(budget - budget / 4)
        }
    }

    fun stats(): CacheStats = cache.stats()

    override fun onTrimMemory(level: Int) = trimMemory(level)
    override fun onLowMemory() = clearMemoryCache()
    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    /** Cancels pending delivery, drops cache ownership and unregisters memory callbacks. */
    override fun close() {
        val requests: List<Waiter>
        synchronized(lock) {
            if (closed) return
            closed = true
            requests = pending.toList()
        }
        requests.forEach { it.cancel() }
        application.unregisterComponentCallbacks(this)
        cache.close()
    }

    private class Flight(val key: String, val request: SvgaRequest) {
        val waiters = LinkedHashSet<Waiter>()
        var cancelDecode: CancelHandle? = null
        var completed = false
        var completedValue: SVGAVideoEntity? = null
        var abandoned = false
    }

    private inner class Waiter(callback: (Result<ResourceLease<SVGAVideoEntity>>) -> Unit) : CancelHandle {
        var flight: Flight? = null // Accessed only under loader lock.
        private val stateLock = Any()
        private var observer: ((Result<ResourceLease<SVGAVideoEntity>>) -> Unit)? = callback
        private var cancelled = false
        private var delivered = false
        private var queued: Result<ResourceLease<SVGAVideoEntity>>? = null

        fun enqueue(result: Result<ResourceLease<SVGAVideoEntity>>) {
            val rejected = synchronized(stateLock) {
                if (cancelled || delivered || queued != null) true
                else {
                    queued = result
                    false
                }
            }
            if (rejected) {
                result.getOrNull()?.close()
                return
            }
            handler.post {
                var listener: ((Result<ResourceLease<SVGAVideoEntity>>) -> Unit)? = null
                val delivery = synchronized(stateLock) {
                    if (cancelled) null
                    else queued?.also {
                        queued = null
                        delivered = true
                        listener = observer
                        observer = null
                    }
                } ?: return@post
                synchronized(lock) { pending.remove(this) }
                try {
                    checkNotNull(listener)(delivery)
                } catch (error: Throwable) {
                    delivery.getOrNull()?.close()
                    throw error
                }
            }
        }

        override fun cancel() {
            val release: Result<ResourceLease<SVGAVideoEntity>>?
            synchronized(stateLock) {
                if (cancelled || delivered) return
                cancelled = true
                observer = null
                release = queued
                queued = null
            }
            var cancelDecoder: CancelHandle? = null
            synchronized(lock) {
                pending.remove(this)
                flight?.let { job ->
                    job.waiters.remove(this)
                    if (!job.completed && job.waiters.isEmpty()) {
                        job.abandoned = true
                        if (flights[job.key] === job) flights.remove(job.key)
                        cancelDecoder = job.cancelDecode
                    }
                }
                flight = null
            }
            release?.getOrNull()?.close()
            cancelDecoder?.cancel()
        }
    }
}
