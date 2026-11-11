package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.media.NativeTimingSnapshot

// Session-only fallback when provider timeline continuity cannot be verified.
// Live clocks use distance behind their own window end and a monotonic sample
// time; unrelated provider timestamps are never subtracted. The last playing
// relationship already includes the applied offset, including a split seek.
internal class PairOffsetRetryEstimate {
    private data class Point(val clockMs: Long, val sliding: Boolean)
    private data class Reference(val a: Point, val b: Point, val sampledMs: Long)
    private var reference: Reference? = null

    // An accepted timing edit supersedes this relationship even if the next
    // playing sample is delayed by pause, storage, failure or buffering.
    fun clear() { reference = null }

    private fun point(value: NativeTimingSnapshot?, nowMs: Long): Point? {
        if (value == null || nowMs < 0 || value.state != 3 || value.playingAd ||
            !value.playing || !value.playWhenReady || value.speed != 1f) return null
        val position = value.positionMs?.takeIf { it >= 0 } ?: return null
        val sliding = value.live || value.dynamic
        val clock = if (sliding) {
            val duration = value.durationMs?.takeIf { it > 0 && position <= it } ?: return null
            runCatching { Math.subtractExact(nowMs, Math.subtractExact(duration, position)) }.getOrNull() ?: return null
        } else position
        return Point(clock, sliding)
    }

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
        if (pa.sliding != saved.a.sliding || pb.sliding != saved.b.sliding) return null
        return runCatching {
            Math.subtractExact(Math.subtractExact(pb.clockMs, saved.b.clockMs),
                Math.subtractExact(pa.clockMs, saved.a.clockMs))
        }.getOrNull()?.takeIf { it in -MAX_PAIR_OFFSET_MS..MAX_PAIR_OFFSET_MS }
    }
}
