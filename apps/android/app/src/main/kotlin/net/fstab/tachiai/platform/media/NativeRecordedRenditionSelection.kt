package net.fstab.tachiai.platform.media

import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.google.common.collect.ImmutableList

// #711: the rendition whose history a live feed's rewind buffer recorded, as
// its representation id and bandwidth.
internal data class NativeRecordedRendition(val id: String, val bandwidth: Int)

// The one format of a group the player may use while pinned: the recorded
// representation itself, or in another period (an ad break) the closest
// bandwidth, the same rule the cache records by.
@UnstableApi
internal fun nativeRecordedFormat(formats: List<Format>, recorded: NativeRecordedRendition): Format? =
    formats.firstOrNull { it.id == recorded.id }
        ?: formats.minByOrNull { kotlin.math.abs(it.bitrate.toLong() - recorded.bandwidth) }

// Adaptive selection as usual, except that while a live feed plays older than
// its provider's window it stays on the rendition the rewind buffer recorded.
// Older segments of any other rendition were never recorded and are no longer
// declared, so switching to one would fail.
@UnstableApi
internal class NativeRecordedRenditionSelectionFactory(
    // The recorded rendition for a track type while pinned; null otherwise.
    private val recorded: (Int) -> NativeRecordedRendition?,
) : AdaptiveTrackSelection.Factory() {
    override fun createAdaptiveTrackSelection(group: TrackGroup, tracks: IntArray, type: Int,
        bandwidthMeter: BandwidthMeter, adaptationCheckpoints: ImmutableList<AdaptiveTrackSelection.AdaptationCheckpoint>,
    ): AdaptiveTrackSelection = object : AdaptiveTrackSelection(group, tracks, type, bandwidthMeter,
        DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS.toLong(), DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS.toLong(),
        DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS.toLong(), DEFAULT_MAX_WIDTH_TO_DISCARD,
        DEFAULT_MAX_HEIGHT_TO_DISCARD, DEFAULT_BANDWIDTH_FRACTION, DEFAULT_BUFFERED_FRACTION_TO_LIVE_EDGE_FOR_QUALITY_INCREASE,
        adaptationCheckpoints, Clock.DEFAULT) {
        override fun canSelectFormat(format: Format, trackBitrate: Int, effectiveBitrate: Long): Boolean {
            val pinned = try { recorded(trackGroup.type) } catch (_: Exception) { null }
                ?: return super.canSelectFormat(format, trackBitrate, effectiveBitrate)
            val formats = (0 until length()).map(::getFormat)
            return nativeRecordedFormat(formats, pinned)?.let { it === format || it.id != null && it.id == format.id } ?: true
        }
    }
}
