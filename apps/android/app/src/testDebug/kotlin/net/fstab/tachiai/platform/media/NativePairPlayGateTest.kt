package net.fstab.tachiai.platform.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePairPlayGateTest {
    @Test fun completionIsOneShot() {
        val gate = NativePairPlayGate()
        val complete = gate.begin()
        assertTrue(complete())
        assertFalse(complete())
    }

    @Test fun newerPauseSeekLossOrStopInvalidatesPendingCompletion() {
        val gate = NativePairPlayGate()
        repeat(4) {
            val complete = gate.begin()
            gate.invalidate()
            assertFalse(complete())
        }
    }

    @Test fun onlyMostRecentPlayCanCompleteAndInvalidateDoesNotResurrectOlderPlay() {
        val gate = NativePairPlayGate()
        val first = gate.begin()
        val second = gate.begin()
        assertFalse(first())
        assertTrue(second())
        gate.invalidate()
        assertFalse(first())
        assertFalse(second())
        assertTrue(gate.begin()())
    }
}
