// SPDX-License-Identifier: Apache-2.0
package dev.readablesvga.player

import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CacheFilePublicationTest {
    @get:Rule val dir = TemporaryFolder()

    @Test fun cancellationAtCommitDoesNotPublishStagedBytes() {
        val file = dir.root.resolve("movie.svga")
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            publishCacheFile(file, commit = {
                val staged = dir.root.listFiles()!!.single()
                assertEquals("complete payload", staged.readText())
                assertFalse(file.exists())
                throw java.util.concurrent.CancellationException()
            }) { it.write("complete payload".toByteArray()) }
        }
        assertFalse(file.exists())
        assertTrue(dir.root.listFiles()!!.isEmpty())
    }

    @Test fun incompleteWriteNeverReplacesLastCompleteFile() {
        val file = dir.newFile("movie.svga").apply { writeText("old") }
        assertThrows(IOException::class.java) {
            publishCacheFile(file) {
                it.write("partial".toByteArray())
                assertEquals("old", file.readText())
                throw IOException("interrupted")
            }
        }
        assertEquals("old", file.readText())
        assertEquals(listOf(file), dir.root.listFiles()!!.toList())
        publishCacheFile(file) { it.write("new".toByteArray()) }
        assertEquals("new", file.readText())
    }
}
