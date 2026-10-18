package net.fstab.tachiai.platform.media

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import net.fstab.tachiai.platform.network.ProviderTransportEvidence
import net.fstab.tachiai.platform.network.ProviderTransportException
import net.fstab.tachiai.platform.network.ProviderTransportFailure
import net.fstab.tachiai.platform.network.ProviderTransportPhase
import org.junit.Assert.*
import org.junit.Test

class NativeFailureDiagnosticsTest {
    @Test fun `media request projection classifies transport without retaining private exception metadata`() {
        val secret = "https://private.example/path?token=synthetic-secret"
        val failures = listOf(
            UnknownHostException(secret) to NativeMediaRequestFailure.DNS,
            ConnectException(secret) to NativeMediaRequestFailure.CONNECT,
            SocketTimeoutException(secret) to NativeMediaRequestFailure.TIMEOUT,
            SSLHandshakeException(secret) to NativeMediaRequestFailure.TLS,
            java.io.InterruptedIOException(secret) to NativeMediaRequestFailure.TRANSPORT_CANCELLED,
            IOException(secret) to NativeMediaRequestFailure.IO,
            IllegalStateException(secret) to NativeMediaRequestFailure.ILLEGAL_STATE,
            IllegalArgumentException(secret) to NativeMediaRequestFailure.ILLEGAL_ARGUMENT,
            SecurityException(secret) to NativeMediaRequestFailure.SECURITY,
            java.util.concurrent.CancellationException(secret) to NativeMediaRequestFailure.CANCELLED,
            NativeMediaRequestException(NativeMediaRequestFailure.REQUEST_DEADLINE) to NativeMediaRequestFailure.REQUEST_DEADLINE,
            Exception(secret) to NativeMediaRequestFailure.OTHER,
        )
        for ((original, expected) in failures) {
            original.initCause(IOException(secret))
            original.addSuppressed(IOException(secret))
            val projected = nativeMediaRequestException(original)
            assertEquals(expected, projected.failure)
            assertEquals("Native media request failed", projected.message)
            assertNull(projected.cause)
            assertTrue(projected.suppressed.isEmpty())
            assertFalse(projected.toString().contains(secret))
            assertFalse(projected.stackTraceToString().contains(secret))
        }
    }

    @Test fun `safe transport carrier categories and local cancellation take precedence over generic IO`() {
        val expected = mapOf(
            ProviderTransportFailure.DNS to NativeMediaRequestFailure.DNS,
            ProviderTransportFailure.CONNECT to NativeMediaRequestFailure.CONNECT,
            ProviderTransportFailure.TIMEOUT to NativeMediaRequestFailure.TIMEOUT,
            ProviderTransportFailure.TLS to NativeMediaRequestFailure.TLS,
            ProviderTransportFailure.CANCELLED to NativeMediaRequestFailure.TRANSPORT_CANCELLED,
            ProviderTransportFailure.UNKNOWN to NativeMediaRequestFailure.TRANSPORT_UNKNOWN,
        )
        for ((failure, category) in expected) {
            for (cancelled in listOf(false, true)) {
                val original = ProviderTransportException(ProviderTransportEvidence(failure,
                    ProviderTransportPhase.RESPONSE, cancelled))
                val projected = nativeMediaRequestException(original)
                assertEquals(if (cancelled) NativeMediaRequestFailure.TRANSPORT_CANCELLED else category, projected.failure)
                assertNull(projected.cause)
            }
        }
    }

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
