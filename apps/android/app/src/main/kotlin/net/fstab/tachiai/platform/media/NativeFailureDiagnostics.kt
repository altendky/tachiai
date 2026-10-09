package net.fstab.tachiai.platform.media

// The release media layer knows only operations, never a diagnostic store or
// provider identity. Debug callers can preserve sanitized evidence separately.
internal enum class NativeFailureStage { MEDIA_DISCONNECT, BEFORE_PLAYER_RELEASE, PLAYER_RELEASE, QUALITY_RELEASE, PLAYER_ERROR, DRM_ERROR }

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
