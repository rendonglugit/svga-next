// SPDX-License-Identifier: Apache-2.0
package dev.svganext.sample

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.svganext.runtime.ManagedSvgaImageView
import dev.svganext.runtime.SvgaLoader
import dev.svganext.runtime.SvgaRequest
import dev.svganext.runtime.SvgaSource

/** Two views deliberately share one loader to demonstrate safe cache ownership. */
class MainActivity : Activity() {
    private lateinit var loader: SvgaLoader
    private lateinit var first: ManagedSvgaImageView
    private lateinit var second: ManagedSvgaImageView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loader = SvgaLoader(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            fitsSystemWindows = true
            setBackgroundColor(Color.rgb(246, 247, 251))
        }
        column.addView(TextView(this).apply { text = "Readable SVGA"; textSize = 26f })
        column.addView(TextView(this).apply {
            text = "两个 View 共享动画资源，各自控制播放。清缓存后，正在播放的引用继续有效。"
            textSize = 14f
        })
        first = ManagedSvgaImageView(this)
        second = ManagedSvgaImageView(this)
        column.addView(first, LinearLayout.LayoutParams(-1, 0, 1f))
        column.addView(second, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply { textSize = 12f }
        column.addView(status)
        fun action(label: String, run: () -> Unit) {
            column.addView(Button(this).apply { text = label; setOnClickListener { run() } })
        }
        action("心跳动画") { play("heartbeat.svga") }
        action("遮罩动画") { play("matteRect.svga") }
        action("音频动画") { play("jojo_audio.svga") }
        action("快速切换，验证旧请求失效") {
            play("matteRect.svga")
            play("heartbeat.svga")
        }
        action("显示 / 隐藏第二个动画") {
            second.visibility = if (second.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
            updateStats()
        }
        action("清除内存缓存") { loader.clearMemoryCache(); updateStats() }
        action("性能对比 · 官方 SVGAPlayer") {
            first.clearRequest(); second.clearRequest(); loader.clearMemoryCache()
            startActivity(Intent(this, PerformanceActivity::class.java))
        }
        action("存量兼容模式 · svga-compat 演示") {
            startActivity(Intent(this, CompatDemoActivity::class.java))
        }
        setContentView(column)
        first.post { play("heartbeat.svga") }
    }

    private fun play(asset: String) {
        val size = dp(220)
        val request = SvgaRequest(SvgaSource.Asset(asset), size, size)
        first.bind(loader, request)
        second.bind(loader, request)
        first.postDelayed({ if (!isFinishing && !isDestroyed) updateStats() }, 1000)
    }

    private fun updateStats() {
        val stats = loader.stats()
        status.text = "缓存：${stats.entryCount} 个动画，${stats.cachedBytes / 1024} KiB / ${stats.maxBytes / 1024} KiB"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        first.clearRequest()
        second.clearRequest()
        loader.close()
        super.onDestroy()
    }
}
