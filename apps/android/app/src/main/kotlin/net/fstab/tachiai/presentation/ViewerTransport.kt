package net.fstab.tachiai.presentation

import android.view.KeyEvent
import net.fstab.tachiai.platform.media.NativePairSkipPlan
import net.fstab.tachiai.platform.media.NativeSeekOutcome

// Replay navigation (#655 part 1), also on live feeds (#660): on-screen skips
// of 10 s and 1 min, remote and gesture skips of 10 s, and a scrub bar.
// Larger jumps use the scrub bar.
internal object ViewerTransportSteps {
    const val SHORT_MS = 10_000L
    const val LONG_MS = 60_000L
    // A scrub with no further change commits after this idle time.
    const val SCRUB_COMMIT_IDLE_MS = 1_000L
    // A scrub key held this long switches to large steps.
    const val SCRUB_ACCELERATE_AFTER_MS = 500L
    // A live feed this close behind its live point reads LIVE (#660).
    const val LIVE_EDGE_MS = 3_000L
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

// A live feed's distance behind its live point: "−1:35" (h:mm:ss from an
// hour), or "LIVE" within 3 s of it (#660). The margin absorbs a feed started
// at the window's default position, which the player snaps to the start of a
// segment, slightly behind its live point.
internal fun formatBehindLive(behindMs: Long): String =
    if (behindMs <= ViewerTransportSteps.LIVE_EDGE_MS) "LIVE" else "−${formatPlaybackTime(behindMs)}"

// The position row's time. On a replay the scrub bar spans 0 to the duration;
// on a live feed it spans the oldest available point to the live point, so
// spanMs is the live point and the position reads as the distance behind it.
internal fun formatTransportPosition(positionMs: Long, spanMs: Long, live: Boolean): String =
    if (live) formatBehindLive(spanMs - positionMs) else formatPlaybackPosition(positionMs, spanMs)

// The same position for screen readers: "2:14:05 of 4:45:00", "1:35 behind
// live" or "Live".
internal fun describeTransportPosition(positionMs: Long, spanMs: Long, live: Boolean): String {
    if (!live) return "${formatPlaybackTime(positionMs, spanMs)} of ${formatPlaybackTime(spanMs, spanMs)}"
    val behind = spanMs - positionMs
    return if (behind <= ViewerTransportSteps.LIVE_EDGE_MS) "Live" else "${formatPlaybackTime(behind)} behind live"
}

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

// A remote key while the overlay is hidden. While skipping is available
// (replays and seekable live feeds alike, #660), Left/Right skip 10 s and
// leave the overlay hidden; otherwise they reveal it. Select reveals it
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
// On a live feed the duration is the bar's span, a few minutes, so steps
// stay at 10 s.
internal fun viewerScrubStepMs(heldMs: Long, durationMs: Long): Long {
    val duration = durationMs.coerceAtLeast(0)
    val base = maxOf(ViewerTransportSteps.SHORT_MS, duration / 100)
    return if (heldMs >= ViewerTransportSteps.SCRUB_ACCELERATE_AFTER_MS) maxOf(base, duration / 20) else base
}

// The scrub bar's pending target while choosing, before it commits:
// "→ 1:42:10 / 3:24:00", or "→ −1:35" or "→ LIVE" on a live feed.
internal fun formatScrubTarget(targetMs: Long, durationMs: Long, live: Boolean = false): String =
    "→ ${formatTransportPosition(targetMs, durationMs, live)}"

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
// live: the refusal is for Live, whose last resort is each feed's own Back to
// live in More.
internal fun viewerSkipRefusal(plan: NativePairSkipPlan, forward: Boolean, pair: Boolean, live: Boolean = false): String? {
    val blocked = plan.blockedBy?.name?.takeIf { pair }
    val limited = plan.limitedBy?.name?.takeIf { pair }
    if (plan.outcome != NativeSeekOutcome.REQUESTED && plan.outcome != NativeSeekOutcome.CLAMPED)
        plan.reason?.let { return "$it; neither feed moved." } // A pair's refusal after holding (#660).
    return when (plan.outcome) {
        NativeSeekOutcome.REQUESTED, NativeSeekOutcome.CLAMPED -> null
        NativeSeekOutcome.NO_CHANGE -> when {
            limited != null && plan.limitedAtLive -> "$limited is live; neither feed moved."
            plan.limitedAtLive -> "Already live."
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
        // A feed outside its window (#660): name it and what brings it back.
        NativeSeekOutcome.OUTSIDE_WINDOW -> when {
            live && blocked != null -> "$blocked is outside its window; use Back to live $blocked in ⋯ More."
            live -> "The feed is outside its window; use Back to live in ⋯ More."
            blocked != null -> "$blocked is outside its window; neither feed moved. Live brings it back."
            else -> "The feed is outside its window. Live brings it back."
        }
        NativeSeekOutcome.UNAVAILABLE ->
            if (blocked != null) "Skipping waits for $blocked; neither feed moved."
            else "Skipping is unavailable right now."
    }
}

// Live's notice when it brought a feed back from outside its window (#660):
// whether the pair kept its offset. Null for an ordinary move to live, which
// the position row shows.
internal fun viewerLiveNotice(plan: NativePairSkipPlan, pair: Boolean): String? {
    if (plan.outcome != NativeSeekOutcome.REQUESTED && plan.outcome != NativeSeekOutcome.CLAMPED) return null
    val outside = plan.targets.filter { it.fromMs < 0 }.map { it.side }
    val subject = when (outside.size) {
        0 -> "A feed was outside its window"
        1 -> "${outside.single().name} was outside its window"
        else -> "Both feeds were outside their windows"
    }
    if (pair && !plan.keepsOffset) return "$subject, so each feed returned to live; the offset was not kept."
    if (outside.isEmpty()) return null
    return if (pair) "$subject; both feeds moved forward to live together, keeping the offset."
        else "The feed was outside its window; it returned to live."
}
