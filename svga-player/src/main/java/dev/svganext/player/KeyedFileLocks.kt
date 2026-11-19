// SPDX-License-Identifier: Apache-2.0
package dev.svganext.player

import java.util.concurrent.locks.ReentrantReadWriteLock

/** Serializes writes to one cache target without retaining keys after the last caller. */
internal class KeyedFileLocks {
    private class Slot(var users: Int = 0, val lock: ReentrantReadWriteLock = ReentrantReadWriteLock())
    private val slots = HashMap<String, Slot>()

    fun <T> withLock(key: String, block: () -> T): T = withAccess(key, true, block)

    fun <T> read(key: String, block: () -> T): T = withAccess(key, false, block)

    private fun <T> withAccess(key: String, write: Boolean, block: () -> T): T {
        val slot = synchronized(slots) { slots.getOrPut(key) { Slot() }.also { it.users++ } }
        val access = if (write) slot.lock.writeLock() else slot.lock.readLock()
        access.lock()
        try {
            return block()
        } finally {
            access.unlock()
            synchronized(slots) {
                if (--slot.users == 0) slots.remove(key)
            }
        }
    }
}
