package net.fstab.tachiai.platform.network

import java.io.IOException
import java.io.InterruptedIOException
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.ProtocolException
import java.nio.channels.ClosedChannelException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import org.junit.Assert.*
import org.junit.Test

class ProviderTransportFailureTest {
    @Test fun diagnosticExceptionKindsDoNotPromoteOpaqueIoOrParseSocketMessages() {
        val values = listOf(
            EOFException("https://private.example/token") to ProviderTransportExceptionKind.EOF,
            SocketException("Connection reset PRIVATE") to ProviderTransportExceptionKind.SOCKET,
            SocketException("Socket closed PRIVATE") to ProviderTransportExceptionKind.SOCKET,
            ClosedChannelException() to ProviderTransportExceptionKind.CHANNEL_CLOSED,
            ProtocolException("PRIVATE") to ProviderTransportExceptionKind.PROTOCOL,
            IOException("PRIVATE") to ProviderTransportExceptionKind.IO,
        )
        for ((error, kind) in values) {
            error.initCause(UnknownHostException("private nested cause must not promote retry"))
            val evidence = providerTransportEvidence(error)
            assertEquals(kind, evidence.exceptionKind)
            assertEquals(ProviderTransportFailure.UNKNOWN, evidence.failure)
            assertFalse(evidence.retryableAvailability)
            assertEquals(ProviderTransportOperation.UNKNOWN, evidence.operation)
            assertFalse(evidence.phaseAvailable); assertFalse(evidence.proxyPresent)
            val projected = ProviderTransportException(evidence)
            assertNull(projected.cause); assertTrue(projected.suppressed.isEmpty())
            assertFalse(evidence.toString().contains("PRIVATE")); assertFalse(evidence.toString().contains("https:"))
        }
    }

    @Test fun diagnosticDetailsNeverChangeExistingRetryAndCancellationDecisions() {
        for (failure in ProviderTransportFailure.entries) for (phase in ProviderTransportPhase.entries)
            for (cancelled in listOf(false, true)) {
                val plain = ProviderTransportEvidence(failure, phase, cancelled)
                for (kind in ProviderTransportExceptionKind.entries) {
                    val enriched = plain.copy(exceptionKind = kind, operation = ProviderTransportOperation.RESPONSE_BODY,
                        phaseAvailable = true, proxyPresent = true)
                    assertEquals(plain.retryableAvailability, enriched.retryableAvailability)
                    assertEquals(plain.failure, enriched.failure); assertEquals(plain.localCancellationRequested, enriched.localCancellationRequested)
                }
            }
    }

    @Test fun explicitAvailabilityIsDistinctFromSecurityCancellationAndOpaqueIo() {
        for (error in listOf(UnknownHostException("PRIVATE"), ConnectException("PRIVATE"))) {
            assertTrue(providerTransportEvidence(error).retryableAvailability)
        }
        for (error in listOf(SSLHandshakeException("PRIVATE"), SSLPeerUnverifiedException("PRIVATE"),
            SocketTimeoutException("PRIVATE"), SocketException("PRIVATE"), InterruptedIOException("PRIVATE"),
            IOException("PRIVATE"), IllegalStateException("PRIVATE"))) {
            assertFalse(providerTransportEvidence(error).retryableAvailability)
        }
        assertEquals(ProviderTransportFailure.TLS, providerTransportEvidence(SSLPeerUnverifiedException("PRIVATE")).failure)
        assertEquals(ProviderTransportFailure.CANCELLED, providerTransportEvidence(InterruptedIOException()).failure)
    }

    @Test fun onlyProvenPreTlsTimeoutCanAdvanceAndLocalCancellationAlwaysStops() {
        for (phase in ProviderTransportPhase.entries) {
            val evidence = ProviderTransportEvidence(ProviderTransportFailure.TIMEOUT, phase)
            assertEquals(phase == ProviderTransportPhase.DNS || phase == ProviderTransportPhase.CONNECT,
                evidence.retryableAvailability)
        }
        for (failure in ProviderTransportFailure.entries) {
            assertFalse(ProviderTransportEvidence(failure, ProviderTransportPhase.CONNECT, true).retryableAvailability)
        }
    }

    @Test fun safeEvidenceSurvivesWrappingWithoutReadingOrRetainingPrivateExceptions() {
        var inspected = false
        val hostile = object : IOException() {
            override val message: String get() { inspected = true; error("Must not read private message") }
            override fun toString(): String { inspected = true; error("Must not stringify private exception") }
        }
        val unknown = providerTransportEvidence(hostile)
        assertEquals(ProviderTransportFailure.UNKNOWN, unknown.failure); assertFalse(inspected)
        val original = ProviderTransportEvidence(ProviderTransportFailure.TIMEOUT, ProviderTransportPhase.CONNECT)
        val safe = ProviderTransportException(original)
        assertSame(original, providerTransportEvidence(safe)); assertNull(safe.cause)
        assertFalse(safe.message.orEmpty().contains("PRIVATE")); assertTrue(safe.suppressed.isEmpty())
    }
}
