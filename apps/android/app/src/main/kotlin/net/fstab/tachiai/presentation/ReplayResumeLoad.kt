package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.media.NativeTimingSnapshot

internal enum class ReplayResumeStatus { LOADING, AT_TARGET, STALLED, TIMED_OUT }

// A replay that seeked to its resume position (a retry position or a saved
// one) before it prepares (#655 part 2, #747). Loading a mid-item position can
// take longer than a fixed wait, for example while the sibling feed shares the
// route, so the wait is measured from loading progress: each increase of the
// player's buffered position since the seek counts. A resume that makes no
// progress for STALL_MS stops, and LIMIT_MS bounds one that never arrives.
// It applies the same way to an initial start, a Retry, an automatic recovery
// and a route switch; each starts a new load with its seek.
internal class ReplayResumeLoad(val targetMs: Long, private val startedAtMs: Long) {
    companion object {
        const val STALL_MS = 8_000L
        const val LIMIT_MS = 30_000L
        const val TARGET_TOLERANCE_MS = 1_000L
        private const val STATE_READY = 3
    }

    private var bufferedMs: Long? = null
    private var progressAtMs = startedAtMs
    private var progressCount = 0

    // A missing player or snapshot is no progress; the bounds still apply.
    fun observe(nowMs: Long, snapshot: NativeTimingSnapshot?): ReplayResumeStatus {
        val buffered = snapshot?.bufferedMs?.takeIf { it >= 0 }
        if (buffered != null) {
            // The first sample after the seek is only the baseline.
            if (bufferedMs?.let { buffered > it } == true) { progressAtMs = nowMs; progressCount++ }
            bufferedMs = buffered
        }
        if (snapshot != null && snapshot.state == STATE_READY &&
            snapshot.positionMs?.let { kotlin.math.abs(it - targetMs) <= TARGET_TOLERANCE_MS } == true)
            return ReplayResumeStatus.AT_TARGET
        val elapsed = nowMs - startedAtMs
        // A clock that ran backwards cannot extend the bound.
        if (elapsed !in 0 until LIMIT_MS) return ReplayResumeStatus.TIMED_OUT
        if (nowMs - progressAtMs >= STALL_MS) return ReplayResumeStatus.STALLED
        return ReplayResumeStatus.LOADING
    }

    // The feed did not finish preparing; not a player error.
    fun failure() = PrototypeFeedFailure(PrototypeFailureReason.PREPARATION_TIMEOUT)

    // Closed fields only: durations and a count, never positions or identities.
    fun summary(nowMs: Long) =
        "elapsedMs=${nowMs - startedAtMs} progressAgeMs=${nowMs - progressAtMs} progress=$progressCount"
}
