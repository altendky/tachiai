package net.fstab.tachiai.platform.media

internal data class NativeSkipTarget(val side: NativeMixedSide, val fromMs: Long, val targetMs: Long)

// One movement shared by every feed, so a pair keeps its relative offset (#655).
// blockedBy names the feed that refused; limitedBy the feed whose start or end
// shortened (CLAMPED) or prevented (NO_CHANGE) the movement.
internal data class NativePairSkipPlan(
    val outcome: NativeSeekOutcome,
    val movementMs: Long = 0,
    val targets: List<NativeSkipTarget> = emptyList(),
    val blockedBy: NativeMixedSide? = null,
    val limitedBy: NativeMixedSide? = null,
)

// A forward skip stops this far before the nearest end, so skipping never ends
// a feed (an ended feed fails into Play again); playing to the end is unchanged.
internal const val SKIP_END_MARGIN_MS = 1_000L

// Replay skip and scrub for a single feed or a pair (#655 part 1). Every listed
// feed moves by the same amount: the request is shortened so no feed leaves its
// range, and if any feed cannot move (advertisement, live, unsupported,
// unavailable) none moves. Live feeds are refused here; rewinding live
// buffers is #660. Each target is checked with the shared Rust relative-seek
// rule. Unrelated clocks are never subtracted.
internal fun nativePairSkipPlan(feeds: Map<NativeMixedSide, NativeTimingSnapshot?>, deltaMs: Long): NativePairSkipPlan {
    if (feeds.isEmpty()) return NativePairSkipPlan(NativeSeekOutcome.UNAVAILABLE)
    val sides = NativeMixedSide.entries.filter { it in feeds }
    data class Feed(val side: NativeMixedSide, val sample: NativeTimingSnapshot, val position: Long, val duration: Long)
    val ready = mutableListOf<Feed>()
    for (side in sides) {
        val sample = feeds[side]
        if (sample == null || sample.state != 3) return NativePairSkipPlan(NativeSeekOutcome.UNAVAILABLE, blockedBy = side)
        if (sample.playingAd) return NativePairSkipPlan(NativeSeekOutcome.AD_BLOCKED, blockedBy = side)
        if (sample.live || sample.dynamic || !sample.seekable)
            return NativePairSkipPlan(NativeSeekOutcome.UNSUPPORTED, blockedBy = side)
        val position = sample.positionMs
        val duration = sample.durationMs
        if (position == null || duration == null || duration <= 0)
            return NativePairSkipPlan(NativeSeekOutcome.UNAVAILABLE, blockedBy = side)
        if (position !in 0..duration) return NativePairSkipPlan(NativeSeekOutcome.OUTSIDE_WINDOW, blockedBy = side)
        ready += Feed(side, sample, position, duration)
    }
    if (deltaMs == 0L) return NativePairSkipPlan(NativeSeekOutcome.NO_CHANGE)
    val forward = deltaMs > 0
    fun room(feed: Feed): Long = if (forward) (feed.duration - SKIP_END_MARGIN_MS - feed.position).coerceAtLeast(0)
        else feed.position
    val tightest = ready.minBy(::room)
    val available = room(tightest)
    val magnitude = if (deltaMs == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(deltaMs)
    val amount = minOf(magnitude, available)
    val limited = if (amount < magnitude) tightest.side else null
    if (amount == 0L) return NativePairSkipPlan(NativeSeekOutcome.NO_CHANGE, limitedBy = limited)
    val movement = if (forward) amount else -amount
    val targets = ready.map { feed ->
        val target = feed.position + movement // In range: bounded by this feed's own room.
        val checked = nativeRelativeSeekPlan(feed.sample, movement)
        if (checked.outcome != NativeSeekOutcome.REQUESTED || checked.targetMs != target)
            return NativePairSkipPlan(if (checked.outcome == NativeSeekOutcome.REQUESTED) NativeSeekOutcome.UNAVAILABLE
                else checked.outcome, blockedBy = feed.side)
        NativeSkipTarget(feed.side, feed.position, target)
    }
    return NativePairSkipPlan(if (limited == null) NativeSeekOutcome.REQUESTED else NativeSeekOutcome.CLAMPED,
        movement, targets, limitedBy = limited)
}
