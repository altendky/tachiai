package net.fstab.tachiai.provider.abema

import org.junit.Assert.*
import org.junit.Test
import net.fstab.tachiai.platform.media.NativeTimingSnapshot

class AbemaSlotLinearAdmissionTest {
    @Test fun linearRequiresPausedReadyLiveDefaultBeforePresentation() {
        val live = NativeTimingSnapshot(1000, 60000, 2000, null, null, 55000,
            true, true, true, true, false, false, false, 3, 1f)
        val linear = AbemaSlotLinearAdmission(allowed = true)
        assertTrue(linear.select(true))
        assertTrue(linear.initialLiveStartAllowed(live))
        assertTrue(linear.initialLiveStartAllowed(live.copy(positionMs = 55000)))
        for (snapshot in listOf(null, live.copy(live = false, dynamic = false), live.copy(canSeekDefault = false),
            live.copy(defaultPositionMs = null), live.copy(durationMs = null), live.copy(defaultPositionMs = 60001),
            live.copy(playingAd = true), live.copy(playWhenReady = true), live.copy(state = 2)))
            assertFalse(linear.initialLiveStartAllowed(snapshot))
        val ended = AbemaSlotLinearAdmission(allowed = true)
        assertTrue(ended.select(false))
        assertTrue(ended.initialLiveStartAllowed(live.copy(live = false, dynamic = false)))
        assertFalse(linear.select(false))
        assertFalse(linear.initialLiveStartAllowed(live))
    }

    @Test fun defaultChecksPremiumEvenBeforeSelectionAndRefusalCannotRecover() {
        val admission = AbemaSlotLinearAdmission()
        var reads = 0
        var candidate = true
        fun current() = admission.current { reads++; candidate }
        assertFalse(admission.linearSelected)
        assertTrue(current()); assertEquals(1, reads)
        candidate = false
        assertFalse(current()); assertEquals(2, reads)
        candidate = true
        assertFalse(current()); assertFalse(admission.select(false)); assertEquals(2, reads)
    }

    @Test fun linearRequiresExplicitOptInAndDeniedSelectionIsSticky() {
        val admission = AbemaSlotLinearAdmission()
        assertFalse(admission.select(true)); assertFalse(admission.linearSelected)
        assertFalse(admission.select(false)); assertFalse(admission.select(true))
        assertFalse(admission.current { error("Refused selection must not read a candidate") })
    }

    @Test fun optInDefersCandidateUntilEndedSelectionAndNeverReadsItForLinear() {
        val admission = AbemaSlotLinearAdmission(allowed = true)
        var reads = 0
        fun current() = admission.current { reads++; error("Unused private candidate") }
        assertTrue(current()); assertFalse(admission.linearSelected)
        assertTrue(admission.select(true)); assertTrue(admission.linearSelected)
        assertTrue(current()); assertTrue(admission.select(true)); assertTrue(current())
        assertEquals(0, reads)
    }

    @Test fun expiredUnusedPremiumReceiptCannotTruncateLinearButDoesRefuseEnded() {
        val scope = AbemaSlotAuthorityScope(Any(), "instance", "owner", "source", "channel", "slot", 1, 5000)
        val receipt = checkNotNull(AbemaSlotAuthorityReceipt.publish(scope, 2000, 1000, 100, 1000, 1200, 200))
        var wall = 1300L
        val linear = AbemaSlotLinearAdmission(allowed = true)
        val ended = AbemaSlotLinearAdmission(allowed = true)
        assertTrue(linear.select(true)); assertTrue(ended.select(false))
        assertTrue(ended.current { receipt.current(scope, wall, 300) })
        wall = 2100
        assertTrue(linear.current { error("Expired unused candidate must not be inspected") })
        assertFalse(ended.current { receipt.current(scope, wall, 300) })
        wall = 1300
        assertFalse(ended.current { receipt.current(scope, wall, 300) })
        assertFalse(ended.select(true))
    }

    @Test fun nonLinearRequiresCandidateForDefaultAndOptInAndFailureRemainsClosed() {
        for (allowed in listOf(false, true)) for (throws in listOf(false, true)) {
            val admission = AbemaSlotLinearAdmission(allowed)
            assertTrue(admission.select(false)); assertTrue(admission.select(false))
            assertTrue(admission.current { true })
            assertFalse(admission.current { if (throws) error("Private candidate failure") else false })
            assertFalse(admission.current { true }); assertFalse(admission.select(false))
            assertFalse(admission.linearSelected)
        }
    }

    @Test fun selectionCannotFlipAndReentrantRefusalCannotBeOverwrittenByCandidateSuccess() {
        for (initial in listOf(false, true)) {
            val admission = AbemaSlotLinearAdmission(allowed = true)
            assertTrue(admission.select(initial)); assertTrue(admission.select(initial))
            assertFalse(admission.select(!initial)); assertFalse(admission.select(initial))
            assertFalse(admission.current { true }); assertFalse(admission.linearSelected)
        }
        val admission = AbemaSlotLinearAdmission(allowed = true)
        assertTrue(admission.select(false))
        assertFalse(admission.current { assertFalse(admission.select(true)); true })
        assertFalse(admission.current { true })
    }

    @Test fun stringRepresentationDoesNotExportSelectionOrCandidateState() {
        for (allowed in listOf(false, true)) {
            val admission = AbemaSlotLinearAdmission(allowed)
            assertEquals("AbemaSlotLinearAdmission(redacted)", admission.toString())
            admission.select(allowed)
            assertEquals("AbemaSlotLinearAdmission(redacted)", admission.toString())
            admission.current { false }
            assertEquals("AbemaSlotLinearAdmission(redacted)", admission.toString())
        }
    }
}
