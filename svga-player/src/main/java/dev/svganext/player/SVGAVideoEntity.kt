package dev.svganext.player

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import dev.svganext.player.bitmap.SVGABitmapByteArrayDecoder
import dev.svganext.player.bitmap.SVGABitmapFileDecoder
import dev.svganext.player.entities.SVGAAudioEntity
import dev.svganext.player.entities.SVGAVideoSpriteEntity
import dev.svganext.player.proto.AudioEntity
import dev.svganext.player.proto.MovieEntity
import dev.svganext.player.proto.MovieParams
import dev.svganext.player.utils.SVGARect
import dev.svganext.player.utils.log.LogUtils
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import okio.ByteString
import kotlin.collections.ArrayList

/**
 * Created by PonyCui on 16/6/18.
 */
private val audioFileLock = Any()

open class SVGAVideoEntity {

    private val TAG = "SVGAVideoEntity"

    var antiAlias = true
    var movieItem: MovieEntity? = null

    var videoSize = SVGARect(0.0, 0.0, 0.0, 0.0)
        protected set

    var FPS = 15
        protected set

    var frames: Int = 0
        protected set

    internal var spriteList: List<SVGAVideoSpriteEntity> = emptyList()
    internal var audioList: List<SVGAAudioEntity> = emptyList()
    internal var soundPool: SoundPool? = null
    internal var usesSharedSoundPool = false
    internal var imageMap = HashMap<String, Bitmap>()

    /**
     * Unmodifiable view of decoded bitmap assets.
     */
    val bitmaps: Map<String, Bitmap>
        get() = java.util.Collections.unmodifiableMap(imageMap)
    internal var mCacheDir: File
    internal var mFrameHeight = 0
    internal var mFrameWidth = 0
    private val preparationLock = Any()
    internal var preparationStarted = false
    internal var prepared = false
    internal var released = false
    val isReleased: Boolean
        get() = released
    private var mCallback: (() -> Unit)? = null
    internal var decodingFailure: Throwable? = null

    /**
     * Cache weight: allocated bitmap storage plus encoded media payloads still retained in
     * [movieItem]. It excludes JVM object graphs, GPU storage and SoundPool native buffers;
     * this is a cache accounting estimate, not a measurement of total process memory.
     */
    val estimatedMemoryBytes: Long
        get() = imageMap.values.sumOf { if (it.isRecycled) 0L else it.allocationByteCount.toLong() } +
                (movieItem?.images?.values?.sumOf { it.size.toLong() } ?: 0L)

    /** Drops preparation closures. Preparation normally does this itself before completion. */
    fun releaseCallbackReferences() {
        synchronized(preparationLock) { mCallback = null }
    }

    internal fun validateForPlayback() {
        require(FPS > 0 && frames > 0) { "SVGA fps and frame count must be positive" }
        require(videoSize.width.isFinite() && videoSize.width > 0 &&
                videoSize.height.isFinite() && videoSize.height > 0) {
            "SVGA viewBox must have positive finite dimensions"
        }
        decodingFailure?.let { throw IOException("SVGA image data could not be decoded", it) }
    }

    constructor(source: SVGAVideoEntity) {
        this.mCacheDir = source.mCacheDir
        this.mFrameWidth = source.mFrameWidth
        this.mFrameHeight = source.mFrameHeight
        this.movieItem = source.movieItem
        this.videoSize = source.videoSize
        this.FPS = source.FPS
        this.frames = source.frames
        this.antiAlias = source.antiAlias
        this.spriteList = source.spriteList
        this.audioList = source.audioList
        this.soundPool = source.soundPool
        this.usesSharedSoundPool = source.usesSharedSoundPool
        this.imageMap = source.imageMap
        this.preparationStarted = source.preparationStarted
        this.prepared = source.prepared
        this.released = source.released
        this.decodingFailure = source.decodingFailure
    }

    constructor(json: JSONObject, cacheDir: File) : this(json, cacheDir, 0, 0)

    constructor(json: JSONObject, cacheDir: File, frameWidth: Int, frameHeight: Int) {
        mFrameWidth = frameWidth
        mFrameHeight = frameHeight
        mCacheDir = cacheDir
        val movieJsonObject = json.optJSONObject("movie") ?: return
        setupByJson(movieJsonObject)
        try {
            parserImages(json)
        } catch (e: Exception) {
            decodingFailure = e
            e.printStackTrace()
        } catch (e: OutOfMemoryError) {
            decodingFailure = e
            e.printStackTrace()
        }
        resetSprites(json)
    }

    private fun setupByJson(movieObject: JSONObject) {
        movieObject.optJSONObject("viewBox")?.let { viewBoxObject ->
            val width = viewBoxObject.optDouble("width", 0.0)
            val height = viewBoxObject.optDouble("height", 0.0)
            videoSize = SVGARect(0.0, 0.0, width, height)
        }
        FPS = movieObject.optInt("fps", 20)
        frames = movieObject.optInt("frames", 0)
    }

    constructor(entity: MovieEntity, cacheDir: File) : this(entity, cacheDir, 0, 0)

    constructor(entity: MovieEntity, cacheDir: File, frameWidth: Int, frameHeight: Int) {
        this.mFrameWidth = frameWidth
        this.mFrameHeight = frameHeight
        this.mCacheDir = cacheDir
        this.movieItem = entity
        entity.params?.let(this::setupByMovie)
        try {
            parserImages(entity)
        } catch (e: Exception) {
            decodingFailure = e
            e.printStackTrace()
        } catch (e: OutOfMemoryError) {
            decodingFailure = e
            e.printStackTrace()
        }
        resetSprites(entity)
    }

    private fun setupByMovie(movieParams: MovieParams) {
        val width = (movieParams.viewBoxWidth ?: 0.0f).toDouble()
        val height = (movieParams.viewBoxHeight ?: 0.0f).toDouble()
        videoSize = SVGARect(0.0, 0.0, width, height)
        FPS = movieParams.fps ?: 20
        frames = movieParams.frames ?: 0
    }

    internal fun prepare(callback: () -> Unit, playCallback: SVGAParser.PlayCallback?) {
        val alreadyPrepared = synchronized(preparationLock) {
            check(!released) { "Cannot prepare a released SVGA entity" }
            if (prepared) true else {
                check(!preparationStarted) { "SVGA entity preparation is already in progress" }
                preparationStarted = true
                mCallback = callback
                false
            }
        }
        if (alreadyPrepared) {
            callback()
            return
        }
        val movie = movieItem
        if (movie == null) completePreparation()
        else setupAudios(movie, playCallback)
    }

    private fun completePreparation() {
        val callback = synchronized(preparationLock) {
            if (released || prepared) return
            prepared = true
            val result = mCallback
            mCallback = null
            soundPool?.setOnLoadCompleteListener(null)
            result
        }
        callback?.invoke()
    }

    private fun parserImages(json: JSONObject) {
        val imgJson = json.optJSONObject("images") ?: return
        imgJson.keys().forEach { imgKey ->
            val filePath = generateBitmapFilePath(imgJson[imgKey].toString(), imgKey)
            if (filePath.isEmpty()) {
                decodingFailure = IOException("SVGA image $imgKey is missing")
                return@forEach
            }
            val bitmapKey = imgKey.replace(".matte", "")
            val bitmap = createBitmap(filePath)
            if (bitmap != null) {
                imageMap[bitmapKey] = bitmap
            } else decodingFailure = IOException("SVGA image $imgKey could not be decoded")
        }
    }

    private fun generateBitmapFilePath(imgName: String, imgKey: String): String {
        val path = mCacheDir.absolutePath + "/" + imgName
        val path1 = "$path.png"
        val path2 = mCacheDir.absolutePath + "/" + imgKey + ".png"

        return when {
            File(path).exists() -> path
            File(path1).exists() -> path1
            File(path2).exists() -> path2
            else -> ""
        }
    }

    private fun createBitmap(filePath: String): Bitmap? {
        return SVGABitmapFileDecoder.decodeBitmapFrom(filePath, mFrameWidth, mFrameHeight)
    }

    private fun parserImages(obj: MovieEntity) {
        obj.images?.entries?.forEach { entry ->
            if (isAudioData(entry.value)) return@forEach
            val bitmap = SVGABitmapByteArrayDecoder.decodeBitmapFrom(
                entry.value.toByteArray(), mFrameWidth, mFrameHeight
            ) ?: createBitmap(generateBitmapFilePath(entry.value.utf8(), entry.key))
            if (bitmap != null) imageMap[entry.key] = bitmap
            else decodingFailure = IOException("SVGA image ${entry.key} could not be decoded")
        }
    }

    private fun isAudioData(data: ByteString): Boolean = data.size >= 3 &&
            ((data[0].toInt() == 73 && data[1].toInt() == 68 && data[2].toInt() == 51) ||
            (data[0].toInt() == -1 && data[1].toInt() == -5 && data[2].toInt() == -108))

    private fun resetSprites(json: JSONObject) {
        val mutableList: MutableList<SVGAVideoSpriteEntity> = mutableListOf()
        json.optJSONArray("sprites")?.let { item ->
            for (i in 0 until item.length()) {
                item.optJSONObject(i)?.let { entryJson ->
                    mutableList.add(SVGAVideoSpriteEntity(entryJson))
                }
            }
        }
        spriteList = mutableList.toList()
    }

    private fun resetSprites(entity: MovieEntity) {
        spriteList = entity.sprites?.map {
            return@map SVGAVideoSpriteEntity(it)
        } ?: listOf()
    }

    private fun setupAudios(entity: MovieEntity, playCallback: SVGAParser.PlayCallback?) {
        audioList = entity.audios.map(::SVGAAudioEntity)
        if (audioList.isEmpty()) {
            completePreparation()
            return
        }
        val files = generateAudioFileMap(entity)
        if (playCallback != null) {
            // External audio playback receives one file list per preparation, never once per track.
            playCallback.onPlay(files.values.toList())
            completePreparation()
            return
        }
        val loadable = audioList.filter {
            it.totalTime > 0 && it.startTime >= 0 && it.startTime < it.totalTime && files[it.audioKey] != null
        }
        if (loadable.isEmpty()) {
            completePreparation()
            return
        }
        usesSharedSoundPool = SVGASoundManager.isInit()
        if (!usesSharedSoundPool) soundPool = generateSoundPool(entity)
        if (!usesSharedSoundPool && soundPool == null) {
            completePreparation()
            return
        }
        var remaining = loadable.size
        val localCallbacks = mutableMapOf<Int, (Int) -> Unit>()
        soundPool?.setOnLoadCompleteListener { _, sampleId, status ->
            val completion = synchronized(preparationLock) { localCallbacks.remove(sampleId) }
            completion?.invoke(status)
        }
        loadable.forEach { item ->
            val terminal = AtomicBoolean(false)
            val onTerminal: (Int) -> Unit = { status ->
                if (terminal.compareAndSet(false, true)) {
                    val finished = synchronized(preparationLock) {
                        if (status != 0) item.soundID = null
                        remaining--
                        remaining == 0
                    }
                    if (finished) completePreparation()
                }
            }
            try {
                FileInputStream(files.getValue(item.audioKey!!)).use { input ->
                    val length = input.channel.size()
                    val offset = (item.startTime.toDouble() / item.totalTime * length).toLong()
                    synchronized(preparationLock) {
                        val id = if (usesSharedSoundPool) {
                            SVGASoundManager.load(object : SVGASoundManager.SVGASoundCallBack {
                                override fun onComplete(status: Int) = onTerminal(status)
                            }, input.fd, offset, length, 1)
                        } else {
                            soundPool?.load(input.fd, offset, length, 1) ?: 0
                        }
                        if (id > 0) {
                            item.soundID = id
                            if (!usesSharedSoundPool) localCallbacks[id] = onTerminal
                        } else onTerminal(-1)
                    }
                }
            } catch (e: Exception) {
                LogUtils.error(TAG, e)
                onTerminal(-1)
            }
        }
    }

    private fun generateAudioFileMap(entity: MovieEntity): HashMap<String, File> {
        val files = HashMap<String, File>()
        generateAudioMap(entity).forEach { (key, data) ->
            // Same audioKey in two movies may contain different bytes. Content identity avoids
            // reusing the other movie's sound sample and keeps cache filenames within the root.
            val file = SVGACache.buildAudioFile(data.sha256().hex())
            synchronized(audioFileLock) {
                if (!file.isFile || file.length() != data.size.toLong()) {
                    FileOutputStream(file).use { it.write(data.toByteArray()) }
                }
            }
            files[key] = file
        }
        return files
    }

    private fun generateAudioMap(entity: MovieEntity): HashMap<String, ByteString> {
        val result = HashMap<String, ByteString>()
        entity.images?.forEach { (key, value) ->
            if (isAudioData(value)) result[key] = value
        }
        return result
    }

    private fun generateSoundPool(entity: MovieEntity): SoundPool? {
        return try {
            if (Build.VERSION.SDK_INT >= 21) {
                val attributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                SoundPool.Builder().setAudioAttributes(attributes)
                        .setMaxStreams(12.coerceAtMost(entity.audios.count()))
                        .build()
            } else {
                SoundPool(12.coerceAtMost(entity.audios.count()), AudioManager.STREAM_MUSIC, 0)
            }
        } catch (e: Exception) {
            LogUtils.error(TAG, e)
            null
        }
    }

    open fun clearAudioList() {
        synchronized(preparationLock) {
            if (usesSharedSoundPool) {
                audioList.forEach { audio ->
                    audio.soundID?.let { id ->
                        try { SVGASoundManager.unload(id) } catch (_: Throwable) {}
                    }
                    audio.soundID = null
                }
            }
        }
    }

    /** Disposes shared resources. Call only after every owner has released its lease. */
    open fun clear() {
        synchronized(preparationLock) {
            if (released) return
            released = true
            mCallback = null
            var failure: Throwable? = null
            fun cleanup(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    val previous = failure
                    if (previous == null) failure = error
                    else if (previous !== error) previous.addSuppressed(error)
                }
            }
            try {
                if (usesSharedSoundPool) {
                    audioList.forEach { audio ->
                        audio.soundID?.let { id -> cleanup { SVGASoundManager.unload(id) } }
                    }
                }
                cleanup { soundPool?.setOnLoadCompleteListener(null) }
                cleanup { soundPool?.release() }
            } finally {
                soundPool = null
                audioList = emptyList()
                spriteList = emptyList()
                imageMap.clear()
                movieItem = null
                decodingFailure = null
            }
            failure?.let { throw it }
        }
    }
}
