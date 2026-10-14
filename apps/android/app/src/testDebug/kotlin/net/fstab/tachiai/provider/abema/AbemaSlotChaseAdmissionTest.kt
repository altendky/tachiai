package net.fstab.tachiai.provider.abema

import org.junit.Assert.*
import org.junit.Test
import net.fstab.tachiai.platform.media.NativeTimingSnapshot

class AbemaSlotChaseAdmissionTest {
    @Test fun unusedExpiredOrMalformedChaseCandidateNeverTruncatesOrdinary() {
        var reads = 0
        val admission = AbemaSlotChaseAdmission { reads++; error("Unused private candidate") }
        assertTrue(admission.current()); assertEquals(0, reads)
        assertTrue(admission.select(false)); repeat(3) { assertTrue(admission.current()) }
        assertEquals(0, reads)
    }
    @Test fun selectedChaseRequiresCurrentReceiptAndCannotRecoverAfterExpiryOrOwnerLoss() {
        var candidate = true
        val admission = AbemaSlotChaseAdmission { candidate }
        assertTrue(admission.select(true)); assertTrue(admission.current())
        candidate = false; assertFalse(admission.current())
        candidate = true; assertFalse(admission.current()); assertFalse(admission.select(true))
    }
    @Test fun sourceCannotSwitchModesAndMissingAuthorityCannotSelectChase() {
        for (initial in listOf(true, false)) {
            val admission = AbemaSlotChaseAdmission { true }
            assertTrue(admission.select(initial)); assertTrue(admission.select(initial))
            assertFalse(admission.select(!initial)); assertFalse(admission.current())
        }
        val absent = AbemaSlotChaseAdmission { false }
        assertFalse(absent.select(true)); assertFalse(absent.select(false))
    }
    @Test fun actualCandidateReceiptBoundsOnlyChaseWithOriginalScopeClocksAndDeadline() {
        val scope = AbemaSlotAuthorityScope(Any(), "instance", "owner", "source", "channel", "slot", 1, 5000)
        val receipt = checkNotNull(AbemaSlotAuthorityReceipt.publish(scope, 2000, 1000, 100, 1000, 1200, 200))
        var wall = 1300L; var elapsed = 300L
        val ordinary = AbemaSlotChaseAdmission { receipt.current(scope, wall, elapsed) }
        assertTrue(ordinary.select(false)); wall = 2100; assertTrue(ordinary.current())
        wall = 1300
        val chase = AbemaSlotChaseAdmission { receipt.current(scope, wall, elapsed) }
        assertTrue(chase.select(true)); elapsed = 5000
        assertFalse(chase.current()); elapsed = 300; assertFalse(chase.current())
    }
    @Test fun selectedChaseCannotStartAtBeginningWhenTheSdkCannotSeekItsLiveDefault() {
        val live = NativeTimingSnapshot(1000, 60000, 2000, null, null, 55000,
            true, true, true, true, false, false, false, 3, 1f)
        val chase = AbemaSlotChaseAdmission { true }
        assertTrue(chase.select(true)); assertTrue(chase.chaseSelected)
        assertTrue(chase.initialLiveStartAllowed(live))
        assertTrue(chase.initialLiveStartAllowed(live.copy(positionMs = 55000)))
        for (snapshot in listOf(null, live.copy(live = false), live.copy(canSeekDefault = false),
            live.copy(defaultPositionMs = null), live.copy(durationMs = null), live.copy(defaultPositionMs = 60001),
            live.copy(playingAd = true), live.copy(playWhenReady = true), live.copy(state = 2)))
            assertFalse(chase.initialLiveStartAllowed(snapshot))
        val ordinary = AbemaSlotChaseAdmission { false }
        assertTrue(ordinary.select(false)); assertFalse(ordinary.chaseSelected)
        assertTrue(ordinary.initialLiveStartAllowed(live.copy(live = false, dynamic = false)))
    }
}
