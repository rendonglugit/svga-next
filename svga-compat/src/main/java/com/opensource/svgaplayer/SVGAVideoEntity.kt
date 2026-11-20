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
 * and adds Safe-Clear protection: if the entity is currently being displayed by any
 * active SVGAImageView, calls to [clear] are safely intercepted to prevent black
 * screens or blank frames on concurrent views.
 */
open class SVGAVideoEntity : dev.svganext.player.SVGAVideoEntity {

    var safeClearEnabled: Boolean = true

    private val activeViews = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())

    constructor(source: dev.svganext.player.SVGAVideoEntity) : super(source)
    constructor(json: JSONObject, cacheDir: File) : super(json, cacheDir)
    constructor(json: JSONObject, cacheDir: File, frameWidth: Int, frameHeight: Int) : super(json, cacheDir, frameWidth, frameHeight)
    constructor(entity: MovieEntity, cacheDir: File) : super(entity, cacheDir)
    constructor(entity: MovieEntity, cacheDir: File, frameWidth: Int, frameHeight: Int) : super(entity, cacheDir, frameWidth, frameHeight)

    internal fun attachView(view: Any) {
        synchronized(activeViews) { activeViews.add(view) }
    }

    internal fun detachView(view: Any) {
        synchronized(activeViews) { activeViews.remove(view) }
    }

    val isBoundToActiveView: Boolean
        get() = synchronized(activeViews) { activeViews.isNotEmpty() }

    override fun clear() {
        if (safeClearEnabled && isBoundToActiveView) {
            // Safe-Clear: An active view is currently displaying this animation.
            // Intercept clear() to prevent breaking concurrent playback.
            return
        }
        super.clear()
    }
}
