package net.fstab.tachiai.platform.media

// A portable preference contains only a closed codec and bounded numeric
// properties. SDK groups, format identities and provider metadata stay local.
internal data class NativeQualityRequest(val track: NativeQualityTrack? = null) {
    init {
        track?.let {
            require(it.codec != NativeQualityCodec.OTHER)
            require(it.bitrateBps == null || it.bitrateBps in 1..1_000_000_000)
            if (it.kind == NativeQualityKind.VIDEO) {
                require(it.codec in setOf(NativeQualityCodec.AVC, NativeQualityCodec.HEVC, NativeQualityCodec.AV1, NativeQualityCodec.VP8, NativeQualityCodec.VP9))
                require(it.width != null && it.width in 1..16384 && it.height != null && it.height in 1..16384)
                require(it.channelCount == null && it.sampleRateHz == null)
            } else {
                require(it.codec in setOf(NativeQualityCodec.AAC, NativeQualityCodec.OPUS, NativeQualityCodec.VORBIS,
                    NativeQualityCodec.AC3, NativeQualityCodec.EAC3, NativeQualityCodec.MP3))
                require(it.channelCount != null && it.channelCount in 1..64 && it.sampleRateHz != null && it.sampleRateHz in 1..768000)
                require(it.width == null && it.height == null)
            }
        }
    }
    val title: String get() = track?.summary() ?: "Auto"
    companion object { val auto = NativeQualityRequest() }
}

internal data class NativeQualityPreferences(
    val video: NativeQualityRequest = NativeQualityRequest.auto,
    val audio: NativeQualityRequest = NativeQualityRequest.auto,
) {
    init { require(video.track?.kind != NativeQualityKind.AUDIO && audio.track?.kind != NativeQualityKind.VIDEO) }
    fun get(kind: NativeQualityKind) = if (kind == NativeQualityKind.VIDEO) video else audio
    fun with(kind: NativeQualityKind, request: NativeQualityRequest) =
        if (kind == NativeQualityKind.VIDEO) copy(video = request) else copy(audio = request)
}

internal enum class NativeQualityOutcome { AUTO, PENDING, REQUESTED, UNAVAILABLE }
internal data class NativeQualityControl(
    val requested: NativeQualityRequest,
    val outcome: NativeQualityOutcome,
    val options: List<NativeQualityRequest>,
    val omitted: Int = 0,
) {
    val explanation: String get() = when (outcome) {
        NativeQualityOutcome.AUTO -> "Auto selection"
        NativeQualityOutcome.PENDING -> "Waiting for supported tracks; Auto until available"
        NativeQualityOutcome.REQUESTED -> "Manual selection requested; actual format is reported separately"
        NativeQualityOutcome.UNAVAILABLE -> "Requested quality unavailable or ambiguous; Auto until available"
    }
    val capability: String get() = if (options.size <= 1) "No separate quality alternatives exposed; bundled tracks may be coupled"
        else "Supported alternatives in the current content group; independent audio/video changes are not guaranteed"
}
