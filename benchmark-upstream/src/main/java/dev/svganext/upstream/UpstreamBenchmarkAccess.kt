// SPDX-License-Identifier: Apache-2.0
package dev.svganext.upstream

/** Observation bridge only. It does not change parsing, cache or drawing behavior. */
object UpstreamBenchmarkAccess {
    fun estimatedMemoryBytes(video: SVGAVideoEntity): Long =
        video.imageMap.values.sumOf { if (it.isRecycled) 0L else it.allocationByteCount.toLong() } +
            (video.movieItem?.images?.values?.sumOf { it.size.toLong() } ?: 0L)
}
