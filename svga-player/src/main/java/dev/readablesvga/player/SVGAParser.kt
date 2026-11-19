package dev.readablesvga.player

import android.content.Context
import android.net.http.HttpResponseCache
import android.os.Handler
import android.os.Looper
import dev.readablesvga.player.proto.MovieEntity
import dev.readablesvga.player.utils.log.LogUtils
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/**
 * Created by PonyCui 16/6/18.
 */
private val fileLocks = KeyedFileLocks()

class SVGAParser(context: Context?) {
    private var mContext = context?.applicationContext
    private val frameLock = Any()

    init {
        SVGACache.onCreate(context)
    }

    @Volatile
    private var mFrameWidth: Int = 0

    @Volatile
    private var mFrameHeight: Int = 0

    interface ParseCompletion {
        fun onComplete(videoItem: SVGAVideoEntity)
        fun onError()
        fun onError(error: Exception) { onError() }
    }

    interface PlayCallback{
        fun onPlay(file: List<File>)
    }

    open class FileDownloader {

        var noCache = false

        open fun resume(url: URL, complete: (inputStream: InputStream) -> Unit, failure: (e: Exception) -> Unit): () -> Unit {
            val cancelled = AtomicBoolean(false)
            val connection = AtomicReference<HttpURLConnection?>()
            val callbackLock = Any()
            fun disconnect(value: HttpURLConnection?) {
                try { value?.disconnect() } catch (e: Exception) { LogUtils.error(TAG, e) }
            }
            val cancelBlock = {
                synchronized(callbackLock) { cancelled.set(true) }
                disconnect(connection.getAndSet(null))
            }
            threadPoolExecutor.execute {
                var activeConnection: HttpURLConnection? = null
                try {
                    if (cancelled.get()) return@execute
                    require(url.protocol == "http" || url.protocol == "https") {
                        "SVGA downloads support HTTP(S) only"
                    }
                    if (HttpResponseCache.getInstalled() == null && !noCache) {
                        LogUtils.warn(TAG, "HttpResponseCache is not installed; URL downloads are not cached")
                    }
                    val opened = url.openConnection() as? HttpURLConnection
                        ?: throw IOException("URL did not provide an HTTP connection")
                    activeConnection = opened
                    connection.set(opened)
                    if (cancelled.get()) return@execute
                    opened.connectTimeout = 20_000
                    opened.readTimeout = 20_000
                    opened.requestMethod = "GET"
                    opened.setRequestProperty("Connection", "close")
                    opened.connect()
                    opened.inputStream.use { input ->
                        ByteArrayOutputStream().use { output ->
                            val buffer = ByteArray(4096)
                            var count = if (cancelled.get()) -1 else input.read(buffer)
                            while (count > 0 && !cancelled.get()) {
                                output.write(buffer, 0, count)
                                count = input.read(buffer)
                            }
                            synchronized(callbackLock) {
                                if (!cancelled.get()) {
                                    ByteArrayInputStream(output.toByteArray()).use(complete)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    synchronized(callbackLock) {
                        if (!cancelled.get()) failure(e)
                    }
                } finally {
                    connection.compareAndSet(activeConnection, null)
                    disconnect(activeConnection)
                }
            }
            return cancelBlock
        }
    }

    private class DownloadFlight {
        val listeners = LinkedHashMap<Any, (Result<ByteArray>) -> Unit>()
        var cancel: (() -> Unit)? = null
        var abandoned = false
    }

    private class DownloadKey(val backend: Any, val noCache: Boolean, val url: String) {
        override fun equals(other: Any?): Boolean = other is DownloadKey &&
            backend === other.backend && noCache == other.noCache && url == other.url
        override fun hashCode(): Int = 31 * (31 * System.identityHashCode(backend) + noCache.hashCode()) + url.hashCode()
    }

    private fun downloadShared(url: URL, complete: (InputStream) -> Unit, failure: (Exception) -> Unit): () -> Unit {
        val downloader = fileDownloader
        val backend = if (downloader.javaClass == FileDownloader::class.java) FileDownloader::class.java else downloader
        val key = DownloadKey(backend, downloader.noCache, url.toExternalForm())
        val token = Any()
        val cancelled = AtomicBoolean(false)
        val (flight, start) = synchronized(downloads) {
            val existing = downloads[key]
            val selected = existing ?: DownloadFlight().also { downloads[key] = it }
            selected.listeners[token] = { result ->
                if (!cancelled.get()) result.fold(
                    { bytes -> try { ByteArrayInputStream(bytes).use(complete) } catch (e: Exception) { failure(e) } },
                    { error -> failure(error as Exception) }
                )
            }
            selected to (existing == null)
        }
        fun finish(result: Result<ByteArray>) {
            val listeners = synchronized(downloads) {
                if (downloads[key] !== flight) return
                downloads.remove(key)
                flight.listeners.values.toList().also { flight.listeners.clear() }
            }
            listeners.forEach { listener ->
                try { listener(result) } catch (e: Exception) { LogUtils.error(TAG, e) }
            }
        }
        if (start) {
            try {
                val cancel = downloader.resume(url, { input ->
                    val result = try { Result.success(input.readBytes()) } catch (e: Exception) { Result.failure(e) }
                    finish(result)
                }, { finish(Result.failure(it)) })
                val abandoned = synchronized(downloads) {
                    if (downloads[key] === flight) flight.cancel = cancel
                    flight.abandoned
                }
                if (abandoned) cancel()
            } catch (e: Exception) {
                finish(Result.failure(e))
            }
        }
        return {
            cancelled.set(true)
            val cancel = synchronized(downloads) {
                flight.listeners.remove(token)
                if (downloads[key] === flight && flight.listeners.isEmpty()) {
                    downloads.remove(key)
                    flight.abandoned = true
                    flight.cancel
                } else null
            }
            cancel?.invoke()
        }
    }

    var fileDownloader = FileDownloader()

    companion object {
        private val downloads = HashMap<DownloadKey, DownloadFlight>()
        private const val TAG = "SVGAParser"

        private val threadNum = AtomicInteger(0)
        private var mShareParser = SVGAParser(null)

        internal var threadPoolExecutor = Executors.newFixedThreadPool(4) { r ->
            Thread(r, "SVGAParser-Thread-${threadNum.getAndIncrement()}")
        }

        fun setThreadPoolExecutor(executor: ThreadPoolExecutor) {
            threadPoolExecutor = executor
        }

        fun shareParser(): SVGAParser {
            return mShareParser
        }
    }

    fun init(context: Context) {
        mContext = context.applicationContext
        SVGACache.onCreate(mContext)
    }

    fun setFrameSize(frameWidth: Int, frameHeight: Int) {
        synchronized(frameLock) {
            mFrameWidth = frameWidth
            mFrameHeight = frameHeight
        }
    }

    private fun frameSize(): Pair<Int, Int> = synchronized(frameLock) { mFrameWidth to mFrameHeight }

    /** Opens file I/O on a parser worker and transfers stream ownership to the decoder. */
    fun decodeFromFile(file: File, callback: ParseCompletion?, playCallback: PlayCallback? = null) {
        val targetSize = frameSize()
        try {
            threadPoolExecutor.execute {
                try {
                    val input = FileInputStream(file)
                    decodeFromInputStreamWithSize(input, SVGACache.buildCacheKey(file.toURI().toString()),
                        callback, true, playCallback, file.absolutePath, targetSize, dispatch = false)
                } catch (e: Exception) {
                    invokeErrorCallback(e, callback, file.absolutePath)
                }
            }
        } catch (e: Exception) {
            invokeErrorCallback(e, callback, file.absolutePath)
        }
    }

    fun decodeFromAssets(
            name: String,
            callback: ParseCompletion?,
            playCallback: PlayCallback? = null
    ) {
        if (mContext == null) {
            LogUtils.error(TAG, "在配置 SVGAParser context 前, 无法解析 SVGA 文件。")
            return
        }
        val targetSize = frameSize()
        LogUtils.info(TAG, "================ decode $name from assets ================")
        threadPoolExecutor.execute {
            try {
                mContext?.assets?.open(name)?.let {
                    this.decodeFromInputStreamWithSize(
                            it,
                            SVGACache.buildCacheKey("file:///assets/$name"),
                            callback,
                            true,
                            playCallback,
                            alias = name,
                            targetSize = targetSize
                    )
                }
            } catch (e: Exception) {
                this.invokeErrorCallback(e, callback, name)
            }
        }

    }

    /** Warms the URL disk cache without constructing or preparing a playback entity. */
    fun prefetchToDisk(url: URL, callback: (Result<File>) -> Unit): () -> Unit {
        val cancelled = AtomicBoolean(false)
        val publicationLock = Any()
        val cancelDownload = AtomicReference<(() -> Unit)?>(null)
        val main = Handler(Looper.getMainLooper())
        val key = SVGACache.buildCacheKey(url)
        fun deliver(result: Result<File>) {
            main.post { if (!cancelled.get()) callback(result) }
        }
        try {
            threadPoolExecutor.execute {
                if (cancelled.get()) return@execute
                try {
                    checkNotNull(mContext) { "SVGAParser context is not initialized" }
                    val cached = fileLocks.read(key) { cachedUrlArtifact(key) }
                    if (cached != null) {
                        deliver(Result.success(cached))
                    } else if (!cancelled.get()) {
                        val cancel = downloadShared(url, { input ->
                            if (!cancelled.get()) {
                                val artifact = fileLocks.withLock(key) {
                                    if (cancelled.get()) null else cachedUrlArtifact(key) ?: SVGACache.buildSvgaFile(key).also { file ->
                                        publishCacheFile(file, commit = { publish ->
                                            synchronized(publicationLock) {
                                                if (cancelled.get()) throw java.util.concurrent.CancellationException()
                                                publish()
                                            }
                                        }) { output ->
                                            val buffer = ByteArray(8192)
                                            var hasBytes = false
                                            while (!cancelled.get()) {
                                                val count = input.read(buffer)
                                                if (count < 0) break
                                                if (count > 0) hasBytes = true
                                                output.write(buffer, 0, count)
                                            }
                                            if (cancelled.get()) throw java.util.concurrent.CancellationException()
                                            if (!hasBytes) throw IOException("Empty SVGA download")
                                        }
                                    }
                                }
                                if (artifact != null) deliver(Result.success(artifact))
                            }
                        }, { deliver(Result.failure(it)) })
                        cancelDownload.set(cancel)
                        if (cancelled.get()) cancelDownload.getAndSet(null)?.invoke()
                    }
                } catch (error: Exception) {
                    deliver(Result.failure(error))
                }
            }
        } catch (error: Exception) {
            deliver(Result.failure(error))
        }
        return {
            synchronized(publicationLock) { cancelled.set(true) }
            cancelDownload.getAndSet(null)?.invoke()
        }
    }

    private fun cachedUrlArtifact(key: String): File? {
        val folder = SVGACache.buildCacheDir(key)
        return if (File(folder, "movie.binary").isFile || File(folder, "movie.spec").isFile) folder
        else SVGACache.buildSvgaFile(key).takeIf { it.isFile && it.length() > 0 }
    }

    fun decodeFromURL(
            url: URL,
            callback: ParseCompletion?,
            playCallback: PlayCallback? = null
    ): (() -> Unit)? {
        if (mContext == null) {
            LogUtils.error(TAG, "在配置 SVGAParser context 前, 无法解析 SVGA 文件。")
            return null
        }
        val targetSize = frameSize()
        val urlPath = url.toString()
        LogUtils.info(TAG, "================ decode from url: $urlPath ================")
        val cacheKey = SVGACache.buildCacheKey(url);
        // This probe only selects a route. Actual reads take the keyed lock on the worker.
        val artifact = cachedUrlArtifact(cacheKey)
        return if (artifact != null) {
            LogUtils.info(TAG, "this url cached")
            threadPoolExecutor.execute {
                if (artifact.isDirectory) {
                    this.decodeFromCacheKey(cacheKey, callback, urlPath, playCallback, targetSize)
                } else {
                    this.decodeFromSVGAFileCacheKeyWithSize(cacheKey, callback, playCallback, urlPath, targetSize)
                }
            }
            return null
        } else {
            LogUtils.info(TAG, "no cached, prepare to download")
            downloadShared(url, {
                this.decodeFromInputStreamWithSize(
                    it,
                    cacheKey,
                    callback,
                    false,
                    playCallback,
                    alias = urlPath,
                    targetSize = targetSize
                )
            }, {
                LogUtils.error(
                    TAG,
                    "================ svga file: $url download fail ================"
                )
                this.invokeErrorCallback(it, callback, alias = urlPath)
            })
        }
    }

    /**
     * 读取解析本地缓存的 svga 文件.
     */
    fun decodeFromSVGAFileCacheKey(
            cacheKey: String,
            callback: ParseCompletion?,
            playCallback: PlayCallback?,
            alias: String? = null
    ) {
        decodeFromSVGAFileCacheKeyWithSize(cacheKey, callback, playCallback, alias, frameSize())
    }

    private fun decodeFromSVGAFileCacheKeyWithSize(
        cacheKey: String,
        callback: ParseCompletion?,
        playCallback: PlayCallback?,
        alias: String?,
        targetSize: Pair<Int, Int>
    ) {
        threadPoolExecutor.execute {
            try {
                LogUtils.info(TAG, "================ decode $alias from svga cachel file to entity ================")
                val cachedBytes = fileLocks.read(cacheKey) {
                    FileInputStream(SVGACache.buildSvgaFile(cacheKey)).use(::readAsBytes)
                }
                run {
                    cachedBytes?.let { bytes ->
                        if (isZipFile(bytes)) {
                            fileLocks.withLock(cacheKey) {
                                if (cachedUrlArtifact(cacheKey)?.isDirectory != true) {
                                    ByteArrayInputStream(bytes).use { unzip(it, cacheKey) }
                                }
                            }
                            this.decodeFromCacheKey(cacheKey, callback, alias, playCallback, targetSize)
                        } else {
                            LogUtils.info(TAG, "inflate start")
                            inflate(bytes)?.let {
                                LogUtils.info(TAG, "inflate complete")
                                val videoItem = SVGAVideoEntity(
                                    MovieEntity.ADAPTER.decode(it),
                                    File(cacheKey),
                                    targetSize.first,
                                    targetSize.second
                                )
                                LogUtils.info(TAG, "SVGAVideoEntity prepare start")
                                prepareForPlayback(videoItem, {
                                    LogUtils.info(TAG, "SVGAVideoEntity prepare success")
                                    this.invokeCompleteCallback(videoItem, callback, alias)
                                },playCallback)

                            } ?: this.invokeErrorCallback(
                                Exception("inflate(bytes) cause exception"),
                                callback,
                                alias
                            )
                        }
                    } ?: this.invokeErrorCallback(
                        Exception("readAsBytes(inputStream) cause exception"),
                        callback,
                        alias
                    )
                }
            } catch (e: java.lang.Exception) {
                this.invokeErrorCallback(e, callback, alias)
            } finally {
                LogUtils.info(TAG, "================ decode $alias from svga cachel file to entity end ================")
            }
        }
    }

    fun decodeFromInputStream(
            inputStream: InputStream,
            cacheKey: String,
            callback: ParseCompletion?,
            closeInputStream: Boolean = false,
            playCallback: PlayCallback? = null,
            alias: String? = null
    ) {
        decodeFromInputStreamWithSize(inputStream, cacheKey, callback, closeInputStream,
                playCallback, alias, frameSize())
    }

    private fun decodeFromInputStreamWithSize(
        inputStream: InputStream,
        cacheKey: String,
        callback: ParseCompletion?,
        closeInputStream: Boolean,
        playCallback: PlayCallback?,
        alias: String?,
        targetSize: Pair<Int, Int>,
        dispatch: Boolean = true
    ) {
        if (mContext == null) {
            LogUtils.error(TAG, "在配置 SVGAParser context 前, 无法解析 SVGA 文件。")
            if (closeInputStream) inputStream.close()
            invokeErrorCallback(IllegalStateException("SVGAParser context is not initialized"), callback, alias)
            return
        }
        LogUtils.info(TAG, "================ decode $alias from input stream ================")
        val decodeTask = Runnable {
            try {
                readAsBytes(inputStream)?.let { bytes ->
                    if (isZipFile(bytes)) {
                        LogUtils.info(TAG, "decode from zip file")
                        fileLocks.withLock(cacheKey) {
                            if (!SVGACache.buildCacheDir(cacheKey).exists()) {
                                LogUtils.info(TAG, "no cached, prepare to unzip")
                                ByteArrayInputStream(bytes).use { unzip(it, cacheKey) }
                                LogUtils.info(TAG, "unzip success")
                            }
                        }
                        this.decodeFromCacheKey(cacheKey, callback, alias, playCallback, targetSize)
                    } else {
                        if (!SVGACache.isDefaultCache()) {
                            // 如果 SVGACache 设置类型为 FILE
                            threadPoolExecutor.execute {
                                fileLocks.withLock(cacheKey) {
                                    SVGACache.buildSvgaFile(cacheKey).let { cacheFile ->
                                        try {
                                            publishCacheFile(cacheFile) { it.write(bytes) }
                                        } catch (e: Exception) {
                                            LogUtils.error(TAG, "create cache file fail.", e)
                                        }
                                    }
                                }
                            }
                        }
                        LogUtils.info(TAG, "inflate start")
                        inflate(bytes)?.let {
                            LogUtils.info(TAG, "inflate complete")
                            val videoItem = SVGAVideoEntity(
                                    MovieEntity.ADAPTER.decode(it),
                                    File(cacheKey),
                                    targetSize.first,
                                    targetSize.second
                            )
                            LogUtils.info(TAG, "SVGAVideoEntity prepare start")
                            prepareForPlayback(videoItem, {
                                LogUtils.info(TAG, "SVGAVideoEntity prepare success")
                                this.invokeCompleteCallback(videoItem, callback, alias)
                            },playCallback)

                        } ?: this.invokeErrorCallback(
                                Exception("inflate(bytes) cause exception"),
                                callback,
                                alias
                        )
                    }
                } ?: this.invokeErrorCallback(
                        Exception("readAsBytes(inputStream) cause exception"),
                        callback,
                        alias
                )
            } catch (e: java.lang.Exception) {
                this.invokeErrorCallback(e, callback, alias)
            } finally {
                if (closeInputStream) {
                    inputStream.close()
                }
                LogUtils.info(TAG, "================ decode $alias from input stream end ================")
            }
        }
        if (!dispatch) {
            decodeTask.run()
            return
        }
        try {
            threadPoolExecutor.execute(decodeTask)
        } catch (e: Exception) {
            if (closeInputStream) {
                try { inputStream.close() } catch (closeError: Exception) { e.addSuppressed(closeError) }
            }
            invokeErrorCallback(e, callback, alias)
        }
    }

    /**
     * @deprecated from 2.4.0
     */
    @Deprecated("This method has been deprecated from 2.4.0.", ReplaceWith("this.decodeFromAssets(assetsName, callback)"))
    fun parse(assetsName: String, callback: ParseCompletion?) {
        this.decodeFromAssets(assetsName, callback,null)
    }

    /**
     * @deprecated from 2.4.0
     */
    @Deprecated("This method has been deprecated from 2.4.0.", ReplaceWith("this.decodeFromURL(url, callback)"))
    fun parse(url: URL, callback: ParseCompletion?) {
        this.decodeFromURL(url, callback,null)
    }

    /**
     * @deprecated from 2.4.0
     */
    @Deprecated("This method has been deprecated from 2.4.0.", ReplaceWith("this.decodeFromInputStream(inputStream, cacheKey, callback, closeInputStream)"))
    fun parse(
            inputStream: InputStream,
            cacheKey: String,
            callback: ParseCompletion?,
            closeInputStream: Boolean = false
    ) {
        this.decodeFromInputStream(inputStream, cacheKey, callback, closeInputStream,null)
    }

    private fun prepareForPlayback(
        videoItem: SVGAVideoEntity,
        callback: () -> Unit,
        playCallback: PlayCallback?
    ) {
        try {
            videoItem.validateForPlayback()
            videoItem.prepare(callback, playCallback)
        } catch (e: Exception) {
            videoItem.clear()
            throw e
        }
    }

    private fun invokeCompleteCallback(
            videoItem: SVGAVideoEntity,
            callback: ParseCompletion?,
            alias: String?
    ) {
        Handler(Looper.getMainLooper()).post {
            LogUtils.info(TAG, "================ $alias parser complete ================")
            callback?.onComplete(videoItem)
        }
    }

    private fun invokeErrorCallback(
            e: Exception,
            callback: ParseCompletion?,
            alias: String?
    ) {
        e.printStackTrace()
        LogUtils.error(TAG, "================ $alias parser error ================")
        LogUtils.error(TAG, "$alias parse error", e)
        Handler(Looper.getMainLooper()).post {
            callback?.onError(e)
        }
    }

    private fun decodeFromCacheKey(
        cacheKey: String,
        callback: ParseCompletion?,
        alias: String?,
        playCallback: PlayCallback?,
        targetSize: Pair<Int, Int>
    ) {
        try {
            val videoItem = fileLocks.read(cacheKey) {
                val cacheDir = SVGACache.buildCacheDir(cacheKey)
                val binaryFile = File(cacheDir, "movie.binary")
                val jsonFile = File(cacheDir, "movie.spec")
                when {
                    binaryFile.isFile -> FileInputStream(binaryFile).use {
                        SVGAVideoEntity(MovieEntity.ADAPTER.decode(it), cacheDir,
                            targetSize.first, targetSize.second)
                    }
                    jsonFile.isFile -> FileInputStream(jsonFile).use {
                        SVGAVideoEntity(JSONObject(it.bufferedReader(Charsets.UTF_8).readText()), cacheDir,
                            targetSize.first, targetSize.second)
                    }
                    else -> throw FileNotFoundException("SVGA cache has neither movie.binary nor movie.spec")
                }
            }
            prepareForPlayback(videoItem, { invokeCompleteCallback(videoItem, callback, alias) }, playCallback)
        } catch (e: Exception) {
            invokeErrorCallback(e, callback, alias)
        }
    }

    private fun readAsBytes(inputStream: InputStream): ByteArray? {
        ByteArrayOutputStream().use { byteArrayOutputStream ->
            val byteArray = ByteArray(2048)
            while (true) {
                val count = inputStream.read(byteArray, 0, 2048)
                if (count <= 0) {
                    break
                } else {
                    byteArrayOutputStream.write(byteArray, 0, count)
                }
            }
            return byteArrayOutputStream.toByteArray()
        }
    }

    private fun inflate(byteArray: ByteArray): ByteArray? {
        val inflater = Inflater()
        try {
            inflater.setInput(byteArray)
            val buffer = ByteArray(2048)
            ByteArrayOutputStream().use { output ->
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    if (count > 0) output.write(buffer, 0, count)
                    else if (!inflater.finished()) throw IOException("Truncated or unsupported SVGA compressed stream")
                }
                return output.toByteArray()
            }
        } finally {
            inflater.end()
        }
    }

    // 是否是 zip 文件
    private fun isZipFile(bytes: ByteArray): Boolean {
        return bytes.size > 4 && bytes[0].toInt() == 80 && bytes[1].toInt() == 75 && bytes[2].toInt() == 3 && bytes[3].toInt() == 4
    }

    // 解压
    private fun unzip(inputStream: InputStream, cacheKey: String) {
        LogUtils.info(TAG, "================ unzip prepare ================")
        val cacheDir = SVGACache.buildCacheDir(cacheKey)
        cacheDir.mkdirs()
        try {
            BufferedInputStream(inputStream).use {
                ZipInputStream(it).use { zipInputStream ->
                    while (true) {
                        val zipItem = zipInputStream.nextEntry ?: break
                        if (zipItem.name.contains("../")) {
                            // 解压路径存在路径穿越问题，直接过滤
                            continue
                        }
                        if (zipItem.name.contains("/")) {
                            continue
                        }
                        val file = File(cacheDir, zipItem.name)
                        ensureUnzipSafety(file, cacheDir.absolutePath)
                        FileOutputStream(file).use { fileOutputStream ->
                            val buff = ByteArray(2048)
                            while (true) {
                                val readBytes = zipInputStream.read(buff)
                                if (readBytes <= 0) {
                                    break
                                }
                                fileOutputStream.write(buff, 0, readBytes)
                            }
                        }
                        LogUtils.error(TAG, "================ unzip complete ================")
                        zipInputStream.closeEntry()
                    }
                }
            }
        } catch (e: Exception) {
            LogUtils.error(TAG, "================ unzip error ================")
            LogUtils.error(TAG, "error", e)
            SVGACache.clearDir(cacheDir.absolutePath)
            cacheDir.delete()
            throw e
        }
    }

    // 检查 zip 路径穿透
    private fun ensureUnzipSafety(outputFile: File, dstDirPath: String) {
        val dstDirCanonicalPath = File(dstDirPath).canonicalPath
        val outputFileCanonicalPath = outputFile.canonicalPath
        if (!outputFileCanonicalPath.startsWith(dstDirCanonicalPath + File.separator)) {
            throw IOException("Found Zip Path Traversal Vulnerability with $dstDirCanonicalPath")
        }
    }
}
