package net.fstab.tachiai.platform.diagnostics

import android.util.Log
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.platform.media.NativeFailureObserver
import net.fstab.tachiai.platform.media.NativePlayerReleaseTimeoutException
import net.fstab.tachiai.platform.media.NativeMediaTransportObserver

internal fun FailureReporter.nativeObserver(): NativeFailureObserver = { stage, error ->
    if (BuildConfig.DEBUG && error is NativePlayerReleaseTimeoutException) runCatching {
        // Ephemeral, closed numeric evidence; never print the Throwable itself.
        Log.i("TachiaiNativeRelease", "event=PLAYER_RELEASE_TIMEOUT code=${error.errorCode} elapsedMs=${error.elapsedMs}")
    }
    report(FailureStage.valueOf(stage.name), error)
}

internal fun FailureReporter.nativeTransportObserver(): NativeMediaTransportObserver? = if (!BuildConfig.DEBUG) null else {
    { value ->
        // Ephemeral closed evidence only; the persisted v1 journal is unchanged.
        Log.i("TachiaiNativeTransport", "slot=${diagnosticSlot.name} type=${value.type.name} category=${value.category.name}" +
            " phase=${value.phase.name} phaseAvailable=${value.phaseAvailable} proxyPresent=${value.proxyPresent}" +
            " localCancellationRequested=${value.localCancellationRequested} routeInactiveObserved=${value.routeInactiveObserved}")
    }
}
