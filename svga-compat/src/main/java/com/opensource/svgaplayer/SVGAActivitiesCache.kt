// SPDX-License-Identifier: Apache-2.0
package com.opensource.svgaplayer

import android.content.ComponentCallbacks2
import android.util.LruCache
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory LRU cache for parsed [SVGAVideoEntity] animations.
 *
 * Provides drop-in compatibility with legacy projects while utilizing byte-estimated
 * size calculations and safe lifecycle release coordination:
 * - Recurring animations are served directly from cache without re-parsing or bitmap allocations.
 * - Cache eviction detaches cache ownership; animations still playing on views stay intact,
 *   while unreferenced animations are disposed immediately.
 */
class SVGAActivitiesCache private constructor() {

    companion object {
        const val TAG = "SVGAParser-Act-Cache"

        @JvmStatic
        val instance: SVGAActivitiesCache by lazy(mode = LazyThreadSafetyMode.SYNCHRONIZED) {
            SVGAActivitiesCache()
        }

        private const val DEFAULT_MAX_SIZE = 20 * 1024 * 1024 // 20 MB default

        var mInitCacheSize: Boolean = false
            private set

        private val bigResourceRef = AtomicReference<BigResource?>(null)
    }

    private class BigResource(val key: String, val entity: SVGAVideoEntity)

    private val lock = Any()
    private var mCache: LruCache<String, SVGAVideoEntity>? = null

    init {
        createCache(DEFAULT_MAX_SIZE)
    }

    private fun createCache(maxBytes: Int) {
        synchronized(lock) {
            mCache = object : LruCache<String, SVGAVideoEntity>(maxBytes) {
                override fun sizeOf(key: String, value: SVGAVideoEntity): Int {
                    return getImageSize(value)
                }

                override fun entryRemoved(
                    evicted: Boolean,
                    key: String,
                    oldValue: SVGAVideoEntity,
                    newValue: SVGAVideoEntity?
                ) {
                    if (oldValue !== newValue) {
                        oldValue.detachCache()
                    }
                }
            }
        }
    }

    fun setMaxSize(size: Int) {
        synchronized(lock) {
            mInitCacheSize = true
            mCache?.evictAll()
            createCache(size)
        }
    }

    private fun getImageSize(value: SVGAVideoEntity?): Int {
        if (value == null) return 0
        val bytes = value.estimatedMemoryBytes
        return if (bytes > Int.MAX_VALUE) Int.MAX_VALUE else bytes.toInt()
    }

    fun put(key: String, entity: SVGAVideoEntity) {
        synchronized(lock) {
            entity.attachCache()
            val cache = mCache ?: return
            val imageSize = getImageSize(entity)
            val maxSize = cache.maxSize()
            if (imageSize >= maxSize || (maxSize > 0 && imageSize > (maxSize * 0.75f))) {
                val oldBig = bigResourceRef.getAndSet(BigResource(key, entity))
                if (oldBig != null && oldBig.entity !== entity) {
                    oldBig.entity.detachCache()
                }
                return
            }
            cache.put(key, entity)
        }
    }

    fun get(key: String): SVGAVideoEntity? {
        synchronized(lock) {
            val current = bigResourceRef.get()
            if (current != null && current.key == key) {
                if (current.entity.isReleased) {
                    bigResourceRef.compareAndSet(current, null)
                } else {
                    return current.entity
                }
            }
            val entity = mCache?.get(key)
            if (entity != null && entity.isReleased) {
                mCache?.remove(key)
                return null
            }
            return entity
        }
    }

    fun remove(cacheKey: String) {
        synchronized(lock) {
            val current = bigResourceRef.get()
            if (current != null && current.key == cacheKey) {
                bigResourceRef.compareAndSet(current, null)
                current.entity.detachCache()
            }
            mCache?.remove(cacheKey)
        }
    }

    fun clear() {
        synchronized(lock) {
            clearBigEntity()
            mCache?.evictAll()
        }
    }

    fun clearBigEntity() {
        val current = bigResourceRef.getAndSet(null)
        current?.entity?.detachCache()
    }

    fun isBigEntity(entity: SVGAVideoEntity?): Boolean {
        if (entity == null) return false
        val current = bigResourceRef.get()
        return current != null && current.entity === entity
    }

    fun isBigEntity(key: String?): Boolean {
        if (key == null) return false
        val current = bigResourceRef.get()
        return current != null && current.key == key
    }

    fun trimCache(level: Int) {
        synchronized(lock) {
            val c = mCache ?: return
            val maxSize = c.maxSize()
            when (level) {
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> c.trimToSize((maxSize * 0.8f).toInt())
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> c.trimToSize((maxSize * 0.7f).toInt())
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> c.trimToSize((maxSize * 0.3f).toInt())
                else -> clear()
            }
        }
    }
}
