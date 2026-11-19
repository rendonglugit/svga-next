package dev.readablesvga.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ResourceLeaseTest {
    @Test fun forkKeepsResourceAliveUntilFinalClose() {
        val disposed = AtomicInteger()
        val value = Any()
        val owner = ResourceLease.own(value) { disposed.incrementAndGet() }
        val client = owner.fork()

        owner.close()
        assertSame(value, client.value)
        assertEquals(0, disposed.get())
        client.close()
        client.close()
        assertEquals(1, disposed.get())
        assertThrows(IllegalStateException::class.java) { client.value }
        assertThrows(IllegalStateException::class.java) { client.fork() }
    }

    @Test(timeout = 5000) fun concurrentClosesDisposeOnlyOnce() {
        val disposed = AtomicInteger()
        val lease = ResourceLease.own(Any()) { disposed.incrementAndGet() }
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val tasks = (1..100).map {
                executor.submit { start.await(); lease.close() }
            }
            start.countDown()
            tasks.forEach { it.get(2, TimeUnit.SECONDS) }
            assertEquals(1, disposed.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test(timeout = 5000) fun forkRacingCloseEitherAcquiresOrRejectsWithoutResurrection() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val disposed = AtomicInteger()
                val value = Any()
                val lease = ResourceLease.own(value) { disposed.incrementAndGet() }
                val start = CountDownLatch(1)
                val fork = executor.submit<ResourceLease<Any>?> {
                    start.await()
                    try { lease.fork() } catch (_: IllegalStateException) { null }
                }
                val close = executor.submit { start.await(); lease.close() }
                start.countDown()
                val acquired = fork.get(2, TimeUnit.SECONDS)
                close.get(2, TimeUnit.SECONDS)
                if (acquired == null) {
                    assertEquals(1, disposed.get())
                } else {
                    assertEquals(0, disposed.get())
                    assertSame(value, acquired.value)
                    acquired.close()
                }
                assertEquals(1, disposed.get())
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test(timeout = 5000) fun disposalDoesNotHoldTheLeaseLock() {
        val executor = Executors.newSingleThreadExecutor()
        lateinit var lease: ResourceLease<Any>
        try {
            lease = ResourceLease.own(Any()) {
                executor.submit { lease.close() }.get(1, TimeUnit.SECONDS)
            }
            lease.close()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun throwingDisposerStillCannotRunTwice() {
        val calls = AtomicInteger()
        val lease = ResourceLease.own(Any()) { calls.incrementAndGet(); error("cleanup failed") }
        assertThrows(IllegalStateException::class.java) { lease.close() }
        lease.close()
        assertEquals(1, calls.get())
        assertThrows(IllegalStateException::class.java) { lease.value }
    }
}
