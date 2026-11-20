// SPDX-License-Identifier: Apache-2.0
package com.opensource.svgaplayer

import dev.svganext.player.proto.MovieEntity
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.WeakHashMap

/**
 * Drop-in replacement for com.opensource.svgaplayer.SVGAVideoEntity.
 *
 * Inherits all performance features of dev.svganext.player.SVGAVideoEntity,
 * and adds Safe-Clear protection and reference-based immediate resource disposal:
 * - If an entity is currently bound to any active SVGAImageView or cached in
 *   [SVGAActivitiesCache], calls to [clear] are safely intercepted to prevent black
 *   screens or blank frames on concurrent views.
 * - When all views detach and the entity is not retained in cache, resources are
 *   cleared immediately to promptly return memory to the OS (cliff-drop recovery).
 */
open class SVGAVideoEntity : dev.svganext.player.SVGAVideoEntity {

    var safeClearEnabled: Boolean = true

    @Volatile
    internal var isCachedInPool: Boolean = false

    private val activeViews = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())

    constructor(source: dev.svganext.player.SVGAVideoEntity) : super(source)
    constructor(json: JSONObject, cacheDir: File) : super(json, cacheDir)
    constructor(json: JSONObject, cacheDir: File, frameWidth: Int, frameHeight: Int) : super(json, cacheDir, frameWidth, frameHeight)
    constructor(entity: MovieEntity, cacheDir: File) : super(entity, cacheDir)
    constructor(entity: MovieEntity, cacheDir: File, frameWidth: Int, frameHeight: Int) : super(entity, cacheDir, frameWidth, frameHeight)

    internal fun attachView(view: Any) {
        synchronized(this) {
            synchronized(activeViews) { activeViews.add(view) }
        }
    }

    internal fun detachView(view: Any) {
        val shouldClear = synchronized(this) {
            synchronized(activeViews) { activeViews.remove(view) }
            !isBoundToActiveView && !isCachedInPool
        }
        if (shouldClear) {
            super.clear()
        }
    }

    internal fun attachCache() {
        synchronized(this) {
            isCachedInPool = true
        }
    }

    internal fun detachCache() {
        val shouldClear = synchronized(this) {
            isCachedInPool = false
            !isBoundToActiveView
        }
        if (shouldClear) {
            super.clear()
        }
    }

    val isBoundToActiveView: Boolean
        get() = synchronized(activeViews) { activeViews.isNotEmpty() }

    override fun clearAudioList() {
        super.clearAudioList()
    }

    fun clearCallback() {
        releaseCallbackReferences()
    }

    override fun clear() {
        val shouldIntercept = synchronized(this) {
            safeClearEnabled && (isBoundToActiveView || isCachedInPool)
        }
        if (shouldIntercept) {
            // Safe-Clear: An active view is currently displaying this animation or it is cached.
            // Intercept clear() to prevent breaking concurrent playback.
            return
        }
        super.clear()
    }
}
