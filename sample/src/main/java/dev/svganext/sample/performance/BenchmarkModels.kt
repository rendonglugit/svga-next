// SPDX-License-Identifier: Apache-2.0
package dev.svganext.sample.performance

import android.os.Debug
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

data class BenchmarkConfig(val asset: String, val width: Int = 480, val height: Int = 270,
                           val samples: Int = 5, val playbackMillis: Long = 3000) {
    init {
        require(asset in ASSETS)
        require(width > 0 && height > 0)
        require(samples in 3..20)
        require(playbackMillis in 1500..10000)
    }
    companion object { val ASSETS = listOf("heartbeat.svga", "matteRect.svga", "Castle.svga") }
}

/** Nearest-rank p95; median interpolates the middle pair of an even sample count. */
object BenchmarkStatistics {
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        require(values.all { it.isFinite() })
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }
    fun p95(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        require(values.all { it.isFinite() })
        return values.sorted()[(ceil(values.size * 0.95).toInt() - 1).coerceAtLeast(0)]
    }
}

data class MemorySnapshot(val javaHeapBytes: Long, val nativeHeapBytes: Long, val processPssBytes: Long?) {
    companion object {
        fun capture(): MemorySnapshot {
            val runtime = Runtime.getRuntime()
            val pss = try { Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss.toLong() * 1024 }
                      catch (_: RuntimeException) { null }
            return MemorySnapshot(runtime.totalMemory() - runtime.freeMemory(), Debug.getNativeHeapAllocatedSize(), pss)
        }
    }
    fun toJson() = JSONObject().put("java_heap_bytes", javaHeapBytes).put("native_heap_bytes", nativeHeapBytes)
        .put("process_pss_bytes", processPssBytes ?: JSONObject.NULL)
}

data class RenderMeasurements(val draws: Int, val durationMillis: Double, val drawDurationsMs: List<Double>,
                              val mainFrameIntervalsMs: List<Double>) {
    val drawFps: Double get() = if (durationMillis > 0) draws * 1000.0 / durationMillis else 0.0
    fun toJson() = JSONObject().put("draw_count", draws).put("duration_ms", durationMillis)
        .put("draw_fps", drawFps).put("on_draw_p95_ms", BenchmarkStatistics.p95(drawDurationsMs) ?: JSONObject.NULL)
        .put("main_callback_interval_p95_ms", BenchmarkStatistics.p95(mainFrameIntervalsMs) ?: JSONObject.NULL)
        .put("on_draw_samples_ms", JSONArray(drawDurationsMs)).put("main_callback_intervals_ms", JSONArray(mainFrameIntervalsMs))
}

/** Main-thread only. Counts actual View.onDraw calls, not animator updates or display refreshes. */
class DrawRecorder {
    private var started = 0L
    private var active = false
    private val drawDurations = ArrayList<Double>()
    private val frameIntervals = ArrayList<Double>()
    fun begin() {
        check(!active)
        drawDurations.clear(); frameIntervals.clear()
        started = SystemClock.elapsedRealtimeNanos(); active = true
    }
    fun recordDraw(nanos: Long) { if (active) drawDurations.add(nanos / 1_000_000.0) }
    fun recordMainInterval(nanos: Long) { if (active && nanos > 0) frameIntervals.add(nanos / 1_000_000.0) }
    fun finish(): RenderMeasurements {
        check(active)
        active = false
        return RenderMeasurements(drawDurations.size, (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0,
                                  drawDurations.toList(), frameIntervals.toList())
    }
    fun cancel() { active = false }
}

data class EngineMeasurements(val id: String, val label: String, val sourceFps: Int, val kernelMs: List<Double>,
                              val repeatedLoadMs: List<Double>, val render: RenderMeasurements,
                              val estimatedResourceBytes: Long, val cachedBytes: Long,
                              val memoryBefore: MemorySnapshot, val memoryAfter: MemorySnapshot) {
    fun toJson() = JSONObject().put("id", id).put("label", label).put("source_fps", sourceFps)
        .put("kernel_parse_samples_ms", JSONArray(kernelMs)).put("kernel_parse_median_ms", BenchmarkStatistics.median(kernelMs))
        .put("kernel_parse_p95_ms", BenchmarkStatistics.p95(kernelMs)).put("repeat_load_samples_ms", JSONArray(repeatedLoadMs))
        .put("repeat_load_median_ms", BenchmarkStatistics.median(repeatedLoadMs)).put("repeat_load_p95_ms", BenchmarkStatistics.p95(repeatedLoadMs))
        .put("render", render.toJson()).put("estimated_resource_bytes", estimatedResourceBytes).put("cache_owned_bytes", cachedBytes)
        .put("memory_before", memoryBefore.toJson()).put("memory_after", memoryAfter.toJson())
}
