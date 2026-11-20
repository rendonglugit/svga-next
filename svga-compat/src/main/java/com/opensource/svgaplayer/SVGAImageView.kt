// SPDX-License-Identifier: Apache-2.0
package com.opensource.svgaplayer

import android.content.Context
import android.util.AttributeSet
import dev.svganext.player.SVGAImageView as CoreSVGAImageView
import dev.svganext.player.SVGAVideoEntity as CoreEntity
import dev.svganext.player.SVGADynamicEntity as CoreDynamicEntity

/**
 * Drop-in replacement for com.opensource.svgaplayer.SVGAImageView.
 *
 * Fully compatible with existing XML layout declarations (including app:source,
 * autoPlay, loopCount, fillMode) and existing Java/Kotlin code, while utilizing
 * SVGA-Next's zero-allocation Canvas drawing and robust locking engine.
 */
open class SVGAImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : CoreSVGAImageView(context, attrs, defStyleAttr) {

    private var currentEntity: SVGAVideoEntity? = null

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

    private fun updateBinding(newEntity: SVGAVideoEntity?) {
        currentEntity?.detachView(this)
        currentEntity = newEntity
        newEntity?.attachView(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (clearsAfterDetached) {
            currentEntity?.detachView(this)
            currentEntity = null
        }
    }
}
