package net.fstab.tachiai.presentation

import org.junit.Assert.*
import org.junit.Test

class FeedRecoveryTest {
    @Test fun `live-window failure explains recovery while network errors offer retry`() {
        val behind = PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = 1002)
        assertEquals("Playback fell behind live.", behind.viewerMessage)
        assertEquals(FeedRecoveryAction.GO_LIVE, behind.recoveryAction())
        assertEquals(FeedRecoveryAction.RETRY, PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED).recoveryAction())
    }

    @Test fun `authorization license policy and unsafe cleanup never offer playback retry`() {
        for (reason in listOf(PrototypeFailureReason.LOGIN_REJECTED, PrototypeFailureReason.ABEMA_ACCOUNT_UNAVAILABLE,
            PrototypeFailureReason.LICENSE_FAILED, PrototypeFailureReason.MEDIA_BLOCKED,
            PrototypeFailureReason.MEDIA_APPROVAL_REQUIRED, PrototypeFailureReason.CLEANUP_FAILED,
            PrototypeFailureReason.ROUTE_FAILED)) assertNull(PrototypeFeedFailure(reason).recoveryAction())
        for (code in listOf(2004, 2006, 2007, 3001, 4001, 6001))
            assertNull(PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = code).recoveryAction())
        for (status in listOf(400, 401, 403))
            assertNull(PrototypeFeedFailure(PrototypeFailureReason.HTTP_REJECTED, httpStatus = status).recoveryAction())
        assertEquals(FeedRecoveryAction.RETRY,
            PrototypeFeedFailure(PrototypeFailureReason.HTTP_REJECTED, httpStatus = 503).recoveryAction())
    }

    @Test fun `duplicate taps and late old events cannot replace a retry in flight`() {
        val state = FeedRetryState()
        val old = state.newAttempt()
        state.rememberReplay(123_400)
        state.invalidate()
        assertFalse(state.current(old))
        assertTrue(state.request())
        assertFalse(state.request())
        val replacement = state.newAttempt()
        assertFalse(state.current(old))
        assertTrue(state.current(replacement))
        assertFalse(state.request())
        assertEquals(123_400L, state.replayPositionMs)
        state.ready()
        state.invalidate()
        assertTrue(state.request())
    }
}
