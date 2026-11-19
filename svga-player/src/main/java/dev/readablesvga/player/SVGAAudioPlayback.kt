package dev.readablesvga.player

import android.util.Log
import dev.readablesvga.player.entities.SVGAAudioEntity

/** Per-drawable stream ownership. The cached entity owns only reusable sound samples. */
internal class SVGAAudioPlayback(
    val videoItem: SVGAVideoEntity,
    private val player: StreamPlayer = EntityStreamPlayer(videoItem)
) {
    internal interface StreamPlayer {
        fun play(sampleId: Int): Int
        fun stop(streamId: Int)
        fun pause(streamId: Int)
        fun resume(streamId: Int)
        fun setVolume(streamId: Int, volume: Float)
    }

    private fun trace(action: String, streamId: Int) {
        if (Log.isLoggable("SvgaStreamTrace", Log.DEBUG)) {
            Log.d("SvgaStreamTrace", "owner=${System.identityHashCode(this)} $action stream=$streamId")
        }
    }

    private fun stopStream(streamId: Int) {
        player.stop(streamId)
        trace("stop", streamId)
    }

    private val streams = mutableMapOf<SVGAAudioEntity, Int>()
    private var lastFrame = -1
    private var paused = false

    @Synchronized
    fun onFrame(frameIndex: Int) {
        if (paused || frameIndex == lastFrame) return
        if (frameIndex < lastFrame) stop()
        lastFrame = frameIndex
        videoItem.audioList.forEach { audio ->
            if (audio.startFrame == frameIndex && audio.endFrame > frameIndex) {
                streams.remove(audio)?.let(::stopStream)
                audio.soundID?.let { sampleId ->
                    val streamId = player.play(sampleId)
                    if (streamId > 0) {
                        streams[audio] = streamId
                        trace("play", streamId)
                    }
                }
            }
            if (audio.endFrame <= frameIndex) streams.remove(audio)?.let(::stopStream)
        }
        if (videoItem.usesSharedSoundPool && streams.isNotEmpty()) {
            SVGASoundManager.registerPlayback(this)
        } else {
            SVGASoundManager.unregisterPlayback(this)
        }
    }

    @Synchronized
    fun pause() {
        paused = true
        streams.values.forEach(player::pause)
    }

    @Synchronized
    fun resume() {
        paused = false
        streams.values.forEach(player::resume)
    }

    @Synchronized
    fun stop() {
        streams.values.forEach(::stopStream)
        streams.clear()
        lastFrame = -1
        paused = false
        SVGASoundManager.unregisterPlayback(this)
    }

    @Synchronized
    fun setVolume(volume: Float) = streams.values.forEach { player.setVolume(it, volume) }

    private class EntityStreamPlayer(private val videoItem: SVGAVideoEntity) : StreamPlayer {
        override fun play(sampleId: Int): Int = if (videoItem.usesSharedSoundPool) {
            SVGASoundManager.play(sampleId)
        } else {
            videoItem.soundPool?.play(sampleId, 1f, 1f, 1, 0, 1f) ?: 0
        }

        override fun stop(streamId: Int) {
            if (videoItem.usesSharedSoundPool) SVGASoundManager.stop(streamId)
            else videoItem.soundPool?.stop(streamId)
        }

        override fun pause(streamId: Int) {
            if (videoItem.usesSharedSoundPool) SVGASoundManager.pause(streamId)
            else videoItem.soundPool?.pause(streamId)
        }

        override fun resume(streamId: Int) {
            if (videoItem.usesSharedSoundPool) SVGASoundManager.resume(streamId)
            else videoItem.soundPool?.resume(streamId)
        }

        override fun setVolume(streamId: Int, volume: Float) {
            if (videoItem.usesSharedSoundPool) SVGASoundManager.setStreamVolume(streamId, volume)
            else videoItem.soundPool?.setVolume(streamId, volume, volume)
        }
    }
}
