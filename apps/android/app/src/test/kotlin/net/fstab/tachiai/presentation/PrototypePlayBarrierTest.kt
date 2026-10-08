package net.fstab.tachiai.presentation

import org.junit.Assert.*
import org.junit.Test

class PrototypePlayBarrierTest {
    @Test fun `both confirmations needed and duplicate callback cannot complete`() {
        val outcomes = mutableListOf<Boolean>()
        val barrier = PrototypePlayBarrier(2, outcomes::add)
        barrier.result(0, true); barrier.result(0, true)
        assertTrue(outcomes.isEmpty())
        barrier.result(1, true); barrier.result(1, true)
        assertEquals(listOf(true), outcomes)
    }
    @Test fun `either failure is terminal`() {
        for (failed in 0..1) {
            val outcomes = mutableListOf<Boolean>()
            val barrier = PrototypePlayBarrier(2, outcomes::add)
            barrier.result(failed, false); barrier.result(1 - failed, true)
            assertEquals(listOf(false), outcomes)
        }
    }
    @Test fun `cancel discards late provider confirmations`() {
        val outcomes = mutableListOf<Boolean>()
        val barrier = PrototypePlayBarrier(2, outcomes::add)
        barrier.result(0, true); barrier.cancel(); barrier.result(1, true)
        assertTrue(outcomes.isEmpty())
    }
    @Test fun `timeout fails even after first provider already confirmed`() {
        val outcomes = mutableListOf<Boolean>()
        val barrier = PrototypePlayBarrier(2, outcomes::add)
        barrier.result(0, true); barrier.fail(); barrier.result(1, true); barrier.fail()
        assertEquals(listOf(false), outcomes)
    }
}
