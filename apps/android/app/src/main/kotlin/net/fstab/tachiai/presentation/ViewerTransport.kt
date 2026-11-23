package net.fstab.tachiai.presentation

import android.view.KeyEvent
import net.fstab.tachiai.platform.media.NativePairSkipPlan
import net.fstab.tachiai.platform.media.NativeSeekOutcome

// Replay navigation (#655 part 1): on-screen skips of 10 s and 1 min, remote
// and gesture skips of 10 s, and a scrub bar. Larger jumps use the scrub bar.
internal object ViewerTransportSteps {
    const val SHORT_MS = 10_000L
    const val LONG_MS = 60_000L
    // A scrub with no further change commits after this idle time.
    const val SCRUB_COMMIT_IDLE_MS = 1_000L
    // A scrub key held this long switches to large steps.
    const val SCRUB_ACCELERATE_AFTER_MS = 500L
}

// h:mm:ss, or m:ss while the total is under an hour. Negative values show 0.
internal fun formatPlaybackTime(ms: Long, totalMs: Long = ms): String {
    val seconds = ms.coerceAtLeast(0) / 1_000
    val h = seconds / 3_600
    val m = seconds / 60 % 60
    val s = seconds % 60
    return if (totalMs >= 3_600_000L || h > 0) "$h:${pad(m)}:${pad(s)}" else "$m:${pad(s)}"
}

private fun pad(value: Long) = value.toString().padStart(2, '0')

internal fun formatPlaybackPosition(positionMs: Long, durationMs: Long): String =
    "${formatPlaybackTime(positionMs, durationMs)} / ${formatPlaybackTime(durationMs, durationMs)}"

// "+10 s", "−1 min", "+1 min 30 s".
internal fun formatSkipAmount(deltaMs: Long): String {
    val sign = if (deltaMs < 0) "−" else "+"
    val seconds = (if (deltaMs == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(deltaMs)) / 1_000
    val minutes = seconds / 60
    val rest = seconds % 60
    return sign + when {
        minutes == 0L -> "$rest s"
        rest == 0L -> "$minutes min"
        else -> "$minutes min $rest s"
    }
}

internal enum class ViewerHiddenKey { SKIP_BACK, SKIP_FORWARD, REVEAL, REVEAL_ON_PLAY }

// A remote key while the overlay is hidden. On a replay, Left/Right skip 10 s
// and leave the overlay hidden; on live feeds they reveal it. Select reveals it
// focused on Play/Pause; Up/Down reveal the last focused control.
internal fun viewerHiddenKey(keyCode: Int, skippable: Boolean): ViewerHiddenKey = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_LEFT -> if (skippable) ViewerHiddenKey.SKIP_BACK else ViewerHiddenKey.REVEAL
    KeyEvent.KEYCODE_DPAD_RIGHT -> if (skippable) ViewerHiddenKey.SKIP_FORWARD else ViewerHiddenKey.REVEAL
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_BUTTON_A -> ViewerHiddenKey.REVEAL_ON_PLAY
    else -> ViewerHiddenKey.REVEAL
}

// Fast-forward and rewind media keys skip 10 s; other keys are not skips.
internal fun viewerMediaSkipMs(keyCode: Int): Long? = when (keyCode) {
    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> ViewerTransportSteps.SHORT_MS
    KeyEvent.KEYCODE_MEDIA_REWIND -> -ViewerTransportSteps.SHORT_MS
    else -> null
}

// Left/Right on the scrub bar step by 1% of the duration (at least 10 s), so
// a 3:24 replay moves about 2 min per press. After half a second of holding,
// steps grow to 5% (about 10 min there). The caller clamps to [0, duration].
internal fun viewerScrubStepMs(heldMs: Long, durationMs: Long): Long {
    val duration = durationMs.coerceAtLeast(0)
    val base = maxOf(ViewerTransportSteps.SHORT_MS, duration / 100)
    return if (heldMs >= ViewerTransportSteps.SCRUB_ACCELERATE_AFTER_MS) maxOf(base, duration / 20) else base
}

// The scrub bar's pending target while choosing, before it commits.
internal fun formatScrubTarget(targetMs: Long, durationMs: Long): String =
    "→ ${formatPlaybackPosition(targetMs, durationMs)}"

internal enum class ViewerTap { TOGGLE_NOW, DEFER_TOGGLE, SKIP_BACK, SKIP_FORWARD }

// Phone video taps: one tap shows or hides the overlay; a double tap on the
// left or right half skips 10 s back or forward, and each further tap in the
// same quick run skips again. While skipping is available a single tap waits
// one double-tap interval; otherwise it acts at once.
internal class ViewerTapGesture(private val windowMs: Long) {
    private var pendingTapMs: Long? = null
    private var lastSkipMs: Long? = null

    fun tap(nowMs: Long, x: Float, width: Int, skippable: Boolean): ViewerTap {
        if (!skippable) { reset(); return ViewerTap.TOGGLE_NOW }
        val pending = pendingTapMs
        val skipped = lastSkipMs
        if (pending != null && nowMs - pending in 0..windowMs || skipped != null && nowMs - skipped in 0..windowMs) {
            pendingTapMs = null
            lastSkipMs = nowMs
            return if (x < width / 2f) ViewerTap.SKIP_BACK else ViewerTap.SKIP_FORWARD
        }
        pendingTapMs = nowMs
        lastSkipMs = null
        return ViewerTap.DEFER_TOGGLE
    }

    // The deferred single tap ran: the next tap starts a new gesture.
    fun deferredDone() { pendingTapMs = null }

    fun reset() { pendingTapMs = null; lastSkipMs = null }
}

// A brief notice when a skip moves nothing, naming the feed that prevented it.
// Successful skips return null: the position row or a position flash shows them.
internal fun viewerSkipRefusal(plan: NativePairSkipPlan, forward: Boolean, pair: Boolean): String? {
    val blocked = plan.blockedBy?.name?.takeIf { pair }
    val limited = plan.limitedBy?.name?.takeIf { pair }
    return when (plan.outcome) {
        NativeSeekOutcome.REQUESTED, NativeSeekOutcome.CLAMPED -> null
        NativeSeekOutcome.NO_CHANGE -> when {
            limited != null && forward -> "$limited is at its end; neither feed moved."
            limited != null -> "$limited is at its start; neither feed moved."
            plan.limitedBy == null -> null
            forward -> "At the end."
            else -> "At the start."
        }
        NativeSeekOutcome.AD_BLOCKED -> if (blocked != null) "Skipping waits for $blocked's advertisement; neither feed moved."
            else "Skipping waits for the advertisement to finish."
        NativeSeekOutcome.UNSUPPORTED -> if (blocked != null) "$blocked can't skip, so neither feed moved."
            else "This feed can't skip."
        NativeSeekOutcome.UNAVAILABLE, NativeSeekOutcome.OUTSIDE_WINDOW ->
            if (blocked != null) "Skipping waits for $blocked; neither feed moved."
            else "Skipping is unavailable right now."
    }
}
