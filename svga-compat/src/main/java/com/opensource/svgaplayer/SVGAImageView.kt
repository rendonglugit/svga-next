// SPDX-License-Identifier: Apache-2.0
package com.opensource.svgaplayer

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import dev.svganext.player.SVGAImageView as CoreSVGAImageView
import dev.svganext.player.SVGAVideoEntity as CoreEntity
import dev.svganext.player.SVGADrawable as CoreDrawable
import dev.svganext.player.SVGADynamicEntity as CoreDynamicEntity

/**
 * Drop-in replacement for com.opensource.svgaplayer.SVGAImageView.
 *
 * Fully compatible with existing XML layout declarations (including app:source,
 * autoPlay, loopCount, fillMode) and existing Java/Kotlin code, while utilizing
 * SVGA-Next's zero-allocation Canvas drawing, robust locking engine, and automatic
 * reference-counted resource disposal.
 */
open class SVGAImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : CoreSVGAImageView(context, attrs, defStyleAttr) {

    private var currentEntity: SVGAVideoEntity? = null

    var startAfterAttached: Boolean = false

    override fun setVideoItem(videoItem: CoreEntity?) {
        val entity = when (videoItem) {
            null -> null
            is SVGAVideoEntity -> videoItem
            else -> SVGAVideoEntity(videoItem)
        }
        updateBinding(entity)
        super.setVideoItem(entity)
    }

    override fun setVideoItem(videoItem: CoreEntity?, dynamicItem: CoreDynamicEntity?) {
        val entity = when (videoItem) {
            null -> null
            is SVGAVideoEntity -> videoItem
            else -> SVGAVideoEntity(videoItem)
        }
        updateBinding(entity)
        super.setVideoItem(entity, dynamicItem)
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        if (drawable == null) {
            updateBinding(null)
        }
    }

    private fun updateBinding(newEntity: SVGAVideoEntity?) {
        val old = currentEntity
        if (old === newEntity) return
        currentEntity = newEntity
        old?.detachView(this)
        newEntity?.attachView(this)
    }

    override fun clear() {
        super.clear()
        updateBinding(null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        currentEntity?.attachView(this)
        if (startAfterAttached && drawable != null && !isAnimating) {
            startAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (clearsAfterDetached) {
            updateBinding(null)
        }
    }

    fun startAnimationFromAppointFrame(videoItem: CoreEntity?, frame: Int) {
        setVideoItem(videoItem)
        val total = videoItem?.frames ?: 0
        if (total > 0) {
            startAnimation(frame, (total - 1).coerceAtLeast(frame), false)
        } else {
            startAnimation()
        }
    }

    @JvmOverloads
    fun startAnimationFromAppointFrame(frame: Int, reverse: Boolean = false) {
        val total = (drawable as? CoreDrawable)?.videoItem?.frames ?: 0
        if (total > 0) {
            startAnimation(frame, (total - 1).coerceAtLeast(frame), reverse)
        } else {
            startAnimation()
        }
    }
}
