// SPDX-License-Identifier: Apache-2.0
package com.opensource.svgaplayer.drawer

import android.graphics.Canvas
import android.widget.ImageView
import dev.svganext.player.SVGADrawable
import dev.svganext.player.SVGADynamicEntity
import dev.svganext.player.SVGAVideoEntity

/**
 * Drop-in backward compatibility wrapper for the legacy official SVGACanvasDrawer.
 *
 * In modern code, prefer using [SVGADrawable.drawFrame].
 */
@Deprecated(
    message = "Direct use of SVGACanvasDrawer is deprecated. Use SVGADrawable.drawFrame instead.",
    replaceWith = ReplaceWith("drawable.drawFrame(canvas, frameIndex, scaleType)")
)
open class SVGACanvasDrawer @JvmOverloads constructor(
    val videoItem: SVGAVideoEntity,
    val dynamicItem: SVGADynamicEntity = SVGADynamicEntity()
) {
    private val drawable = SVGADrawable(videoItem, dynamicItem)

    @JvmOverloads
    open fun drawFrame(
        canvas: Canvas,
        frameIndex: Int,
        scaleType: ImageView.ScaleType = ImageView.ScaleType.MATRIX
    ) {
        drawable.drawFrame(canvas, frameIndex, scaleType, playAudio = false)
    }
}
