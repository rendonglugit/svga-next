// SPDX-License-Identifier: Apache-2.0
package com.opensource.svgaplayer

import dev.svganext.player.SVGASoundManager as CoreSoundManager
import dev.svganext.player.SVGAVideoEntity

object SVGASoundManager {
    fun init() = CoreSoundManager.init()
    fun init(maxStreams: Int) = CoreSoundManager.init(maxStreams)
    fun release() = CoreSoundManager.release()
    fun isInit(): Boolean = CoreSoundManager.isInit()
    fun setVolume(volume: Float) = CoreSoundManager.setVolume(volume)
    fun setVolume(volume: Float, videoItem: SVGAVideoEntity) = CoreSoundManager.setVolume(volume, videoItem)
}
