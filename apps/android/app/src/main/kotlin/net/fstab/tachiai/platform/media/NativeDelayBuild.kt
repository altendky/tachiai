package net.fstab.tachiai.platform.media

// Building delay (#618, user decision 5 of 2026-11-25). A Timing change or a
// saved-offset restore that needs a live feed further behind live than its
// recorded history reaches, but within the delay limit, first moves that feed
// back as far as its history allows, then holds it on that frame while the
// other feed and its audio keep playing. The held feed's rewind buffer grows
// while it is held (#697), so once the other feed has played on by the
// shortfall the held feed is exactly where it needs to be, and it resumes.

internal enum class NativeDelayBuildOutcome {
    // Hold `side` until the other feed has played on by -movementMs.
    BUILD,
    // The feed to hold already has the history, so the shortfall is not
    // recorded history; the ordinary refusal stands.
    NOT_NEEDED,
    // A delay builds only while both feeds play.
    NOT_PLAYING,
    // Past the delay limit; refused with LIVE_DELAY_LIMIT_MESSAGE.
    DELAY_LIMITED,
    UNAVAILABLE,
}

// side: the feed held back; movementMs: how far it moves back relative to the
// other feed (negative); deltaMs: the request (positive = A later relative to B).
internal data class NativeDelayBuildPlan(val outcome: NativeDelayBuildOutcome, val side: NativeMixedSide? = null,
    val movementMs: Long = 0, val deltaMs: Long = 0)

// Whether a relative request that found no full step can be built by holding
// the feed that moves back. Positive delta = A later relative to B, so B is
// held; negative, A is held. Only a live feed can be held to build a delay:
// a replay has its whole history already.
internal fun nativeDelayBuildPlan(a: NativeTimingSnapshot?, b: NativeTimingSnapshot?, deltaMs: Long): NativeDelayBuildPlan {
    if (deltaMs == 0L || deltaMs == Long.MIN_VALUE) return NativeDelayBuildPlan(NativeDelayBuildOutcome.UNAVAILABLE)
    if (a == null || b == null || a.state != 3 || b.state != 3 || a.playingAd || b.playingAd)
        return NativeDelayBuildPlan(NativeDelayBuildOutcome.UNAVAILABLE)
    val side = if (deltaMs > 0) NativeMixedSide.B else NativeMixedSide.A
    val held = if (side == NativeMixedSide.A) a else b
    val peer = if (side == NativeMixedSide.A) b else a
    val movement = -kotlin.math.abs(deltaMs)
    if (!(held.live || held.dynamic) || !held.seekable) return NativeDelayBuildPlan(NativeDelayBuildOutcome.UNAVAILABLE)
    val position = held.positionMs ?: return NativeDelayBuildPlan(NativeDelayBuildOutcome.UNAVAILABLE)
    val duration = held.durationMs ?: return NativeDelayBuildPlan(NativeDelayBuildOutcome.UNAVAILABLE)
    if (duration <= 0 || position !in 0..duration || held.contentTimeMs == null)
        return NativeDelayBuildPlan(NativeDelayBuildOutcome.UNAVAILABLE)
    val peerLive = peer.live || peer.dynamic
    if ((if (peerLive) peer.contentTimeMs else peer.positionMs) == null)
        return NativeDelayBuildPlan(NativeDelayBuildOutcome.UNAVAILABLE)
    // Recorded history already reaches the target: not a history shortfall.
    if (position + movement >= 0) return NativeDelayBuildPlan(NativeDelayBuildOutcome.NOT_NEEDED)
    if (nativeLiveDelayExceeded(held, movement)) return NativeDelayBuildPlan(NativeDelayBuildOutcome.DELAY_LIMITED)
    if (!a.playWhenReady || !b.playWhenReady || !a.playing || !b.playing)
        return NativeDelayBuildPlan(NativeDelayBuildOutcome.NOT_PLAYING)
    return NativeDelayBuildPlan(NativeDelayBuildOutcome.BUILD, side, movement, deltaMs)
}

// A held frame stays this far inside the oldest point of its window. The
// oldest part of a window can still be the provider's own segments, which
// slide away unless the recorder caches each before it leaves; the recorder
// starts up to 30 s (or half the window) ahead. At startup a frame held only
// 5 s (the skip margin) inside was overtaken within a second on the emulator,
// so a held frame keeps the rewind buffer's own 30 s margin (#707).
internal const val DELAY_BUILD_HOLD_MARGIN_MS = LIVE_REWIND_MARGIN_MS

// If the window slides past the held frame anyway, the frame moves back
// inside, which only lengthens the build, up to this many times.
internal const val DELAY_BUILD_REANCHOR_LIMIT = 3

// How far back the feed to delay moves at once, using the history it already
// has: back to DELAY_BUILD_HOLD_MARGIN_MS inside its oldest point, and never
// the whole movement (that would be an ordinary shift).
internal fun nativeDelayBuildRecordedMs(positionMs: Long?, movementMs: Long): Long {
    if (positionMs == null || movementMs >= 0 || movementMs == Long.MIN_VALUE) return 0
    return (positionMs - DELAY_BUILD_HOLD_MARGIN_MS).coerceIn(0, -movementMs - 1)
}

// How much further back the held feed still has to go, from the live
// snapshots: the requested movement less how far the other feed has played on
// relative to the held one since the build began (a jump back into recorded
// history counts as built). Negative once past the target, as after a Timing
// step that reduced the build.
internal fun nativeDelayBuildNeedMs(movementMs: Long, peerAdvanceMs: Long, heldAdvanceMs: Long): Long {
    val built = try { Math.subtractExact(peerAdvanceMs, heldAdvanceMs) } catch (_: ArithmeticException) { return 0 }
    val wanted = if (movementMs == Long.MIN_VALUE) Long.MAX_VALUE else -movementMs
    return try { Math.subtractExact(wanted, built) } catch (_: ArithmeticException) { 0 }
}

// The countdown: what is still needed, never below zero. It falls by about a
// second per second while the other feed plays and pauses while that feed
// buffers.
internal fun nativeDelayBuildRemainingMs(movementMs: Long, peerAdvanceMs: Long, heldAdvanceMs: Long): Long =
    nativeDelayBuildNeedMs(movementMs, peerAdvanceMs, heldAdvanceMs).coerceAtLeast(0)

// A Timing step while a delay builds (user, 2026-11-25).
internal enum class NativeDelayBuildAdjustment { ADDED, REDUCED, DELAY_LIMITED, UNAVAILABLE, NOT_BUILDING }

// Beyond the requested amount, how long the other feed may stall (buffering,
// advertisements) before the build gives up.
internal const val DELAY_BUILD_SLACK_MS = 60_000L

// The held feed resumes by a seek, which also resets Media3's live target
// offset to the built delay; resuming without one would let live speed
// control play it faster until the delay was gone. A resume that has not
// reached playback by then fails.
internal const val DELAY_BUILD_RESUME_LIMIT_MS = 8_000L

// What the viewer shows in the held feed's pane, counting down.
internal data class NativeDelayBuildState(val side: NativeMixedSide, val remainingMs: Long, val totalMs: Long)

// How a build ended. completed: the offset was applied on resume. restored:
// a canceled build moved the held feed back to its previous relationship, so
// the applied offset is unchanged; otherwise it is unknown.
internal data class NativeDelayBuildEnd(val side: NativeMixedSide, val completed: Boolean, val restored: Boolean,
    val canceled: Boolean, val message: String)

internal const val DELAY_BUILD_CANCELED = "Building delay canceled"
internal const val DELAY_BUILD_NEEDS_PLAYBACK = "That needs more recorded history; a delay builds only while both feeds play."

// "1:30", whole seconds rounded up so it reaches 0:00 only as the feed resumes.
internal fun formatDelayBuildRemaining(remainingMs: Long): String {
    val seconds = (remainingMs.coerceAtLeast(0) + 999) / 1_000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

// "Building delay… 1:30"
internal fun delayBuildLabel(remainingMs: Long): String = "Building delay… ${formatDelayBuildRemaining(remainingMs)}"
