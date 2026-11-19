package dev.svganext.player.entities

import android.graphics.Matrix
import dev.svganext.player.proto.FrameEntity
import dev.svganext.player.utils.SVGARect

import org.json.JSONObject

/**
 * Created by cuiminghui on 2016/10/17.
 */
internal class SVGAVideoSpriteFrameEntity {

    companion object {
        const val INDEX_A = 0
        const val INDEX_C = 1
        const val INDEX_TX = 2
        const val INDEX_B = 3
        const val INDEX_D = 4
        const val INDEX_TY = 5
        const val INDEX_ALPHA = 6
        const val INDEX_LAYOUT_X = 7
        const val INDEX_LAYOUT_Y = 8
        const val INDEX_LAYOUT_WIDTH = 9
        const val INDEX_LAYOUT_HEIGHT = 10
        const val FRAME_DATA_SIZE = 11
    }

    val frameData: FloatArray
    var maskPath: SVGAPathEntity? = null
    var shapes: List<SVGAVideoShapeEntity> = emptyList()

    val alpha: Double
        get() = frameData[INDEX_ALPHA].toDouble()

    val layoutX: Float
        get() = frameData[INDEX_LAYOUT_X]

    val layoutY: Float
        get() = frameData[INDEX_LAYOUT_Y]

    val layoutWidth: Float
        get() = frameData[INDEX_LAYOUT_WIDTH]

    val layoutHeight: Float
        get() = frameData[INDEX_LAYOUT_HEIGHT]

    val layout: SVGARect
        get() = SVGARect(
            layoutX.toDouble(),
            layoutY.toDouble(),
            layoutWidth.toDouble(),
            layoutHeight.toDouble()
        )

    val transform: Matrix
        get() {
            val matrix = Matrix()
            matrix.setValues(
                floatArrayOf(
                    frameData[INDEX_A], frameData[INDEX_C], frameData[INDEX_TX],
                    frameData[INDEX_B], frameData[INDEX_D], frameData[INDEX_TY],
                    0f, 0f, 1f
                )
            )
            return matrix
        }

    constructor(frameData: FloatArray) {
        require(frameData.size == FRAME_DATA_SIZE) { "Frame data must have length $FRAME_DATA_SIZE" }
        this.frameData = frameData
    }

    constructor(obj: JSONObject) {
        val data = FloatArray(FRAME_DATA_SIZE)
        val transformObj = obj.optJSONObject("transform")
        if (transformObj != null) {
            data[INDEX_A] = transformObj.optDouble("a", 1.0).toFloat()
            data[INDEX_C] = transformObj.optDouble("c", 0.0).toFloat()
            data[INDEX_TX] = transformObj.optDouble("tx", 0.0).toFloat()
            data[INDEX_B] = transformObj.optDouble("b", 0.0).toFloat()
            data[INDEX_D] = transformObj.optDouble("d", 1.0).toFloat()
            data[INDEX_TY] = transformObj.optDouble("ty", 0.0).toFloat()
        } else {
            data[INDEX_A] = 1.0f
            data[INDEX_C] = 0.0f
            data[INDEX_TX] = 0.0f
            data[INDEX_B] = 0.0f
            data[INDEX_D] = 1.0f
            data[INDEX_TY] = 0.0f
        }
        data[INDEX_ALPHA] = obj.optDouble("alpha", 0.0).toFloat()
        val layoutObj = obj.optJSONObject("layout")
        if (layoutObj != null) {
            data[INDEX_LAYOUT_X] = layoutObj.optDouble("x", 0.0).toFloat()
            data[INDEX_LAYOUT_Y] = layoutObj.optDouble("y", 0.0).toFloat()
            data[INDEX_LAYOUT_WIDTH] = layoutObj.optDouble("width", 0.0).toFloat()
            data[INDEX_LAYOUT_HEIGHT] = layoutObj.optDouble("height", 0.0).toFloat()
        }
        this.frameData = data

        obj.optString("clipPath")?.let { d ->
            if (d.isNotEmpty()) {
                maskPath = SVGAPathEntity(d)
            }
        }
        obj.optJSONArray("shapes")?.let {
            val mutableList: MutableList<SVGAVideoShapeEntity> = mutableListOf()
            for (i in 0 until it.length()) {
                it.optJSONObject(i)?.let {
                    mutableList.add(SVGAVideoShapeEntity(it))
                }
            }
            shapes = mutableList.toList()
        }
    }

    constructor(obj: FrameEntity) {
        val data = FloatArray(FRAME_DATA_SIZE)
        val transform = obj.transform
        if (transform != null) {
            data[INDEX_A] = transform.a ?: 1.0f
            data[INDEX_C] = transform.c ?: 0.0f
            data[INDEX_TX] = transform.tx ?: 0.0f
            data[INDEX_B] = transform.b ?: 0.0f
            data[INDEX_D] = transform.d ?: 1.0f
            data[INDEX_TY] = transform.ty ?: 0.0f
        } else {
            data[INDEX_A] = 1.0f
            data[INDEX_C] = 0.0f
            data[INDEX_TX] = 0.0f
            data[INDEX_B] = 0.0f
            data[INDEX_D] = 1.0f
            data[INDEX_TY] = 0.0f
        }
        data[INDEX_ALPHA] = obj.alpha ?: 0.0f
        val layout = obj.layout
        if (layout != null) {
            data[INDEX_LAYOUT_X] = layout.x ?: 0.0f
            data[INDEX_LAYOUT_Y] = layout.y ?: 0.0f
            data[INDEX_LAYOUT_WIDTH] = layout.width ?: 0.0f
            data[INDEX_LAYOUT_HEIGHT] = layout.height ?: 0.0f
        }
        this.frameData = data

        obj.clipPath?.takeIf { it.isNotEmpty() }?.let {
            maskPath = SVGAPathEntity(it)
        }
        this.shapes = obj.shapes.map {
            return@map SVGAVideoShapeEntity(it)
        }
    }

}
