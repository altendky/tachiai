package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.media.NativeTimingSnapshot

// Recovers an applied pair offset after a feed moved on its own. Example: on
// the SHIELD, ABEMA's player repositioned 16 s toward live at the end of a
// commercial pod, with no user action and no timing settlement, losing the
// alignment.
//
// After the offset settles, the first steady playing sample freezes the A/B
// relationship (attempt history lasts for the guard, one viewer session). Any change of pair, user playback intent or timing settlement
// (every seek, Mark, nudge, Pause, Play or Back to live) discards it.
//
// A correction is recommended only when all of these hold:
// - both clocks advanced in step with real time since the previous sample, so
//   a stalled or frozen position (as during ABEMA's breaks) never triggers it;
// - the relationship has moved beyond the threshold;
// - it stayed there for the sustain time.
//
// Attempts are spaced by a cooldown and capped within a rolling window, so the
// guard cannot loop. It is a heuristic over player clocks, not proof of
// content synchronization.
internal class PairOffsetDeviationGuard(
    private val thresholdMs: Long = 3_000,
    private val coarseThresholdMs: Long = 10_000,
    private val sustainMs: Long = 2_000,
    private val steadyToleranceMs: Long = 400,
    private val steadyStepMs: Long = 1_000,
    private val maxSampleGapMs: Long = 3_000,
    private val cooldownMs: Long = 15_000,
    private val maxAttempts: Int = 3,
    private val attemptWindowMs: Long = 600_000,
) {
    private data class Sample(val a: PairOffsetClockPoint, val b: PairOffsetClockPoint, val atMs: Long)
    private var pair: Any? = null
    private var intentRevision = -1L
    private var settlementRevision = -1L
    private var reference: Sample? = null
    // The last step sample at least steadyStepMs old, and whether the step
    // ending there kept both clocks in time with the monotonic clock.
    private var previous: Sample? = null
    private var steady = false
    private var deviatingSinceMs: Long? = null
    private var lastAttemptMs: Long? = null
    private val attempts = ArrayDeque<Long>()

    // True once the rolling attempt cap is reached.
    val exhausted get() = attempts.size >= maxAttempts

    private fun forget() { reference = null; previous = null; steady = false; deviatingSinceMs = null }

    // Returns the alignRelative delta that restores the frozen relationship,
    // or null. active is false whenever the offset is not settled and applied
    // or playback is not plainly running.
    fun observe(pair: Any?, intentRevision: Long, settlementRevision: Long, active: Boolean,
        a: NativeTimingSnapshot?, b: NativeTimingSnapshot?, nowMs: Long): Long? {
        if (pair !== this.pair || intentRevision != this.intentRevision || settlementRevision != this.settlementRevision) {
            this.pair = pair; this.intentRevision = intentRevision; this.settlementRevision = settlementRevision
            forget()
        }
        if (pair == null || !active || nowMs < 0) { forget(); return null }
        val pa = pairOffsetClockPoint(a, nowMs)
        val pb = pairOffsetClockPoint(b, nowMs)
        if (pa == null || pb == null) { previous = null; steady = false; deviatingSinceMs = null; return null }
        val current = Sample(pa, pb, nowMs)
        val last = previous
        if (last == null) { previous = current; steady = false }
        else if (nowMs - last.atMs >= steadyStepMs) {
            val elapsed = nowMs - last.atMs
            steady = elapsed <= maxSampleGapMs && inStep(last.a, pa, elapsed) && inStep(last.b, pb, elapsed)
            previous = current
        }
        val saved = reference
        // The relationship is frozen only from a steady sample.
        if (saved == null) { if (steady) reference = current; return null }
        val delta = pairOffsetRestoreDeltaMs(saved.a, saved.b, pa, pb)
        // A clock kind can flip briefly at a boundary; keep the frozen
        // relationship and wait for comparable samples again.
        if (delta == null) { previous = null; steady = false; deviatingSinceMs = null; return null }
        val threshold = if (exact(pa) && exact(pb)) thresholdMs else coarseThresholdMs
        if (!steady || kotlin.math.abs(delta) < threshold) { deviatingSinceMs = null; return null }
        val since = deviatingSinceMs ?: nowMs.also { deviatingSinceMs = it }
        if (nowMs - since < sustainMs) return null
        if (lastAttemptMs?.let { nowMs - it < cooldownMs } == true) return null
        while (attempts.firstOrNull()?.let { nowMs - it >= attemptWindowMs } == true) attempts.removeFirst()
        if (attempts.size >= maxAttempts) return null
        return delta
    }

    // The caller dispatched a recommended correction; only dispatched attempts
    // count toward the cooldown and cap.
    fun attempted(nowMs: Long) {
        attempts.addLast(nowMs); lastAttemptMs = nowMs; deviatingSinceMs = null
    }

    // A replay position or a live offset is smooth; a live window's
    // duration-minus-position saws by a refresh interval.
    private fun exact(point: PairOffsetClockPoint) = !point.sliding || point.liveOffset

    private fun inStep(before: PairOffsetClockPoint, after: PairOffsetClockPoint, elapsedMs: Long): Boolean {
        val progressed = runCatching { Math.subtractExact(after.clockMs, before.clockMs) }.getOrNull() ?: return false
        val tolerance = if (exact(after)) steadyToleranceMs else coarseThresholdMs
        return kotlin.math.abs(progressed - elapsedMs) <= tolerance
    }
}
