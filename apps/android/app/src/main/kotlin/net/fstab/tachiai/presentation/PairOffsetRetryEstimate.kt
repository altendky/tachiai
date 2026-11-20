package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.media.NativeTimingSnapshot

// Session-only fallback when provider timeline continuity cannot be verified.
// Live clocks use distance behind live and a monotonic sample time; unrelated
// provider timestamps are never subtracted. The last playing relationship
// already includes the applied offset, including a split seek. Prefer the
// player's continuous live offset: a live window's duration only changes when
// its playlist/manifest refreshes, so distance behind the window end saws by
// up to one refresh interval. Both samples of one feed must use the same kind.
// One feed's playing clock on a monotonic sample timeline: live uses distance
// behind live, replay its position. Null unless plainly playing at normal speed.
internal data class PairOffsetClockPoint(val clockMs: Long, val sliding: Boolean, val liveOffset: Boolean)

internal fun pairOffsetClockPoint(value: NativeTimingSnapshot?, nowMs: Long): PairOffsetClockPoint? {
    if (value == null || nowMs < 0 || value.state != 3 || value.playingAd ||
        !value.playing || !value.playWhenReady || value.speed != 1f) return null
    val position = value.positionMs?.takeIf { it >= 0 } ?: return null
    val sliding = value.live || value.dynamic
    val liveOffset = if (value.live) value.liveOffsetMs else null
    val clock = if (liveOffset != null) {
        runCatching { Math.subtractExact(nowMs, liveOffset) }.getOrNull() ?: return null
    } else if (sliding) {
        val duration = value.durationMs?.takeIf { it > 0 && position <= it } ?: return null
        runCatching { Math.subtractExact(nowMs, Math.subtractExact(duration, position)) }.getOrNull() ?: return null
    } else position
    return PairOffsetClockPoint(clock, sliding, liveOffset != null)
}

// The movement that restores a saved A/B relationship, in alignRelative terms.
internal fun pairOffsetRestoreDeltaMs(savedA: PairOffsetClockPoint, savedB: PairOffsetClockPoint,
    a: PairOffsetClockPoint, b: PairOffsetClockPoint): Long? {
    if (a.sliding != savedA.sliding || b.sliding != savedB.sliding ||
        a.liveOffset != savedA.liveOffset || b.liveOffset != savedB.liveOffset) return null
    return runCatching {
        Math.subtractExact(Math.subtractExact(b.clockMs, savedB.clockMs), Math.subtractExact(a.clockMs, savedA.clockMs))
    }.getOrNull()?.takeIf { it in -MAX_PAIR_OFFSET_MS..MAX_PAIR_OFFSET_MS }
}

internal class PairOffsetRetryEstimate {
    private data class Reference(val a: PairOffsetClockPoint, val b: PairOffsetClockPoint, val sampledMs: Long)
    private var reference: Reference? = null

    // An accepted timing edit supersedes this relationship even if the next
    // playing sample is delayed by pause, storage, failure or buffering.
    fun clear() { reference = null }

    private fun point(value: NativeTimingSnapshot?, nowMs: Long) = pairOffsetClockPoint(value, nowMs)

    fun remember(a: NativeTimingSnapshot?, b: NativeTimingSnapshot?, nowMs: Long) {
        val pa = point(a, nowMs) ?: return
        val pb = point(b, nowMs) ?: return
        reference = Reference(pa, pb, nowMs)
    }

    fun correctionMs(a: NativeTimingSnapshot?, b: NativeTimingSnapshot?, nowMs: Long): Long? {
        val saved = reference ?: return null
        if (nowMs < saved.sampledMs) return null
        val pa = point(a, nowMs) ?: return null
        val pb = point(b, nowMs) ?: return null
        return pairOffsetRestoreDeltaMs(saved.a, saved.b, pa, pb)
    }
}
