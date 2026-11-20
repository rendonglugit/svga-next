// SPDX-License-Identifier: Apache-2.0
package com.opensource.svgaplayer

import android.content.Context
import com.opensource.svgaplayer.internal.SvgaInitProvider
import dev.svganext.player.proto.MovieEntity
import dev.svganext.player.proto.MovieParams
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SvgaCompatTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun initProviderInitializesCacheAndAudioWithoutCrash() {
        val controller = Robolectric.buildContentProvider(SvgaInitProvider::class.java).create()
        assertNotNull(controller.get())
        assertTrue(SVGACache.isInitialized())
    }

    private fun testMovie(): MovieEntity {
        val params = MovieParams.Builder()
            .viewBoxWidth(100f)
            .viewBoxHeight(100f)
            .fps(20)
            .frames(10)
            .build()
        return MovieEntity.Builder()
            .version("2.0")
            .params(params)
            .build()
    }

    @Test
    fun safeClearPreventsDisposalWhileBoundToActiveView() {
        val dummyFile = File(context.cacheDir, "test_cache")
        dummyFile.mkdirs()
        val entity = SVGAVideoEntity(testMovie(), dummyFile)

        assertFalse(entity.isBoundToActiveView)
        assertFalse(entity.isReleased)

        val view1 = Any()
        entity.attachView(view1)
        assertTrue(entity.isBoundToActiveView)

        // SafeClear should intercept clear() because view1 is attached
        entity.clear()
        assertFalse(entity.isReleased)

        // Detach view1
        entity.detachView(view1)
        assertFalse(entity.isBoundToActiveView)

        // Now clear() should proceed
        entity.clear()
        assertTrue(entity.isReleased)
    }

    @Test
    fun svgaImageViewBindsAndUnbindsCompatEntity() {
        val dummyFile = File(context.cacheDir, "test_cache_view")
        dummyFile.mkdirs()
        val entity = SVGAVideoEntity(testMovie(), dummyFile)

        val imageView = SVGAImageView(context)
        imageView.setVideoItem(entity)
        assertTrue(entity.isBoundToActiveView)

        // Setting another video item or null unbinds entity
        imageView.setVideoItem(null)
        assertFalse(entity.isBoundToActiveView)
    }

    @Test
    fun sharedParserAndCompatTypesAreAccessible() {
        val parser = SVGAParser.shareParser()
        assertNotNull(parser)

        val player = SVGAPlayer(context)
        assertNotNull(player)

        val dynamic = SVGADynamicEntity()
        assertNotNull(dynamic)

        val dummyFile = File(context.cacheDir, "test_cache_drawable")
        dummyFile.mkdirs()
        val entity = SVGAVideoEntity(testMovie(), dummyFile)
        val drawable = SVGADrawable(entity, dynamic)
        assertNotNull(drawable)
    }
}
