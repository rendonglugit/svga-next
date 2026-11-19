package dev.svganext.player.drawer

import android.graphics.*
import android.os.Build
import android.text.StaticLayout
import android.text.TextUtils
import android.widget.ImageView
import dev.svganext.player.SVGADynamicEntity
import dev.svganext.player.SVGAAudioPlayback
import dev.svganext.player.SVGAVideoEntity
import dev.svganext.player.entities.SVGAVideoShapeEntity

/**
 * Created by cuiminghui on 2017/3/29.
 */

internal class SVGACanvasDrawer(
    videoItem: SVGAVideoEntity,
    val dynamicItem: SVGADynamicEntity,
    private val audioPlayback: SVGAAudioPlayback
) : SGVADrawer(videoItem) {

    var antiAlias: Boolean = videoItem.antiAlias

    private val sharedValues = ShareValues()
    private val drawTextCache: HashMap<String, Bitmap> = hashMapOf()
    private val pathCache = PathCache()

    private var beginIndexList: Array<Boolean>? = null
    private var endIndexList: Array<Boolean>? = null

    override fun drawFrame(canvas: Canvas, frameIndex: Int, scaleType: ImageView.ScaleType) {
        super.drawFrame(canvas, frameIndex, scaleType)
        audioPlayback.onFrame(frameIndex)
        this.pathCache.onSizeChanged(canvas)
        val sprites = requestFrameSprites(frameIndex)
        // Filter null sprites
        if (sprites.count() <= 0) return
        val matteSprites = mutableMapOf<String, SVGADrawerSprite>()
        var saveID = -1
        beginIndexList = null
        endIndexList = null

        // Filter no matte layer
        var hasMatteLayer = false
        sprites.get(0).imageKey?.let {
            if (it.endsWith(".matte")) {
                hasMatteLayer = true
            }
        }
        sprites.forEachIndexed { index, svgaDrawerSprite ->

            // Save matte sprite
            svgaDrawerSprite.imageKey?.let {
                /// No matte layer included or VERSION Unsopport matte
                if (!hasMatteLayer || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    // Normal sprite
                    drawSprite(svgaDrawerSprite, canvas, frameIndex)
                    // Continue
                    return@forEachIndexed
                }
                /// Cache matte sprite
                if (it.endsWith(".matte")) {
                    matteSprites.put(it, svgaDrawerSprite)
                    // Continue
                    return@forEachIndexed
                }
            }
            /// Is matte begin
            if (isMatteBegin(index, sprites)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    saveID = canvas.saveLayer(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), null)
                } else {
                    canvas.save()
                }
            }
            /// Normal matte
            drawSprite(svgaDrawerSprite, canvas, frameIndex)

            /// Is matte end
            if (isMatteEnd(index, sprites)) {
                if (saveID != -1) {
                    try {
                        matteSprites[svgaDrawerSprite.matteKey]?.let { mask ->
                            drawSprite(mask, sharedValues.shareMatteCanvas(canvas.width, canvas.height), frameIndex)
                            canvas.drawBitmap(sharedValues.sharedMatteBitmap(), 0f, 0f, sharedValues.shareMattePaint())
                        }
                    } finally {
                        canvas.restoreToCount(saveID)
                        saveID = -1
                    }
                }
            }
        }
        releaseFrameSprites(sprites)
    }

    private fun isMatteBegin(spriteIndex: Int, sprites: List<SVGADrawerSprite>): Boolean {
        if (beginIndexList == null) {
            val boolArray = Array(sprites.count()) { false }
            sprites.forEachIndexed { index, svgaDrawerSprite ->
                svgaDrawerSprite.imageKey?.let {
                    /// Filter matte sprite
                    if (it.endsWith(".matte")) {
                        // Continue
                        return@forEachIndexed
                    }
                }
                svgaDrawerSprite.matteKey?.let {
                    if (it.length > 0) {
                        sprites.get(index - 1)?.let { lastSprite ->
                            if (lastSprite.matteKey.isNullOrEmpty()) {
                                boolArray[index] = true
                            } else {
                                if (lastSprite.matteKey != svgaDrawerSprite.matteKey) {
                                    boolArray[index] = true
                                }
                            }
                        }
                    }
                }
            }
            beginIndexList = boolArray
        }
        return beginIndexList?.get(spriteIndex) ?: false
    }

    private fun isMatteEnd(spriteIndex: Int, sprites: List<SVGADrawerSprite>): Boolean {
        if (endIndexList == null) {
            val boolArray = Array(sprites.count()) { false }
            sprites.forEachIndexed { index, svgaDrawerSprite ->
                svgaDrawerSprite.imageKey?.let {
                    /// Filter matte sprite
                    if (it.endsWith(".matte")) {
                        // Continue
                        return@forEachIndexed
                    }
                }
                svgaDrawerSprite.matteKey?.let {
                    if (it.length > 0) {
                        // Last one
                        if (index == sprites.count() - 1) {
                            boolArray[index] = true
                        } else {
                            sprites.get(index + 1)?.let { nextSprite ->
                                if (nextSprite.matteKey.isNullOrEmpty()) {
                                    boolArray[index] = true
                                } else {
                                    if (nextSprite.matteKey != svgaDrawerSprite.matteKey) {
                                        boolArray[index] = true
                                    }
                                }
                            }
                        }
                    }
                }
            }
            endIndexList = boolArray
        }
        return endIndexList?.get(spriteIndex) ?: false
    }

    private fun shareFrameMatrix(transform: Matrix): Matrix {
        val matrix = this.sharedValues.sharedMatrix()
        matrix.postScale(scaleInfo.scaleFx, scaleInfo.scaleFy)
        matrix.postTranslate(scaleInfo.tranFx, scaleInfo.tranFy)
        matrix.preConcat(transform)
        return matrix
    }

    private fun shareFrameMatrix(frameData: FloatArray): Matrix {
        val transform = this.sharedValues.populateFrameTransform(frameData)
        val matrix = this.sharedValues.sharedMatrix()
        matrix.postScale(scaleInfo.scaleFx, scaleInfo.scaleFy)
        matrix.postTranslate(scaleInfo.tranFx, scaleInfo.tranFy)
        matrix.preConcat(transform)
        return matrix
    }

    private fun drawSprite(sprite: SVGADrawerSprite, canvas: Canvas, frameIndex: Int) {
        drawImage(sprite, canvas)
        drawShape(sprite, canvas)
        drawDynamic(sprite, canvas, frameIndex)
    }

    private fun drawImage(sprite: SVGADrawerSprite, canvas: Canvas) {
        val imageKey = sprite.imageKey ?: return
        val isHidden = dynamicItem.dynamicHidden[imageKey] == true
        if (isHidden) {
            return
        }
        val bitmapKey = if (imageKey.endsWith(".matte")) imageKey.substring(0, imageKey.length - 6) else imageKey
        val drawingBitmap = (dynamicItem.dynamicImage[bitmapKey] ?: videoItem.imageMap[bitmapKey])
                ?: return
        val frameMatrix = shareFrameMatrix(sprite.frameEntity.frameData)
        val paint = this.sharedValues.sharedPaint()
        paint.isAntiAlias = antiAlias
        paint.isFilterBitmap = antiAlias
        paint.alpha = (sprite.frameEntity.alpha * 255).toInt()
        val layoutW = sprite.frameEntity.layoutWidth
        val layoutH = sprite.frameEntity.layoutHeight
        if (sprite.frameEntity.maskPath != null) {
            val maskPath = sprite.frameEntity.maskPath ?: return
            canvas.save()
            val path = this.sharedValues.sharedPath()
            maskPath.buildPath(path)
            path.transform(frameMatrix)
            canvas.clipPath(path)
            frameMatrix.preScale(layoutW / drawingBitmap.width, layoutH / drawingBitmap.height)
            if (!drawingBitmap.isRecycled) {
                canvas.drawBitmap(drawingBitmap, frameMatrix, paint)
            }
            canvas.restore()
        } else {
            frameMatrix.preScale(layoutW / drawingBitmap.width, layoutH / drawingBitmap.height)
            if (!drawingBitmap.isRecycled) {
                canvas.drawBitmap(drawingBitmap, frameMatrix, paint)
            }
        }
        dynamicItem.dynamicIClickArea.let {
            it.get(imageKey)?.let { listener ->
                val matrixArray = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
                frameMatrix.getValues(matrixArray)
                listener.onResponseArea(imageKey, matrixArray[2].toInt()
                        , matrixArray[5].toInt()
                        , (drawingBitmap.width * matrixArray[0] + matrixArray[2]).toInt()
                        , (drawingBitmap.height * matrixArray[4] + matrixArray[5]).toInt())
            }
        }
        drawTextOnBitmap(canvas, drawingBitmap, sprite, frameMatrix)
    }

    private fun drawTextOnBitmap(canvas: Canvas, drawingBitmap: Bitmap, sprite: SVGADrawerSprite, frameMatrix: Matrix) {
        if (dynamicItem.isTextDirty) {
            this.drawTextCache.clear()
            dynamicItem.isTextDirty = false
        }
        val imageKey = sprite.imageKey ?: return
        var textBitmap: Bitmap? = null
        dynamicItem.dynamicText[imageKey]?.let { drawingText ->
            dynamicItem.dynamicTextPaint[imageKey]?.let { drawingTextPaint ->
                drawTextCache[imageKey]?.let {
                    textBitmap = it
                } ?: kotlin.run {
                    val createdBitmap = Bitmap.createBitmap(drawingBitmap.width, drawingBitmap.height, Bitmap.Config.ARGB_8888)
                    textBitmap = createdBitmap
                    val drawRect = Rect(0, 0, drawingBitmap.width, drawingBitmap.height)
                    val textCanvas = Canvas(createdBitmap)
                    drawingTextPaint.isAntiAlias = true
                    val fontMetrics = drawingTextPaint.getFontMetrics();
                    val top = fontMetrics.top
                    val bottom = fontMetrics.bottom
                    val baseLineY = drawRect.centerY() - top / 2 - bottom / 2
                    textCanvas.drawText(drawingText, drawRect.centerX().toFloat(), baseLineY, drawingTextPaint);
                    drawTextCache.put(imageKey, createdBitmap)
                }
            }
        }

        dynamicItem.dynamicBoringLayoutText[imageKey]?.let {
            drawTextCache[imageKey]?.let {
                textBitmap = it
            } ?: kotlin.run {
                it.paint.isAntiAlias = true

                val createdBitmap = Bitmap.createBitmap(drawingBitmap.width, drawingBitmap.height, Bitmap.Config.ARGB_8888)
                    textBitmap = createdBitmap
                val textCanvas = Canvas(createdBitmap)
                textCanvas.translate(0f, ((drawingBitmap.height - it.height) / 2).toFloat())
                it.draw(textCanvas)
                drawTextCache.put(imageKey, createdBitmap)
            }
        }

        dynamicItem.dynamicStaticLayoutText[imageKey]?.let {
            drawTextCache[imageKey]?.let {
                textBitmap = it
            } ?: kotlin.run {
                it.paint.isAntiAlias = true
                var layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    var lineMax = try {
                        val field = StaticLayout::class.java.getDeclaredField("mMaximumVisibleLineCount")
                        field.isAccessible = true
                        field.getInt(it)
                    } catch (e: Exception) {
                        Int.MAX_VALUE
                    }
                    StaticLayout.Builder
                            .obtain(it.text, 0, it.text.length, it.paint, drawingBitmap.width)
                            .setAlignment(it.alignment)
                            .setMaxLines(lineMax)
                            .setEllipsize(TextUtils.TruncateAt.END)
                            .build()
                } else {
                    StaticLayout(it.text, 0, it.text.length, it.paint, drawingBitmap.width, it.alignment, it.spacingMultiplier, it.spacingAdd, false)
                }
                val createdBitmap = Bitmap.createBitmap(drawingBitmap.width, drawingBitmap.height, Bitmap.Config.ARGB_8888)
                    textBitmap = createdBitmap
                val textCanvas = Canvas(createdBitmap)
                textCanvas.translate(0f, ((drawingBitmap.height - layout.height) / 2).toFloat())
                layout.draw(textCanvas)
                drawTextCache.put(imageKey, createdBitmap)
            }
        }
        textBitmap?.let { textBitmap ->
            val paint = this.sharedValues.sharedPaint()
            paint.isAntiAlias = antiAlias
            paint.alpha = (sprite.frameEntity.alpha * 255).toInt()
            if (sprite.frameEntity.maskPath != null) {
                val maskPath = sprite.frameEntity.maskPath ?: return@let
                canvas.save()
                canvas.concat(frameMatrix)
                canvas.clipRect(0, 0, drawingBitmap.width, drawingBitmap.height)
                val bitmapShader = BitmapShader(textBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                paint.shader = bitmapShader
                val path = this.sharedValues.sharedPath()
                maskPath.buildPath(path)
                canvas.drawPath(path, paint)
                canvas.restore()
            } else {
                paint.isFilterBitmap = antiAlias
                canvas.drawBitmap(textBitmap, frameMatrix, paint)
            }
        }
    }

    private fun drawShape(sprite: SVGADrawerSprite, canvas: Canvas) {
        val frameMatrix = shareFrameMatrix(sprite.frameEntity.frameData)
        sprite.frameEntity.shapes.forEach { shape ->
            shape.buildPath()
            shape.shapePath?.let {
                val paint = this.sharedValues.sharedPaint()
                paint.reset()
                paint.isAntiAlias = antiAlias
                paint.alpha = (sprite.frameEntity.alpha * 255).toInt()
                val path = this.sharedValues.sharedPath()
                path.reset()
                path.addPath(this.pathCache.buildPath(shape))
                val shapeMatrix = this.sharedValues.sharedMatrix2()
                shapeMatrix.reset()
                shape.transform?.let {
                    shapeMatrix.postConcat(it)
                }
                shapeMatrix.postConcat(frameMatrix)
                path.transform(shapeMatrix)
                shape.styles?.fill?.let {
                    if (it != 0x00000000) {
                        paint.style = Paint.Style.FILL
                        paint.color = it
                        val alpha = Math.min(255, Math.max(0, (sprite.frameEntity.alpha * 255).toInt()))
                        if (alpha != 255) {
                            paint.alpha = alpha
                        }
                        if (sprite.frameEntity.maskPath !== null) canvas.save()
                        sprite.frameEntity.maskPath?.let { maskPath ->
                            val path2 = this.sharedValues.sharedPath2()
                            maskPath.buildPath(path2)
                            path2.transform(frameMatrix)
                            canvas.clipPath(path2)
                        }
                        canvas.drawPath(path, paint)
                        if (sprite.frameEntity.maskPath !== null) canvas.restore()
                    }
                }
                shape.styles?.strokeWidth?.let {
                    if (it > 0) {
                        paint.alpha = (sprite.frameEntity.alpha * 255).toInt()
                        paint.style = Paint.Style.STROKE
                        shape.styles?.stroke?.let {
                            paint.color = it
                            val alpha = Math.min(255, Math.max(0, (sprite.frameEntity.alpha * 255).toInt()))
                            if (alpha != 255) {
                                paint.alpha = alpha
                            }
                        }
                        val scale = matrixScale(frameMatrix)
                        shape.styles?.strokeWidth?.let {
                            paint.strokeWidth = it * scale
                        }
                        shape.styles?.lineCap?.let {
                            when {
                                it.equals("butt", true) -> paint.strokeCap = Paint.Cap.BUTT
                                it.equals("round", true) -> paint.strokeCap = Paint.Cap.ROUND
                                it.equals("square", true) -> paint.strokeCap = Paint.Cap.SQUARE
                            }
                        }
                        shape.styles?.lineJoin?.let {
                            when {
                                it.equals("miter", true) -> paint.strokeJoin = Paint.Join.MITER
                                it.equals("round", true) -> paint.strokeJoin = Paint.Join.ROUND
                                it.equals("bevel", true) -> paint.strokeJoin = Paint.Join.BEVEL
                            }
                        }
                        shape.styles?.miterLimit?.let {
                            paint.strokeMiter = it.toFloat() * scale
                        }
                        shape.styles?.lineDash?.let {
                            if (it.size == 3 && (it[0] > 0 || it[1] > 0)) {
                                paint.pathEffect = DashPathEffect(floatArrayOf(
                                        (if (it[0] < 1.0f) 1.0f else it[0]) * scale,
                                        (if (it[1] < 0.1f) 0.1f else it[1]) * scale
                                ), it[2] * scale)
                            }
                        }
                        if (sprite.frameEntity.maskPath !== null) canvas.save()
                        sprite.frameEntity.maskPath?.let { maskPath ->
                            val path2 = this.sharedValues.sharedPath2()
                            maskPath.buildPath(path2)
                            path2.transform(frameMatrix)
                            canvas.clipPath(path2)
                        }
                        canvas.drawPath(path, paint)
                        if (sprite.frameEntity.maskPath !== null) canvas.restore()
                    }
                }
            }

        }
    }

    private val matrixScaleTempValues = FloatArray(16)

    private fun matrixScale(matrix: Matrix): Float {
        matrix.getValues(matrixScaleTempValues)
        if (matrixScaleTempValues[0] == 0f) {
            return 0f
        }
        var A = matrixScaleTempValues[0].toDouble()
        var B = matrixScaleTempValues[3].toDouble()
        var C = matrixScaleTempValues[1].toDouble()
        var D = matrixScaleTempValues[4].toDouble()
        if (A * D == B * C) return 0f
        var scaleX = Math.sqrt(A * A + B * B)
        A /= scaleX
        B /= scaleX
        var skew = A * C + B * D
        C -= A * skew
        D -= B * skew
        var scaleY = Math.sqrt(C * C + D * D)
        C /= scaleY
        D /= scaleY
        skew /= scaleY
        if (A * D < B * C) {
            scaleX = -scaleX
        }
        return if (scaleInfo.ratioX) Math.abs(scaleX.toFloat()) else Math.abs(scaleY.toFloat())
    }

    private fun drawDynamic(sprite: SVGADrawerSprite, canvas: Canvas, frameIndex: Int) {
        val imageKey = sprite.imageKey ?: return
        dynamicItem.dynamicDrawer[imageKey]?.let {
            val frameMatrix = shareFrameMatrix(sprite.frameEntity.frameData)
            canvas.save()
            canvas.concat(frameMatrix)
            it.invoke(canvas, frameIndex)
            canvas.restore()
        }
        dynamicItem.dynamicDrawerSized[imageKey]?.let {
            val frameMatrix = shareFrameMatrix(sprite.frameEntity.frameData)
            canvas.save()
            canvas.concat(frameMatrix)
            it.invoke(canvas, frameIndex, sprite.frameEntity.layoutWidth.toInt(), sprite.frameEntity.layoutHeight.toInt())
            canvas.restore()
        }
    }

    class ShareValues {

        private val sharedPaint = Paint()
        private val sharedPath = Path()
        private val sharedPath2 = Path()
        private val sharedMatrix = Matrix()
        private val sharedMatrix2 = Matrix()
        private val sharedFrameTransform = Matrix()
        private val sharedMatrixValues = FloatArray(9).apply {
            this[6] = 0f
            this[7] = 0f
            this[8] = 1f
        }

        private val shareMattePaint = Paint()
        private var shareMatteCanvas: Canvas? = null
        private var sharedMatteBitmap: Bitmap? = null

        fun sharedPaint(): Paint {
            sharedPaint.reset()
            return sharedPaint
        }

        fun sharedPath(): Path {
            sharedPath.reset()
            return sharedPath
        }

        fun sharedPath2(): Path {
            sharedPath2.reset()
            return sharedPath2
        }

        fun sharedMatrix(): Matrix {
            sharedMatrix.reset()
            return sharedMatrix
        }

        fun sharedMatrix2(): Matrix {
            sharedMatrix2.reset()
            return sharedMatrix2
        }

        fun populateFrameTransform(frameData: FloatArray): Matrix {
            sharedMatrixValues[0] = frameData[dev.svganext.player.entities.SVGAVideoSpriteFrameEntity.INDEX_A]
            sharedMatrixValues[1] = frameData[dev.svganext.player.entities.SVGAVideoSpriteFrameEntity.INDEX_C]
            sharedMatrixValues[2] = frameData[dev.svganext.player.entities.SVGAVideoSpriteFrameEntity.INDEX_TX]
            sharedMatrixValues[3] = frameData[dev.svganext.player.entities.SVGAVideoSpriteFrameEntity.INDEX_B]
            sharedMatrixValues[4] = frameData[dev.svganext.player.entities.SVGAVideoSpriteFrameEntity.INDEX_D]
            sharedMatrixValues[5] = frameData[dev.svganext.player.entities.SVGAVideoSpriteFrameEntity.INDEX_TY]
            sharedFrameTransform.setValues(sharedMatrixValues)
            return sharedFrameTransform
        }

        fun shareMattePaint(): Paint {
            shareMattePaint.setXfermode(PorterDuffXfermode(PorterDuff.Mode.DST_IN))
            return shareMattePaint
        }

        fun sharedMatteBitmap(): Bitmap {
            return sharedMatteBitmap as Bitmap
        }

        fun shareMatteCanvas(width: Int, height: Int): Canvas {
            val current = sharedMatteBitmap
            if (current == null || current.width != width || current.height != height) {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                sharedMatteBitmap = bitmap
                shareMatteCanvas = Canvas(bitmap)
            }
            return requireNotNull(shareMatteCanvas).also {
                it.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            }
        }
    }

    class PathCache {

        private var canvasWidth: Int = 0
        private var canvasHeight: Int = 0
        private val cache = HashMap<SVGAVideoShapeEntity, Path>()

        fun onSizeChanged(canvas: Canvas) {
            if (this.canvasWidth != canvas.width || this.canvasHeight != canvas.height) {
                this.cache.clear()
            }
            this.canvasWidth = canvas.width
            this.canvasHeight = canvas.height
        }

        fun buildPath(shape: SVGAVideoShapeEntity): Path {
            if (!this.cache.containsKey(shape)) {
                val path = Path()
                shape.shapePath?.let(path::set)
                this.cache[shape] = path
            }
            return this.cache[shape]!!
        }

    }

}
