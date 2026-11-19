// SPDX-License-Identifier: Apache-2.0
package dev.svganext.runtime

import android.content.Context
import dev.svganext.player.SVGAParser
import dev.svganext.player.SVGAVideoEntity
import java.net.URL

internal fun interface VideoDecoder {
    fun decode(request: SvgaRequest, complete: (Result<SVGAVideoEntity>) -> Unit): CancelHandle
}

internal class ParserVideoDecoder(context: Context) : VideoDecoder {
    private val application = context.applicationContext

    override fun decode(request: SvgaRequest, complete: (Result<SVGAVideoEntity>) -> Unit): CancelHandle {
        val parser = SVGAParser(application)
        parser.setFrameSize(request.width, request.height)
        val callback = object : SVGAParser.ParseCompletion {
            override fun onComplete(videoItem: SVGAVideoEntity) = complete(Result.success(videoItem))
            override fun onError() = complete(Result.failure(SvgaDecodeException(request.source)))
            override fun onError(error: Exception) = complete(Result.failure(SvgaDecodeException(request.source, error)))
        }
        val cancel = when (val source = request.source) {
            is SvgaSource.Asset -> {
                parser.decodeFromAssets(source.path, callback)
                null
            }
            is SvgaSource.File -> {
                parser.decodeFromFile(source.file, callback)
                null
            }
            is SvgaSource.Url -> parser.decodeFromURL(URL(source.url), callback)
        }
        return CancelHandle { cancel?.invoke() }
    }
}

class SvgaDecodeException(val source: SvgaSource, cause: Throwable? = null) :
    Exception("Unable to decode SVGA animation", cause)
