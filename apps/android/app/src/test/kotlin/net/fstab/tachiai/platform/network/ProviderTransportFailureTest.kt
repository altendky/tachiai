package net.fstab.tachiai.platform.network

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import org.junit.Assert.*
import org.junit.Test

class ProviderTransportFailureTest {
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
