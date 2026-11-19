package dev.readablesvga.player

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.widget.ImageView
import dev.readablesvga.player.drawer.SVGACanvasDrawer

class SVGADrawable private constructor(
    val videoItem: SVGAVideoEntity,
    val dynamicItem: SVGADynamicEntity,
    internal val audioPlayback: SVGAAudioPlayback
): Drawable() {

    constructor(videoItem: SVGAVideoEntity, dynamicItem: SVGADynamicEntity) :
            this(videoItem, dynamicItem, SVGAAudioPlayback(videoItem))

    internal constructor(videoItem: SVGAVideoEntity, audioPlayback: SVGAAudioPlayback) :
            this(videoItem, SVGADynamicEntity(), audioPlayback)

    constructor(videoItem: SVGAVideoEntity): this(videoItem, SVGADynamicEntity())

    var cleared = true
        internal set (value) {
            if (field == value) {
                return
            }
            field = value
            invalidateSelf()
        }

    var currentFrame = 0
        internal set (value) {
            if (field == value) {
                return
            }
            field = value
            invalidateSelf()
        }

    var scaleType: ImageView.ScaleType = ImageView.ScaleType.MATRIX

    private val drawer = SVGACanvasDrawer(videoItem, dynamicItem, audioPlayback)

    /** Rendering preference belongs to this drawable, rather than to a cached shared entity. */
    var antiAlias: Boolean
        get() = drawer.antiAlias
        set(value) {
            drawer.antiAlias = value
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        if (cleared) {
            return
        }
        drawer.drawFrame(canvas, currentFrame, scaleType)
    }

    override fun setAlpha(alpha: Int) {

    }

    override fun getOpacity(): Int {
        return PixelFormat.TRANSPARENT
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {

    }

    fun resume() {
        audioPlayback.resume()
    }

    fun pause() {
        audioPlayback.pause()
    }

    fun stop() {
        audioPlayback.stop()
    }

    /** Clears this playback only. Its owner releases the entity after the final lease closes. */
    fun clear() {
        stop()
        cleared = true
    }
}
