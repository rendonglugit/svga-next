// SPDX-License-Identifier: Apache-2.0
package dev.readablesvga.runtime

import android.os.Looper
import android.content.ComponentCallbacks2
import dev.readablesvga.core.ResourceLease
import dev.readablesvga.player.SVGAVideoEntity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class SvgaLoaderTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var decoder: FakeVideoDecoder
    private lateinit var loader: SvgaLoader
    private val disposed = mutableListOf<SVGAVideoEntity>()
    private val leases = mutableListOf<ResourceLease<SVGAVideoEntity>>()
    private val request = SvgaRequest(SvgaSource.Asset("demo.svga"))

    @Before fun setUp() {
        decoder = FakeVideoDecoder()
        loader = SvgaLoader(context, SvgaConfig(32), decoder) {
            disposed.add(it)
            it.clear()
        }
    }

    @After fun tearDown() {
        leases.forEach { it.close() }
        loader.close()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun identicalRequestsShareDecodeButEachReceiveOwnership() {
        repeat(2) {
            loader.load(request) { result ->
                assertSame(Looper.getMainLooper(), Looper.myLooper())
                leases.add(result.getOrThrow())
            }
        }
        assertEquals(1, decoder.calls.size)
        val video = emptyVideo(context)
        decoder.calls.single().succeed(video)
        assertTrue(leases.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, leases.size)
        assertNotSame(leases[0], leases[1])
        assertSame(video, leases[0].value)
        assertSame(video, leases[1].value)
        loader.close()
        assertTrue(disposed.isEmpty())
        leases[0].close()
        assertTrue(disposed.isEmpty())
        leases[1].close()
        assertEquals(listOf(video), disposed)
    }

    @Test fun differentRequestedSizesAreNotCoalesced() {
        loader.load(request.copy(width = 32, height = 32)) { leases.add(it.getOrThrow()) }
        loader.load(request.copy(width = 256, height = 256)) { leases.add(it.getOrThrow()) }
        assertEquals(2, decoder.calls.size)
    }

    @Test fun cancellationAfterQueueingSuccessReleasesUndeliveredLease() {
        var called = false
        val handle = loader.load(request) { called = true }
        val video = emptyVideo(context)
        decoder.calls.single().succeed(video)
        handle.cancel()
        loader.clearMemoryCache()
        assertEquals(listOf(video), disposed)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(called)
    }

    @Test fun lastSubscriberCancellationDisposesAnUnavoidableLateDecode() {
        var called = false
        val handle = loader.load(request) { called = true }
        handle.cancel()
        val call = decoder.calls.single()
        assertEquals(1, call.cancelCount)
        val video = emptyVideo(context)
        call.succeed(video)
        assertEquals(listOf(video), disposed)
        assertEquals(0, loader.stats().entryCount)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(called)
    }

    @Test fun oneSubscriberCannotCancelAnotherSubscriber() {
        val first = loader.load(request) { fail("cancelled callback delivered") }
        loader.load(request) { leases.add(it.getOrThrow()) }
        first.cancel()
        assertEquals(0, decoder.calls.single().cancelCount)
        val video = emptyVideo(context)
        decoder.calls.single().succeed(video)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, leases.size)
        assertSame(video, leases.single().value)
    }

    @Test fun cacheHitIsAsynchronousAndCreatesANewLease() {
        loader.load(request) { leases.add(it.getOrThrow()) }
        val video = emptyVideo(context)
        decoder.calls.single().succeed(video)
        shadowOf(Looper.getMainLooper()).idle()
        val previous = leases.single()
        previous.close()
        loader.load(request) { leases.add(it.getOrThrow()) }
        assertEquals(1, leases.size)
        assertEquals(1, decoder.calls.size)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotSame(previous, leases.last())
        assertSame(video, leases.last().value)
    }

    @Test fun closeSuppressesAlreadyQueuedDelivery() {
        var called = false
        loader.load(request) { called = true }
        val video = emptyVideo(context)
        decoder.calls.single().succeed(video)
        loader.close()
        assertEquals(listOf(video), disposed)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(called)
    }

    @Test fun loadingAfterCloseReportsFailureOnMainThread() {
        loader.close()
        var error: Throwable? = null
        loader.load(request) { error = it.exceptionOrNull() }
        assertNull(error)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(error is IllegalStateException)
        assertTrue(decoder.calls.isEmpty())
    }

    @Test fun duplicateSuccessDoesNotDisposeTheFirstCachedVideo() {
        loader.load(request) { leases.add(it.getOrThrow()) }
        val video = emptyVideo(context)
        val call = decoder.calls.single()
        call.succeed(video)
        call.succeed(video)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, leases.size)
        assertTrue(disposed.isEmpty())
        val unexpected = emptyVideo(context)
        call.succeed(unexpected)
        assertEquals(listOf(unexpected), disposed)
        assertSame(video, leases.single().value)
    }

    @Test fun synchronousDecoderCompletionStillProducesOneDeferredCallback() {
        loader.close()
        val video = emptyVideo(context)
        val immediate = VideoDecoder { _, complete ->
            complete(Result.success(video))
            CancelHandle { }
        }
        loader = SvgaLoader(context, SvgaConfig(0), immediate) { disposed.add(it); it.clear() }
        loader.load(request) { leases.add(it.getOrThrow()) }
        assertTrue(leases.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, leases.size)
        assertEquals(0, loader.stats().entryCount)
        assertSame(video, leases.single().value)
    }

    @Test fun anEvictionCleanupFailureCannotInvalidateTheIncomingCachedVideo() {
        loader.close()
        val previous = emptyVideo(context)
        val attempts = mutableListOf<SVGAVideoEntity>()
        loader = SvgaLoader(context, SvgaConfig(1), decoder) { video ->
            attempts.add(video)
            if (video === previous) throw IllegalStateException("Simulated platform cleanup failure")
            video.clear()
        }
        loader.load(request) { leases.add(it.getOrThrow()) }
        decoder.calls[0].succeed(previous)
        shadowOf(Looper.getMainLooper()).idle()
        leases[0].close()
        val nextRequest = request.copy(source = SvgaSource.Asset("next.svga"))
        loader.load(nextRequest) { leases.add(it.getOrThrow()) }
        val incoming = emptyVideo(context)
        decoder.calls[1].succeed(incoming)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(incoming, leases.last().value)
        assertEquals(listOf(previous), attempts)
        leases.last().close()
        loader.load(nextRequest) { leases.add(it.getOrThrow()) }
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(incoming, leases.last().value)
        assertEquals(2, decoder.calls.size)
        loader.close()
        leases.last().close()
        assertEquals(listOf(previous, incoming), attempts)
    }

    @Test fun closeAlsoSuppressesAQueuedSourceIdentityFailure() {
        val broken = object : File("broken.svga") {
            override fun getCanonicalPath(): String = throw IOException("Identity unavailable")
        }
        var called = false
        loader.load(SvgaRequest(SvgaSource.File(broken))) { called = true }
        loader.close()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(called)
        assertTrue(decoder.calls.isEmpty())
    }

    @Test fun concurrentCancellationAndDecodeCompletionDisposeExactlyOnce() {
        loader.close()
        val cleanup = AtomicInteger()
        loader = SvgaLoader(context, SvgaConfig(0), decoder) { cleanup.incrementAndGet(); it.clear() }
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(16) { index ->
                var called = false
                val handle = loader.load(request.copy(source = SvgaSource.Asset("race-$index.svga"))) { called = true }
                val call = decoder.calls.last()
                val video = emptyVideo(context)
                val ready = CountDownLatch(2)
                val release = CountDownLatch(1)
                val cancel = workers.submit {
                    ready.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    handle.cancel()
                }
                val complete = workers.submit {
                    ready.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    call.succeed(video)
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                release.countDown()
                cancel.get(5, TimeUnit.SECONDS)
                complete.get(5, TimeUnit.SECONDS)
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse(called)
                assertEquals(index + 1, cleanup.get())
            }
        } finally {
            workers.shutdownNow()
        }
    }

    @Test fun moderateTrimCannotOverflowALongCacheBudget() {
        loader.close()
        loader = SvgaLoader(context, SvgaConfig(Long.MAX_VALUE), decoder)
        loader.trimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        assertEquals(Long.MAX_VALUE, loader.stats().maxBytes)
        assertEquals(0L, loader.stats().cachedBytes)
    }
}
