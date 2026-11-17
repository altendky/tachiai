package net.fstab.tachiai.platform.diagnostics

import android.util.Log
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.platform.media.NativeFailureObserver
import net.fstab.tachiai.platform.media.NativePlayerReleaseTimeoutException
import net.fstab.tachiai.platform.media.NativeMediaTransportObserver
import net.fstab.tachiai.platform.media.NativeMediaRequestException
import net.fstab.tachiai.platform.media.NativeMediaOpenPhase
import net.fstab.tachiai.platform.network.ProviderTransportException

internal fun FailureReporter.nativeObserver(): NativeFailureObserver = { stage, error ->
    val transport = (error as? NativeMediaRequestException)?.transportEvidence
        ?: (error as? ProviderTransportException)?.evidence
    if (transport != null) observeNativeEvidence(stage, transport)
    if (BuildConfig.DEBUG && error is NativePlayerReleaseTimeoutException) runCatching {
        // Ephemeral, closed numeric evidence; never print the Throwable itself.
        Log.i("TachiaiNativeRelease", "event=PLAYER_RELEASE_TIMEOUT code=${error.errorCode} elapsedMs=${error.elapsedMs}")
    }
    report(FailureStage.valueOf(stage.name), error)
    (error as? NativeMediaRequestException)?.openPhase?.let { phase ->
        val boundary = when (phase) {
            NativeMediaOpenPhase.REQUEST_SETUP -> FailureStage.MEDIA_REQUEST_SETUP
            NativeMediaOpenPhase.CONNECTION_OPEN -> FailureStage.MEDIA_CONNECTION_OPEN
            NativeMediaOpenPhase.RESPONSE_OPEN -> FailureStage.MEDIA_RESPONSE_OPEN
            NativeMediaOpenPhase.RESPONSE_POLICY -> FailureStage.MEDIA_RESPONSE_POLICY
            NativeMediaOpenPhase.INPUT_OPEN -> FailureStage.MEDIA_INPUT_OPEN
        }
        reportCategory(boundary, SafeFailure.capture(error).category)
    }
}

internal fun FailureReporter.nativeTransportObserver(): NativeMediaTransportObserver? = if (!BuildConfig.DEBUG) null else {
    { value ->
        // Ephemeral closed evidence only; the persisted v1 journal is unchanged.
        Log.i("TachiaiNativeTransport", "slot=${diagnosticSlot.name} type=${value.type.name} category=${value.category.name}" +
            " phase=${value.phase.name} phaseAvailable=${value.phaseAvailable} proxyPresent=${value.proxyPresent}" +
            " localCancellationRequested=${value.localCancellationRequested} routeInactiveObserved=${value.routeInactiveObserved}")
    }
}
