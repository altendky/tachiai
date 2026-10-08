package net.fstab.tachiai.platform.media

// Only normalized clocks/capabilities: no media item, URI, track or provider data.
internal data class NativeTimingSnapshot(
    val positionMs: Long?,
    val durationMs: Long?,
    val bufferedMs: Long?,
    val liveOffsetMs: Long?,
    val windowStartMs: Long?,
    val defaultPositionMs: Long?,
    val live: Boolean,
    val dynamic: Boolean,
    val seekable: Boolean,
    val canSeekDefault: Boolean,
    val playingAd: Boolean,
    val playing: Boolean,
    val playWhenReady: Boolean,
    val state: Int,
    val speed: Float,
) {
    // Live position alone is relative to a sliding window, not a stable clock.
    val contentTimeMs: Long? get() = if (windowStartMs != null && positionMs != null && windowStartMs >= 0)
        try { Math.addExact(windowStartMs, positionMs).takeIf { it >= 0 } }
        catch (_: ArithmeticException) { null } else null
}

internal enum class NativeSeekOutcome { REQUESTED, CLAMPED, NO_CHANGE, UNAVAILABLE, UNSUPPORTED, AD_BLOCKED, OUTSIDE_WINDOW }
internal data class NativeSeekPlan(val outcome: NativeSeekOutcome, val targetMs: Long? = null)

internal fun nativeSeekPlan(snapshot: NativeTimingSnapshot?, targetMs: Long): NativeSeekPlan {
    if (snapshot == null || snapshot.positionMs == null || snapshot.durationMs == null ||
        snapshot.durationMs <= 0) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
    if (snapshot.playingAd) return NativeSeekPlan(NativeSeekOutcome.AD_BLOCKED)
    if (!snapshot.seekable) return NativeSeekPlan(NativeSeekOutcome.UNSUPPORTED)
    val bounded = targetMs.coerceIn(0, snapshot.durationMs)
    return NativeSeekPlan(when {
        bounded == snapshot.positionMs -> NativeSeekOutcome.NO_CHANGE
        bounded != targetMs -> NativeSeekOutcome.CLAMPED
        else -> NativeSeekOutcome.REQUESTED
    }, bounded)
}

internal fun nativeRelativeSeekPlan(snapshot: NativeTimingSnapshot?, deltaMs: Long): NativeSeekPlan {
    val position = snapshot?.positionMs ?: return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
    val target = try { Math.addExact(position, deltaMs) }
        catch (_: ArithmeticException) { if (deltaMs > 0) Long.MAX_VALUE else Long.MIN_VALUE }
    val plan = nativeSeekPlan(snapshot, target)
    // A stale live point can lie outside a sliding window. Never turn Back
    // into Forward (or vice versa) by clamping it into that newer window.
    val bounded = plan.targetMs ?: return plan
    if ((deltaMs < 0 && bounded > position) || (deltaMs > 0 && bounded < position) ||
        (deltaMs == 0L && bounded != position)) return NativeSeekPlan(NativeSeekOutcome.OUTSIDE_WINDOW)
    return plan
}

internal fun nativeLiveDefaultPlan(snapshot: NativeTimingSnapshot?): NativeSeekPlan {
    if (snapshot == null) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
    if (!snapshot.live || !snapshot.canSeekDefault) return NativeSeekPlan(NativeSeekOutcome.UNSUPPORTED)
    // Default-position seeking has its own Media3 command; it need not imply
    // arbitrary seeking. It still cannot invent a point outside this window.
    val target = snapshot.defaultPositionMs ?: return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
    val duration = snapshot.durationMs?.takeIf { it > 0 } ?: return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
    if (snapshot.playingAd) return NativeSeekPlan(NativeSeekOutcome.AD_BLOCKED)
    val bounded = target.coerceIn(0, duration)
    return NativeSeekPlan(when {
        snapshot.positionMs == bounded -> NativeSeekOutcome.NO_CHANGE
        bounded != target -> NativeSeekOutcome.CLAMPED
        else -> NativeSeekOutcome.REQUESTED
    }, bounded)
}
