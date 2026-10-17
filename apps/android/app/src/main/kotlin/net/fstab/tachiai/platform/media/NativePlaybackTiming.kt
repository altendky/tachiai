package net.fstab.tachiai.platform.media

import net.fstab.tachiai.core.SeekContext as CoreSeekContext
import net.fstab.tachiai.core.SeekPlan as CoreSeekPlan
import net.fstab.tachiai.core.contentTimeMs as coreContentTimeMs
import net.fstab.tachiai.core.planAbsoluteSeek
import net.fstab.tachiai.core.planLiveDefault
import net.fstab.tachiai.core.planRelativeSeek

// Only normalized clocks/capabilities: no media item, URI, track or provider data.
internal data class NativeTimingSnapshot(
    val positionMs: Long?,
    val durationMs: Long?,
    val bufferedMs: Long?,
    val liveOffsetMs: Long?,
    val windowStartMs: Long?,
    val defaultPositionMs: Long?,
    val live: Boolean,
    val dynamic: Boolean,
    val seekable: Boolean,
    val canSeekDefault: Boolean,
    val playingAd: Boolean,
    val playing: Boolean,
    val playWhenReady: Boolean,
    val state: Int,
    val speed: Float,
) {
    // Live position alone is relative to a sliding window, not a stable clock.
    val contentTimeMs: Long? get() = coreContentTimeMs(coreContext())
}

internal enum class NativeSeekOutcome { REQUESTED, CLAMPED, NO_CHANGE, UNAVAILABLE, UNSUPPORTED, AD_BLOCKED, OUTSIDE_WINDOW }
internal data class NativeSeekPlan(val outcome: NativeSeekOutcome, val targetMs: Long? = null)

// Keep platform snapshots and dispatch separate from the reusable Rust rules.
// Generated bindings/library checksums are mandatory; there is no Kotlin fallback.
private fun NativeTimingSnapshot.coreContext() = CoreSeekContext(
    positionMs = positionMs,
    durationMs = durationMs,
    windowStartMs = windowStartMs,
    defaultPositionMs = defaultPositionMs,
    live = live,
    seekable = seekable,
    canSeekDefault = canSeekDefault,
    playingAd = playingAd,
)

private fun CoreSeekPlan.platformPlan(): NativeSeekPlan = when (this) {
    is CoreSeekPlan.Requested -> NativeSeekPlan(NativeSeekOutcome.REQUESTED, targetMs)
    is CoreSeekPlan.Clamped -> NativeSeekPlan(NativeSeekOutcome.CLAMPED, targetMs)
    is CoreSeekPlan.NoChange -> NativeSeekPlan(NativeSeekOutcome.NO_CHANGE, targetMs)
    is CoreSeekPlan.Unavailable -> NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
    is CoreSeekPlan.Unsupported -> NativeSeekPlan(NativeSeekOutcome.UNSUPPORTED)
    is CoreSeekPlan.AdBlocked -> NativeSeekPlan(NativeSeekOutcome.AD_BLOCKED)
    is CoreSeekPlan.OutsideWindow -> NativeSeekPlan(NativeSeekOutcome.OUTSIDE_WINDOW)
}

internal fun nativeSeekPlan(snapshot: NativeTimingSnapshot?, targetMs: Long): NativeSeekPlan =
    planAbsoluteSeek(snapshot?.coreContext(), targetMs).platformPlan()

internal fun nativeRelativeSeekPlan(snapshot: NativeTimingSnapshot?, deltaMs: Long): NativeSeekPlan =
    planRelativeSeek(snapshot?.coreContext(), deltaMs).platformPlan()

internal fun nativeLiveDefaultPlan(snapshot: NativeTimingSnapshot?): NativeSeekPlan =
    planLiveDefault(snapshot?.coreContext()).platformPlan()
