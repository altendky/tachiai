package net.fstab.tachiai.platform.media

import java.io.IOException

internal const val NATIVE_PLAYER_RELEASE_TIMEOUT_CODE = 1003
internal const val NATIVE_PLAYER_RELEASE_DURATION_LIMIT_MS = 60_000L

// App-owned, closed evidence only. Never retain Media3's exception or cause,
// which may contain provider data. -1 means no usable monotonic measurement.
internal class NativePlayerReleaseTimeoutException(val elapsedMs: Long) : IOException("Native player release timed out") {
    val errorCode get() = NATIVE_PLAYER_RELEASE_TIMEOUT_CODE
    init { require(elapsedMs in -1..NATIVE_PLAYER_RELEASE_DURATION_LIMIT_MS) }
}

// A void release can notify its timeout listener synchronously or later. Wait
// for the call to return before reporting its duration, and report only once.
// This observes cleanup; it never decides whether cleanup is confirmed.
internal class NativePlayerReleaseDiagnostics(private val clockMs: () -> Long,
    private val report: (NativePlayerReleaseTimeoutException) -> Unit) {
    private val lock = Any()
    private var started = false
    private var began: Long? = null
    private var duration = -1L
    private var completed = false
    private var timedOut = false
    private var reported = false

    private fun now() = runCatching(clockMs).getOrNull()?.takeIf { it >= 0 }
    fun begin() { synchronized(lock) { started = true; began = now() } }
    fun finish() {
        synchronized(lock) {
            val end = now()
            duration = began?.let { start -> end?.takeIf { it >= start }?.let {
                (it - start).coerceAtMost(NATIVE_PLAYER_RELEASE_DURATION_LIMIT_MS)
            } } ?: -1L
            completed = true
        }
        publish()
    }
    fun playerError(releasing: Boolean, errorCode: Int) {
        if (!releasing || errorCode != NATIVE_PLAYER_RELEASE_TIMEOUT_CODE) return
        synchronized(lock) {
            // The owner's closed flag predates transport cleanup. A queued
            // playback error then cannot identify this later release call.
            if (!started) return
            timedOut = true
        }
        publish()
    }
    private fun publish() {
        val evidence = synchronized(lock) {
            if (!completed || !timedOut || reported) null
            else { reported = true; NativePlayerReleaseTimeoutException(duration) }
        }
        if (evidence != null) runCatching { report(evidence) }
    }
}
