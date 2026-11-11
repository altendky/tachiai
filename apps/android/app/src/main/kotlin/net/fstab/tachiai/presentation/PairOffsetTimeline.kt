package net.fstab.tachiai.presentation

import net.fstab.tachiai.core.eventMarkOffsetMs
import net.fstab.tachiai.platform.media.NativeTimelineConsistency
import net.fstab.tachiai.platform.media.NativeTimelineEvidence
import net.fstab.tachiai.platform.media.NativeTimingSnapshot
import net.fstab.tachiai.platform.media.compareNativeTimelineEvidence

internal data class PairOffsetTimelineCheck(val consistency: NativeTimelineConsistency, val correctionMs: Long? = null)

// This compares clock mappings, never the video/audio content or a displayed
// frame. The saved event can be outside the current seek window: only the new
// correction's targets will be checked by the normal native seek transaction.
internal fun checkPairOffsetTimeline(saved: PairOffsetCorrespondence?, a: NativeTimingSnapshot?,
    b: NativeTimingSnapshot?, aTimeline: NativeTimelineEvidence?, bTimeline: NativeTimelineEvidence?): PairOffsetTimelineCheck {
    if (saved == null || a == null || b == null) return PairOffsetTimelineCheck(NativeTimelineConsistency.UNKNOWN)
    if (saved.aLive != (a.live || a.dynamic) || saved.bLive != (b.live || b.dynamic))
        return PairOffsetTimelineCheck(NativeTimelineConsistency.CHANGED)
    val comparisons = listOf(compareNativeTimelineEvidence(saved.aTimeline, aTimeline),
        compareNativeTimelineEvidence(saved.bTimeline, bTimeline))
    if (NativeTimelineConsistency.CHANGED in comparisons) return PairOffsetTimelineCheck(NativeTimelineConsistency.CHANGED)
    if (comparisons.any { it != NativeTimelineConsistency.CONSISTENT } || a.state != 3 || b.state != 3 || a.playingAd || b.playingAd)
        return PairOffsetTimelineCheck(NativeTimelineConsistency.UNKNOWN)
    val ca = (if (saved.aLive) a.contentTimeMs else a.positionMs)?.takeIf { it >= 0 }
    val cb = (if (saved.bLive) b.contentTimeMs else b.positionMs)?.takeIf { it >= 0 }
    val gap = if (ca != null && cb != null) eventMarkOffsetMs(saved.aMs, ca, saved.bMs, cb) else null
    val correction = gap?.let { runCatching { Math.subtractExact(saved.residualMs, it) }.getOrNull() }
        ?.takeIf { it in -MAX_PAIR_OFFSET_MS..MAX_PAIR_OFFSET_MS }
    return if (correction == null) PairOffsetTimelineCheck(NativeTimelineConsistency.UNKNOWN)
        else PairOffsetTimelineCheck(NativeTimelineConsistency.CONSISTENT, correction)
}
