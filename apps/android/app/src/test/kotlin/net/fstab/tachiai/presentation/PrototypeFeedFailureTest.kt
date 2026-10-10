package net.fstab.tachiai.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrototypeFeedFailureTest {
    @Test fun `catalog access failures distinguish discovery from playback authorization`() {
        val catalog = PrototypeFeedFailure(PrototypeFailureReason.CATALOG_CONNECTION_REQUIRED).message
        assertTrue(catalog.contains("catalog account"))
        assertFalse(catalog.contains("saved Twitch login"))
        assertTrue(PrototypeFeedFailure(PrototypeFailureReason.CATALOG_RATE_LIMITED).message.contains("Try again later"))
        assertFalse(PrototypeFeedFailure(PrototypeFailureReason.LOGIN_EXPIRED).message.contains("catalog account"))
    }

    @Test fun `initial HTTP failure survives subsequent player and cleanup events`() {
        val latch = PrototypeFeedFailureLatch()
        assertNull(latch.failure)
        val initial = PrototypeFeedFailure(PrototypeFailureReason.MEDIA_NOT_FOUND, httpStatus = 404)
        latch.remember(initial)
        latch.remember(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED))
        latch.remember(PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = 2000))
        latch.remember(PrototypeFeedFailure(PrototypeFailureReason.STOPPED))
        latch.remember(PrototypeFeedFailure(PrototypeFailureReason.CLEANUP_FAILED))
        assertEquals(initial, latch.failure)
    }

    @Test fun `not found does not claim an offline channel or rejected login`() {
        val message = PrototypeFeedFailure(PrototypeFailureReason.MEDIA_NOT_FOUND, httpStatus = 404).message
        assertTrue(message.contains("may be offline"))
        assertTrue(message.contains("HTTP 404"))
        assertFalse(message.contains("Reconnect"))
    }

    @Test fun `failure diagnostics accept only bounded numeric values`() {
        for (status in listOf(-1, 0, 99, 600, Int.MAX_VALUE)) {
            try {
                PrototypeFeedFailure(PrototypeFailureReason.HTTP_REJECTED, httpStatus = status)
                throw AssertionError("Unbounded status accepted")
            } catch (_: IllegalArgumentException) { }
        }
        for (code in listOf(-1, 0, 999, 10000, Int.MAX_VALUE)) {
            try {
                PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = code)
                throw AssertionError("Unbounded player code accepted")
            } catch (_: IllegalArgumentException) { }
        }
        assertTrue(PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = 2004)
            .message.contains("Player error 2004"))
    }

    @Test fun `all reasons supply fixed source independent text`() {
        for (reason in PrototypeFailureReason.entries) {
            val message = PrototypeFeedFailure(reason).message
            assertTrue(message.isNotBlank())
            assertFalse(message.contains("https://"))
            assertFalse(message.contains("token="))
        }
    }
}
