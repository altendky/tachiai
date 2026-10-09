package net.fstab.tachiai.platform.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter

// Each player owns its measurements, including when two feeds use distinct
// routes. Video uses SDK Auto, including its physical-display constraints.
// Shared-route fairness and per-pane/render-target policies remain separate.
@UnstableApi
internal class NativePlayerQuality(context: Context) : AutoCloseable {
    val bandwidthMeter = DefaultBandwidthMeter.Builder(context.applicationContext).build()
    val trackSelector = DefaultTrackSelector(context.applicationContext).apply {
        parameters = parameters.buildUpon()
            .setAllowMultipleAdaptiveSelections(false)
            .setForceHighestSupportedBitrate(false)
            .setForceLowestBitrate(false)
            .build()
    }
    private var sample: NativeBandwidthSample? = null
    private val listener = BandwidthMeter.EventListener { elapsedMs, bytes, estimate ->
        // Zero-byte events include network-type resets, not measured throughput.
        sample = if (elapsedMs > 0 && bytes > 0) NativeBandwidthSample(elapsedMs, bytes, estimate) else null
    }

    init { bandwidthMeter.addEventListener(Handler(Looper.getMainLooper()), listener) }

    fun configure(builder: ExoPlayer.Builder): ExoPlayer.Builder =
        builder.setBandwidthMeter(bandwidthMeter).setTrackSelector(trackSelector)

    fun snapshot(player: ExoPlayer): NativeQualitySnapshot {
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO || it.type == C.TRACK_TYPE_AUDIO }
        val available = mutableListOf<NativeQualityOption>()
        var availableCount = 0
        groups.forEach { group ->
            availableCount += group.length
            for (index in 0 until minOf(group.length, 24 - available.size)) {
                available += NativeQualityOption(nativeQualityTrack(group.getTrackFormat(index), group.type),
                    group.isTrackSupported(index), group.isTrackSelected(index))
            }
        }
        return NativeQualitySnapshot(
            player.videoFormat?.let { nativeQualityTrack(it, C.TRACK_TYPE_VIDEO) },
            player.audioFormat?.let { nativeQualityTrack(it, C.TRACK_TYPE_AUDIO) },
            bandwidthMeter.bitrateEstimate, sample, available, availableCount,
        )
    }

    override fun close() { bandwidthMeter.removeEventListener(listener) }
}

internal enum class NativeQualityKind { VIDEO, AUDIO }
internal enum class NativeQualityCodec { AVC, HEVC, AV1, VP8, VP9, AAC, OPUS, VORBIS, AC3, EAC3, MP3, OTHER }
internal data class NativeQualityTrack(
    val kind: NativeQualityKind, val codec: NativeQualityCodec, val bitrateBps: Int?,
    val width: Int? = null, val height: Int? = null,
    val channelCount: Int? = null, val sampleRateHz: Int? = null,
) {
    fun summary(): String = if (kind == NativeQualityKind.VIDEO)
        "${width ?: "?"}×${height ?: "?"} $codec ${bitrateBps?.div(1000) ?: "?"} kb/s"
    else "$codec ${bitrateBps?.div(1000) ?: "?"} kb/s ${channelCount ?: "?"} ch ${sampleRateHz ?: "?"} Hz"
}
internal data class NativeQualityOption(val track: NativeQualityTrack, val supported: Boolean, val selected: Boolean)
internal data class NativeBandwidthSample(val elapsedMs: Int, val bytes: Long, val estimateBps: Long)
internal data class NativeQualitySnapshot(
    val video: NativeQualityTrack?, val audio: NativeQualityTrack?,
    val bandwidthEstimateBps: Long, val sample: NativeBandwidthSample?,
    val available: List<NativeQualityOption>, val availableCount: Int,
) {
    fun summary(): String = "Video: ${video?.summary() ?: "unavailable"}; audio: ${audio?.summary() ?: "unavailable"}\n" +
        "Bandwidth estimate: ${bandwidthEstimateBps / 1000} kb/s (${if (sample == null) "initial/reset" else "samples observed"})."

    fun details(): String = summary() + (sample?.let { "\nLast sample: ${it.bytes} bytes in ${it.elapsedMs} ms." } ?: "") +
        "\nAvailable tracks: " + available.joinToString("; ") {
        "${it.track.kind}: ${it.track.summary()}${if (!it.supported) " [unsupported]" else ""}" +
            (if (it.selected) " [eligible/selected]" else "")
    } + (if (availableCount > available.size) " (+${availableCount - available.size} omitted)" else "")
}

// Only numeric properties and closed codec names leave the SDK. Never expose
// Format.toString(), id, label, language, metadata, URLs or DRM initialization.
@UnstableApi
internal fun nativeQualityTrack(format: Format, type: Int): NativeQualityTrack {
    val codec = when (format.sampleMimeType) {
        "video/avc" -> NativeQualityCodec.AVC
        "video/hevc" -> NativeQualityCodec.HEVC
        "video/av01" -> NativeQualityCodec.AV1
        "video/x-vnd.on2.vp8" -> NativeQualityCodec.VP8
        "video/x-vnd.on2.vp9" -> NativeQualityCodec.VP9
        "audio/mp4a-latm" -> NativeQualityCodec.AAC
        "audio/opus" -> NativeQualityCodec.OPUS
        "audio/vorbis" -> NativeQualityCodec.VORBIS
        "audio/ac3" -> NativeQualityCodec.AC3
        "audio/eac3", "audio/eac3-joc" -> NativeQualityCodec.EAC3
        "audio/mpeg" -> NativeQualityCodec.MP3
        else -> NativeQualityCodec.OTHER
    }
    val video = type == C.TRACK_TYPE_VIDEO
    return NativeQualityTrack(if (video) NativeQualityKind.VIDEO else NativeQualityKind.AUDIO, codec,
        format.bitrate.takeIf { it > 0 },
        if (video) format.width.takeIf { it in 1..16384 } else null,
        if (video) format.height.takeIf { it in 1..16384 } else null,
        if (!video) format.channelCount.takeIf { it in 1..64 } else null,
        if (!video) format.sampleRate.takeIf { it in 1..768000 } else null)
}
