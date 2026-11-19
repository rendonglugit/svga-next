// SPDX-License-Identifier: Apache-2.0
package dev.readablesvga.player

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Stage beside the target so a failed write never truncates the previous value. */
internal fun publishCacheFile(target: File, write: (FileOutputStream) -> Unit) =
    publishCacheFile(target, commit = { it() }, write = write)

internal fun publishCacheFile(
    target: File,
    commit: (() -> Unit) -> Unit,
    write: (FileOutputStream) -> Unit,
) {
    val staged = File.createTempFile("svga-", ".pending", target.absoluteFile.parentFile)
    try {
        FileOutputStream(staged).use { stream ->
            write(stream)
            stream.fd.sync()
        }
        commit {
            if (!staged.renameTo(target)) throw IOException("Unable to publish SVGA cache file")
        }
    } finally {
        staged.delete()
    }
}
