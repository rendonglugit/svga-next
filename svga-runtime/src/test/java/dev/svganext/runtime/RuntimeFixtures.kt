// SPDX-License-Identifier: Apache-2.0
package dev.svganext.runtime

import android.content.Context
import dev.svganext.player.SVGAVideoEntity
import org.json.JSONObject

internal class FakeVideoDecoder : VideoDecoder {
    val calls = mutableListOf<Call>()

    override fun decode(request: SvgaRequest, complete: (Result<SVGAVideoEntity>) -> Unit): CancelHandle {
        val call = Call(request, complete)
        calls.add(call)
        return CancelHandle { call.cancelCount++ }
    }

    class Call(val request: SvgaRequest, val complete: (Result<SVGAVideoEntity>) -> Unit) {
        var cancelCount = 0
        fun succeed(entity: SVGAVideoEntity) = complete(Result.success(entity))
    }
}

internal fun emptyVideo(context: Context): SVGAVideoEntity = SVGAVideoEntity(
    JSONObject().put(
        "movie", JSONObject()
            .put("viewBox", JSONObject().put("width", 16).put("height", 16))
            .put("fps", 20)
            .put("frames", 2),
    ).put("images", JSONObject()),
    context.cacheDir,
)
