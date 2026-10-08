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
    fun fullStep(sample: NativeTimingSnapshot, movement: Long): Boolean {
        val position = sample.positionMs ?: return false
        val duration = sample.durationMs ?: return false
        if (position < 0 || position > duration) return false
        val initialClock = if (sample.live || sample.dynamic) sample.contentTimeMs else position
        if (initialClock == null || initialClock < 0) return false
        val target = try { Math.addExact(position, movement) } catch (_: ArithmeticException) { return false }
        val targetClock = try { Math.addExact(initialClock, movement) } catch (_: ArithmeticException) { return false }
        if (targetClock < 0) return false
        val plan = nativeRelativeSeekPlan(sample, movement)
        return plan.outcome == NativeSeekOutcome.REQUESTED && plan.targetMs == target
    }
    return when {
        fullStep(a, deltaMs) -> NativeRelativeNudgePlan(NativeRelativeNudgeOutcome.SELECTED, NativeMixedSide.A, deltaMs)
        fullStep(b, opposite) -> NativeRelativeNudgePlan(NativeRelativeNudgeOutcome.SELECTED, NativeMixedSide.B, opposite)
        else -> NativeRelativeNudgePlan(NativeRelativeNudgeOutcome.NO_FULL_STEP)
    }
}
