package net.fstab.tachiai.presentation

import net.fstab.tachiai.core.eventMarkOffsetMs
import net.fstab.tachiai.platform.media.NativeTimelineConsistency
import net.fstab.tachiai.platform.media.NativeTimelineEvidence
import net.fstab.tachiai.platform.media.NativeTimingSnapshot
import net.fstab.tachiai.platform.media.compareNativeTimelineEvidence

internal enum class PairOffsetDriftStatus { UNKNOWN, CHANGED, BLOCKED, MEASURING }

// Relative progress only. No provider clocks, source identifiers or pair tokens
// escape the observer. Positive drift means A progressed farther than B.
internal data class PairOffsetDriftSample(val status: PairOffsetDriftStatus, val driftMs: Long? = null,
    val baselineAgeMs: Long? = null, val segment: Long = 0)

// Diagnostic observation, never alignment authority or a reason to seek. Each
// content clock is compared only with its own baseline. Consistent manifest
// witnesses advance each sample while the baseline clocks remain fixed.
internal class PairOffsetDriftObserver {
    private class Point(val clockMs: Long, val live: Boolean)
    private class Baseline(val atMs: Long, val a: Point, val b: Point,
        var aWitness: NativeTimelineEvidence, var bWitness: NativeTimelineEvidence,
        var lastA: Long = a.clockMs, var lastB: Long = b.clockMs)
    private var owner: Any? = null
    private var revision: Long? = null
    private var timing: Any? = null
    private var baseline: Baseline? = null
    private var lastNow: Long? = null
    private var segment = 0L

    fun clear() {
        owner = null; revision = null; timing = null
        baseline = null; lastNow = null
    }

    private fun point(value: NativeTimingSnapshot?): Point? {
        if (value == null) return null
        val live = value.live || value.dynamic
        val clock = (if (live) value.contentTimeMs else value.positionMs)?.takeIf { it >= 0 } ?: return null
        return Point(clock, live)
    }
    private fun ready(value: NativeTimingSnapshot?) = value != null && value.state == 3 &&
        value.playing && value.playWhenReady && !value.playingAd && value.speed.isFinite() && value.speed > 0f

    fun observe(pairToken: Any?, intentRevision: Long, timingToken: Any?,
        a: NativeTimingSnapshot?, b: NativeTimingSnapshot?, aTimeline: NativeTimelineEvidence?,
        bTimeline: NativeTimelineEvidence?, nowMs: Long, blocked: Boolean = false): PairOffsetDriftSample {
        fun retired(status: PairOffsetDriftStatus): PairOffsetDriftSample {
            baseline = null
            return PairOffsetDriftSample(status, segment = segment)
        }
        if (pairToken == null) {
            clear()
            return PairOffsetDriftSample(PairOffsetDriftStatus.UNKNOWN, segment = segment)
        }
        if (nowMs < 0 || intentRevision < 0 || lastNow?.let { nowMs < it } == true)
            return retired(PairOffsetDriftStatus.UNKNOWN)
        lastNow = nowMs
        val changed = owner != null && (owner !== pairToken || revision != intentRevision || timing !== timingToken)
        if (owner !== pairToken || revision != intentRevision || timing !== timingToken) baseline = null
        owner = pairToken; revision = intentRevision; timing = timingToken
        if (blocked || !ready(a) || !ready(b)) return retired(PairOffsetDriftStatus.BLOCKED)
        val pa = point(a) ?: return retired(PairOffsetDriftStatus.UNKNOWN)
        val pb = point(b) ?: return retired(PairOffsetDriftStatus.UNKNOWN)
        if (aTimeline == null || bTimeline == null) return retired(PairOffsetDriftStatus.UNKNOWN)
        val saved = baseline
        if (saved == null) {
            if (segment == Long.MAX_VALUE) return retired(PairOffsetDriftStatus.UNKNOWN)
            segment++
            baseline = Baseline(nowMs, pa, pb, aTimeline, bTimeline)
            return PairOffsetDriftSample(if (changed) PairOffsetDriftStatus.CHANGED else PairOffsetDriftStatus.MEASURING,
                driftMs = 0L.takeUnless { changed }, baselineAgeMs = 0, segment = segment)
        }
        if (pa.live != saved.a.live || pb.live != saved.b.live || pa.clockMs < saved.lastA || pb.clockMs < saved.lastB)
            return retired(PairOffsetDriftStatus.CHANGED)
        val ca = compareNativeTimelineEvidence(saved.aWitness, aTimeline)
        val cb = compareNativeTimelineEvidence(saved.bWitness, bTimeline)
        if (ca == NativeTimelineConsistency.CHANGED || cb == NativeTimelineConsistency.CHANGED)
            return retired(PairOffsetDriftStatus.CHANGED)
        if (ca != NativeTimelineConsistency.CONSISTENT || cb != NativeTimelineConsistency.CONSISTENT)
            return retired(PairOffsetDriftStatus.UNKNOWN)
        val drift = eventMarkOffsetMs(saved.a.clockMs, pa.clockMs, saved.b.clockMs, pb.clockMs)
            ?: return retired(PairOffsetDriftStatus.UNKNOWN)
        if (drift !in -MAX_PAIR_OFFSET_MS..MAX_PAIR_OFFSET_MS) return retired(PairOffsetDriftStatus.UNKNOWN)
        saved.aWitness = aTimeline; saved.bWitness = bTimeline
        saved.lastA = pa.clockMs; saved.lastB = pb.clockMs
        return PairOffsetDriftSample(PairOffsetDriftStatus.MEASURING, drift, nowMs - saved.atMs, segment)
    }
}
