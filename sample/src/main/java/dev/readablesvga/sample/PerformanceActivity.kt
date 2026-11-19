// SPDX-License-Identifier: Apache-2.0
package dev.readablesvga.sample

import android.app.Activity
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import dev.readablesvga.sample.performance.BenchmarkConfig
import dev.readablesvga.sample.performance.BenchmarkRunner
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Same-resource, sequential device measurements; no prefilled performance scores. */
class PerformanceActivity : Activity() {
    private lateinit var asset: Spinner
    private lateinit var rounds: Spinner
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var export: Button
    private lateinit var status: TextView
    private lateinit var conditions: TextView
    private lateinit var preview: FrameLayout
    private val cells = LinkedHashMap<String, Pair<TextView, TextView>>()
    private var runner: BenchmarkRunner? = null
    private var report: JSONObject? = null
    private var readableFirst = true
    private var running = false
    private val folder get() = File(filesDir, "benchmarks").apply { mkdirs() }
    private val sampleOptions = listOf(3, 5, 10)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(16)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; fitsSystemWindows = true; setBackgroundColor(Color.rgb(245, 247, 250)); addView(body) }
        body.addView(text("性能对比", 25f, Color.rgb(24, 34, 52)))
        body.addView(text("Readable SVGA  ·  官方 SVGAPlayer", 15f))
        body.addView(text("相同资源 / 尺寸 / 设备，顺序测试。未运行时不显示性能分数。", 12f))
        asset = Spinner(this).apply { adapter = ArrayAdapter(this@PerformanceActivity, android.R.layout.simple_spinner_dropdown_item,
            listOf("心跳 · heartbeat", "遮罩 · matteRect", "城堡 · Castle")) }
        rounds = Spinner(this).apply { adapter = ArrayAdapter(this@PerformanceActivity, android.R.layout.simple_spinner_dropdown_item,
            sampleOptions.map { "$it 轮采样" }); setSelection(1) }
        val selectors = LinearLayout(this)
        selectors.addView(asset, LinearLayout.LayoutParams(0, dp(44), 2f))
        selectors.addView(rounds, LinearLayout.LayoutParams(0, dp(44), 1f))
        body.addView(selectors)
        val buttons = LinearLayout(this)
        start = Button(this).apply { text = "开始对比"; setOnClickListener { begin() } }
        stop = Button(this).apply { text = "停止"; isEnabled = false; setOnClickListener { cancel() } }
        export = Button(this).apply { text = "导出 JSON"; isEnabled = false; setOnClickListener { export() } }
        listOf(start, stop, export).forEach { buttons.addView(it, LinearLayout.LayoutParams(0, dp(44), 1f)) }
        body.addView(buttons)
        status = text("等待测试", 13f, Color.rgb(24, 101, 166))
        body.addView(status)
        conditions = text("480 × 270 px · 每个负载预热 1 轮 · 每库播放 3 秒 · 官方基线 662d0dc", 11f)
        body.addView(conditions)
        preview = FrameLayout(this).apply { setBackgroundColor(Color.WHITE); visibility = View.GONE }
        body.addView(preview, LinearLayout.LayoutParams(-1, 270 + dp(8)))
        val table = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, 0) }
        body.addView(table)
        table.addView(metricRow("指标", "Readable", "官方", header = true))
        listOf(
            "parse" to "内核解析中位 / P95 ms", "repeat" to "重复加载中位 / P95 ms",
            "fps" to "实际绘制 / 素材 FPS", "draw" to "onDraw P95 ms", "interval" to "主线程帧间隔 P95 ms",
            "resource" to "位图+编码估算 MiB", "cache" to "重复加载缓存 KiB",
            "java" to "Java 堆快照变化 MiB", "pss" to "进程 PSS 变化 MiB"
        ).forEach { (id, label) ->
            val row = metricRow(label, "—", "—")
            cells[id] = row.getChildAt(1) as TextView to (row.getChildAt(2) as TextView)
            table.addView(row)
        }
        body.addView(text("重复加载单列：Readable 命中运行时缓存，官方重新解析。绘制 FPS 受素材帧率限制；onDraw 只计 CPU，不含 GPU。两库均初始化共享声音管理器，准备音频但静音播放。内存快照会受 GC 影响。", 11f))
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) body.addView(text("当前为调试构建。结果用于本场景对比，不代表发布版或所有设备。", 11f))
        setContentView(scroll)
        val prior = File(folder, "latest.json")
        if (prior.isFile) runCatching { JSONObject(prior.readText()) }.getOrNull()?.let { showReport(it, previous = true) }
        applyLaunchRequest(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLaunchRequest(intent)
    }

    private fun applyLaunchRequest(request: Intent) {
        if (!request.getBooleanExtra("benchmark_auto", false)) return
        request.removeExtra("benchmark_auto") // A recreation must not unexpectedly repeat a run.
        cancel()
        val selected = BenchmarkConfig.ASSETS.indexOf(request.getStringExtra("benchmark_asset") ?: "heartbeat.svga")
        asset.setSelection(selected.coerceAtLeast(0))
        rounds.setSelection(sampleOptions.indexOf(request.getIntExtra("benchmark_samples", 5)).takeIf { it >= 0 } ?: 1)
        preview.post { begin() }
    }

    private fun begin() {
        if (running) return
        val enabled = if (Build.VERSION.SDK_INT >= 26) ValueAnimator.areAnimatorsEnabled()
                      else Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0
        if (!enabled) { status.text = "系统已禁用动画，启用后才能比较播放性能"; return }
        report = null
        cells.values.forEach { it.first.text = "—"; it.second.text = "—" }
        setRunning(true)
        val config = BenchmarkConfig(BenchmarkConfig.ASSETS[asset.selectedItemPosition], samples = sampleOptions[rounds.selectedItemPosition])
        conditions.text = "${config.asset} · ${config.width} × ${config.height} px · ${config.samples} 轮 · 每库 3 秒"
        writeState("running")
        val selected = BenchmarkRunner(this, preview)
        runner = selected
        selected.run(config, readableFirst, { status.text = it }) { result ->
            runner = null
            setRunning(false)
            result.fold(onSuccess = { data ->
                File(folder, "latest.json").writeText(data.toString(2))
                writeState("complete")
                showReport(data, previous = false)
            }, onFailure = { error -> status.text = "未完成：${error.message}"; writeState("failed", error.message) })
        }
        readableFirst = !readableFirst
    }

    private fun showReport(data: JSONObject, previous: Boolean) {
        report = data
        val engines = data.getJSONArray("engines")
        val byId = (0 until engines.length()).associate { engines.getJSONObject(it).let { value -> value.getString("id") to value } }
        fun format(value: Double?) = value?.let { String.format(Locale.US, "%.2f", it) } ?: "—"
        fun render(engine: JSONObject): Map<String, String> {
            if (engine.optString("status", "complete") != "complete") return cells.keys.associateWith { "—" }
            val playback = engine.getJSONObject("render")
            val before = engine.getJSONObject("memory_before"); val after = engine.getJSONObject("memory_after")
            val pss = if (before.isNull("process_pss_bytes") || after.isNull("process_pss_bytes")) null
                      else (after.getLong("process_pss_bytes") - before.getLong("process_pss_bytes")) / 1048576.0
            return mapOf(
                "parse" to "${format(engine.getDouble("kernel_parse_median_ms"))} / ${format(engine.getDouble("kernel_parse_p95_ms"))}",
                "repeat" to "${format(engine.getDouble("repeat_load_median_ms"))} / ${format(engine.getDouble("repeat_load_p95_ms"))}",
                "fps" to "${format(playback.getDouble("draw_fps"))} / ${engine.getInt("source_fps")}",
                "draw" to format(playback.takeUnless { it.isNull("on_draw_p95_ms") }?.getDouble("on_draw_p95_ms")),
                "interval" to format(playback.takeUnless { it.isNull("main_callback_interval_p95_ms") }?.getDouble("main_callback_interval_p95_ms")),
                "resource" to format(engine.getLong("estimated_resource_bytes") / 1048576.0),
                "cache" to format(engine.getLong("cache_owned_bytes") / 1024.0),
                "java" to format((after.getLong("java_heap_bytes") - before.getLong("java_heap_bytes")) / 1048576.0),
                "pss" to format(pss))
        }
        val readable = render(checkNotNull(byId["readable"])); val official = render(checkNotNull(byId["official"]))
        cells.forEach { (key, pair) -> pair.first.text = readable[key]; pair.second.text = official[key] }
        val failures = byId.values.filter { it.optString("status", "complete") != "complete" }
        status.text = if (failures.isNotEmpty()) "测试结束 · ${failures.joinToString { "${it.getString("label")}：${it.optString("error")}" }}"
            else if (previous) "上次实测 · ${data.getJSONObject("device").getString("model")} / API ${data.getJSONObject("device").getInt("sdk")}" else "实测完成 · ${data.getJSONObject("device").getString("model")} / API ${data.getJSONObject("device").getInt("sdk") }"
        conditions.text = "${data.getString("asset")} · ${data.getInt("samples")} 轮 · ${data.getInt("width_px")} × ${data.getInt("height_px")} px · ${data.getString("captured_at_utc")}"
        export.isEnabled = !running
    }

    private fun setRunning(value: Boolean) {
        running = value
        start.isEnabled = !value; stop.isEnabled = value; export.isEnabled = !value && report != null
        asset.isEnabled = !value; rounds.isEnabled = !value
        preview.visibility = if (value) View.VISIBLE else View.GONE
    }

    private fun cancel() {
        runner?.close(); runner = null
        if (running) { setRunning(false); status.text = "已停止，未生成本轮结果"; writeState("cancelled") }
    }

    private fun export() {
        if (report == null) return
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "application/json"; addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_TITLE, "readable-svga-performance.json")
        }, 7)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7 && resultCode == RESULT_OK) data?.data?.let { uri ->
            runCatching { checkNotNull(contentResolver.openOutputStream(uri)) { "无法打开目标文件" }.use { it.write(checkNotNull(report).toString(2).toByteArray()) } }
                .onSuccess { Toast.makeText(this, "已导出实测数据", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(this, "导出失败：${it.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    override fun onPause() { cancel(); super.onPause() }
    private fun writeState(state: String, error: String? = null) = File(folder, "status.json").writeText(JSONObject().put("state", state).put("error", error ?: JSONObject.NULL).toString())
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float, color: Int = Color.rgb(65, 75, 90)) = TextView(this).apply { text = value; textSize = size; setTextColor(color); setPadding(0, dp(3), 0, dp(3)) }
    private fun metricRow(label: String, left: String, right: String, header: Boolean = false) = LinearLayout(this).apply {
        if (header) setBackgroundColor(Color.rgb(225, 234, 245))
        listOf(label, left, right).forEachIndexed { index, value ->
            addView(text(value, if (header) 13f else 12f).apply { if (index > 0) gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, if (index == 0) 1.5f else 1f))
        }
    }
}
