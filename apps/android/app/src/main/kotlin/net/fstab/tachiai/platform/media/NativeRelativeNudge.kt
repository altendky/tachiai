package net.fstab.tachiai.platform.media

internal enum class NativeRelativeNudgeOutcome { SELECTED, NO_CHANGE, UNAVAILABLE, AD_BLOCKED, NO_FULL_STEP }
internal data class NativeRelativeNudgePlan(
    val outcome: NativeRelativeNudgeOutcome,
    val side: NativeMixedSide? = null,
    val movementMs: Long? = null,
)

// Positive = A selects later footage relative to B (A forward or B backward).
// Each candidate uses only its own window; unrelated clocks are never subtracted.
internal fun nativeRelativeNudgePlan(a: NativeTimingSnapshot?, b: NativeTimingSnapshot?, deltaMs: Long): NativeRelativeNudgePlan {
    if (a == null || b == null || a.state != 3 || b.state != 3)
        return NativeRelativeNudgePlan(NativeRelativeNudgeOutcome.UNAVAILABLE)
    if (a.playingAd || b.playingAd) return NativeRelativeNudgePlan(NativeRelativeNudgeOutcome.AD_BLOCKED)
    if (deltaMs == 0L) return NativeRelativeNudgePlan(NativeRelativeNudgeOutcome.NO_CHANGE)
    val opposite = try { Math.negateExact(deltaMs) }
        catch (_: ArithmeticException) { return NativeRelativeNudgePlan(NativeRelativeNudgeOutcome.NO_FULL_STEP) }
    fun fullStepHeadroom(sample: NativeTimingSnapshot, movement: Long): Long? {
        val position = sample.positionMs ?: return null
        val duration = sample.durationMs ?: return null
        if (position < 0 || position > duration) return null
        val live = sample.live || sample.dynamic
        val initialClock = if (live) sample.contentTimeMs else position
        if (initialClock == null || initialClock < 0) return null
        val target = try { Math.addExact(position, movement) } catch (_: ArithmeticException) { return null }
        val targetClock = try { Math.addExact(initialClock, movement) } catch (_: ArithmeticException) { return null }
        if (targetClock < 0) return null
        val plan = nativeRelativeSeekPlan(sample, movement)
        if (plan.outcome != NativeSeekOutcome.REQUESTED || plan.targetMs != target) return null
        return if (live) minOf(target, duration - target) else Long.MAX_VALUE
    }
    // Both movements deliver the same relative step with one SDK seek. Prefer
    // more room for a live window to move while held; replay has no sliding
    // edge. This is a preference, never a minimum margin or admission threshold.
    val roomA = fullStepHeadroom(a, deltaMs)
    val roomB = fullStepHeadroom(b, opposite)
    return when {
        roomA != null && (roomB == null || roomA >= roomB) ->
            NativeRelativeNudgePlan(NativeRelativeNudgeOutcome.SELECTED, NativeMixedSide.A, deltaMs)
        roomB != null -> NativeRelativeNudgePlan(NativeRelativeNudgeOutcome.SELECTED, NativeMixedSide.B, opposite)
        else -> NativeRelativeNudgePlan(NativeRelativeNudgeOutcome.NO_FULL_STEP)
    }
}
