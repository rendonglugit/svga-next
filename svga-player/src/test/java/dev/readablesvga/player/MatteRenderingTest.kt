// SPDX-License-Identifier: Apache-2.0
package dev.readablesvga.player

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import dev.readablesvga.player.proto.FrameEntity
import dev.readablesvga.player.proto.Layout
import dev.readablesvga.player.proto.MovieEntity
import dev.readablesvga.player.proto.MovieParams
import dev.readablesvga.player.proto.SpriteEntity
import dev.readablesvga.player.proto.Transform
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MatteRenderingTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun matteClipsContentWithoutClippingFollowingSprite() = verify("mask.matte", Color.TRANSPARENT)

    @Test fun missingMatteRestoresLayerAndKeepsFollowingSprite() = verify("missing.matte", Color.RED)

    private fun verify(matteKey: String, outside: Int) {
        fun sprite(key: String, width: Float, x: Float = 0f, matte: String? = null) = SpriteEntity(
            key, listOf(FrameEntity(1f, Layout(0f, 0f, width, 8f),
                Transform(1f, 0f, 0f, 1f, x, 0f), null, emptyList())), matte
        )
        val video = SVGAVideoEntity(MovieEntity("2.0", MovieParams(12f, 8f, 20, 1), emptyMap(),
            listOf(sprite("mask.matte", 4f), sprite("content", 8f, matte = matteKey), sprite("after", 4f, 8f)), emptyList()), temp.root)
        fun bitmap(color: Int) = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        video.imageMap["mask"] = bitmap(Color.WHITE)
        video.imageMap["content"] = bitmap(Color.RED)
        video.imageMap["after"] = bitmap(Color.BLUE)
        val output = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val saveCount = canvas.saveCount
        try {
            val dynamic = SVGADynamicEntity()
            val drawable = SVGADrawable(video, dynamic).apply { cleared = false; antiAlias = false }
            drawable.draw(canvas)
            assertEquals(saveCount, canvas.saveCount)
            assertEquals(Color.RED, output.getPixel(2, 4))
            assertEquals(outside, output.getPixel(6, 4))
            assertEquals(Color.BLUE, output.getPixel(10, 4))
            if (matteKey == "mask.matte") {
                val replacement = bitmap(Color.GREEN)
                val transparentMask = bitmap(Color.TRANSPARENT)
                try {
                    dynamic.setDynamicImage(replacement, "content")
                    output.eraseColor(Color.TRANSPARENT)
                    drawable.draw(canvas)
                    assertEquals(Color.GREEN, output.getPixel(2, 4))
                    assertEquals(Color.TRANSPARENT, output.getPixel(6, 4))
                    dynamic.setDynamicImage(transparentMask, "mask")
                    output.eraseColor(Color.TRANSPARENT)
                    drawable.draw(canvas)
                    assertEquals(Color.TRANSPARENT, output.getPixel(2, 4))
                    assertEquals(Color.BLUE, output.getPixel(10, 4))
                    assertEquals(saveCount, canvas.saveCount)
                } finally {
                    replacement.recycle()
                    transparentMask.recycle()
                }
            }
        } finally {
            video.clear()
            output.recycle()
        }
    }
}
