package net.fstab.tachiai.platform.network

import java.io.IOException
import java.io.InterruptedIOException
import java.io.EOFException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.ClosedChannelException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

internal enum class ProviderTransportFailure { DNS, CONNECT, TIMEOUT, TLS, CANCELLED, UNKNOWN }
internal enum class ProviderTransportPhase { UNKNOWN, DNS, CONNECT, TLS, REQUEST, RESPONSE }
// Diagnostic-only detail. These values never participate in retry/admission
// decisions and cannot distinguish socket reset/closure from private messages.
internal enum class ProviderTransportExceptionKind {
    UNKNOWN, TLS_HANDSHAKE, TLS_PEER, TLS_OTHER, DNS, CONNECT, SOCKET_TIMEOUT,
    PROTOCOL, EOF, SOCKET, CHANNEL_CLOSED, INTERRUPTED_IO, IO, OTHER,
}
internal enum class ProviderTransportOperation {
    UNKNOWN, CALL, DNS, CONNECT, TLS, REQUEST_HEADERS, REQUEST_HEADERS_COMPLETE,
    REQUEST_BODY, RESPONSE_HEADERS, RESPONSE_BODY,
}

// Request-local evidence only. A timeout without a known pre-TLS phase is not
// proof of route availability failure. HTTP/provider outcomes are separate.
internal data class ProviderTransportEvidence(
    val failure: ProviderTransportFailure,
    val phase: ProviderTransportPhase = ProviderTransportPhase.UNKNOWN,
    val localCancellationRequested: Boolean = false,
    val exceptionKind: ProviderTransportExceptionKind = ProviderTransportExceptionKind.UNKNOWN,
    val operation: ProviderTransportOperation = ProviderTransportOperation.UNKNOWN,
    val phaseAvailable: Boolean = false,
    val proxyPresent: Boolean = false,
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

internal fun providerTransportExceptionKind(error: Throwable): ProviderTransportExceptionKind = when (error) {
    is SSLHandshakeException -> ProviderTransportExceptionKind.TLS_HANDSHAKE
    is SSLPeerUnverifiedException -> ProviderTransportExceptionKind.TLS_PEER
    is SSLException -> ProviderTransportExceptionKind.TLS_OTHER
    is UnknownHostException -> ProviderTransportExceptionKind.DNS
    is ConnectException -> ProviderTransportExceptionKind.CONNECT
    is SocketTimeoutException -> ProviderTransportExceptionKind.SOCKET_TIMEOUT
    is ProtocolException -> ProviderTransportExceptionKind.PROTOCOL
    is EOFException -> ProviderTransportExceptionKind.EOF
    is SocketException -> ProviderTransportExceptionKind.SOCKET
    is ClosedChannelException -> ProviderTransportExceptionKind.CHANNEL_CLOSED
    is InterruptedIOException -> ProviderTransportExceptionKind.INTERRUPTED_IO
    is IOException -> ProviderTransportExceptionKind.IO
    else -> ProviderTransportExceptionKind.OTHER
}

internal fun providerTransportEvidence(error: Throwable): ProviderTransportEvidence =
    (error as? ProviderTransportFailureCarrier)?.transportFailure ?: ProviderTransportEvidence(when (error) {
        is SSLException -> ProviderTransportFailure.TLS
        is UnknownHostException -> ProviderTransportFailure.DNS
        is ConnectException -> ProviderTransportFailure.CONNECT
        is SocketTimeoutException -> ProviderTransportFailure.TIMEOUT
        is InterruptedIOException -> ProviderTransportFailure.CANCELLED
        else -> ProviderTransportFailure.UNKNOWN
    }, exceptionKind = providerTransportExceptionKind(error))
