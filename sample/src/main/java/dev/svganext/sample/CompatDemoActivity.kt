// SPDX-License-Identifier: Apache-2.0
package dev.svganext.sample

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.SVGAParser
import com.opensource.svgaplayer.SVGAVideoEntity
import com.opensource.svgaplayer.SVGADynamicEntity

/**
 * Demonstrates 100% zero-code-change compatibility with com.opensource.svgaplayer.
 *
 * Uses the exact legacy imports and APIs:
 * - com.opensource.svgaplayer.SVGAImageView
 * - com.opensource.svgaplayer.SVGAParser.shareParser()
 * - com.opensource.svgaplayer.SVGAVideoEntity
 * - SafeClear protection when calling entity.clear() while active views are playing
 */
class CompatDemoActivity : Activity() {
    private lateinit var firstView: SVGAImageView
    private lateinit var secondView: SVGAImageView
    private lateinit var statusText: TextView
    private var currentEntity: SVGAVideoEntity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(Color.rgb(246, 247, 251))
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        scroll.addView(column)

        column.addView(TextView(this).apply {
            text = "svga-compat 兼容模式演示"
            textSize = 22f
            setTextColor(Color.DKGRAY)
        })
        column.addView(TextView(this).apply {
            text = "使用原始 com.opensource.svgaplayer 包名、旧版 SVGAParser 与 SVGAImageView。展示 Safe-Clear 在手动调用 clear() 时的防崩溃保护。"
            textSize = 13f
            setPadding(0, dp(6), 0, dp(14))
        })

        firstView = SVGAImageView(this).apply {
            loops = 0
            clearsAfterStop = false
        }
        secondView = SVGAImageView(this).apply {
            loops = 0
            clearsAfterStop = false
        }

        val viewsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(firstView, LinearLayout.LayoutParams(0, dp(180), 1f))
            addView(secondView, LinearLayout.LayoutParams(0, dp(180), 1f))
        }
        column.addView(viewsLayout)

        statusText = TextView(this).apply {
            text = "状态：就绪"
            textSize = 13f
            setPadding(0, dp(10), 0, dp(10))
        }
        column.addView(statusText)

        fun action(label: String, run: () -> Unit) {
            column.addView(Button(this).apply {
                text = label
                setOnClickListener { run() }
            })
        }

        action("加载并播放心跳 (heartbeat.svga)") {
            loadAsset("heartbeat.svga")
        }

        action("加载并播放音频 (jojo_audio.svga)") {
            loadAsset("jojo_audio.svga")
        }

        action("动态替换文字 (SVGADynamicEntity)") {
            loadDynamicAsset("heartbeat.svga")
        }

        action("⚠️ 模拟旧业务调用 videoItem.clear()") {
            val entity = currentEntity
            if (entity != null) {
                entity.clear()
                val bound = entity.isBoundToActiveView
                statusText.text = "状态：已调用 videoItem.clear()！\nSafeClear 拦截保护中 (boundToView=$bound)\n两个 View 依然正常播放，未黑屏！"
                Toast.makeText(this, "SafeClear 成功拦截！播放未受影响", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请先加载动画", Toast.LENGTH_SHORT).show()
            }
        }

        action("停止并移除第一个 View") {
            firstView.stopAnimation()
            firstView.setVideoItem(null)
            val bound = currentEntity?.isBoundToActiveView ?: false
            statusText.text = "状态：View 1 已解绑，View 2 仍处于在播状态 (boundToView=$bound)"
        }

        action("停止全部 View 并真正释放") {
            firstView.stopAnimation()
            firstView.setVideoItem(null)
            secondView.stopAnimation()
            secondView.setVideoItem(null)
            val entity = currentEntity
            entity?.clear()
            val released = entity?.isReleased ?: false
            statusText.text = "状态：全部 View 已解绑，再次调用 clear()\n已真正释放底层资源 (isReleased=$released)"
            Toast.makeText(this, "资源已完全释放", Toast.LENGTH_SHORT).show()
        }

        setContentView(scroll)

        // 默认自动起播
        firstView.post { loadAsset("heartbeat.svga") }
    }

    private fun loadAsset(assetName: String) {
        statusText.text = "正在通过 SVGAParser.shareParser() 解析: $assetName..."
        SVGAParser.shareParser().decodeFromAssets(assetName, object : SVGAParser.ParseCompletion {
            override fun onComplete(videoItem: SVGAVideoEntity) {
                currentEntity = videoItem
                firstView.setVideoItem(videoItem)
                firstView.startAnimation()

                secondView.setVideoItem(videoItem)
                secondView.startAnimation()

                statusText.text = "状态：$assetName 已交付，两个 View 共享同一 SVGAVideoEntity 播放中"
            }

            override fun onError() {
                statusText.text = "状态：解析失败 $assetName"
            }
        })
    }

    private fun loadDynamicAsset(assetName: String) {
        statusText.text = "正在加载动态内容动画..."
        SVGAParser.shareParser().decodeFromAssets(assetName, object : SVGAParser.ParseCompletion {
            override fun onComplete(videoItem: SVGAVideoEntity) {
                currentEntity = videoItem

                val dynamic1 = SVGADynamicEntity().apply {
                    setDynamicText("Compat View 1", android.text.TextPaint().apply {
                        color = Color.RED
                        textSize = 28f
                    }, "banner")
                }

                val dynamic2 = SVGADynamicEntity().apply {
                    setDynamicText("Compat View 2", android.text.TextPaint().apply {
                        color = Color.BLUE
                        textSize = 28f
                    }, "banner")
                }

                firstView.setVideoItem(videoItem, dynamic1)
                firstView.startAnimation()

                secondView.setVideoItem(videoItem, dynamic2)
                secondView.startAnimation()

                statusText.text = "状态：已绑定各自独立的 SVGADynamicEntity"
            }

            override fun onError() {
                statusText.text = "状态：动态解析失败"
            }
        })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        firstView.stopAnimation()
        firstView.setVideoItem(null)
        secondView.stopAnimation()
        secondView.setVideoItem(null)
        currentEntity?.clear()
        super.onDestroy()
    }
}
