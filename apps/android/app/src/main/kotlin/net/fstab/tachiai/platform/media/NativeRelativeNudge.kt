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
        val live = sample.live || sample.dynamic
        // A live feed playing retained footage before its sliding window start
        // may still move forward into the window; nothing else leaves it.
        val rescue = live && position < 0 && movement > 0
        if (position > duration || position < 0 && !rescue) return null
        val initialClock = if (live) sample.contentTimeMs else position
        if (initialClock == null || initialClock < 0) return null
        val target = try { Math.addExact(position, movement) } catch (_: ArithmeticException) { return null }
        val targetClock = try { Math.addExact(initialClock, movement) } catch (_: ArithmeticException) { return null }
        if (targetClock < 0 || rescue && target !in 0..duration) return null
        // #618: never hold a live feed further behind live than the delay limit.
        if (live && nativeLiveDelayExceeded(sample, movement)) return null
        val plan = nativeRelativeSeekPlan(sample, movement)
        if (plan.outcome != NativeSeekOutcome.REQUESTED || plan.targetMs != target) return null
        return if (live) minOf(target, duration - target) else Long.MAX_VALUE
    }
    // Current margin of the feed that stays put: negative before a live window.
    fun currentMargin(sample: NativeTimingSnapshot): Long? {
        if (!(sample.live || sample.dynamic)) return Long.MAX_VALUE
        val position = sample.positionMs ?: return null
        val duration = sample.durationMs ?: return null
        return minOf(position, duration - position)
    }
    // Both movements deliver the same relative step with one SDK seek. First
    // keep the pair's tightest live margin away from a sliding window edge,
    // including the feed that does not move, so a step rescues an endangered
    // feed rather than moving its safe peer. Margins beyond a comfortable bound
    // are equivalent and fall back to the moved feed's own room (replay has no
    // sliding edge). This is a preference, never a minimum margin or threshold.
    val roomA = fullStepHeadroom(a, deltaMs)
    val roomB = fullStepHeadroom(b, opposite)
    fun pairScore(moved: Long, untouched: Long?): Long =
        minOf(moved, untouched ?: Long.MIN_VALUE, COMFORTABLE_LIVE_MARGIN_MS)
    val chooseA = roomA != null && (roomB == null || run {
        val scoreA = pairScore(roomA, currentMargin(b))
        val scoreB = pairScore(roomB, currentMargin(a))
        scoreA > scoreB || scoreA == scoreB && roomA >= roomB
    })
    return when {
        chooseA -> NativeRelativeNudgePlan(NativeRelativeNudgeOutcome.SELECTED, NativeMixedSide.A, deltaMs)
        roomB != null -> NativeRelativeNudgePlan(NativeRelativeNudgeOutcome.SELECTED, NativeMixedSide.B, opposite)
        else -> NativeRelativeNudgePlan(NativeRelativeNudgeOutcome.NO_FULL_STEP)
    }
}

private const val COMFORTABLE_LIVE_MARGIN_MS = 10_000L

// True when the step's backward option (A back for a negative delta, B back
// for a positive one) is refused by the live delay limit (#618).
internal fun nativeNudgeDelayLimited(a: NativeTimingSnapshot?, b: NativeTimingSnapshot?, deltaMs: Long): Boolean {
    if (deltaMs == 0L || deltaMs == Long.MIN_VALUE) return false
    val (sample, movement) = if (deltaMs > 0) b to -deltaMs else a to deltaMs
    return sample != null && nativeLiveDelayExceeded(sample, movement)
}
