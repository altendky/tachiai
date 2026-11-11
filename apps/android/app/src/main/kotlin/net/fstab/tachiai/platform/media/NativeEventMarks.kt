package net.fstab.tachiai.platform.media

import net.fstab.tachiai.core.eventMarkOffsetMs
import net.fstab.tachiai.presentation.PairOffsetCorrespondence

// Session-local observations of the same user-identified event. The viewer
// clears these when either member changes. No mark seeks, pauses or saves media.
internal class NativeEventMarks {
    private data class Clock(val milliseconds: Long, val sliding: Boolean, val timeline: NativeTimelineEvidence? = null)
    private var a: Clock? = null
    private var b: Clock? = null
    val markedA get() = a != null
    val markedB get() = b != null
    val complete get() = markedA && markedB

    fun clear() { a = null; b = null }

    private fun clock(snapshot: NativeTimingSnapshot?): Clock? {
        if (snapshot == null || snapshot.state != 3 || snapshot.playingAd) return null
        val sliding = snapshot.live || snapshot.dynamic
        val value = if (sliding) snapshot.contentTimeMs else snapshot.positionMs
        return value?.takeIf { it >= 0 }?.let { Clock(it, sliding) }
    }

    // Advance a known continuity witness without changing the event clock. An
    // unknown comparison never upgrades an old mark with new timeline evidence.
    private fun observeTimelines(aTimeline: NativeTimelineEvidence?, bTimeline: NativeTimelineEvidence?) {
        val aConsistency = a?.let { compareNativeTimelineEvidence(it.timeline, aTimeline) }
        val bConsistency = b?.let { compareNativeTimelineEvidence(it.timeline, bTimeline) }
        if (aConsistency == NativeTimelineConsistency.CHANGED || bConsistency == NativeTimelineConsistency.CHANGED) {
            clear(); return
        }
        if (aConsistency == NativeTimelineConsistency.CONSISTENT) a = a?.copy(timeline = aTimeline)
        if (bConsistency == NativeTimelineConsistency.CONSISTENT) b = b?.copy(timeline = bTimeline)
    }

    fun canMark(sa: NativeTimingSnapshot?, sb: NativeTimingSnapshot?,
        aTimeline: NativeTimelineEvidence? = null, bTimeline: NativeTimelineEvidence? = null): Boolean {
        observeTimelines(aTimeline, bTimeline)
        return clock(sa) != null && clock(sb) != null
    }

    fun mark(side: NativeMixedSide, sa: NativeTimingSnapshot?, sb: NativeTimingSnapshot?,
        aTimeline: NativeTimelineEvidence? = null, bTimeline: NativeTimelineEvidence? = null): Boolean {
        if (if (side == NativeMixedSide.A) markedA else markedB) {
            clear(); return true
        }
        val ca = clock(sa) ?: run { observeTimelines(aTimeline, bTimeline); return false }
        val cb = clock(sb) ?: run { observeTimelines(aTimeline, bTimeline); return false }
        observeTimelines(aTimeline, bTimeline)
        if (a?.sliding?.let { it != ca.sliding } == true || b?.sliding?.let { it != cb.sliding } == true)
            clear()
        if (side == NativeMixedSide.A) a = ca.copy(timeline = aTimeline) else b = cb.copy(timeline = bTimeline)
        return true
    }

    fun correspondence(): PairOffsetCorrespondence? {
        val ma = a ?: return null
        val mb = b ?: return null
        return PairOffsetCorrespondence(ma.milliseconds, mb.milliseconds, ma.sliding, mb.sliding,
            ma.timeline, mb.timeline, residualMs = 0L)
    }

    // Each subtraction uses one feed's own clock. The two providers' origins
    // are never compared. Positive means A is ahead of B for the marked event.
    fun offsetMs(sa: NativeTimingSnapshot?, sb: NativeTimingSnapshot?,
        aTimeline: NativeTimelineEvidence? = null, bTimeline: NativeTimelineEvidence? = null): Long? {
        observeTimelines(aTimeline, bTimeline)
        val ca = clock(sa) ?: return null
        val cb = clock(sb) ?: return null
        if (a?.sliding?.let { it != ca.sliding } == true || b?.sliding?.let { it != cb.sliding } == true) {
            clear(); return null
        }
        val ma = a ?: return null
        val mb = b ?: return null
        return eventMarkOffsetMs(ma.milliseconds, ca.milliseconds, mb.milliseconds, cb.milliseconds)
    }
}
