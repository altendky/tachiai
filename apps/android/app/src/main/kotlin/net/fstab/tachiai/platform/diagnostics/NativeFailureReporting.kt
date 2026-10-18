package net.fstab.tachiai.platform.diagnostics

import android.util.Log
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.platform.media.NativeFailureObserver
import net.fstab.tachiai.platform.media.NativePlayerReleaseTimeoutException

internal fun FailureReporter.nativeObserver(): NativeFailureObserver = { stage, error ->
    if (BuildConfig.DEBUG && error is NativePlayerReleaseTimeoutException) runCatching {
        // Ephemeral, closed numeric evidence; never print the Throwable itself.
        Log.i("TachiaiNativeRelease", "event=PLAYER_RELEASE_TIMEOUT code=${error.errorCode} elapsedMs=${error.elapsedMs}")
    }
    report(FailureStage.valueOf(stage.name), error)
}
