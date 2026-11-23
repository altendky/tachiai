package net.fstab.tachiai.presentation

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import net.fstab.tachiai.platform.media.NativeMixedSide
import net.fstab.tachiai.platform.media.NativePairSkipPlan
import net.fstab.tachiai.platform.media.NativeSeekOutcome
import net.fstab.tachiai.platform.media.NativeTimingSnapshot
import net.fstab.tachiai.provider.catalog.CatalogResource

// Remembered replay positions (#655 part 2). Each replay or on-demand item's
// position is kept locally and resumed the next time it is opened. There is no
// finished threshold and no expiry: the most recently saved items are kept.
internal const val MAX_REPLAY_POSITION_RECORDS = 200
// Bounds a stored value; real replays are hours long.
internal const val MAX_REPLAY_POSITION_MS = 7L * 24 * 60 * 60 * 1_000
internal const val REPLAY_POSITION_SAVE_INTERVAL_MS = 10_000L

internal fun validReplayPositionKey(key: String) = key.matches(Regex("[a-f0-9]{64}"))

// Only a hash of the item's public catalog identity (an ABEMA slot or episode,
// a Twitch video, a YouTube video). Titles, provider instances, accounts,
// routes and signed sources never contribute, so two accounts share an item.
internal fun replayPositionKey(resource: CatalogResource): String {
    val bytes = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { data ->
            listOf("tachiai:replay-position:v1", resource.providerId.value, resource.kind, resource.identity,
                resource.intent.name).forEach { value ->
                val encoded = value.toByteArray(Charsets.UTF_8)
                data.writeInt(encoded.size); data.write(encoded)
            }
        }
    }.toByteArray()
    return MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}

// Positions are remembered only in a replay view, where every selected feed is
// a replay: the view with the position row and skips. A replay paired with a
// live feed is positioned relative to it through Timing instead.
internal fun replayPositionKeys(selection: ConfiguredPlaybackSelection): List<String?> =
    if (selection.feeds.any { it.kind != PrototypePlaybackKind.REPLAY }) listOf(null, null)
    else selection.slots.map { feed -> feed?.let { replayPositionKey(it.resource) } }

// A retry resumes this session's last position. Otherwise a feed that has not
// prepared yet in this session resumes its saved position.
internal fun replayResumeTargetMs(retrying: Boolean, retryPositionMs: Long?, savedPositionMs: Long?): Long? =
    if (retrying && retryPositionMs != null) retryPositionMs else savedPositionMs

// A ready replay's position outside an advertisement; nothing else is remembered.
internal fun rememberedReplayPositionMs(snapshot: NativeTimingSnapshot?): Long? =
    snapshot?.takeIf { it.state == 3 && !it.live && !it.dynamic && !it.playingAd }?.positionMs?.takeIf { it >= 0 }

// A saved pair offset is relative to both feeds at their default starts (the
// start of a replay, the live position of a live feed). When this session
// resumed a feed, the first prepared pair already holds that displacement, so
// it starts as the applied offset (A relative to B): the saved offset then
// restores only the remaining difference instead of being added again. Null
// when it is outside the supported offset range.
internal fun pairOffsetResumeBaselineMs(resumed: Boolean, a: NativeTimingSnapshot?, b: NativeTimingSnapshot?): Long? {
    if (!resumed) return 0L
    fun displacement(value: NativeTimingSnapshot?): Long =
        value?.takeIf { !it.live && !it.dynamic }?.positionMs?.coerceAtLeast(0) ?: 0L
    return (displacement(a) - displacement(b)).takeIf { it in -MAX_PAIR_OFFSET_MS..MAX_PAIR_OFFSET_MS }
}

internal data class ReplayResumed(val positionMs: Long, val durationMs: Long)

// "Resumed at 2:14:05." or, in a pair, "Resumed A at 2:14:05, B at 2:14:17."
internal fun replayResumeNotice(resumed: Map<NativeMixedSide, ReplayResumed>, pair: Boolean): String? {
    if (resumed.isEmpty()) return null
    val sides = NativeMixedSide.entries.filter { it in resumed }
    val where = if (!pair) resumed.getValue(sides.first()).let { "at ${formatPlaybackTime(it.positionMs, it.durationMs)}" }
        else sides.joinToString(", ") { side ->
            resumed.getValue(side).let { "${side.name} at ${formatPlaybackTime(it.positionMs, it.durationMs)}" }
        }
    return "Resumed $where. Start over is in ⋯ More."
}

// Start over (in More) moves every feed back by one shared amount, as far as
// the earliest feed's start, so a pair keeps its offset (#655).
internal const val START_OVER_MS = -Long.MAX_VALUE

internal fun startOverNotice(plan: NativePairSkipPlan, targetMs: Long?, durationMs: Long, pair: Boolean): String? =
    when (plan.outcome) {
        NativeSeekOutcome.REQUESTED, NativeSeekOutcome.CLAMPED ->
            "Started over · ${formatPlaybackPosition(targetMs ?: 0L, durationMs)}"
        else -> viewerSkipRefusal(plan, forward = false, pair = pair)
    }

// Decides when positions are written: every interval while they change, and
// at once when forced (pause, leaving, background). Unchanged positions, such
// as a paused feed's, are not written again.
internal class ReplayPositionSaver(startMs: Long, private val intervalMs: Long = REPLAY_POSITION_SAVE_INTERVAL_MS) {
    private val saved = HashMap<String, Long>()
    private var lastWriteMs = startMs

    // Positions already stored, read when the viewer opened.
    fun seed(positions: Map<String, Long>) { saved.putAll(positions) }

    fun due(nowMs: Long, current: Map<String, Long>, force: Boolean): Map<String, Long> {
        val changed = current.filter { (key, value) -> saved[key] != value }
        if (changed.isEmpty() || !force && nowMs - lastWriteMs < intervalMs) return emptyMap()
        saved.putAll(changed)
        lastWriteMs = nowMs
        return changed
    }

    // Start over and Play again remove the stored position; the next one is written anew.
    fun forget(keys: Set<String>) { keys.forEach(saved::remove) }
}
