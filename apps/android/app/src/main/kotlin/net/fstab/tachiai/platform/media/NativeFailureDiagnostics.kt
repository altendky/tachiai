package net.fstab.tachiai.platform.media

import java.io.IOException
import net.fstab.tachiai.platform.network.ProviderTransportFailure
import net.fstab.tachiai.platform.network.ProviderTransportFailureCarrier
import net.fstab.tachiai.platform.network.providerTransportEvidence

// The release media layer knows only operations, never a diagnostic store or
// provider identity. Debug callers can preserve sanitized evidence separately.
internal enum class NativeFailureStage { MEDIA_REQUEST_OPEN, MEDIA_REQUEST_READ, MEDIA_DISCONNECT, BEFORE_PLAYER_RELEASE, PLAYER_RELEASE, QUALITY_RELEASE, PLAYER_ERROR, DRM_ERROR }

internal enum class NativeMediaRequestFailure {
    DNS, CONNECT, TIMEOUT, TLS, TRANSPORT_CANCELLED, TRANSPORT_UNKNOWN,
    REQUEST_DEADLINE, REQUEST_ADMISSION_REFUSED, PLAYBACK_INACTIVE,
    IO, ILLEGAL_STATE, ILLEGAL_ARGUMENT, SECURITY, CANCELLED, NETWORK_ON_MAIN_THREAD, OTHER,
}

// Only a fixed category crosses the request observer boundary. In particular,
// never retain the original transport exception, private text or URL as a cause.
internal class NativeMediaRequestException(val failure: NativeMediaRequestFailure) :
    IOException("Native media request failed")

internal fun nativeMediaRequestException(error: Exception): NativeMediaRequestException {
    if (error is NativeMediaRequestException) return NativeMediaRequestException(error.failure)
    val transport = providerTransportEvidence(error)
    val failure = when {
        transport.localCancellationRequested -> NativeMediaRequestFailure.TRANSPORT_CANCELLED
        transport.failure != ProviderTransportFailure.UNKNOWN || error is ProviderTransportFailureCarrier -> when (transport.failure) {
            ProviderTransportFailure.DNS -> NativeMediaRequestFailure.DNS
            ProviderTransportFailure.CONNECT -> NativeMediaRequestFailure.CONNECT
            ProviderTransportFailure.TIMEOUT -> NativeMediaRequestFailure.TIMEOUT
            ProviderTransportFailure.TLS -> NativeMediaRequestFailure.TLS
            ProviderTransportFailure.CANCELLED -> NativeMediaRequestFailure.TRANSPORT_CANCELLED
            ProviderTransportFailure.UNKNOWN -> NativeMediaRequestFailure.TRANSPORT_UNKNOWN
        }
        error.javaClass.name == "android.os.NetworkOnMainThreadException" -> NativeMediaRequestFailure.NETWORK_ON_MAIN_THREAD
        error is java.util.concurrent.CancellationException -> NativeMediaRequestFailure.CANCELLED
        error is IOException -> NativeMediaRequestFailure.IO
        error is IllegalStateException -> NativeMediaRequestFailure.ILLEGAL_STATE
        error is IllegalArgumentException -> NativeMediaRequestFailure.ILLEGAL_ARGUMENT
        error is SecurityException -> NativeMediaRequestFailure.SECURITY
        else -> NativeMediaRequestFailure.OTHER
    }
    return NativeMediaRequestException(failure)
}

internal typealias NativeFailureObserver = (NativeFailureStage, Throwable) -> Unit

internal fun reportNativeFailure(stage: NativeFailureStage, observer: NativeFailureObserver, error: Throwable) {
    runCatching { observer(stage, error) }
}

internal fun <T> observeNativeFailure(stage: NativeFailureStage, observer: NativeFailureObserver, action: () -> T): T =
    try { action() } catch (error: Throwable) {
        // A broken observer must not replace the original exception or change
        // the caller's cleanup order and admission policy.
        reportNativeFailure(stage, observer, error)
        throw error
    }
