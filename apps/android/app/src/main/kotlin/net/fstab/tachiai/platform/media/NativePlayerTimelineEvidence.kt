package net.fstab.tachiai.platform.media

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.hls.HlsManifest

// Read only the currently accepted SDK manifest. No new request, provider
// lookup, raw ID/URI copying, ad modification or change to timing admission.
@UnstableApi
internal fun nativePlayerTimelineEvidence(player: ExoPlayer): NativeTimelineEvidence? = try {
    val timeline = player.currentTimeline
    if (timeline.isEmpty || timeline.windowCount != 1 || player.isPlayingAd) null
    else if (!player.isCurrentMediaItemLive && !player.isCurrentMediaItemDynamic)
        player.duration.takeIf { it > 0 }?.let { NativeTimelineEvidence.Replay(it) }
    else when (val manifest = player.currentManifest) {
        is DashManifest -> {
            val index = player.currentPeriodIndex
            // A single native DASH source maps timeline period indices directly
            // to its current manifest; reject an unexpected wrapper/layout.
            if (timeline.periodCount != manifest.periodCount || index !in 0 until manifest.periodCount) null
            else NativeTimelineEvidence.Dash(manifest.availabilityStartTimeMs, manifest.getPeriod(index).startMs)
        }
        is HlsManifest -> {
            val playlist = manifest.mediaPlaylist
            if (!playlist.hasProgramDateTime || playlist.mediaSequence < 0) null
            else {
                val first = maxOf(0, playlist.segments.size - MAX_NATIVE_TIMELINE_SAMPLES)
                val samples = (first until playlist.segments.size).filterNot { playlist.segments[it].hasGapTag }.map { index ->
                    val segment = playlist.segments[index]
                    nativeHlsTimelineSample(Math.addExact(playlist.mediaSequence, index.toLong()),
                        playlist.startTimeUs, segment.relativeStartTimeUs, segment.durationUs,
                        playlist.discontinuitySequence.toLong(), segment.relativeDiscontinuitySequence.toLong())
                }
                if (samples.isEmpty() || samples.any { it == null }) null
                else NativeTimelineEvidence.Hls(samples.filterNotNull())
            }
        }
        else -> null
    }
} catch (_: Exception) { null }
