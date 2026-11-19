// SPDX-License-Identifier: Apache-2.0
package dev.svganext.sample.performance

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import dev.svganext.runtime.CancelHandle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class BenchmarkTests {
    @Test fun medianAndNearestRankP95PreserveUnitsAndDoNotInventEmptyValues() {
        assertEquals(4.0, BenchmarkStatistics.median(listOf(9.0, 2.0, 4.0))!!, 0.0)
        assertEquals(3.5, BenchmarkStatistics.median(listOf(1.0, 6.0, 3.0, 4.0))!!, 0.0)
        assertEquals(19.0, BenchmarkStatistics.p95((1..20).map(Int::toDouble))!!, 0.0)
        assertEquals(0.003, BenchmarkStatistics.median(listOf(0.001, 0.003, 0.009))!!, 0.0)
        assertNull(BenchmarkStatistics.median(emptyList()))
        assertNull(BenchmarkStatistics.p95(emptyList()))
    }

    @Test fun invalidSampleCountsAndAssetsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { BenchmarkConfig("unknown.svga") }
        assertThrows(IllegalArgumentException::class.java) { BenchmarkConfig("heartbeat.svga", samples = 0) }
        assertThrows(IllegalArgumentException::class.java) { BenchmarkConfig("heartbeat.svga", playbackMillis = 0) }
    }

    @Test fun renderFpsCountsActualDrawsInsteadOfMainThreadCallbacks() {
        val render = RenderMeasurements(30, 3000.0, listOf(0.3, 0.9), List(179) { 16.666 })
        assertEquals(10.0, render.drawFps, 0.0)
        assertEquals(10.0, render.toJson().getDouble("draw_fps"), 0.0)
        assertEquals(0.9, render.toJson().getDouble("on_draw_p95_ms"), 0.0)
    }

    @Test fun exportedMemoryPreservesNegativeAndMissingProcessSnapshots() {
        val before = MemorySnapshot(100, 200, null)
        val after = MemorySnapshot(50, 150, null)
        val measurement = EngineMeasurements("official", "official", 20, listOf(1.0, 2.0, 3.0), listOf(4.0, 5.0, 6.0),
            RenderMeasurements(60, 3000.0, listOf(1.0), listOf(16.0)), 1024, 0, before, after).toJson()
        assertEquals(2.0, measurement.getDouble("kernel_parse_median_ms"), 0.0)
        assertEquals(0L, measurement.getLong("cache_owned_bytes"))
        assertTrue(measurement.getJSONObject("memory_after").isNull("process_pss_bytes"))
        assertEquals(-50L, after.javaHeapBytes - before.javaHeapBytes)
    }

    @Test fun cancelledDecodeCannotRestartTheRunAndClosesItsLateResult() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val engine = DelayedEngine()
        val runner = BenchmarkRunner(activity, FrameLayout(activity), listOf(engine), DirectExecutor())
        var finished = 0
        runner.run(BenchmarkConfig("heartbeat.svga"), true, {}, { finished++ })
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(engine.callback)
        runner.close()
        var disposed = 0
        engine.callback!!(Result.success(object : LoadedBenchmarkAnimation {
            override val estimatedBytes = 1L
            override val sourceFps = 20
            override fun createPlayback(context: android.content.Context, recorder: DrawRecorder): BenchmarkPlayback = error("A cancelled result must not display")
            override fun close() { disposed++ }
        }))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, disposed)
        assertEquals(0, finished)
        assertEquals(1, engine.closed)
        controller.pause().stop().destroy()
    }

    @Test fun failedImplementationsAreExportedAsFailuresWithoutFabricatedTimings() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        fun failed(id: String) = object : BenchmarkEngine {
            override val id = id
            override val label = id
            override fun kernelLoad(config: BenchmarkConfig, callback: (Result<LoadedBenchmarkAnimation>) -> Unit): CancelHandle {
                callback(Result.failure(IllegalStateException("expected decode failure")))
                return CancelHandle {}
            }
            override fun repeatedLoad(config: BenchmarkConfig, callback: (Result<LoadedBenchmarkAnimation>) -> Unit) = error("Not expected")
            override fun clearCache() = Unit
            override fun cachedBytes() = 0L
            override fun close() = Unit
        }
        var result: org.json.JSONObject? = null
        val runner = BenchmarkRunner(activity, FrameLayout(activity), listOf(failed("readable"), failed("official")), DirectExecutor())
        runner.run(BenchmarkConfig("heartbeat.svga"), true, {}, { result = it.getOrThrow() })
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(checkNotNull(result).getBoolean("partial"))
        val engines = checkNotNull(result).getJSONArray("engines")
        assertEquals(2, engines.length())
        for (i in 0 until engines.length()) {
            assertEquals("failed", engines.getJSONObject(i).getString("status"))
            assertFalse(engines.getJSONObject(i).has("kernel_parse_median_ms"))
            assertFalse(engines.getJSONObject(i).has("render"))
        }
        controller.pause().stop().destroy()
    }

    private class DelayedEngine : BenchmarkEngine {
        override val id = "readable"
        override val label = "test"
        var callback: ((Result<LoadedBenchmarkAnimation>) -> Unit)? = null
        var closed = 0
        override fun kernelLoad(config: BenchmarkConfig, callback: (Result<LoadedBenchmarkAnimation>) -> Unit): CancelHandle {
            this.callback = callback
            return CancelHandle {}
        }
        override fun repeatedLoad(config: BenchmarkConfig, callback: (Result<LoadedBenchmarkAnimation>) -> Unit) = error("Not expected")
        override fun clearCache() = Unit
        override fun cachedBytes() = 0L
        override fun close() { closed++ }
    }
    private class DirectExecutor : AbstractExecutorService() {
        private var stopped = false
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return mutableListOf() }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = stopped
    }
}
