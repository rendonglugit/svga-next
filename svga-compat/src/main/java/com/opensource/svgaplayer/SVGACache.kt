// SPDX-License-Identifier: Apache-2.0
package com.opensource.svgaplayer

import android.content.Context
import dev.svganext.player.SVGACache as CoreCache
import java.io.File
import java.net.URL

object SVGACache {
    enum class Type {
        DEFAULT,
        FILE
    }

    fun onCreate(context: Context?) = CoreCache.onCreate(context)
    fun onCreate(context: Context?, type: Type) {
        val coreType = if (type == Type.DEFAULT) CoreCache.Type.DEFAULT else CoreCache.Type.FILE
        CoreCache.onCreate(context, coreType)
    }

    fun clearCache() = CoreCache.clearCache()
    fun isInitialized(): Boolean = CoreCache.isInitialized()
    fun isDefaultCache(): Boolean = CoreCache.isDefaultCache()
    fun isCached(cacheKey: String): Boolean = CoreCache.isCached(cacheKey)
    fun buildCacheKey(str: String): String = CoreCache.buildCacheKey(str)
    fun buildCacheKey(url: URL): String = CoreCache.buildCacheKey(url)
    fun buildCacheDir(cacheKey: String): File = CoreCache.buildCacheDir(cacheKey)
    fun buildSvgaFile(cacheKey: String): File = CoreCache.buildSvgaFile(cacheKey)
}
