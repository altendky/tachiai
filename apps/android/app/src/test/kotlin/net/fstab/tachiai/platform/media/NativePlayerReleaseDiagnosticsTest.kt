package net.fstab.tachiai.platform.media

import org.junit.Assert.*
import org.junit.Test

class NativePlayerReleaseDiagnosticsTest {
    @Test fun timeoutBeforeActualReleaseBeginsCannotBecomeReleaseTimeoutEvidence() {
        var now = 100L
        val events = mutableListOf<NativePlayerReleaseTimeoutException>()
        val diagnostic = NativePlayerReleaseDiagnostics({ now }, events::add)
        diagnostic.playerError(true, 1003)
        diagnostic.begin(); now = 124; diagnostic.finish()
        assertTrue(events.isEmpty())
        // A later notification actually associated with the begun release
        // still reports the measured call once.
        diagnostic.playerError(true, 1003)
        assertEquals(24L, events.single().elapsedMs)
    }

    @Test fun timeoutDuringVoidReleaseIsReportedOnceAfterItsMeasuredReturn() {
        var now = 100L
        val events = mutableListOf<NativePlayerReleaseTimeoutException>()
        val diagnostic = NativePlayerReleaseDiagnostics({ now }, events::add)
        diagnostic.begin()
        diagnostic.playerError(true, 1003); diagnostic.playerError(true, 1003)
        assertTrue(events.isEmpty())
        now = 124
        diagnostic.finish()
        diagnostic.playerError(true, 1003)
        val event = events.single()
        assertEquals(1003, event.errorCode); assertEquals(24L, event.elapsedMs)
        assertNull(event.cause); assertTrue(event.suppressed.isEmpty())
    }

    @Test fun delayedTimeoutRetainsFinishedCallDurationWhileOtherErrorsAndSuccessStaySilent() {
        var now = 100L
        val events = mutableListOf<NativePlayerReleaseTimeoutException>()
        val diagnostic = NativePlayerReleaseDiagnostics({ now }, events::add)
        diagnostic.begin(); now = 107; diagnostic.finish()
        diagnostic.playerError(false, 1003); diagnostic.playerError(true, 1002)
        assertTrue(events.isEmpty())
        now = 1000
        diagnostic.playerError(true, 1003)
        assertEquals(7L, events.single().elapsedMs)
    }

    @Test fun durationIsBoundedAndBrokenClockOrObserverCannotChangeReleaseFlow() {
        for (end in listOf(-1L, 99L, 100L, Long.MAX_VALUE)) {
            var now = 100L
            val events = mutableListOf<NativePlayerReleaseTimeoutException>()
            val diagnostic = NativePlayerReleaseDiagnostics({ now }, events::add)
            diagnostic.begin(); diagnostic.playerError(true, 1003); now = end; diagnostic.finish()
            assertEquals(when (end) {
                -1L, 99L -> -1L
                100L -> 0L
                else -> NATIVE_PLAYER_RELEASE_DURATION_LIMIT_MS
            }, events.single().elapsedMs)
        }
        var reports = 0
        var duration: Long? = null
        val broken = NativePlayerReleaseDiagnostics({ error("Synthetic clock failure") }) {
            reports++; duration = it.elapsedMs; throw AssertionError("Synthetic observer failure")
        }
        broken.begin(); broken.playerError(true, 1003); broken.finish(); broken.playerError(true, 1003)
        assertEquals(1, reports); assertEquals(-1L, duration)
    }
}
