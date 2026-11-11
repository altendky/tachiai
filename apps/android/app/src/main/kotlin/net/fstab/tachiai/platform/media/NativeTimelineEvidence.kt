package net.fstab.tachiai.platform.media

internal const val MAX_NATIVE_TIMELINE_SAMPLES = 8

// Bounded raw manifest scalars, never provider identity or playback authority.
// Absolute times are private continuity inputs: do not log/toString this data.
internal data class NativeHlsTimelineSample(val sequence: Long, val startTimeMs: Long,
    val durationMs: Long, val discontinuity: Long) {
    init {
        require(sequence >= 0 && startTimeMs >= 0 && durationMs > 0 && discontinuity >= 0)
        require(startTimeMs <= Long.MAX_VALUE - durationMs)
    }
    override fun toString() = "NativeHlsTimelineSample(redacted)"
}
internal sealed interface NativeTimelineEvidence {
    data class Dash(val availabilityStartTimeMs: Long, val periodStartMs: Long) : NativeTimelineEvidence {
        init { require(availabilityStartTimeMs >= 0 && periodStartMs >= 0 &&
            availabilityStartTimeMs <= Long.MAX_VALUE - periodStartMs) }
        override fun toString() = "NativeTimelineEvidence.Dash(redacted)"
    }
    class Hls(samples: List<NativeHlsTimelineSample>) : NativeTimelineEvidence {
        val samples: List<NativeHlsTimelineSample> = java.util.Collections.unmodifiableList(samples.toList())
        init {
            require(this.samples.size in 1..MAX_NATIVE_TIMELINE_SAMPLES)
            require(this.samples.zipWithNext().all { (first, second) -> first.sequence < second.sequence })
        }
        fun copy(samples: List<NativeHlsTimelineSample> = this.samples) = Hls(samples)
        operator fun component1() = samples
        override fun equals(other: Any?) = other is Hls && samples == other.samples
        override fun hashCode() = samples.hashCode()
        override fun toString() = "NativeTimelineEvidence.Hls(redacted)"
    }
    data class Replay(val durationMs: Long) : NativeTimelineEvidence {
        init { require(durationMs > 0) }
        override fun toString() = "NativeTimelineEvidence.Replay(redacted)"
    }
}
internal enum class NativeTimelineConsistency { CONSISTENT, CHANGED, UNKNOWN }

// CONSISTENT means "seems consistent", not a verified stream/content version.
// The caller must separately bind the exact configured public resource. No
// overlap is unknown, even if independent UTC timestamps appear continuous.
internal fun compareNativeTimelineEvidence(saved: NativeTimelineEvidence?,
    current: NativeTimelineEvidence?): NativeTimelineConsistency = when {
    saved == null || current == null -> NativeTimelineConsistency.UNKNOWN
    saved is NativeTimelineEvidence.Dash && current is NativeTimelineEvidence.Dash ->
        if (saved == current) NativeTimelineConsistency.CONSISTENT else NativeTimelineConsistency.CHANGED
    saved is NativeTimelineEvidence.Replay && current is NativeTimelineEvidence.Replay ->
        if (saved.durationMs == current.durationMs) NativeTimelineConsistency.CONSISTENT else NativeTimelineConsistency.CHANGED
    saved is NativeTimelineEvidence.Hls && current is NativeTimelineEvidence.Hls -> {
        fun valid(samples: List<NativeHlsTimelineSample>) = samples.size in 1..MAX_NATIVE_TIMELINE_SAMPLES &&
            samples.zipWithNext().all { (first, second) -> first.sequence < second.sequence }
        if (!valid(saved.samples) || !valid(current.samples)) NativeTimelineConsistency.UNKNOWN
        else if (current.samples.last().sequence < saved.samples.last().sequence) NativeTimelineConsistency.CHANGED
        else if (current.samples.last().discontinuity != saved.samples.last().discontinuity) NativeTimelineConsistency.CHANGED
        else {
            val before = saved.samples.associateBy { it.sequence }
            val overlap = current.samples.mapNotNull { sample -> before[sample.sequence]?.let { it to sample } }
            when {
                overlap.any { (first, second) -> first != second } -> NativeTimelineConsistency.CHANGED
                overlap.isNotEmpty() -> NativeTimelineConsistency.CONSISTENT
                else -> NativeTimelineConsistency.UNKNOWN
            }
        }
    }
    else -> NativeTimelineConsistency.UNKNOWN
}

internal fun nativeHlsTimelineSample(sequence: Long, playlistStartTimeUs: Long, relativeStartTimeUs: Long,
    durationUs: Long, discontinuitySequence: Long, relativeDiscontinuity: Long): NativeHlsTimelineSample? = try {
    if (playlistStartTimeUs < 0 || relativeStartTimeUs < 0 || durationUs < 1_000 ||
        discontinuitySequence < 0 || relativeDiscontinuity < 0) null
    else NativeHlsTimelineSample(sequence, Math.addExact(playlistStartTimeUs, relativeStartTimeUs) / 1_000,
        durationUs / 1_000, Math.addExact(discontinuitySequence, relativeDiscontinuity))
} catch (_: Exception) { null }
