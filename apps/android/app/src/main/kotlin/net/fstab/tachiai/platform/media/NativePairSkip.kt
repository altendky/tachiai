package net.fstab.tachiai.platform.media

// fromMs and targetMs are window positions, dispatched in the same call that
// planned them. A live feed's window slides, so its target is also given on
// its content clock (liveClock), which settlement compares instead (#660).
internal data class NativeSkipTarget(val side: NativeMixedSide, val fromMs: Long, val targetMs: Long,
    val liveClock: Boolean = false, val targetClockMs: Long = targetMs)

// One movement shared by every feed, so a pair keeps its relative offset (#655).
// blockedBy names the feed that refused; limitedBy the feed whose start or end
// shortened (CLAMPED) or prevented (NO_CHANGE) the movement. limitedAtLive:
// that limit was a live feed's live point.
internal data class NativePairSkipPlan(
    val outcome: NativeSeekOutcome,
    val movementMs: Long = 0,
    val targets: List<NativeSkipTarget> = emptyList(),
    val blockedBy: NativeMixedSide? = null,
    val limitedBy: NativeMixedSide? = null,
    val limitedAtLive: Boolean = false,
)

// A forward skip stops this far before the nearest end, so skipping never ends
// a feed (an ended feed fails into Play again); playing to the end is unchanged.
internal const val SKIP_END_MARGIN_MS = 1_000L

// A backward skip on a live feed stops this far after the oldest point its
// window holds, so the target is not dropped from the sliding window while a
// pair skip settles (#660).
internal const val LIVE_SKIP_START_MARGIN_MS = 5_000L

// A live feed's skip range within its current window: from just after the
// oldest available point to the live point (the player's default position).
// Null when the window, its live point or a stable content clock is unknown.
internal data class NativeLiveSkipRange(val oldestMs: Long, val liveMs: Long, val positionMs: Long)

internal fun nativeLiveSkipRange(sample: NativeTimingSnapshot): NativeLiveSkipRange? {
    if (!(sample.live || sample.dynamic)) return null
    val position = sample.positionMs ?: return null
    val duration = sample.durationMs?.takeIf { it > 0 } ?: return null
    val live = sample.defaultPositionMs?.coerceAtMost(duration)?.takeIf { it > 0 } ?: return null
    if (sample.contentTimeMs == null) return null
    return NativeLiveSkipRange(minOf(LIVE_SKIP_START_MARGIN_MS, live), live, position)
}

// Skip and scrub for a single feed or a pair (#655 part 1), replays and live
// feeds alike (#660). Every listed feed moves by the same amount: the request
// is shortened so no feed leaves its range, and if any feed cannot move
// (advertisement, unsupported, unavailable) none moves. A replay's range is
// its whole length less the end margin; a live feed's is what its window
// holds, up to its live point, so a skip never builds delay and a forward
// skip to the right end returns the nearer feed to live, keeping the offset.
// Each target is checked with the shared Rust relative-seek rule. Unrelated
// clocks are never subtracted.
internal fun nativePairSkipPlan(feeds: Map<NativeMixedSide, NativeTimingSnapshot?>, deltaMs: Long): NativePairSkipPlan {
    if (feeds.isEmpty()) return NativePairSkipPlan(NativeSeekOutcome.UNAVAILABLE)
    val sides = NativeMixedSide.entries.filter { it in feeds }
    data class Feed(val side: NativeMixedSide, val sample: NativeTimingSnapshot, val position: Long,
        val start: Long, val end: Long, val live: Boolean)
    val ready = mutableListOf<Feed>()
    for (side in sides) {
        val sample = feeds[side]
        if (sample == null || sample.state != 3) return NativePairSkipPlan(NativeSeekOutcome.UNAVAILABLE, blockedBy = side)
        if (sample.playingAd) return NativePairSkipPlan(NativeSeekOutcome.AD_BLOCKED, blockedBy = side)
        if (!sample.seekable) return NativePairSkipPlan(NativeSeekOutcome.UNSUPPORTED, blockedBy = side)
        val position = sample.positionMs
        val duration = sample.durationMs
        if (position == null || duration == null || duration <= 0)
            return NativePairSkipPlan(NativeSeekOutcome.UNAVAILABLE, blockedBy = side)
        if (position !in 0..duration) return NativePairSkipPlan(NativeSeekOutcome.OUTSIDE_WINDOW, blockedBy = side)
        ready += if (sample.live || sample.dynamic) {
            // Without a live point or a content clock the target can't be
            // bounded or confirmed after the window slides.
            val range = nativeLiveSkipRange(sample) ?: return NativePairSkipPlan(NativeSeekOutcome.UNSUPPORTED, blockedBy = side)
            Feed(side, sample, position, range.oldestMs, range.liveMs, true)
        } else Feed(side, sample, position, 0, duration - SKIP_END_MARGIN_MS, false)
    }
    if (deltaMs == 0L) return NativePairSkipPlan(NativeSeekOutcome.NO_CHANGE)
    val forward = deltaMs > 0
    fun room(feed: Feed): Long = (if (forward) feed.end - feed.position else feed.position - feed.start).coerceAtLeast(0)
    val tightest = ready.minBy(::room)
    val available = room(tightest)
    val magnitude = if (deltaMs == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(deltaMs)
    val amount = minOf(magnitude, available)
    val limited = if (amount < magnitude) tightest.side else null
    val atLive = limited != null && forward && tightest.live
    if (amount == 0L) return NativePairSkipPlan(NativeSeekOutcome.NO_CHANGE, limitedBy = limited, limitedAtLive = atLive)
    val movement = if (forward) amount else -amount
    val targets = ready.map { feed ->
        val target = feed.position + movement // In range: bounded by this feed's own room.
        val checked = nativeRelativeSeekPlan(feed.sample, movement)
        if (checked.outcome != NativeSeekOutcome.REQUESTED || checked.targetMs != target)
            return NativePairSkipPlan(if (checked.outcome == NativeSeekOutcome.REQUESTED) NativeSeekOutcome.UNAVAILABLE
                else checked.outcome, blockedBy = feed.side)
        if (feed.live) {
            val clock = checkNotNull(feed.sample.contentTimeMs) + movement
            if (clock < 0) return NativePairSkipPlan(NativeSeekOutcome.OUTSIDE_WINDOW, blockedBy = feed.side)
            NativeSkipTarget(feed.side, feed.position, target, true, clock)
        } else NativeSkipTarget(feed.side, feed.position, target)
    }
    return NativePairSkipPlan(if (limited == null) NativeSeekOutcome.REQUESTED else NativeSeekOutcome.CLAMPED,
        movement, targets, limitedBy = limited, limitedAtLive = atLive)
}
