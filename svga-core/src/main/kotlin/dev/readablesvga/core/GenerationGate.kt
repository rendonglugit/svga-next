// SPDX-License-Identifier: Apache-2.0
package dev.readablesvga.core

/** Assigns request generations so a consumer can reject results from older requests. */
class GenerationGate {
    private val lock = Any()
    private var generation = 0L
    private var active = false

    fun begin(): Long = synchronized(lock) {
        check(generation < Long.MAX_VALUE) { "Request generation limit reached" }
        generation++
        active = true
        generation
    }

    fun invalidate() = synchronized(lock) {
        active = false
    }

    fun isCurrent(token: Long): Boolean = synchronized(lock) {
        active && token == generation
    }
}
