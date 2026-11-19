// SPDX-License-Identifier: Apache-2.0
package dev.svganext.player

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import org.junit.Assert.*
import org.junit.Test

class KeyedFileLocksTest {
    @Test fun sameKeyReadersShareAccessButWriterWaits() {
        val locks = KeyedFileLocks()
        val pool = Executors.newFixedThreadPool(3)
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writerAttempt = CountDownLatch(1)
        try {
            val first = pool.submit {
                locks.read("zip") {
                    reading.countDown()
                    assertTrue(release.await(5, SECONDS))
                }
            }
            assertTrue(reading.await(5, SECONDS))
            pool.submit { locks.read("zip") {} }.get(5, SECONDS)
            val writer = pool.submit<Boolean> {
                writerAttempt.countDown()
                locks.withLock("zip") { release.count == 0L }
            }
            assertTrue(writerAttempt.await(5, SECONDS))
            pool.submit { locks.withLock("other") {} }.get(5, SECONDS)
            assertFalse(writer.isDone)
            release.countDown()
            first.get(5, SECONDS)
            assertTrue(writer.get(5, SECONDS))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun distinctKeysProceedWhileSameKeyWaits() {
        val locks = KeyedFileLocks()
        val pool = Executors.newFixedThreadPool(3)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val contender = CountDownLatch(1)
        try {
            val first = pool.submit {
                locks.withLock("a") {
                    entered.countDown()
                    assertTrue(release.await(5, SECONDS))
                }
            }
            assertTrue(entered.await(5, SECONDS))
            val second = pool.submit<Boolean> {
                contender.countDown()
                locks.withLock("a") { release.count == 0L }
            }
            assertTrue(contender.await(5, SECONDS))
            pool.submit { locks.withLock("b") {} }.get(5, SECONDS)
            assertFalse(second.isDone)
            release.countDown()
            first.get(5, SECONDS)
            assertTrue(second.get(5, SECONDS))
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }
}
