// SPDX-License-Identifier: Apache-2.0
package dev.readablesvga.runtime

import java.io.File as IoFile
import java.net.URL

/** A local asset, local file, or HTTP(S) animation. */
sealed class SvgaSource {
    data class Asset(val path: String) : SvgaSource() {
        init {
            require(path.isNotBlank() && !path.startsWith("/") && path.split('/').none { it == ".." }) {
                "Asset path must be a non-empty relative path"
            }
        }
    }

    data class File(val file: IoFile) : SvgaSource()

    data class Url(val url: String) : SvgaSource() {
        init {
            val parsed = URL(url)
            require(parsed.protocol == "http" || parsed.protocol == "https") { "Only HTTP(S) URLs are supported" }
            require(parsed.host.isNotEmpty()) { "URL must contain a host" }
        }
    }

    internal fun identity(): String = when (this) {
        is Asset -> "asset:$path"
        is File -> "file:${file.canonicalPath}:${file.length()}:${file.lastModified()}"
        is Url -> "url:$url"
    }
}

/** Dimensions are decoded pixel bounds. Zero/zero preserves the source resolution. */
data class SvgaRequest(val source: SvgaSource, val width: Int = 0, val height: Int = 0) {
    init {
        require((width == 0 && height == 0) || (width > 0 && height > 0)) {
            "Width and height must either both be positive or both be zero"
        }
    }

    /** File identity uses path, length and mtime; replace a file atomically when updating its content. */
    val cacheKey: String
        get() = "${source.identity()}|${width}x$height"
}

data class SvgaConfig(val memoryCacheBytes: Long = 32L * 1024 * 1024) {
    init {
        require(memoryCacheBytes >= 0) { "Cache budget cannot be negative" }
    }
}

/** Cancelling suppresses callbacks and releases any queued result. Delivered leases belong to the caller. */
fun interface CancelHandle : AutoCloseable {
    fun cancel()
    override fun close() = cancel()
}
