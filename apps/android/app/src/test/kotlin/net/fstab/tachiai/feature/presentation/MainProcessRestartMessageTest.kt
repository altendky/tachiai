package net.fstab.tachiai.feature.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainProcessRestartMessageTest {
    @Test fun aDrawnMessageStaysForItsMinimumBeforeTachiaiReopens() {
        val drawnAt = 1_000L
        assertFalse(restartMessageShown(now = drawnAt, drawnAt = drawnAt, readyAt = drawnAt))
        assertFalse(restartMessageShown(now = drawnAt + RESTART_MESSAGE_MIN_MS - 1, drawnAt = drawnAt, readyAt = drawnAt))
        assertTrue(restartMessageShown(now = drawnAt + RESTART_MESSAGE_MIN_MS, drawnAt = drawnAt, readyAt = drawnAt))
    }

    @Test fun theMinimumCountsFromTheFirstFrameNotFromTheOldProcessEnding() {
        // The old process ended before the message drew.
        assertFalse(restartMessageShown(now = 1_400L, drawnAt = 1_000L, readyAt = 500L))
        // It ended long after the message drew: nothing more to wait for.
        assertTrue(restartMessageShown(now = 5_000L, drawnAt = 1_000L, readyAt = 5_000L))
    }

    @Test fun aMessageThatNeverDrawsCannotHoldTheRestart() {
        val readyAt = 2_000L
        assertFalse(restartMessageShown(now = readyAt + RESTART_MESSAGE_WAIT_MS - 1, drawnAt = null, readyAt = readyAt))
        assertTrue(restartMessageShown(now = readyAt + RESTART_MESSAGE_WAIT_MS, drawnAt = null, readyAt = readyAt))
    }
}
