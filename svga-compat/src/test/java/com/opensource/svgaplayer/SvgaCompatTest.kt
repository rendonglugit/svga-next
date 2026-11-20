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
        SVGAActivitiesCache.instance.clear()
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
    fun safeClearPreventsDisposalWhileBoundToActiveViewOrCache() {
        val dummyFile = File(context.cacheDir, "test_cache")
        dummyFile.mkdirs()
        val entity = SVGAVideoEntity(testMovie(), dummyFile)

        assertFalse(entity.isBoundToActiveView)
        assertFalse(entity.isReleased)

        val view1 = Any()
        val view2 = Any()
        entity.attachView(view1)
        entity.attachView(view2)
        assertTrue(entity.isBoundToActiveView)

        // SafeClear should intercept clear() because views are attached
        entity.clear()
        assertFalse(entity.isReleased)

        // Detaching view1 still leaves view2 active, so entity remains released = false
        entity.detachView(view1)
        assertTrue(entity.isBoundToActiveView)
        assertFalse(entity.isReleased)

        // Detaching last view (not cached) triggers immediate cliff-drop release
        entity.detachView(view2)
        assertFalse(entity.isBoundToActiveView)
        assertTrue(entity.isReleased)
    }

    @Test
    fun cacheRetainsEntityEvenWhenViewDetachesUntilEvicted() {
        val dummyFile = File(context.cacheDir, "test_cache_cached")
        dummyFile.mkdirs()
        val entity = SVGAVideoEntity(testMovie(), dummyFile)
        val key = "anim_heartbeat"

        SVGAActivitiesCache.instance.put(key, entity)
        assertTrue(entity.isCachedInPool)

        val view = Any()
        entity.attachView(view)

        // View detaches while entity is still in cache
        entity.detachView(view)
        assertFalse(entity.isBoundToActiveView)
        // Must NOT release because it is retained in cache
        assertFalse(entity.isReleased)

        // Cache hit returns the same entity
        val cached = SVGAActivitiesCache.instance.get(key)
        assertSame(entity, cached)

        // Evicting from cache when no views are bound releases it immediately
        SVGAActivitiesCache.instance.remove(key)
        assertFalse(entity.isCachedInPool)
        assertTrue(entity.isReleased)
    }

    @Test
    fun svgaImageViewBindsAndUnbindsCompatEntityWithCliffDrop() {
        val dummyFile = File(context.cacheDir, "test_cache_view")
        dummyFile.mkdirs()
        val entity = SVGAVideoEntity(testMovie(), dummyFile)

        val imageView = SVGAImageView(context)
        imageView.setVideoItem(entity)
        assertTrue(entity.isBoundToActiveView)

        // Setting null unbinds and, because not cached, releases immediately
        imageView.setVideoItem(null)
        assertFalse(entity.isBoundToActiveView)
        assertTrue(entity.isReleased)
    }

    @Test
    fun svgaImageViewClearReleasesUncachedEntity() {
        val dummyFile = File(context.cacheDir, "test_cache_clear")
        dummyFile.mkdirs()
        val entity = SVGAVideoEntity(testMovie(), dummyFile)

        val imageView = SVGAImageView(context)
        imageView.setVideoItem(entity)
        assertTrue(entity.isBoundToActiveView)

        imageView.clear()
        assertFalse(entity.isBoundToActiveView)
        assertTrue(entity.isReleased)
    }

    @Test
    fun svgaImageViewAppointFrameAndLifecycleAPIs() {
        val dummyFile = File(context.cacheDir, "test_cache_appoint")
        dummyFile.mkdirs()
        val entity = SVGAVideoEntity(testMovie(), dummyFile)

        val imageView = SVGAImageView(context)
        imageView.startAfterAttached = true
        assertTrue(imageView.startAfterAttached)

        imageView.startAnimationFromAppointFrame(entity, 3)
        assertTrue(entity.isBoundToActiveView)

        imageView.startAnimationFromAppointFrame(5)
        imageView.clear()
        assertTrue(entity.isReleased)
    }

    @Test
    fun svgaSoundManagerAndActivitiesCacheJavaCompat() {
        SVGASoundManager.init()
        assertTrue(SVGASoundManager.isInit())
        SVGASoundManager.setVolume(0.5f)

        val dummyFile = File(context.cacheDir, "test_cache_sound")
        dummyFile.mkdirs()
        val entity = SVGAVideoEntity(testMovie(), dummyFile)
        SVGASoundManager.setVolume(0f, entity)

        entity.clearAudioList()
        entity.clearCallback()

        assertNotNull(SVGAActivitiesCache.instance)
        assertNotNull(SVGAActivitiesCache.Companion.instance)
        SVGAActivitiesCache.instance.setMaxSize(10 * 1024 * 1024)
        SVGAActivitiesCache.instance.trimCache(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)

        val simpleCallback = object : SVGASimpleCallback() {
            var stepCalled = false
            override fun onStep(frame: Int, percentage: Double) {
                stepCalled = true
            }
        }
        val imageView = SVGAImageView(context)
        imageView.callback = simpleCallback
        simpleCallback.onStep(1, 0.1)
        assertTrue(simpleCallback.stepCalled)

        entity.clear()
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

        val drawer = com.opensource.svgaplayer.drawer.SVGACanvasDrawer(entity, dynamic)
        assertNotNull(drawer)
        val bitmap = android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawer.drawFrame(canvas, 0)
        entity.clear()
    }
}
