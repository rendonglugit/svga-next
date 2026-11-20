// SPDX-License-Identifier: Apache-2.0
package com.opensource.svgaplayer

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.svganext.player.SVGAParser as CoreParser
import java.io.File
import java.io.InputStream
import java.net.URL
import java.util.concurrent.ThreadPoolExecutor

/**
 * Drop-in replacement for com.opensource.svgaplayer.SVGAParser.
 *
 * Delegates decoding to dev.svganext.player.SVGAParser with integrated in-memory
 * caching via [SVGAActivitiesCache] to avoid duplicate parsing of recurring assets,
 * while retaining exact upstream callback signatures and error dispatching.
 */
class SVGAParser(context: Context?) {
    private val coreParser = CoreParser(context)

    interface ParseCompletion {
        fun onComplete(videoItem: SVGAVideoEntity)
        fun onError() {}
        fun onError(error: Exception) { onError() }
        fun onError(error: Exception, alias: String) { onError(error) }
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

    @JvmOverloads
    fun decodeFromAssets(
        name: String,
        callback: ParseCompletion?,
        playCallback: PlayCallback? = null,
        alias: String? = null,
        needCache: Boolean = true,
    ) {
        val cacheKey = SVGACache.buildCacheKey("assets://$name")
        if (needCache) {
            val cached = SVGAActivitiesCache.instance.get(cacheKey)
            if (cached != null) {
                Handler(Looper.getMainLooper()).post { callback?.onComplete(cached) }
                return
            }
        }
        coreParser.decodeFromAssets(name, wrapCallback(cacheKey, needCache, alias, callback), wrapPlayCallback(playCallback))
    }

    @JvmOverloads
    fun decodeFromURL(
        url: URL,
        callback: ParseCompletion?,
        playCallback: PlayCallback? = null,
        alias: String? = null,
        needCache: Boolean = true,
    ): (() -> Unit)? {
        val cacheKey = SVGACache.buildCacheKey(url)
        if (needCache) {
            val cached = SVGAActivitiesCache.instance.get(cacheKey)
            if (cached != null) {
                Handler(Looper.getMainLooper()).post { callback?.onComplete(cached) }
                return null
            }
        }
        return coreParser.decodeFromURL(url, wrapCallback(cacheKey, needCache, alias, callback), wrapPlayCallback(playCallback))
    }

    @JvmOverloads
    fun decodeFromFile(
        file: File,
        callback: ParseCompletion?,
        playCallback: PlayCallback? = null,
        alias: String? = null,
        needCache: Boolean = true,
    ) {
        val cacheKey = SVGACache.buildCacheKey(file.absolutePath)
        if (needCache) {
            val cached = SVGAActivitiesCache.instance.get(cacheKey)
            if (cached != null) {
                Handler(Looper.getMainLooper()).post { callback?.onComplete(cached) }
                return
            }
        }
        coreParser.decodeFromFile(file, wrapCallback(cacheKey, needCache, alias, callback), wrapPlayCallback(playCallback))
    }

    @JvmOverloads
    fun decodeFromInputStream(
        inputStream: InputStream,
        cacheKey: String,
        callback: ParseCompletion?,
        closeInputStream: Boolean = false,
        playCallback: PlayCallback? = null,
        alias: String? = null,
        needCache: Boolean = true,
        frequence: Int = 0,
    ) {
        if (needCache && cacheKey.isNotEmpty()) {
            val cached = SVGAActivitiesCache.instance.get(cacheKey)
            if (cached != null) {
                if (closeInputStream) {
                    try { inputStream.close() } catch (_: Throwable) {}
                }
                Handler(Looper.getMainLooper()).post { callback?.onComplete(cached) }
                return
            }
        }
        coreParser.decodeFromInputStream(
            inputStream,
            cacheKey,
            wrapCallback(cacheKey, needCache, alias, callback),
            closeInputStream,
            wrapPlayCallback(playCallback),
            alias
        )
    }

    @JvmOverloads
    fun decodeFromSVGAFileCacheKey(
        cacheKey: String,
        callback: ParseCompletion?,
        playCallback: PlayCallback? = null,
        alias: String? = null,
        needCache: Boolean = true,
    ) {
        if (needCache && cacheKey.isNotEmpty()) {
            val cached = SVGAActivitiesCache.instance.get(cacheKey)
            if (cached != null) {
                Handler(Looper.getMainLooper()).post { callback?.onComplete(cached) }
                return
            }
        }
        coreParser.decodeFromSVGAFileCacheKey(cacheKey, wrapCallback(cacheKey, needCache, alias, callback), wrapPlayCallback(playCallback), alias)
    }

    private fun wrapCallback(
        cacheKey: String?,
        needCache: Boolean,
        alias: String?,
        callback: ParseCompletion?
    ): CoreParser.ParseCompletion? {
        if (callback == null) return null
        return object : CoreParser.ParseCompletion {
            override fun onComplete(videoItem: dev.svganext.player.SVGAVideoEntity) {
                val compatEntity = if (videoItem is SVGAVideoEntity) videoItem else SVGAVideoEntity(videoItem)
                if (needCache && !cacheKey.isNullOrEmpty()) {
                    SVGAActivitiesCache.instance.put(cacheKey, compatEntity)
                }
                callback.onComplete(compatEntity)
            }

            override fun onError() {
                callback.onError()
            }

            override fun onError(error: Exception) {
                callback.onError(error, alias ?: "")
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

        @JvmStatic
        fun shareParser(): SVGAParser = mShareParser

        @JvmStatic
        fun setThreadPoolExecutor(executor: ThreadPoolExecutor) {
            CoreParser.setThreadPoolExecutor(executor)
        }
    }
}
