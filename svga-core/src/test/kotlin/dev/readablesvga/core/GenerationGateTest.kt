package dev.readablesvga.core

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GenerationGateTest {
    @Test fun beginSupersedesPreviousRequest() {
        val gate = GenerationGate()
        assertFalse(gate.isCurrent(0))
        val old = gate.begin()
        assertTrue(gate.isCurrent(old))
        val current = gate.begin()
        assertFalse(gate.isCurrent(old))
        assertTrue(gate.isCurrent(current))
    }

    @Test fun invalidationRejectsEvenTheLatestRequest() {
        val gate = GenerationGate()
        val first = gate.begin()
        gate.invalidate()
        gate.invalidate()
        assertFalse(gate.isCurrent(first))
        val next = gate.begin()
        assertTrue(next > first)
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(next))
    }

    @Test(timeout = 5000) fun concurrentBeginsUseUniqueGenerations() {
        val gate = GenerationGate()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val generations = (1..100).map { executor.submit<Long> { gate.begin() } }
                .map { it.get(2, TimeUnit.SECONDS) }
            assertEquals(100, generations.toSet().size)
            assertTrue(gate.isCurrent(generations.max()))
            assertEquals(1, generations.count { gate.isCurrent(it) })
        } finally {
            executor.shutdownNow()
        }
    }
}
