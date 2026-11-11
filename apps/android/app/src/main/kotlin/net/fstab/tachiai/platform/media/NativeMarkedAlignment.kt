package net.fstab.tachiai.platform.media

// An observed completed timing transaction, separate from requested history.
internal data class NativeTimingSettlement(val revision: Long, val deltaMs: Long)

internal data class NativeAlignmentTarget(val side: NativeMixedSide, val movementMs: Long,
    val targetMs: Long, val targetClockMs: Long, val liveClock: Boolean)
internal data class NativeAlignmentPlan(val outcome: NativeSeekOutcome, val targets: List<NativeAlignmentTarget> = emptyList(),
    val availableMagnitudeMs: Long? = null)
internal data class NativePendingSeekDiagnostic(val side: NativeMixedSide, val targetClockMs: Long,
    val actualClockMs: Long?, val liveClock: Boolean, val clockKindMatches: Boolean,
    val state: Int?, val playing: Boolean?, val playWhenReady: Boolean?, val playingAd: Boolean?,
    val toleranceMs: Long, val elapsedMs: Long)

// Mark-only exact alignment. Each target belongs to its own source clock.
// Ordinary relative nudges continue to require a single complete movement.
internal fun nativeMarkedAlignmentPlan(a: NativeTimingSnapshot?, b: NativeTimingSnapshot?, delta: Long): NativeAlignmentPlan {
    val single = nativeRelativeNudgePlan(a, b, delta)
    if (single.outcome == NativeRelativeNudgeOutcome.NO_CHANGE) return NativeAlignmentPlan(NativeSeekOutcome.NO_CHANGE)
    if (single.outcome == NativeRelativeNudgeOutcome.AD_BLOCKED) return NativeAlignmentPlan(NativeSeekOutcome.AD_BLOCKED)
    if (single.outcome == NativeRelativeNudgeOutcome.UNAVAILABLE || delta == Long.MIN_VALUE)
        return NativeAlignmentPlan(NativeSeekOutcome.UNAVAILABLE)
    if (a == null || b == null) return NativeAlignmentPlan(NativeSeekOutcome.UNAVAILABLE)
    fun target(side: NativeMixedSide, sample: NativeTimingSnapshot, movement: Long): NativeAlignmentTarget? {
        val position = sample.positionMs ?: return null
        val duration = sample.durationMs ?: return null
        if (duration <= 0 || position < 0 || position > duration) return null
        val live = sample.live || sample.dynamic
        val clock = (if (live) sample.contentTimeMs else position) ?: return null
        return try {
            val value = Math.addExact(position, movement)
            val clockValue = Math.addExact(clock, movement)
            val checked = nativeRelativeSeekPlan(sample, movement)
            if (clockValue < 0 || checked.outcome != NativeSeekOutcome.REQUESTED || checked.targetMs != value) null
            else NativeAlignmentTarget(side, movement, value, clockValue, live)
        } catch (_: ArithmeticException) { null }
    }
    val hasLive = a.live || a.dynamic || b.live || b.dynamic
    if (!hasLive && single.outcome == NativeRelativeNudgeOutcome.SELECTED) {
        val side = checkNotNull(single.side)
        val selected = target(side, if (side == NativeMixedSide.A) a else b, checkNotNull(single.movementMs))
        return if (selected == null) NativeAlignmentPlan(NativeSeekOutcome.UNAVAILABLE)
            else NativeAlignmentPlan(NativeSeekOutcome.REQUESTED, listOf(selected))
    }
    fun headroom(targets: List<NativeAlignmentTarget>): Long = targets.filter { it.liveClock }.minOfOrNull {
        val duration = checkNotNull((if (it.side == NativeMixedSide.A) a else b).durationMs)
        minOf(it.targetMs, duration - it.targetMs)
    } ?: Long.MAX_VALUE // A replay-only movement has no sliding live-window edge.
    val singles = listOfNotNull(target(NativeMixedSide.A, a, delta), target(NativeMixedSide.B, b, -delta))
    // Keep A on equal margins, preserving the existing single-side preference.
    val bestSingle = singles.maxByOrNull { headroom(listOf(it)) }
    fun singleOrUnavailable(available: Long): NativeAlignmentPlan = bestSingle?.let {
        NativeAlignmentPlan(NativeSeekOutcome.REQUESTED, listOf(it))
    } ?: NativeAlignmentPlan(NativeSeekOutcome.UNAVAILABLE, availableMagnitudeMs = available)
    fun room(sample: NativeTimingSnapshot, forward: Boolean): Long {
        val position = sample.positionMs ?: return 0
        val duration = sample.durationMs ?: return 0
        val clock = (if (sample.live || sample.dynamic) sample.contentTimeMs else position) ?: return 0
        if (!sample.seekable || duration <= 0 || position < 0 || position > duration || clock < 0) return 0
        return if (forward) minOf(duration - position, Long.MAX_VALUE - clock) else minOf(position, clock)
    }
    val magnitude = kotlin.math.abs(delta)
    val roomA = room(a, delta > 0)
    val roomB = room(b, delta < 0)
    val available = try { Math.addExact(roomA, roomB) } catch (_: ArithmeticException) { Long.MAX_VALUE }
    // Keep both targets inside their feasible windows instead of consuming
    // the entire room on A: a live window may slide before B is dispatched.
    val lowerA = maxOf(1L, magnitude - roomB)
    val upperA = minOf(roomA, magnitude - 1)
    if (lowerA > upperA)
        return singleOrUnavailable(available)
    val amountA = lowerA + (upperA - lowerA) / 2
    val amountB = magnitude - amountA
    if (amountA <= 0 || amountB <= 0 || amountB > roomB)
        return singleOrUnavailable(available)
    val ta = target(NativeMixedSide.A, a, if (delta > 0) amountA else -amountA)
    val tb = target(NativeMixedSide.B, b, if (delta > 0) -amountB else amountB)
    if (ta == null || tb == null) return singleOrUnavailable(available)
    val composite = listOf(ta, tb)
    if (bestSingle != null) {
        val singleRoom = headroom(listOf(bestSingle))
        val compositeRoom = headroom(composite)
        // A second SDK seek needs more than one second of additional margin,
        // well beyond the existing 100 ms settlement tolerance. This is a
        // selection preference, never a minimum margin or admission threshold.
        if (compositeRoom <= singleRoom || compositeRoom - singleRoom <= 1_000L)
            return NativeAlignmentPlan(NativeSeekOutcome.REQUESTED, listOf(bestSingle))
    }
    return NativeAlignmentPlan(NativeSeekOutcome.REQUESTED, composite, available)
}
