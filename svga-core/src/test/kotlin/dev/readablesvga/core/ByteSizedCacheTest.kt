package dev.readablesvga.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ByteSizedCacheTest {
    private class Value(val bytes: Long) { val disposed = AtomicInteger() }
    private fun cache(maxBytes: Long) = ByteSizedCache<String, Value>(maxBytes, { it.bytes }) { it.disposed.incrementAndGet() }

    @Test fun idleExpiryResetsOnReuseAndDoesNotExpireActiveClients() {
        var now = 0L
        val cache = ByteSizedCache<String, Value>(10, { it.bytes }, retainActive = true, clockMillis = { now }) {
            it.disposed.incrementAndGet()
        }
        val value = Value(1)
        cache.putAndAcquire("key", value).close()
        now = 59_999
        assertTrue(cache.expireIdle(60_000))
        val active = cache.acquire("key")!!
        now = 100_000
        assertFalse(cache.expireIdle(60_000))
        assertSame(value, active.value)
        active.close()
        now = 159_999
        assertTrue(cache.expireIdle(60_000))
        now = 160_000
        assertFalse(cache.expireIdle(60_000))
        assertNull(cache.acquire("key"))
        assertEquals(1, value.disposed.get())
        cache.close()
    }

    @Test fun idleBudgetEvictsOldestIdleEntryWithoutEvictingActiveEntry() {
        val cache = ByteSizedCache<String, Value>(10, { it.bytes }, retainActive = true, maxIdleBytes = 4) { it.disposed.incrementAndGet() }
        val active = cache.putAndAcquire("active", Value(4))
        val older = Value(3)
        val newer = Value(3)
        cache.putAndAcquire("older", older).close()
        cache.putAndAcquire("newer", newer).close()
        assertNull(cache.acquire("older"))
        assertEquals(1, older.disposed.get())
        cache.acquire("active")!!.use { assertSame(active.value, it.value) }
        cache.acquire("newer")!!.use { assertSame(newer, it.value) }
        active.close()
        cache.close()
    }

    @Test fun admissionRejectsWhenActiveBytesLeaveTooLittleRoomWithoutEvictingIdle() {
        val cache = ByteSizedCache<String, Value>(10, { it.bytes }, retainActive = true) { it.disposed.incrementAndGet() }
        val pinned = cache.putAndAcquire("pinned", Value(7))
        val idle = Value(3)
        cache.putAndAcquire("idle", idle).close()
        val rejected = Value(4)
        cache.putAndAcquire("rejected", rejected).use {
            assertSame(rejected, it.value)
            assertNull(cache.acquire("rejected"))
            cache.acquire("idle")!!.use { hit -> assertSame(idle, hit.value) }
        }
        assertEquals(1, rejected.disposed.get())
        pinned.close()
        cache.close()
    }

    @Test fun rejectedReplacementDoesNotRestoreTheOldIndexOnClientClose() {
        val cache = ByteSizedCache<String, Value>(4, { it.bytes }, retainActive = true) { it.disposed.incrementAndGet() }
        val old = Value(4)
        val oldClient = cache.putAndAcquire("key", old)
        val rejected = Value(5)
        cache.putAndAcquire("key", rejected).use {
            assertSame(rejected, it.value)
            assertNull(cache.acquire("key"))
            assertSame(old, oldClient.value)
        }
        val current = Value(4)
        cache.putAndAcquire("key", current).close()
        oldClient.close()
        cache.acquire("key")!!.use { assertSame(current, it.value) }
        assertEquals(1, old.disposed.get())
        assertEquals(1, rejected.disposed.get())
        cache.close()
    }

    @Test fun activeEntriesRemainDiscoverableUntilLastClientCloses() {
        val cache = ByteSizedCache<String, Value>(4, { it.bytes }, retainActive = true) { it.disposed.incrementAndGet() }
        val first = Value(4)
        val second = Value(4)
        val a = cache.putAndAcquire("first", first)
        val b = cache.acquire("first")!!
        cache.putAndAcquire("second", second).close()
        val c = cache.acquire("first")!!
        assertSame(first, c.value)
        assertEquals(1, second.disposed.get())
        a.close()
        b.close()
        cache.trimToSize(0)
        assertEquals(0, first.disposed.get())
        c.close()
        assertNull(cache.acquire("first"))
        assertEquals(1, first.disposed.get())
        cache.close()
    }

    @Test fun evictionPreservesActiveClientOwnership() {
        val cache = cache(4)
        val first = Value(4)
        val second = Value(4)
        val active = cache.putAndAcquire("first", first)
        cache.putAndAcquire("second", second).close()

        assertNull(cache.acquire("first"))
        assertSame(first, active.value)
        assertEquals(0, first.disposed.get())
        assertEquals(CacheStats(4, 4, 1), cache.stats())
        active.close()
        assertEquals(1, first.disposed.get())
        cache.close()
        assertEquals(1, second.disposed.get())
    }

    @Test fun replacingKeyDoesNotDisposeItsActiveOldValue() {
        val cache = cache(10)
        val oldValue = Value(4)
        val newValue = Value(3)
        val oldClient = cache.putAndAcquire("key", oldValue)
        cache.putAndAcquire("key", newValue).close()

        cache.acquire("key")!!.use { assertSame(newValue, it.value) }
        assertSame(oldValue, oldClient.value)
        assertEquals(CacheStats(3, 10, 1), cache.stats())
        assertEquals(0, oldValue.disposed.get())
        oldClient.close()
        assertEquals(1, oldValue.disposed.get())
        cache.close()
        assertEquals(1, newValue.disposed.get())
    }

    @Test fun cacheAccessDeterminesLeastRecentlyUsedEviction() {
        val cache = cache(6)
        val a = Value(3)
        val b = Value(3)
        val c = Value(3)
        cache.putAndAcquire("a", a).close()
        cache.putAndAcquire("b", b).close()
        cache.acquire("a")!!.close()
        cache.putAndAcquire("c", c).close()

        assertEquals(1, b.disposed.get())
        assertNull(cache.acquire("b"))
        assertEquals(0, a.disposed.get())
        cache.close()
        assertEquals(1, a.disposed.get())
        assertEquals(1, c.disposed.get())
    }

    @Test fun oversizedResourceIsOwnedByReturnedClientOnly() {
        val cache = cache(2)
        val value = Value(3)
        val client = cache.putAndAcquire("large", value)
        assertNull(cache.acquire("large"))
        assertSame(value, client.value)
        assertEquals(CacheStats(0, 2, 0), cache.stats())
        assertEquals(0, value.disposed.get())
        client.close()
        assertEquals(1, value.disposed.get())
        cache.close()
    }

    @Test fun zeroCapacityDisablesRetentionEvenForZeroSizedResources() {
        val cache = cache(0)
        val value = Value(0)
        val client = cache.putAndAcquire("value", value)
        assertNull(cache.acquire("value"))
        cache.close()
        assertSame(value, client.value)
        assertEquals(0, value.disposed.get())
        client.close()
        assertEquals(1, value.disposed.get())
    }

    @Test fun clearRemovesZeroSizedEntriesAsWell() {
        val cache = cache(1)
        val value = Value(0)
        cache.putAndAcquire("value", value).close()
        cache.clear()
        assertEquals(CacheStats(0, 1, 0), cache.stats())
        assertNull(cache.acquire("value"))
        assertEquals(1, value.disposed.get())
    }

    @Test fun clearDoesNotDisposeAClientsResource() {
        val cache = cache(4)
        val value = Value(4)
        val client = cache.putAndAcquire("value", value)
        cache.clear()
        cache.clear()
        assertNull(cache.acquire("value"))
        assertSame(value, client.value)
        assertEquals(0, value.disposed.get())
        client.close()
        assertEquals(1, value.disposed.get())
    }

    @Test fun trimDropsOwnershipWithoutChangingCapacity() {
        val cache = cache(10)
        val value = Value(6)
        val client = cache.putAndAcquire("value", value)
        cache.trimToSize(5)
        assertEquals(CacheStats(0, 10, 0), cache.stats())
        assertSame(value, client.value)
        client.close()
        cache.close()
        assertEquals(1, value.disposed.get())
    }

    @Test fun closeRejectsNewOwnersButLeavesExistingClientValid() {
        val cache = cache(4)
        val value = Value(4)
        val client = cache.putAndAcquire("value", value)
        cache.close()
        cache.close()
        assertThrows(IllegalStateException::class.java) { cache.acquire("value") }
        val rejected = Value(1)
        assertThrows(IllegalStateException::class.java) { cache.putAndAcquire("new", rejected) }
        assertEquals(0, rejected.disposed.get())
        assertSame(value, client.value)
        client.close()
        assertEquals(1, value.disposed.get())
        assertEquals(CacheStats(0, 4, 0), cache.stats())
    }

    @Test fun negativeSizeRejectsValueWithoutTakingOwnership() {
        val cache = cache(1)
        val value = Value(-1)
        assertThrows(IllegalArgumentException::class.java) { cache.putAndAcquire("value", value) }
        assertEquals(0, value.disposed.get())
        assertEquals(CacheStats(0, 1, 0), cache.stats())
        cache.close()
    }

    @Test fun longByteAccountingDoesNotOverflowDuringInsertion() {
        val cache = cache(Long.MAX_VALUE)
        val a = Value(Long.MAX_VALUE - 1)
        val b = Value(Long.MAX_VALUE - 1)
        cache.putAndAcquire("a", a).close()
        cache.putAndAcquire("b", b).close()
        assertEquals(CacheStats(Long.MAX_VALUE - 1, Long.MAX_VALUE, 1), cache.stats())
        assertEquals(1, a.disposed.get())
        cache.close()
        assertEquals(1, b.disposed.get())
    }

    @Test fun sameInstanceAtSameKeyAcquiresExistingOwnership() {
        val cache = cache(1)
        val value = Value(1)
        val first = cache.putAndAcquire("value", value)
        val second = cache.putAndAcquire("value", value)
        first.close()
        cache.close()
        assertSame(value, second.value)
        second.close()
        assertEquals(1, value.disposed.get())
    }

    @Test(timeout = 5000) fun clearRacingAnAcquiredClientCannotDisposeItsValue() {
        val cache = cache(1)
        val value = Value(1)
        cache.putAndAcquire("value", value).close()
        val acquired = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val task = executor.submit {
                cache.acquire("value")!!.use {
                    acquired.countDown()
                    assertTrue(proceed.await(2, TimeUnit.SECONDS))
                    assertSame(value, it.value)
                    assertEquals(0, value.disposed.get())
                }
            }
            assertTrue(acquired.await(2, TimeUnit.SECONDS))
            cache.clear()
            assertEquals(0, value.disposed.get())
            proceed.countDown()
            task.get(2, TimeUnit.SECONDS)
            assertEquals(1, value.disposed.get())
        } finally {
            proceed.countDown()
            executor.shutdownNow()
            cache.close()
        }
    }

    @Test(timeout = 5000) fun disposerCanQueryCacheFromAnotherThread() {
        val executor = Executors.newSingleThreadExecutor()
        lateinit var cache: ByteSizedCache<String, Value>
        try {
            cache = ByteSizedCache(1, { it.bytes }) {
                executor.submit { cache.stats() }.get(1, TimeUnit.SECONDS)
                it.disposed.incrementAndGet()
            }
            val value = Value(1)
            cache.putAndAcquire("value", value).close()
            cache.clear()
            assertEquals(1, value.disposed.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun brokenDisposerCannotPreventOtherRetiredResourcesFromClosing() {
        val a = Value(1)
        val b = Value(1)
        val cache = ByteSizedCache<String, Value>(2, { it.bytes }) {
            it.disposed.incrementAndGet()
            if (it === a) error("cleanup failed")
        }
        cache.putAndAcquire("a", a).close()
        cache.putAndAcquire("b", b).close()
        assertThrows(IllegalStateException::class.java) { cache.clear() }
        assertEquals(1, a.disposed.get())
        assertEquals(1, b.disposed.get())
        assertEquals(CacheStats(0, 2, 0), cache.stats())
        cache.close()
    }
}
