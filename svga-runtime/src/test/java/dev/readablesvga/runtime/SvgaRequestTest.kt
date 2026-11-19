// SPDX-License-Identifier: Apache-2.0
package dev.readablesvga.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SvgaRequestTest {
    @Test fun sizesArePartOfMemoryIdentity() {
        val source = SvgaSource.Asset("demo.svga")
        assertNotEquals(SvgaRequest(source, 40, 40).cacheKey, SvgaRequest(source, 200, 200).cacheKey)
        assertNotEquals(SvgaRequest(source).cacheKey, SvgaRequest(source, 200, 200).cacheKey)
    }

    @Test fun halfDefinedOrNegativeSizeIsRejected() {
        val source = SvgaSource.Asset("demo.svga")
        listOf(0 to 30, 30 to 0, -1 to -1, -1 to 30).forEach { (width, height) ->
            assertThrows(IllegalArgumentException::class.java) { SvgaRequest(source, width, height) }
        }
    }

    @Test fun relativePathsAndNetworkProtocolsAreValidated() {
        assertThrows(IllegalArgumentException::class.java) { SvgaSource.Asset("../demo.svga") }
        assertThrows(IllegalArgumentException::class.java) { SvgaSource.Asset("/demo.svga") }
        assertThrows(IllegalArgumentException::class.java) { SvgaSource.Url("file:///demo.svga") }
        assertEquals(0L, SvgaConfig(0).memoryCacheBytes)
        assertThrows(IllegalArgumentException::class.java) { SvgaConfig(-1) }
    }

    @Test fun changedLocalFileDoesNotReuseAnOldResourceKey() {
        val file = File.createTempFile("svga-runtime", ".svga")
        try {
            val request = SvgaRequest(SvgaSource.File(file))
            file.writeBytes(byteArrayOf(1))
            val oldKey = request.cacheKey
            file.writeBytes(byteArrayOf(1, 2))
            assertNotEquals(oldKey, request.cacheKey)
        } finally {
            file.delete()
        }
    }
}
