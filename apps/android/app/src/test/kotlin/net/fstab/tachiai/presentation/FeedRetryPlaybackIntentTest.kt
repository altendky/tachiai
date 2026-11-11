package net.fstab.tachiai.presentation

import org.junit.Assert.*
import org.junit.Test

class FeedRetryPlaybackIntentTest {
    @Test fun healthySiblingRequestRestoresRetryDespiteTimingOwnedDesiredPlayingBeingFalse() {
        val state = FeedRetryPlaybackIntent(); val retry = Any(); val sibling = Any()
        val desiredPlaying = false; val siblingPlayWhenReady = true
        val receipt = state.arm(0, retry, sibling, desiredPlaying || siblingPlayWhenReady)
        assertTrue(state.consume(receipt, retry, sibling))
        assertFalse(state.consume(receipt, retry, sibling))
    }

    @Test fun bufferingSiblingStillCarriesItsPlayRequestAndBothPausedNeverJoin() {
        for (playWhenReady in listOf(true, false)) {
            val state = FeedRetryPlaybackIntent(); val retry = Any(); val sibling = Any()
            // The caller supplies playWhenReady even when no frames are
            // advancing; this model never substitutes an isPlaying check.
            val receipt = state.arm(1, retry, sibling, resumeRequested = playWhenReady)
            assertEquals(playWhenReady, state.consume(receipt, retry, sibling))
            assertFalse(state.consume(receipt, retry, sibling))
        }
    }

    @Test fun explicitRequestedPlayMayJoinWithNoSiblingButCannotAdoptANewSibling() {
        val state = FeedRetryPlaybackIntent(); val retry = Any()
        val withoutSibling = state.arm(0, retry, null, true)
        assertTrue(state.consume(withoutSibling, retry, null))
        val changed = state.arm(0, retry, null, true)
        assertFalse(state.consume(changed, retry, Any()))
        assertFalse(state.consume(changed, retry, null))
        val paused = state.arm(0, retry, null, false)
        assertFalse(state.consume(paused, retry, null))
    }

    @Test fun siblingIdentityUsesReferenceAndAFailedIdentityCheckCannotRevive() {
        val state = FeedRetryPlaybackIntent(); val retry = Any()
        val original = mutableListOf("same"); val replacement = mutableListOf("same")
        assertEquals(original, replacement); assertNotSame(original, replacement)
        val receipt = state.arm(0, retry, original, true)
        assertFalse(state.consume(receipt, retry, replacement))
        assertFalse(state.consume(receipt, retry, original))
    }

    @Test fun newerAttemptSupersedesOldReceiptEvenWhenRetryTokenIsReused() {
        val state = FeedRetryPlaybackIntent(); val retry = Any(); val sibling = Any()
        val old = state.arm(0, retry, sibling, true)
        val current = state.arm(0, retry, sibling, true)
        assertNotEquals(old.generation, current.generation)
        assertFalse(state.consume(old, retry, sibling))
        assertTrue(state.consume(current, retry, sibling))
        assertFalse(state.consume(current, retry, sibling))
    }

    @Test fun wrongRetryTokenRefusesOnlyThatCurrentReceiptWithoutTouchingTheOtherSlot() {
        val state = FeedRetryPlaybackIntent(); val first = Any(); val second = Any(); val sibling = Any()
        val a = state.arm(0, first, sibling, true)
        val b = state.arm(1, second, sibling, true)
        assertFalse(state.consume(a, Any(), sibling))
        assertFalse(state.consume(a, first, sibling))
        assertTrue(state.consume(b, second, sibling))
    }

    @Test fun failedAttemptCancelAndNewArmNeverPermitStaleCompletion() {
        val state = FeedRetryPlaybackIntent(); val retry = Any(); val sibling = Any()
        val old = state.arm(0, retry, sibling, true)
        state.cancel(0)
        assertFalse(state.consume(old, retry, sibling))
        val current = state.arm(0, retry, sibling, true)
        assertFalse(state.consume(old, retry, sibling))
        assertTrue(state.consume(current, retry, sibling))
    }

    @Test fun cancelAllRetiresBothSlotsAndANewerAttemptCanBeArmed() {
        val state = FeedRetryPlaybackIntent(); val retry = Any(); val sibling = Any()
        val a = state.arm(0, retry, sibling, true); val b = state.arm(1, retry, sibling, true)
        state.cancelAll()
        assertFalse(state.consume(a, retry, sibling))
        assertFalse(state.consume(b, retry, sibling))
        val newer = state.arm(0, retry, sibling, true)
        assertFalse(state.consume(a, retry, sibling))
        assertTrue(state.consume(newer, retry, sibling))
    }

    @Test fun cancelingOneSlotDoesNotCancelIndependentHealthyRetryIntent() {
        val state = FeedRetryPlaybackIntent(); val aToken = Any(); val bToken = Any()
        val a = state.arm(0, aToken, bToken, true)
        val b = state.arm(1, bToken, aToken, true)
        state.cancel(0)
        assertFalse(state.consume(a, aToken, bToken))
        assertTrue(state.consume(b, bToken, aToken))
    }

    @Test fun onlyTwoSlotsAreAcceptedAndFabricatedOrForeignReceiptsCannotConsumeIntent() {
        val state = FeedRetryPlaybackIntent(); val token = Any()
        for (invalid in listOf(-1, 2, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { state.arm(invalid, token, null, true) }
            assertThrows(IllegalArgumentException::class.java) { state.cancel(invalid) }
        }
        val real = state.arm(0, token, null, true)
        val fabricated = FeedRetryPlaybackReceipt(real.index, real.generation)
        assertFalse(state.consume(fabricated, token, null))
        val foreign = FeedRetryPlaybackIntent().arm(0, token, null, true)
        assertFalse(state.consume(foreign, token, null))
        assertTrue(state.consume(real, token, null))
    }
}
