package net.fstab.tachiai.platform.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
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
    private var attached: ExoPlayer? = null
    private val displayWidth = context.resources.displayMetrics.widthPixels.takeIf { it > 0 } ?: Int.MAX_VALUE
    private val displayHeight = context.resources.displayMetrics.heightPixels.takeIf { it > 0 } ?: Int.MAX_VALUE
    private var closed = false
    private var requested = NativeQualityPreferences()
    private var controls = NativeQualityKind.entries.associateWith {
        NativeQualityControl(NativeQualityRequest.auto, NativeQualityOutcome.AUTO, emptyList())
    }
    private val tracksListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) { attached?.let { resolve(it, tracks) } }
    }
    private val listener = BandwidthMeter.EventListener { elapsedMs, bytes, estimate ->
        // Zero-byte events include network-type resets, not measured throughput.
        sample = if (elapsedMs > 0 && bytes > 0) NativeBandwidthSample(elapsedMs, bytes, estimate) else null
    }

    init { bandwidthMeter.addEventListener(Handler(Looper.getMainLooper()), listener) }

    fun configure(builder: ExoPlayer.Builder): ExoPlayer.Builder =
        builder.setBandwidthMeter(bandwidthMeter).setTrackSelector(trackSelector)

    fun bind(player: ExoPlayer) {
        check(!closed && attached == null && Looper.myLooper() == player.applicationLooper)
        attached = player; player.addListener(tracksListener); resolve(player, player.currentTracks)
    }

    fun request(player: ExoPlayer, preferences: NativeQualityPreferences): Boolean {
        if (closed || attached !== player || Looper.myLooper() != player.applicationLooper ||
            !player.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)) return false
        requested = preferences
        resolve(player, player.currentTracks)
        return true // Acceptance of a preference is not confirmation of consumed quality.
    }

    private fun resolve(player: ExoPlayer, tracks: Tracks) {
        if (closed || Looper.myLooper() != player.applicationLooper) return
        val result = resolveNativeQuality(tracks, requested, trackSelector.parameters, displayWidth, displayHeight)
        controls = result.controls
        if (!player.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)) return
        val builder = trackSelector.parameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO).clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        result.overrides.forEach { builder.setOverrideForType(it) }
        val parameters = builder.build()
        if (parameters != trackSelector.parameters) trackSelector.parameters = parameters
    }

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
            bandwidthMeter.bitrateEstimate, sample, available, availableCount, controls,
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        attached?.removeListener(tracksListener); attached = null
        bandwidthMeter.removeEventListener(listener)
    }
}

@UnstableApi
internal data class NativeQualityResolution(
    val controls: Map<NativeQualityKind, NativeQualityControl>,
    val overrides: List<TrackSelectionOverride>,
)

// Only the currently selected content group is a quality ladder. Other groups
// can be languages, roles or camera angles; matching numeric formats there
// does not establish equivalent content. Raw handles stay inside the player policy.
@UnstableApi
internal fun resolveNativeQuality(tracks: Tracks, requests: NativeQualityPreferences,
    parameters: DefaultTrackSelector.Parameters? = null,
    displayWidth: Int = Int.MAX_VALUE, displayHeight: Int = Int.MAX_VALUE): NativeQualityResolution {
    val overrides = mutableListOf<TrackSelectionOverride>()
    val controls = NativeQualityKind.entries.associateWith { kind ->
        val type = if (kind == NativeQualityKind.VIDEO) C.TRACK_TYPE_VIDEO else C.TRACK_TYPE_AUDIO
        val selected = tracks.groups.filter { it.type == type && it.isSelected }
        val group = selected.singleOrNull()
        val choices = group?.let { current ->
            (0 until current.length).mapNotNull { index ->
                if (!current.isTrackSupported(index) || !qualityWithinLimits(current.getTrackFormat(index), type,
                        parameters, displayWidth, displayHeight)) null else runCatching {
                    NativeQualityRequest(nativeQualityTrack(current.getTrackFormat(index), type)) to index
                }.getOrNull()
            }
        }.orEmpty()
        val request = requests.get(kind)
        val index = choices.singleOrNull { it.first == request }?.second
        val outcome = when {
            request.track == null -> NativeQualityOutcome.AUTO
            tracks.groups.isEmpty() -> NativeQualityOutcome.PENDING
            index == null || group == null -> NativeQualityOutcome.UNAVAILABLE
            else -> {
                overrides += TrackSelectionOverride(group.mediaTrackGroup, index)
                NativeQualityOutcome.REQUESTED
            }
        }
        // Numeric descriptors deliberately omit identity/metadata. If they do
        // not uniquely identify a track, neither the chooser nor a saved request
        // may arbitrarily choose one of the indistinguishable SDK formats.
        val options = choices.groupBy { it.first }.filterValues { it.size == 1 }.keys.toList()
        NativeQualityControl(request, outcome, options.take(24), (options.size - 24).coerceAtLeast(0))
    }
    return NativeQualityResolution(controls, overrides)
}

@UnstableApi
private fun qualityWithinLimits(format: Format, type: Int, parameters: DefaultTrackSelector.Parameters?,
    displayWidth: Int, displayHeight: Int): Boolean {
    if (parameters == null) return true
    if (type == C.TRACK_TYPE_AUDIO) return (format.bitrate <= 0 || format.bitrate <= parameters.maxAudioBitrate) &&
        (format.channelCount <= 0 || format.channelCount <= parameters.maxAudioChannelCount)
    if (format.width > parameters.maxVideoWidth || format.height > parameters.maxVideoHeight ||
        format.width < parameters.minVideoWidth || format.height < parameters.minVideoHeight ||
        format.bitrate > parameters.maxVideoBitrate || (format.bitrate > 0 && format.bitrate < parameters.minVideoBitrate) ||
        format.frameRate > parameters.maxVideoFrameRate || (format.frameRate > 0 && format.frameRate < parameters.minVideoFrameRate)) return false
    val physical = parameters.isViewportSizeLimitedByPhysicalDisplaySize
    val width = minOf(parameters.viewportWidth, if (physical) displayWidth else Int.MAX_VALUE)
    val height = minOf(parameters.viewportHeight, if (physical) displayHeight else Int.MAX_VALUE)
    return (format.width <= width && format.height <= height) ||
        (parameters.viewportOrientationMayChange && format.width <= height && format.height <= width)
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
    val controls: Map<NativeQualityKind, NativeQualityControl> = emptyMap(),
) {
    fun summary(): String = "Video: ${video?.summary() ?: "unavailable"}; audio: ${audio?.summary() ?: "unavailable"}\n" +
        "Bandwidth estimate: ${bandwidthEstimateBps / 1000} kb/s (${if (sample == null) "initial/reset" else "samples observed"})."

    fun details(): String = summary() + (sample?.let { "\nLast sample: ${it.bytes} bytes in ${it.elapsedMs} ms." } ?: "") +
        "\nAvailable tracks: " + available.joinToString("; ") {
        "${it.track.kind}: ${it.track.summary()}${if (!it.supported) " [unsupported]" else ""}" +
            (if (it.selected) " [eligible/selected]" else "")
    } + (if (availableCount > available.size) " (+${availableCount - available.size} omitted)" else "") +
        controls.entries.joinToString("") { (kind, control) ->
            "\n$kind requested: ${control.requested.title}. ${control.explanation}. ${control.capability}."
        }
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
