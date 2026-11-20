// SPDX-License-Identifier: Apache-2.0
package com.opensource.svgaplayer

import android.content.Context
import dev.svganext.player.SVGAParser as CoreParser
import java.io.File
import java.io.InputStream
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor

/**
 * Drop-in replacement for com.opensource.svgaplayer.SVGAParser.
 *
 * Delegates parsing to dev.svganext.player.SVGAParser while retaining the exact
 * upstream callback signatures and delivering [SVGAVideoEntity] with Safe-Clear protection.
 */
class SVGAParser(context: Context?) {
    private val coreParser = CoreParser(context)

    interface ParseCompletion {
        fun onComplete(videoItem: SVGAVideoEntity)
        fun onError()
        fun onError(error: Exception) { onError() }
    }

    interface PlayCallback {
        fun onPlay(file: List<File>)
    }

    open class FileDownloader : CoreParser.FileDownloader()

    var fileDownloader: CoreParser.FileDownloader
        get() = coreParser.fileDownloader
        set(value) { coreParser.fileDownloader = value }

    fun init(context: Context) {
        coreParser.init(context)
    }

    fun setFrameSize(frameWidth: Int, frameHeight: Int) {
        coreParser.setFrameSize(frameWidth, frameHeight)
    }

    fun decodeFromAssets(
        name: String,
        callback: ParseCompletion?,
        playCallback: PlayCallback? = null,
    ) {
        coreParser.decodeFromAssets(name, wrapCallback(callback), wrapPlayCallback(playCallback))
    }

    fun decodeFromURL(
        url: URL,
        callback: ParseCompletion?,
        playCallback: PlayCallback? = null,
    ): (() -> Unit)? {
        return coreParser.decodeFromURL(url, wrapCallback(callback), wrapPlayCallback(playCallback))
    }

    fun decodeFromFile(
        file: File,
        callback: ParseCompletion?,
        playCallback: PlayCallback? = null,
    ) {
        coreParser.decodeFromFile(file, wrapCallback(callback), wrapPlayCallback(playCallback))
    }

    fun decodeFromInputStream(
        inputStream: InputStream,
        cacheKey: String,
        callback: ParseCompletion?,
        closeInputStream: Boolean = false,
        playCallback: PlayCallback? = null,
        alias: String? = null,
    ) {
        coreParser.decodeFromInputStream(inputStream, cacheKey, wrapCallback(callback), closeInputStream, wrapPlayCallback(playCallback), alias)
    }

    fun decodeFromSVGAFileCacheKey(
        cacheKey: String,
        callback: ParseCompletion?,
        playCallback: PlayCallback?,
        alias: String? = null,
    ) {
        coreParser.decodeFromSVGAFileCacheKey(cacheKey, wrapCallback(callback), wrapPlayCallback(playCallback), alias)
    }

    private fun wrapCallback(callback: ParseCompletion?): CoreParser.ParseCompletion? {
        if (callback == null) return null
        return object : CoreParser.ParseCompletion {
            override fun onComplete(videoItem: dev.svganext.player.SVGAVideoEntity) {
                val compatEntity = if (videoItem is SVGAVideoEntity) videoItem else SVGAVideoEntity(videoItem)
                callback.onComplete(compatEntity)
            }

            override fun onError() {
                callback.onError()
            }

            override fun onError(error: Exception) {
                callback.onError(error)
            }
        }
    }

    private fun wrapPlayCallback(callback: PlayCallback?): CoreParser.PlayCallback? {
        if (callback == null) return null
        return object : CoreParser.PlayCallback {
            override fun onPlay(file: List<File>) {
                callback.onPlay(file)
            }
        }
    }

    companion object {
        private var mShareParser = SVGAParser(null)

        fun shareParser(): SVGAParser = mShareParser

        fun setThreadPoolExecutor(executor: ThreadPoolExecutor) {
            CoreParser.setThreadPoolExecutor(executor)
        }
    }
}
