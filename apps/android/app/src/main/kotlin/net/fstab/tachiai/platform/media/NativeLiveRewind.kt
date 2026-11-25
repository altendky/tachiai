package net.fstab.tachiai.platform.media

// #618: how much history a live feed's in-memory rewind buffer keeps. The rule
// is the same for every provider; only the recorder is format specific.
//
// - Floor: the provider's own window plus 2 minutes, so skipping back is instant.
// - Growth: the feed's current distance behind live plus a 30 s margin, so a
//   feed that is held or offset further behind keeps what it needs.
// - Cap: a 6:00 delay, or 256 MB per feed, whichever comes first.
//
// The span is recomputed on every manifest or playlist refresh, and ordinary
// oldest-first eviction shrinks it as soon as the delay drops.
internal const val LIVE_REWIND_FLOOR_BEYOND_WINDOW_MS = 120_000L
internal const val LIVE_REWIND_MARGIN_MS = 30_000L
internal const val LIVE_DELAY_LIMIT_MS = 360_000L
internal const val LIVE_REWIND_MAX_SPAN_MS = LIVE_DELAY_LIMIT_MS + LIVE_REWIND_MARGIN_MS
internal const val LIVE_REWIND_MAX_BYTES = 256L * 1024 * 1024
internal const val LIVE_DELAY_LIMIT_MESSAGE = "Delay is limited to 6:00"

// How far behind the newest recorded media a feed keeps history.
internal fun liveRewindSpanMs(providerWindowMs: Long, behindLiveMs: Long): Long {
    val window = providerWindowMs.coerceIn(0L, LIVE_REWIND_MAX_SPAN_MS * 16)
    val behind = behindLiveMs.coerceIn(0L, LIVE_REWIND_MAX_SPAN_MS * 16)
    val wanted = maxOf(window + LIVE_REWIND_FLOOR_BEYOND_WINDOW_MS, behind + LIVE_REWIND_MARGIN_MS)
    return maxOf(window, minOf(wanted, LIVE_REWIND_MAX_SPAN_MS))
}

// The one live value that sizes a feed's buffer: the player's continuous
// distance behind live, else its distance behind the live window's end.
internal fun nativeBehindLiveMs(liveOffsetMs: Long?, durationMs: Long?, positionMs: Long?): Long? {
    liveOffsetMs?.takeIf { it >= 0 }?.let { return it }
    if (durationMs == null || positionMs == null || durationMs <= 0) return null
    return (durationMs - positionMs).takeIf { it >= 0 }
}

// How far a live feed is held behind its own live point: its distance behind
// live less the player's normal live latency (window end to default position).
internal fun nativeLiveDelayMs(sample: NativeTimingSnapshot): Long? {
    if (!sample.live && !sample.dynamic) return null
    val behind = nativeBehindLiveMs(sample.liveOffsetMs, sample.durationMs, sample.positionMs) ?: return null
    val duration = sample.durationMs
    val default = sample.defaultPositionMs
    val latency = if (duration != null && default != null) (duration - default).coerceAtLeast(0) else 0L
    return (behind - latency).coerceAtLeast(0)
}

// How much further back a live feed may move before passing the delay limit;
// null for a replay, which has no limit.
internal fun nativeLiveDelayRoomMs(sample: NativeTimingSnapshot): Long? {
    if (!sample.live && !sample.dynamic) return null
    val delay = nativeLiveDelayMs(sample) ?: return null
    return (LIVE_DELAY_LIMIT_MS - delay).coerceAtLeast(0)
}

// True when moving this live feed by movementMs (negative = back) would hold
// it further behind live than the delay limit.
internal fun nativeLiveDelayExceeded(sample: NativeTimingSnapshot, movementMs: Long): Boolean {
    if (movementMs >= 0) return false
    val room = nativeLiveDelayRoomMs(sample) ?: return false
    return movementMs == Long.MIN_VALUE || -movementMs > room
}

// A paused live feed that fell past its oldest recorded point resumes this far
// inside the window, so the next eviction does not immediately pass it again.
internal const val LIVE_OLDEST_RESUME_MARGIN_MS = 5_000L

internal data class NativeOldestResumeTarget(val side: NativeMixedSide, val targetMs: Long)
internal data class NativeOldestResumePlan(val targets: List<NativeOldestResumeTarget>, val keepsOffset: Boolean,
    val stale: Set<NativeMixedSide>)

// A live feed held before its window start, whose window can still be sought.
private fun behindOldest(sample: NativeTimingSnapshot?): Boolean {
    if (sample == null || !(sample.live || sample.dynamic) || sample.state != 3 || sample.playingAd || !sample.seekable) return false
    val position = sample.positionMs ?: return false
    val duration = sample.durationMs ?: return false
    return duration > 0 && position < 0
}

// #618: Play on a paused live feed that fell past its oldest recorded point
// resumes from that point instead of failing. Every listed feed moves forward
// by the same amount when all of them can, so a pair keeps its offset;
// otherwise only the stale feeds move. Null when some feed is outside its
// window in a way this cannot repair (unknown, or ahead of its end).
internal fun nativeOldestResumePlan(feeds: Map<NativeMixedSide, NativeTimingSnapshot?>): NativeOldestResumePlan? {
    val stale = feeds.filterValues(::behindOldest).keys
    if (stale.isEmpty()) return null
    // Any other feed outside its window is not something this can repair.
    for ((side, sample) in feeds) {
        if (side in stale || sample == null || !(sample.live || sample.dynamic)) continue
        val position = sample.positionMs ?: return null
        val duration = sample.durationMs ?: return null
        if (duration <= 0 || position !in 0..duration) return null
    }
    fun oldestTarget(sample: NativeTimingSnapshot): Long =
        minOf(LIVE_OLDEST_RESUME_MARGIN_MS, checkNotNull(sample.durationMs) / 2)
    val movement = stale.maxOf { side ->
        val sample = checkNotNull(feeds[side])
        oldestTarget(sample) - checkNotNull(sample.positionMs)
    }
    val together = feeds.map { (side, sample) ->
        val value = sample ?: return@map null
        val position = value.positionMs ?: return@map null
        val duration = value.durationMs ?: return@map null
        val target = try { Math.addExact(position, movement) } catch (_: ArithmeticException) { return@map null }
        val plan = nativeSeekPlan(value, target)
        if (target !in 0..duration || plan.outcome != NativeSeekOutcome.REQUESTED || plan.targetMs != target) null
        else NativeOldestResumeTarget(side, target)
    }
    if (together.all { it != null }) return NativeOldestResumePlan(together.filterNotNull(), true, stale)
    val alone = stale.map { side ->
        val sample = checkNotNull(feeds[side])
        val target = oldestTarget(sample)
        val plan = nativeSeekPlan(sample, target)
        if (plan.outcome != NativeSeekOutcome.REQUESTED || plan.targetMs != target) return null
        NativeOldestResumeTarget(side, target)
    }
    return NativeOldestResumePlan(alone, feeds.size == 1, stale)
}

internal fun nativeOldestResumeNotice(plan: NativeOldestResumePlan, pair: Boolean): String {
    val names = NativeMixedSide.entries.filter { it in plan.stale }
    val subject = when {
        !pair -> "The feed"
        names.size == 2 -> "Both feeds"
        else -> names.single().name
    }
    val offset = if (pair && !plan.keepsOffset) "; the offset was not kept" else ""
    return "$subject passed the oldest recorded point while paused, so playback resumed there$offset."
}
