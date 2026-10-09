package net.fstab.tachiai.platform.media

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class NativeFailureDiagnosticsTest {
    @Test fun `observer receives original stage and failure without changing the thrown error`() {
        val error = IOException("synthetic secret-bearing message")
        val events = mutableListOf<Pair<NativeFailureStage, Throwable>>()
        val thrown = assertThrows(IOException::class.java) {
            observeNativeFailure(NativeFailureStage.MEDIA_DISCONNECT, { stage, value -> events += stage to value }) {
                throw error
            }
        }
        assertSame(error, thrown)
        assertEquals(listOf(NativeFailureStage.MEDIA_DISCONNECT to error), events)
    }

    @Test fun `broken observer cannot replace original error and successful actions produce no failure`() {
        val error = IllegalStateException("fixture")
        assertSame(error, assertThrows(IllegalStateException::class.java) {
            observeNativeFailure(NativeFailureStage.PLAYER_RELEASE, { _, _ -> throw IOException("sink failed") }) {
                throw error
            }
        })
        assertEquals(42, observeNativeFailure(NativeFailureStage.PLAYER_RELEASE, { _, _ -> fail("unexpected report") }) { 42 })
    }

    @Test fun `release Error retains its stage and identity while later cleanup still runs`() {
        val error = AssertionError("synthetic release failure")
        val events = mutableListOf<Pair<NativeFailureStage, Throwable>>()
        var qualityClosed = false
        assertSame(error, assertThrows(AssertionError::class.java) {
            closeNativeResources(
                { observeNativeFailure(NativeFailureStage.PLAYER_RELEASE, { stage, value -> events += stage to value }) { throw error } },
                { qualityClosed = true },
            )
        })
        assertTrue(qualityClosed)
        assertEquals(listOf(NativeFailureStage.PLAYER_RELEASE to error), events)
    }
}
