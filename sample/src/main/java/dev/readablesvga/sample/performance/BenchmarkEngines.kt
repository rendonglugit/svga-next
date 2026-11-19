// SPDX-License-Identifier: Apache-2.0
package dev.readablesvga.sample.performance

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import dev.readablesvga.player.SVGAImageView
import dev.readablesvga.player.SVGAParser
import dev.readablesvga.player.SVGAVideoEntity
import dev.readablesvga.runtime.CancelHandle
import dev.readablesvga.runtime.SvgaLoader
import dev.readablesvga.runtime.SvgaRequest
import dev.readablesvga.runtime.SvgaSource
import dev.readablesvga.upstream.UpstreamBenchmarkAccess
import dev.readablesvga.upstream.SVGAImageView as OfficialView
import dev.readablesvga.upstream.SVGAParser as OfficialParser
import dev.readablesvga.upstream.SVGAVideoEntity as OfficialVideo
import java.util.concurrent.atomic.AtomicReference

interface LoadedBenchmarkAnimation : AutoCloseable {
    val estimatedBytes: Long
    val sourceFps: Int
    fun createPlayback(context: Context, recorder: DrawRecorder): BenchmarkPlayback
}

interface BenchmarkPlayback : AutoCloseable {
    val view: View
    fun start()
}

interface BenchmarkEngine : AutoCloseable {
    val id: String
    val label: String
    fun kernelLoad(config: BenchmarkConfig, callback: (Result<LoadedBenchmarkAnimation>) -> Unit): CancelHandle
    fun repeatedLoad(config: BenchmarkConfig, callback: (Result<LoadedBenchmarkAnimation>) -> Unit): CancelHandle
    fun clearCache()
    fun cachedBytes(): Long
}

class ReadableBenchmarkEngine(private val context: Context) : BenchmarkEngine {
    override val id = "readable"
    override val label = "Readable SVGA"
    private val loader = SvgaLoader(context)
    init {
        dev.readablesvga.player.SVGASoundManager.init()
        dev.readablesvga.player.SVGASoundManager.setVolume(0f)
    }

    override fun kernelLoad(config: BenchmarkConfig, callback: (Result<LoadedBenchmarkAnimation>) -> Unit): CancelHandle {
        val parser = SVGAParser(context)
        parser.setFrameSize(config.width, config.height)
        parser.decodeFromAssets(config.asset, object : SVGAParser.ParseCompletion {
            override fun onComplete(videoItem: SVGAVideoEntity) = callback(Result.success(ReadableAnimation(videoItem) { videoItem.clear() }))
            override fun onError() = callback(Result.failure(IllegalStateException("Readable parse failed")))
            override fun onError(error: Exception) = callback(Result.failure(error))
        })
        // A started asset decode cannot be interrupted; the runner owns late-result disposal.
        return CancelHandle {}
    }

    override fun repeatedLoad(config: BenchmarkConfig, callback: (Result<LoadedBenchmarkAnimation>) -> Unit): CancelHandle =
        loader.load(SvgaRequest(SvgaSource.Asset(config.asset), config.width, config.height)) { result ->
            callback(result.map { lease -> ReadableAnimation(lease.value, lease::close) })
        }

    override fun clearCache() = loader.clearMemoryCache()
    override fun cachedBytes() = loader.stats().cachedBytes
    override fun close() { loader.close(); dev.readablesvga.player.SVGASoundManager.release() }

    private class ReadableAnimation(private val video: SVGAVideoEntity, private val release: () -> Unit) : LoadedBenchmarkAnimation {
        private var closed = false
        override val estimatedBytes get() = video.estimatedMemoryBytes
        override val sourceFps get() = video.FPS
        override fun createPlayback(context: Context, recorder: DrawRecorder): BenchmarkPlayback {
            val player = object : SVGAImageView(context) {
                override fun onDraw(canvas: Canvas) {
                    val begin = SystemClock.elapsedRealtimeNanos()
                    super.onDraw(canvas)
                    recorder.recordDraw(SystemClock.elapsedRealtimeNanos() - begin)
                }
            }.apply { scaleType = ImageView.ScaleType.FIT_CENTER; loops = 0; setVideoItem(video) }
            return object : BenchmarkPlayback {
                override val view: View = player
                override fun start() = player.startAnimation()
                override fun close() { player.stopAnimation(false); player.setImageDrawable(null) }
            }
        }
        override fun close() { if (!closed) { closed = true; release() } }
    }
}

class OfficialBenchmarkEngine(private val context: Context) : BenchmarkEngine {
    override val id = "official"
    override val label = "官方 SVGAPlayer"
    init {
        dev.readablesvga.upstream.SVGASoundManager.init()
        dev.readablesvga.upstream.SVGASoundManager.setVolume(0f)
    }
    override fun kernelLoad(config: BenchmarkConfig, callback: (Result<LoadedBenchmarkAnimation>) -> Unit): CancelHandle {
        val first = AtomicReference<OfficialVideo?>()
        val parser = OfficialParser(context)
        parser.setFrameSize(config.width, config.height)
        parser.decodeFromAssets(config.asset, object : OfficialParser.ParseCompletion {
            override fun onComplete(videoItem: OfficialVideo) {
                // Observe at most one result without clearing a duplicate of the first entity.
                if (first.compareAndSet(null, videoItem)) callback(Result.success(OfficialAnimation(videoItem)))
                else if (first.get() !== videoItem) videoItem.clear()
            }
            override fun onError() = callback(Result.failure(IllegalStateException("Official parse failed")))
        })
        return CancelHandle {}
    }
    override fun repeatedLoad(config: BenchmarkConfig, callback: (Result<LoadedBenchmarkAnimation>) -> Unit) = kernelLoad(config, callback)
    override fun clearCache() = Unit // The pinned upstream has no parsed-entity memory cache.
    override fun cachedBytes() = 0L
    override fun close() = dev.readablesvga.upstream.SVGASoundManager.release()

    private class OfficialAnimation(private val video: OfficialVideo) : LoadedBenchmarkAnimation {
        private var closed = false
        override val estimatedBytes get() = UpstreamBenchmarkAccess.estimatedMemoryBytes(video)
        override val sourceFps get() = video.FPS
        override fun createPlayback(context: Context, recorder: DrawRecorder): BenchmarkPlayback {
            val player = object : OfficialView(context) {
                override fun onDraw(canvas: Canvas) {
                    val begin = SystemClock.elapsedRealtimeNanos()
                    super.onDraw(canvas)
                    recorder.recordDraw(SystemClock.elapsedRealtimeNanos() - begin)
                }
            }.apply { scaleType = ImageView.ScaleType.FIT_CENTER; loops = 0; setVideoItem(video) }
            return object : BenchmarkPlayback {
                override val view: View = player
                override fun start() = player.startAnimation()
                override fun close() { player.stopAnimation(false); player.setImageDrawable(null) }
            }
        }
        override fun close() { if (!closed) { closed = true; video.clear() } }
    }
}
