// SPDX-License-Identifier: Apache-2.0
package com.opensource.svgaplayer.internal

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.ContentProvider
import android.content.ContentValues
import android.content.res.Configuration
import android.database.Cursor
import android.net.Uri
import com.opensource.svgaplayer.SVGAActivitiesCache
import dev.svganext.player.SVGACache
import dev.svganext.player.SVGASoundManager

/**
 * Initializes SVGACache, SVGASoundManager, and registers memory trim callbacks automatically on app startup.
 * Legacy projects do not need to add any manual initialization code in Application.onCreate.
 */
class SvgaInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { ctx ->
            SVGACache.onCreate(ctx)
            try {
                SVGASoundManager.init()
            } catch (_: Throwable) {
                // If SoundPool fails on non-standard Android test envs, fail gracefully.
            }
            try {
                com.opensource.svgaplayer.SVGAParser.shareParser().init(ctx)
            } catch (_: Throwable) {}

            (ctx.applicationContext as? Application)?.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    SVGAActivitiesCache.instance.trimCache(level)
                }

                override fun onLowMemory() {
                    SVGAActivitiesCache.instance.clear()
                }

                override fun onConfigurationChanged(newConfig: Configuration) {}
            })
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
