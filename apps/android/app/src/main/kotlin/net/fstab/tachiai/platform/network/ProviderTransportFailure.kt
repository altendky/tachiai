package net.fstab.tachiai.platform.network

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal enum class ProviderTransportFailure { DNS, CONNECT, TIMEOUT, TLS, CANCELLED, UNKNOWN }
internal enum class ProviderTransportPhase { UNKNOWN, DNS, CONNECT, TLS, REQUEST, RESPONSE }

// Request-local evidence only. A timeout without a known pre-TLS phase is not
// proof of route availability failure. HTTP/provider outcomes are separate.
internal data class ProviderTransportEvidence(
    val failure: ProviderTransportFailure,
    val phase: ProviderTransportPhase = ProviderTransportPhase.UNKNOWN,
    val localCancellationRequested: Boolean = false,
) {
    val retryableAvailability: Boolean get() = !localCancellationRequested && when (failure) {
        ProviderTransportFailure.DNS, ProviderTransportFailure.CONNECT -> true
        ProviderTransportFailure.TIMEOUT -> phase in setOf(ProviderTransportPhase.DNS, ProviderTransportPhase.CONNECT)
        else -> false
    }
}

internal interface ProviderTransportFailureCarrier {
    val transportFailure: ProviderTransportEvidence?
}

// No original exception, URL, response, headers or private message is retained.
internal class ProviderTransportException(val evidence: ProviderTransportEvidence) :
    IOException("Routed HTTPS request failed"), ProviderTransportFailureCarrier {
    override val transportFailure get() = evidence
}

internal fun providerTransportEvidence(error: Throwable): ProviderTransportEvidence =
    (error as? ProviderTransportFailureCarrier)?.transportFailure ?: ProviderTransportEvidence(when (error) {
        is SSLException -> ProviderTransportFailure.TLS
        is UnknownHostException -> ProviderTransportFailure.DNS
        is ConnectException -> ProviderTransportFailure.CONNECT
        is SocketTimeoutException -> ProviderTransportFailure.TIMEOUT
        is InterruptedIOException -> ProviderTransportFailure.CANCELLED
        else -> ProviderTransportFailure.UNKNOWN
    })
