package net.fstab.tachiai.platform.media

internal enum class NativePairOutcome { REQUESTED, UNAVAILABLE, UNSUPPORTED, AD_BLOCKED, OUT_OF_RANGE }
internal data class NativePairPlan(val outcome: NativePairOutcome, val aMs: Long? = null, val bMs: Long? = null)

// Caller must establish that both sources have the same content/time origin.
// Positive offset means A shows later footage; this is not a live delay model.
internal fun nativeReplayPairPlan(a: NativeTimingSnapshot?, b: NativeTimingSnapshot?,
    offsetMs: Long, anchorMs: Long? = null): NativePairPlan {
    if (a == null || b == null || a.positionMs == null || b.positionMs == null ||
        a.durationMs == null || b.durationMs == null || a.durationMs <= 0 || b.durationMs <= 0)
        return NativePairPlan(NativePairOutcome.UNAVAILABLE)
    if (a.live || b.live || a.dynamic || b.dynamic || !a.seekable || !b.seekable)
        return NativePairPlan(NativePairOutcome.UNSUPPORTED)
    if (a.playingAd || b.playingAd) return NativePairPlan(NativePairOutcome.AD_BLOCKED)
    val targetB = anchorMs ?: b.positionMs
    val targetA = try { Math.addExact(targetB, offsetMs) }
        catch (_: ArithmeticException) { return NativePairPlan(NativePairOutcome.OUT_OF_RANGE) }
    // Refuse the whole transaction rather than silently change its offset.
    if (targetA !in 0..a.durationMs || targetB !in 0..b.durationMs)
        return NativePairPlan(NativePairOutcome.OUT_OF_RANGE)
    return NativePairPlan(NativePairOutcome.REQUESTED, targetA, targetB)
}

internal fun nativeReplayOffsetMs(a: NativeTimingSnapshot?, b: NativeTimingSnapshot?): Long? {
    if (a == null || b == null || a.live || b.live || a.dynamic || b.dynamic ||
        a.playingAd || b.playingAd) return null
    val pa = a.positionMs?.takeIf { it >= 0 } ?: return null
    val pb = b.positionMs?.takeIf { it >= 0 } ?: return null
    return pa - pb
}

internal fun nativePairSettled(plan: NativePairPlan, a: NativeTimingSnapshot?, b: NativeTimingSnapshot?): Boolean {
    if (plan.outcome != NativePairOutcome.REQUESTED || a == null || b == null ||
        a.playing || b.playing || a.playWhenReady || b.playWhenReady || a.state != 3 || b.state != 3 ||
        a.live || b.live || a.dynamic || b.dynamic || a.playingAd || b.playingAd) return false
    fun near(value: Long?, target: Long?) = value != null && target != null && value >= 0 && target >= 0 &&
        kotlin.math.abs(value - target) <= 100
    return near(a.positionMs, plan.aMs) && near(b.positionMs, plan.bMs)
}
