// SPDX-License-Identifier: Apache-2.0
package com.opensource.svgaplayer

import dev.svganext.player.SVGASoundManager as CoreSoundManager
import dev.svganext.player.SVGAVideoEntity as CoreEntity

object SVGASoundManager {
    @JvmStatic
    fun init() = CoreSoundManager.init()

    @JvmStatic
    fun init(maxStreams: Int) = CoreSoundManager.init(maxStreams)

    @JvmStatic
    fun release() = CoreSoundManager.release()

    @JvmStatic
    fun isInit(): Boolean = CoreSoundManager.isInit()

    @JvmStatic
    fun setVolume(volume: Float) = CoreSoundManager.setVolume(volume)

    @JvmStatic
    fun setVolume(volume: Float, videoItem: CoreEntity?) = CoreSoundManager.setVolume(volume, videoItem)

    @JvmStatic
    fun setVolume(volume: Float, videoItem: SVGAVideoEntity) = CoreSoundManager.setVolume(volume, videoItem)

    @JvmStatic
    fun unload(soundId: Int) = CoreSoundManager.unload(soundId)

    @JvmStatic
    fun stop(soundId: Int) = CoreSoundManager.stop(soundId)
}
