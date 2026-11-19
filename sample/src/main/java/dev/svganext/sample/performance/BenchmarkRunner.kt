// SPDX-License-Identifier: Apache-2.0
package dev.svganext.sample.performance

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Gravity
import android.widget.FrameLayout
import dev.svganext.runtime.CancelHandle
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Sequential in-process comparison. Parsing and runtime-cache results are separate workloads. */
class BenchmarkRunner internal constructor(
    private val activity: Activity,
    private val preview: FrameLayout,
    private val engines: List<BenchmarkEngine>,
    private val background: java.util.concurrent.ExecutorService,
) : AutoCloseable {
    constructor(activity: Activity, preview: FrameLayout) : this(activity, preview,
        listOf(ReadableBenchmarkEngine(activity), OfficialBenchmarkEngine(activity)), Executors.newSingleThreadExecutor())
    private val handler = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(true)
    private var phase = 0
    private var pending: CancelHandle? = null
    private var held: LoadedBenchmarkAnimation? = null
    private var playback: BenchmarkPlayback? = null
    private var recorder: DrawRecorder? = null
    private var frameCallback: Choreographer.FrameCallback? = null
    private lateinit var config: BenchmarkConfig
    private lateinit var progress: (String) -> Unit
    private lateinit var completion: (Result<JSONObject>) -> Unit
    private val reports = ArrayList<JSONObject>()
    private val closedEngines = HashSet<BenchmarkEngine>()
    private var currentEngineIndex = -1
    private var assetSha256 = ""
    private var engineOrder: List<BenchmarkEngine> = emptyList()

    fun run(config: BenchmarkConfig, readableFirst: Boolean, progress: (String) -> Unit, completion: (Result<JSONObject>) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!this::config.isInitialized) { "Each runner executes one comparison" }
        this.config = config; this.progress = progress; this.completion = completion
        engineOrder = if (readableFirst) engines else engines.reversed()
        progress("读取测试资源…")
        background.execute {
            val hash = runCatching {
                val digest = MessageDigest.getInstance("SHA-256")
                activity.assets.open(config.asset).use { stream ->
                    val buffer = ByteArray(8192)
                    var read = stream.read(buffer)
                    while (read >= 0) { if (read > 0) digest.update(buffer, 0, read); read = stream.read(buffer) }
                }
                digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            handler.post {
                if (!active.get()) return@post
                hash.fold(onSuccess = { assetSha256 = it; measureEngine(0) }, onFailure = ::fail)
            }
        }
    }

    private fun measureEngine(index: Int) {
        if (!active.get()) return
        currentEngineIndex = index
        if (index == engineOrder.size) { finish(); return }
        engines.forEach { it.clearCache() }
        val engine = engineOrder[index]
        progress("${engine.label} · 内核预热")
        // Process snapshots are observations, not exact retained allocations; no forced GC.
        snapshot { before ->
            load(engine, repeated = false) { warm, _ ->
                warm.close()
                val kernel = ArrayList<Double>()
                progress("${engine.label} · 内核解析 ${config.samples} 轮")
                fun trial() {
                    load(engine, repeated = false) { loaded, millis ->
                        kernel.add(millis)
                        if (kernel.size < config.samples) { loaded.close(); handler.post(::trial) }
                        else {
                            held = loaded
                            val estimate = loaded.estimatedBytes
                            val fps = loaded.sourceFps
                            render(engine, loaded) { rendering ->
                                snapshot { after ->
                                    releasePlayback()
                                    measureRepeated(engine) { repeated ->
                                        reports.add(EngineMeasurements(engine.id, engine.label, fps, kernel.toList(), repeated,
                                            rendering, estimate, engine.cachedBytes(), before, after).toJson().put("status", "complete"))
                                        handler.post { measureEngine(index + 1) }
                                    }
                                }
                            }
                        }
                    }
                }
                handler.postDelayed(::trial, 100)
            }
        }
    }

    private fun measureRepeated(engine: BenchmarkEngine, done: (List<Double>) -> Unit) {
        progress("${engine.label} · 重复加载预热")
        engine.clearCache()
        load(engine, repeated = true) { warm, _ ->
            warm.close()
            val samples = ArrayList<Double>()
            progress("${engine.label} · 重复加载 ${config.samples} 轮")
            fun trial() {
                load(engine, repeated = true) { value, millis ->
                    samples.add(millis); value.close()
                    if (samples.size < config.samples) handler.post(::trial) else done(samples.toList())
                }
            }
            handler.postDelayed(::trial, 100)
        }
    }

    private fun load(engine: BenchmarkEngine, repeated: Boolean, done: (LoadedBenchmarkAnimation, Double) -> Unit) {
        val token = ++phase
        val begin = System.nanoTime()
        var received = false
        val timeout = Runnable { if (active.get() && token == phase && !received) fail(IllegalStateException("${engine.label} 加载超过 15 秒")) }
        handler.postDelayed(timeout, 15_000)
        val callback: (Result<LoadedBenchmarkAnimation>) -> Unit = callback@{ result ->
            check(Looper.myLooper() == Looper.getMainLooper())
            if (!active.get() || phase != token || received) { result.getOrNull()?.close(); return@callback }
            val elapsed = (System.nanoTime() - begin) / 1_000_000.0
            received = true; pending = null; handler.removeCallbacks(timeout)
            result.fold(onSuccess = { value ->
                try { done(value, elapsed) } catch (error: Exception) { value.close(); fail(error) }
            }, onFailure = ::fail)
        }
        try {
            val request = if (repeated) engine.repeatedLoad(config, callback) else engine.kernelLoad(config, callback)
            if (!received) pending = request
        } catch (error: Exception) { handler.removeCallbacks(timeout); fail(error) }
    }

    private fun render(engine: BenchmarkEngine, loaded: LoadedBenchmarkAnimation, done: (RenderMeasurements) -> Unit) {
        progress("${engine.label} · 播放采样 ${config.playbackMillis / 1000.0} 秒")
        val draws = DrawRecorder()
        recorder = draws
        val video = loaded.createPlayback(activity, draws)
        playback = video
        preview.removeAllViews()
        preview.addView(video.view, FrameLayout.LayoutParams(config.width, config.height, Gravity.CENTER))
        video.start()
        // Exclude initial attach/layout/class initialization from the playback window.
        handler.postDelayed({
            if (!active.get()) return@postDelayed
            draws.begin()
            var previous = 0L
            val callback = object : Choreographer.FrameCallback {
                override fun doFrame(time: Long) {
                    if (!active.get() || recorder !== draws) return
                    if (previous != 0L) draws.recordMainInterval(time - previous)
                    previous = time
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
            frameCallback = callback
            Choreographer.getInstance().postFrameCallback(callback)
            handler.postDelayed({
                if (!active.get()) return@postDelayed
                Choreographer.getInstance().removeFrameCallback(callback)
                frameCallback = null
                val result = draws.finish()
                if (result.draws == 0) fail(IllegalStateException("${engine.label} 未产生可见绘制，结果不可用于比较"))
                else done(result)
            }, config.playbackMillis)
        }, 250)
    }

    private fun snapshot(done: (MemorySnapshot) -> Unit) {
        background.execute {
            val value = MemorySnapshot.capture()
            handler.post { if (active.get()) done(value) }
        }
    }

    private fun finish() {
        val data = JSONObject().put("schema", "svga-next-performance-v1")
            .put("captured_at_utc", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date()))
            .put("device", JSONObject().put("model", Build.MODEL).put("manufacturer", Build.MANUFACTURER).put("sdk", Build.VERSION.SDK_INT))
            .put("debuggable", activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            .put("readable_version", "0.1.0").put("official_commit", "662d0dc11824e35a3f3fcd247f491efce2bf5ec5")
            .put("asset", config.asset).put("asset_sha256", assetSha256).put("width_px", config.width).put("height_px", config.height)
            .put("warmup_per_workload", 1).put("samples", config.samples).put("playback_ms", config.playbackMillis)
            .put("engine_order", JSONArray(engineOrder.map { it.id })).put("engines", JSONArray(reports))
            .put("partial", reports.any { it.optString("status") != "complete" })
            .put("audio_policy", "Both public shared SoundManagers initialized before measurement; volume zero; preparation remains measured")
            .put("definitions", JSONObject().put("kernel", "asset open to prepared main-thread callback; no parsed entity cache")
                .put("repeat", "same asset and dimensions after one unmeasured load; Readable runtime cache vs upstream parser")
                .put("draw_fps", "actual View.onDraw calls per second; bounded by animation source FPS, not screen refresh")
                .put("on_draw", "CPU recording time inside View.onDraw; excludes GPU/presentation")
                .put("main_interval", "Choreographer callback intervals; not official FrameMetrics jank")
                .put("resource_bytes", "bitmap allocation bytes plus retained encoded media; excludes object graph/native audio")
                .put("memory", "same-process snapshots before warmup and after playback; not retained allocation or peak, no forced GC"))
        completion(Result.success(data))
        close()
    }

    private fun fail(error: Throwable) {
        if (active.get() && currentEngineIndex in engineOrder.indices) {
            val index = currentEngineIndex
            val failed = engineOrder[index]
            phase++
            handler.removeCallbacksAndMessages(null)
            pending?.cancel(); pending = null
            releasePlayback()
            reports.add(JSONObject().put("id", failed.id).put("label", failed.label).put("status", "failed")
                .put("error", error.message ?: error.javaClass.simpleName))
            closeEngine(failed)
            handler.post { measureEngine(index + 1) }
            return
        }
        if (!active.getAndSet(false)) return
        completion(Result.failure(error))
        cleanup()
    }

    private fun releasePlayback() {
        frameCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        frameCallback = null
        recorder?.cancel(); recorder = null
        playback?.close(); playback = null
        preview.removeAllViews()
        held?.close(); held = null
    }

    override fun close() { active.set(false); cleanup() }
    private fun cleanup() {
        phase++
        handler.removeCallbacksAndMessages(null)
        pending?.cancel(); pending = null
        releasePlayback()
        engines.forEach(::closeEngine)
        background.shutdownNow()
    }
    private fun closeEngine(engine: BenchmarkEngine) { if (closedEngines.add(engine)) engine.close() }
}
