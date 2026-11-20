package dev.svganext.player

/**
 * @author Devin
 *
 * Created on 2/24/21.
 */
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import dev.svganext.player.utils.log.LogUtils
import java.io.FileDescriptor
import java.util.WeakHashMap

/**
 * Author : llk
 * Time : 2020/10/24
 * Description : svga 音频加载管理类
 * 将 SoundPool 抽取到单例里边，规避 load 资源之后不回调 onLoadComplete 的问题。
 *
 * 需要对 SVGASoundManager 进行初始化
 *
 * 相关文章：Android SoundPool 崩溃问题研究
 * https://zhuanlan.zhihu.com/p/29985198
 */
object SVGASoundManager {

    private val TAG = SVGASoundManager::class.java.simpleName

    @Volatile
    private var soundPool: SoundPool? = null

    private val lock = Any()

    private val soundCallBackMap: MutableMap<Int, SVGASoundCallBack> = mutableMapOf()
    private val playbacks = WeakHashMap<SVGAAudioPlayback, Boolean>()

    /**
     * 音量设置，范围在 [0, 1] 之间
     */
    @Volatile
    private var volume: Float = 1f

    /**
     * 音频回调
     */
    internal interface SVGASoundCallBack {

        // Every load reaches a terminal state, including native load failures.
        fun onComplete(status: Int)
    }

    fun init() {
        init(20)
    }

    fun init(maxStreams: Int) {
        synchronized(lock) {
            if (soundPool != null) return
            val created = getSoundPool(maxStreams)
            try {
                created.setOnLoadCompleteListener { pool, soundId, status ->
                    val callback = synchronized(lock) {
                        if (pool === soundPool) soundCallBackMap.remove(soundId) else null
                    }
                    callback?.onComplete(status)
                }
                soundPool = created
            } catch (error: Throwable) {
                try { created.release() } catch (cleanupError: Throwable) {
                    if (cleanupError !== error) error.addSuppressed(cleanupError)
                }
                throw error
            }
        }
    }

    /** Releases the pool and terminates pending loads; callers must dispose cached entities too. */
    fun release() {
        val (pool, callbacks) = synchronized(lock) {
            val pool = soundPool
            soundPool = null
            val callbacks = soundCallBackMap.values.toList()
            soundCallBackMap.clear()
            playbacks.clear()
            pool to callbacks
        }
        var failure: Throwable? = null
        fun cleanup(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                val previous = failure
                if (previous == null) failure = error
                else if (previous !== error) previous.addSuppressed(error)
            }
        }
        cleanup { pool?.setOnLoadCompleteListener(null) }
        cleanup { pool?.release() }
        callbacks.forEach { callback -> cleanup { callback.onComplete(-1) } }
        failure?.let { throw it }
    }

    /**
     * 根据当前播放实体，设置音量
     *
     * @param volume 范围在 [0, 1]
     * @param entity 根据需要控制对应 entity 音量大小，若为空则控制所有正在播放的音频音量
     */
    fun setVolume(volume: Float, entity: SVGAVideoEntity? = null) {
        if (!checkInit()) {
            return
        }

        if (volume < 0f || volume > 1f) {
            LogUtils.error(TAG, "The volume level is in the range of 0 to 1 ")
            return
        }

        val active = synchronized(lock) {
            if (entity == null) this.volume = volume
            playbacks.keys.filter { entity == null || it.videoItem === entity }
        }
        active.forEach { it.setVolume(volume) }
    }

    internal fun registerPlayback(playback: SVGAAudioPlayback) {
        synchronized(lock) { playbacks[playback] = true }
    }

    internal fun unregisterPlayback(playback: SVGAAudioPlayback) {
        synchronized(lock) { playbacks.remove(playback) }
    }

    internal fun setStreamVolume(streamId: Int, value: Float) {
        soundPool?.setVolume(streamId, value, value)
    }

    /**
     * 是否初始化
     * 如果没有初始化，就使用原来SvgaPlayer库的音频加载逻辑。
     * @return true 则已初始化， 否则为 false
     */
    fun isInit(): Boolean {
        return soundPool != null
    }

    private fun checkInit(): Boolean {
        val isInit = isInit()
        if (!isInit) {
            LogUtils.error(TAG, "soundPool is null, you need call init() !!!")
        }
        return isInit
    }

    private fun getSoundPool(maxStreams: Int) = if (Build.VERSION.SDK_INT >= 21) {
        val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
        SoundPool.Builder().setAudioAttributes(attributes)
                .setMaxStreams(maxStreams)
                .build()
    } else {
        SoundPool(maxStreams, AudioManager.STREAM_MUSIC, 0)
    }

    internal fun load(callBack: SVGASoundCallBack?,
                      fd: FileDescriptor?,
                      offset: Long,
                      length: Long,
                      priority: Int): Int {
        if (!checkInit()) return -1

        // Registration and native completion share a lock, so a fast completion cannot
        // arrive before its callback is registered. Invoke callbacks outside that lock.
        val soundId = synchronized(lock) {
            val id = soundPool?.load(fd, offset, length, priority) ?: 0
            if (callBack != null && id > 0) soundCallBackMap[id] = callBack
            id
        }
        LogUtils.debug(TAG, "load soundId=$soundId callBack=$callBack")
        if (soundId <= 0) callBack?.onComplete(-1)
        return soundId
    }

    fun unload(soundId: Int) {
        if (!checkInit()) return

        LogUtils.debug(TAG, "unload soundId=$soundId")

        synchronized(lock) {
            try { soundPool?.unload(soundId) }
            finally { soundCallBackMap.remove(soundId) }
        }
    }

    internal fun play(soundId: Int): Int {
        if (!checkInit()) return -1

        LogUtils.debug(TAG, "play soundId=$soundId")
        return soundPool?.play(soundId, volume, volume, 1, 0, 1.0f) ?: -1
    }

    fun stop(soundId: Int) {
        if (!checkInit()) return

        LogUtils.debug(TAG, "stop soundId=$soundId")
        soundPool?.stop(soundId)
    }

    internal fun resume(soundId: Int) {
        if (!checkInit()) return

        LogUtils.debug(TAG, "stop soundId=$soundId")
        soundPool?.resume(soundId)
    }

    internal fun pause(soundId: Int) {
        if (!checkInit()) return

        LogUtils.debug(TAG, "pause soundId=$soundId")
        soundPool?.pause(soundId)
    }
}
