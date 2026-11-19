// SPDX-License-Identifier: Apache-2.0
package dev.readablesvga.runtime

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import dev.readablesvga.player.SVGADrawable
import dev.readablesvga.player.SVGACallback
import dev.readablesvga.player.R
import dev.readablesvga.player.SVGAVideoEntity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class ManagedSvgaImageViewTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var container: FrameLayout
    private lateinit var view: ManagedSvgaImageView
    private lateinit var decoder: FakeVideoDecoder
    private lateinit var loader: SvgaLoader
    private val disposed = mutableListOf<SVGAVideoEntity>()
    private val request = SvgaRequest(SvgaSource.Asset("demo.svga"))
    private var oldWindowVisibility: String? = null

    @Before fun setUp() {
        // Robolectric 4.16's WindowManager defaults to omitting Android's
        // ADD_FLAG_APP_VISIBLE. Enable its documented migration toggle so
        // actual View.windowVisibility models a visible Android window.
        oldWindowVisibility = System.getProperty("robolectric.areWindowsMarkedVisible")
        System.setProperty("robolectric.areWindowsMarkedVisible", "true")
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        container = FrameLayout(activity)
        activity.setContentView(container)
        view = ManagedSvgaImageView(activity).apply { autoPlay = false }
        container.addView(view, FrameLayout.LayoutParams(200, 200))
        controller.visible()
        controller.windowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        assertTrue("Fixture must attach its view", view.isAttachedToWindow)
        assertTrue("Fixture must show its view", view.isShown)
        assertEquals("Fixture window must be visible", View.VISIBLE, view.windowVisibility)
        decoder = FakeVideoDecoder()
        loader = SvgaLoader(activity, SvgaConfig(32), decoder) { disposed.add(it); it.clear() }
    }

    @After fun tearDown() {
        if (::view.isInitialized) view.clearRequest()
        if (::loader.isInitialized) loader.close()
        if (::controller.isInitialized) controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        if (oldWindowVisibility == null) System.clearProperty("robolectric.areWindowsMarkedVisible")
        else System.setProperty("robolectric.areWindowsMarkedVisible", oldWindowVisibility!!)
    }

    @Test fun aNewBindingRejectsAnOldDecodeResult() {
        val loaded = mutableListOf<SvgaRequest>()
        view.onLoaded = { loaded.add(it) }
        view.bind(loader, request)
        val replacement = request.copy(source = SvgaSource.Asset("replacement.svga"))
        view.bind(loader, replacement)
        assertEquals(2, decoder.calls.size)
        val current = emptyVideo(view.context)
        decoder.calls[1].succeed(current)
        shadowOf(Looper.getMainLooper()).idle()
        val stale = emptyVideo(view.context)
        decoder.calls[0].succeed(stale)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(current, (view.drawable as SVGADrawable).videoItem)
        assertEquals(listOf(replacement), loaded)
        assertEquals(listOf(stale), disposed)
    }

    @Test fun detachCancelsAndReattachLoadsTheRememberedBinding() {
        view.bind(loader, request)
        container.removeView(view)
        assertEquals(1, decoder.calls.single().cancelCount)
        val stale = emptyVideo(view.context)
        decoder.calls.single().succeed(stale)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(view.drawable)
        assertEquals(listOf(stale), disposed)
        container.addView(view)
        assertEquals(2, decoder.calls.size)
        val current = emptyVideo(view.context)
        decoder.calls.last().succeed(current)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(current, (view.drawable as SVGADrawable).videoItem)
    }

    @Test fun reattachBeforeMainQueueDrainsKeepsTheSameDrawable() {
        view.bind(loader, request)
        decoder.calls.single().succeed(emptyVideo(view.context))
        shadowOf(Looper.getMainLooper()).idle()
        val original = view.drawable as SVGADrawable
        container.removeView(view)
        assertSame(original, view.drawable)
        container.addView(view)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(original, view.drawable)
        assertEquals(1, decoder.calls.size)
        assertTrue(disposed.isEmpty())
    }

    @Test fun invisibilityDropsTheViewLeaseButKeepsTheCacheReusable() {
        view.bind(loader, request)
        val video = emptyVideo(view.context)
        decoder.calls.single().succeed(video)
        shadowOf(Looper.getMainLooper()).idle()
        view.visibility = View.INVISIBLE
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(view.drawable)
        assertTrue(disposed.isEmpty())
        view.visibility = View.VISIBLE
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, decoder.calls.size)
        assertSame(video, (view.drawable as SVGADrawable).videoItem)
    }

    @Test fun clearEndsTheBindingInsteadOfReloadingOnReattach() {
        view.bind(loader, request)
        view.clearRequest()
        container.removeView(view)
        container.addView(view)
        assertEquals(1, decoder.calls.size)
        assertNull(view.drawable)
    }

    @Test fun temporaryDetachCannotRestartLoadingUntilItFinishes() {
        view.bind(loader, request)
        view.onStartTemporaryDetach()
        view.visibility = View.INVISIBLE
        view.visibility = View.VISIBLE
        assertEquals(1, decoder.calls.size)
        view.onFinishTemporaryDetach()
        assertEquals(2, decoder.calls.size)
    }

    @Test fun replacingAnAnimationDoesNotFireItsCompletionObserver() {
        var completions = 0
        view.callback = object : SVGACallback {
            override fun onPause() = Unit
            override fun onRepeat() = Unit
            override fun onStep(frame: Int, percentage: Double) = Unit
            override fun onFinished() {
                completions++
                view.bind(loader, request.copy(source = SvgaSource.Asset("unexpected.svga")))
            }
        }
        view.bind(loader, request)
        decoder.calls.single().succeed(emptyVideo(view.context))
        shadowOf(Looper.getMainLooper()).idle()
        view.startAnimation()
        val replacement = request.copy(source = SvgaSource.Asset("next.svga"))
        view.bind(loader, replacement)
        assertEquals(0, completions)
        assertEquals(2, decoder.calls.size)
        assertEquals(replacement, decoder.calls.last().request)
    }

    @Test fun xmlAutoPlayFalseDisplaysWithoutStartingAnimator() {
        val attributes = Robolectric.buildAttributeSet().addAttribute(R.attr.autoPlay, "false").build()
        container.removeView(view)
        view = ManagedSvgaImageView(controller.get(), attributes)
        container.addView(view)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        view.bind(loader, request)
        decoder.calls.single().succeed(emptyVideo(view.context))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(view.autoPlay)
        assertFalse(view.isAnimating)
        assertNotNull(view.drawable)
    }
}
