package net.fstab.tachiai.platform.media

// fromMs and targetMs are window positions, dispatched in the same call that
// planned them. A live feed's window slides, so its target is also given on
// its content clock (liveClock), which settlement compares instead (#660).
internal data class NativeSkipTarget(val side: NativeMixedSide, val fromMs: Long, val targetMs: Long,
    val liveClock: Boolean = false, val targetClockMs: Long = targetMs)

// One movement shared by every feed, so a pair keeps its relative offset (#655).
// blockedBy names the feed that refused; limitedBy the feed whose start or end
// shortened (CLAMPED) or prevented (NO_CHANGE) the movement. limitedAtLive:
// that limit was a live feed's live point. OUTSIDE_WINDOW with blockedBy: that
// feed is outside its window and this movement can't bring it back. Only Live
// can give up the shared movement (keepsOffset false): each live feed then
// moves to its own live point, and movementMs is the largest of those moves.
internal data class NativePairSkipPlan(
    val outcome: NativeSeekOutcome,
    val movementMs: Long = 0,
    val targets: List<NativeSkipTarget> = emptyList(),
    val blockedBy: NativeMixedSide? = null,
    val limitedBy: NativeMixedSide? = null,
    val limitedAtLive: Boolean = false,
    val keepsOffset: Boolean = true,
    // Why a pair refused after holding both feeds, naming the feed (#660).
    val reason: String? = null,
)

// A plan rechecked after holding both feeds differs from the one made while
// they played by the few milliseconds between the reads: a playing feed's
// position, and a live point taken from the continuous live offset, move on.
// More than this is a real change (#660).
internal const val HOLD_RECHECK_TOLERANCE_MS = 1_000L

// The feed whose planned move changed between the plan made before holding
// and the held recheck: moved in one and not the other, by a movement more
// than the tolerance apart, or with the offset kept in one and not the other.
// Null when they agree.
internal fun skipPlanDrift(initial: NativePairSkipPlan, fresh: NativePairSkipPlan): NativeMixedSide? {
    val before = initial.targets.associate { it.side to it.targetMs - it.fromMs }
    val after = fresh.targets.associate { it.side to it.targetMs - it.fromMs }
    for (side in NativeMixedSide.entries) {
        val planned = before[side]
        val held = after[side]
        if (planned == null && held == null) continue
        if (planned == null || held == null || kotlin.math.abs(planned - held) > HOLD_RECHECK_TOLERANCE_MS) return side
    }
    if (initial.keepsOffset != fresh.keepsOffset)
        return fresh.blockedBy ?: fresh.targets.firstOrNull()?.side ?: NativeMixedSide.A
    return null
}

// A forward skip stops this far before the nearest end, so skipping never ends
// a feed (an ended feed fails into Play again); playing to the end is unchanged.
internal const val SKIP_END_MARGIN_MS = 1_000L

// A backward skip on a live feed stops this far after the oldest point its
// window holds, so the target is not dropped from the sliding window while a
// pair skip settles (#660).
internal const val LIVE_SKIP_START_MARGIN_MS = 5_000L

// How far a live feed plays behind its live point. The live point is where
// the player aims to play: its target live offset behind the live edge. The
// player's live offset is continuous, so this doesn't jump as the window
// refreshes. Without a target, the window's default position stands in for
// the live point; that one moves in steps with each refresh (#660).
internal fun nativeBehindLivePointMs(sample: NativeTimingSnapshot): Long? {
    val offset = sample.liveOffsetMs
    val target = sample.targetLiveOffsetMs
    if (offset != null && target != null) return offset - target
    val position = sample.positionMs ?: return null
    val default = sample.defaultPositionMs ?: return null
    return default - position
}

// A live feed's skip range within its current window, as window positions:
// from just after the oldest available point to the live point. Null when
// the window, its live point or a stable content clock is unknown.
internal data class NativeLiveSkipRange(val oldestMs: Long, val liveMs: Long, val positionMs: Long)

internal fun nativeLiveSkipRange(sample: NativeTimingSnapshot): NativeLiveSkipRange? {
    if (!(sample.live || sample.dynamic)) return null
    val position = sample.positionMs ?: return null
    val duration = sample.durationMs?.takeIf { it > 0 } ?: return null
    val behind = nativeBehindLivePointMs(sample) ?: return null
    val live = (position + behind).coerceAtMost(duration).takeIf { it > 0 } ?: return null
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
//
// A live feed that slid behind its window (paused or stalled past its oldest
// point) can only move forward, and only far enough to land back inside it;
// a smaller shared movement is refused as OUTSIDE_WINDOW naming that feed.
internal fun nativePairSkipPlan(feeds: Map<NativeMixedSide, NativeTimingSnapshot?>, deltaMs: Long): NativePairSkipPlan =
    pairSkipPlan(feeds, deltaMs, toLive = false)

// Live (user, 2026-11-25): to the right end, so a pair moves together until
// the nearer feed is live, keeping its offset. When a feed is outside its
// window and the shared movement can't bring it back, Live still brings the
// view to live: each live feed moves to its own live point and the offset is
// not kept (#660). Replays stay where they are in that case.
internal fun nativeLivePlan(feeds: Map<NativeMixedSide, NativeTimingSnapshot?>): NativePairSkipPlan {
    val together = pairSkipPlan(feeds, Long.MAX_VALUE, toLive = true)
    if (together.outcome != NativeSeekOutcome.OUTSIDE_WINDOW) return together
    // Only a live feed that the shared movement can't bring back is repaired.
    val blocked = together.blockedBy?.let { feeds[it] }
    if (blocked == null || !(blocked.live || blocked.dynamic)) return together
    val targets = mutableListOf<NativeSkipTarget>()
    for (side in NativeMixedSide.entries.filter { it in feeds }) {
        val sample = feeds[side]
        if (sample == null || sample.state != 3) return NativePairSkipPlan(NativeSeekOutcome.UNAVAILABLE, blockedBy = side)
        if (sample.playingAd) return NativePairSkipPlan(NativeSeekOutcome.AD_BLOCKED, blockedBy = side)
        if (!(sample.live || sample.dynamic)) continue
        if (!sample.seekable) return NativePairSkipPlan(NativeSeekOutcome.UNSUPPORTED, blockedBy = side)
        val position = sample.positionMs ?: return NativePairSkipPlan(NativeSeekOutcome.UNAVAILABLE, blockedBy = side)
        val duration = sample.durationMs ?: return NativePairSkipPlan(NativeSeekOutcome.UNAVAILABLE, blockedBy = side)
        // Ahead of its window's end: nothing a seek within it can repair.
        if (position > duration) return NativePairSkipPlan(NativeSeekOutcome.OUTSIDE_WINDOW, blockedBy = side)
        val range = nativeLiveSkipRange(sample) ?: return NativePairSkipPlan(NativeSeekOutcome.UNSUPPORTED, blockedBy = side)
        val movement = range.liveMs - position
        if (movement <= 0) continue
        val checked = nativeSeekPlan(sample, range.liveMs)
        val clock = checkNotNull(sample.contentTimeMs) + movement // Known: the range requires it.
        if (checked.outcome != NativeSeekOutcome.REQUESTED || checked.targetMs != range.liveMs || clock < 0)
            return NativePairSkipPlan(NativeSeekOutcome.UNAVAILABLE, blockedBy = side)
        targets += NativeSkipTarget(side, position, range.liveMs, true, clock)
    }
    if (targets.isEmpty()) return together
    return NativePairSkipPlan(NativeSeekOutcome.REQUESTED, targets.maxOf { it.targetMs - it.fromMs }, targets,
        keepsOffset = feeds.size == 1)
}

private fun pairSkipPlan(feeds: Map<NativeMixedSide, NativeTimingSnapshot?>, deltaMs: Long,
    toLive: Boolean): NativePairSkipPlan {
    if (feeds.isEmpty()) return NativePairSkipPlan(NativeSeekOutcome.UNAVAILABLE)
    val sides = NativeMixedSide.entries.filter { it in feeds }
    data class Feed(val side: NativeMixedSide, val sample: NativeTimingSnapshot, val position: Long,
        val start: Long, val end: Long, val live: Boolean)
    val ready = mutableListOf<Feed>()
    val forward = deltaMs > 0
    for (side in sides) {
        val sample = feeds[side]
        if (sample == null || sample.state != 3) return NativePairSkipPlan(NativeSeekOutcome.UNAVAILABLE, blockedBy = side)
        if (sample.playingAd) return NativePairSkipPlan(NativeSeekOutcome.AD_BLOCKED, blockedBy = side)
        if (!sample.seekable) return NativePairSkipPlan(NativeSeekOutcome.UNSUPPORTED, blockedBy = side)
        val position = sample.positionMs
        val duration = sample.durationMs
        if (position == null || duration == null || duration <= 0)
            return NativePairSkipPlan(NativeSeekOutcome.UNAVAILABLE, blockedBy = side)
        val live = sample.live || sample.dynamic
        // Only a live feed behind its window, moving forward, can come back.
        if (position > duration || position < 0 && !(live && forward))
            return NativePairSkipPlan(NativeSeekOutcome.OUTSIDE_WINDOW, blockedBy = side)
        ready += if (live) {
            // Without a live point or a content clock the target can't be
            // bounded or confirmed after the window slides.
            val range = nativeLiveSkipRange(sample) ?: return NativePairSkipPlan(NativeSeekOutcome.UNSUPPORTED, blockedBy = side)
            Feed(side, sample, position, range.oldestMs, range.liveMs, true)
        } else Feed(side, sample, position, 0, duration - SKIP_END_MARGIN_MS, false)
    }
    if (deltaMs == 0L) return NativePairSkipPlan(NativeSeekOutcome.NO_CHANGE)
    fun room(feed: Feed): Long = (if (forward) feed.end - feed.position else feed.position - feed.start).coerceAtLeast(0)
    val tightest = ready.minBy(::room)
    val available = room(tightest)
    val magnitude = if (deltaMs == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(deltaMs)
    val amount = minOf(magnitude, available)
    // A feed behind its window must land at least at its oldest point.
    ready.filter { it.position < it.start && it.position < 0 }.maxByOrNull { it.start - it.position }?.let { behind ->
        if (amount < behind.start - behind.position)
            return NativePairSkipPlan(NativeSeekOutcome.OUTSIDE_WINDOW, blockedBy = behind.side)
    }
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
    // Live reports the movement it planned, never its unbounded request.
    val outcome = if (limited == null || toLive) NativeSeekOutcome.REQUESTED else NativeSeekOutcome.CLAMPED
    return NativePairSkipPlan(outcome, movement, targets, limitedBy = limited, limitedAtLive = atLive)
}
