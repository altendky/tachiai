package net.fstab.tachiai.platform.media

import androidx.media3.common.C
import java.math.BigDecimal
import java.net.URI
import java.util.TreeMap

// #618: the HLS recorder for a live feed's rewind buffer. A live media playlist
// is a sliding window. This keeps the segments the provider has dropped in the
// playlist the player sees, while the feed's NativeLiveSegmentCache holds their
// bodies, so a held feed can sit further behind live than the provider allows.
//
// - Only segments a playlist declared are kept, and only while their bodies are
//   cached; they are listed with their original duration, date and
//   discontinuity, and nothing else of the provider's playlist changes.
// - The playlist's start never moves backwards, so each refresh stays newer
//   than the last one the player saw.
// - A segment recorded in another rendition of the same feed stands in when
//   the rendition in use has none for that sequence number, so an adaptive
//   switch while held does not fall out of the recorded history.
// - Any other playlist shape (multivariant, ended, event/VOD, byte ranges,
//   initialization maps, partial segments, keys) is left unchanged.
internal class NativeHlsLiveRecorder(private val cache: NativeLiveSegmentCache) {
    private class Entry(val rendition: String, val uri: URI, val info: String, val dateTime: String?,
        val discontinuity: Boolean, val discontinuityNumber: Long, val startMs: Long, val endMs: Long)
    private class Rendition {
        val entries = TreeMap<Long, Entry>()
        var firstEmitted = Long.MIN_VALUE
        var used = 0L
    }
    private class Segment(val sequence: Long, val uri: URI, val info: String, val durationMs: Long,
        val dateTime: String?, val discontinuity: Boolean, val relativeDiscontinuity: Long)
    private class Playlist(val lines: List<String>, val firstSegmentLine: Int, val sequenceLines: List<Int>,
        val discontinuitySequence: Long?, val segments: List<Segment>)

    private val lock = Any()
    private val renditions = LinkedHashMap<String, Rendition>()
    private var newestEndMs: Long? = null
    private var uses = 0L

    // Called with each media playlist's text after provider normalization.
    // Returns the text unchanged when it is not a supported live shape.
    fun rewrite(playlistUri: String, text: String): String = try {
        val parsed = parse(URI(playlistUri), text)
        if (parsed == null) text else synchronized(lock) { rewriteLocked(playlistUri, parsed) } ?: text
    } catch (_: Exception) { text }

    private fun rewriteLocked(key: String, playlist: Playlist): String? {
        val own = renditions.getOrPut(key) { Rendition() }
        own.used = ++uses
        while (renditions.size > MAX_RENDITIONS) renditions.remove(renditions.entries.minBy { it.value.used }.key)
        val segments = playlist.segments
        val first = segments.first()
        // A restarted sequence keeps nothing from before.
        if (own.firstEmitted != Long.MIN_VALUE && first.sequence < own.firstEmitted) {
            own.entries.clear(); own.firstEmitted = Long.MIN_VALUE
        }
        fun overlap(rendition: Rendition) = segments.withIndex().firstOrNull { it.value.sequence in rendition.entries }
        val ownOverlap = overlap(own)
        val other = renditions.values.filter { it !== own }.sortedByDescending { it.used }
            .firstNotNullOfOrNull { rendition -> overlap(rendition)?.let { rendition to it } }
        // One discontinuity numbering and one clock per feed, carried across
        // refreshes even when the provider omits its discontinuity sequence.
        val anchor = ownOverlap?.let { own to it } ?: other
        val anchorEntry = anchor?.let { (rendition, indexed) -> checkNotNull(rendition.entries[indexed.value.sequence]) }
        val discontinuityOffset = when {
            ownOverlap != null -> checkNotNull(anchorEntry).discontinuityNumber - ownOverlap.value.relativeDiscontinuity
            playlist.discontinuitySequence != null -> playlist.discontinuitySequence
            anchor != null -> checkNotNull(anchorEntry).discontinuityNumber - anchor.second.value.relativeDiscontinuity
            own.entries.isNotEmpty() -> own.entries.values.maxOf { it.discontinuityNumber } + 1
            else -> 0L
        }.coerceAtLeast(0)
        val totalMs = segments.sumOf { it.durationMs }
        val firstStartMs = when {
            anchor != null -> checkNotNull(anchorEntry).startMs - segments.take(anchor.second.index).sumOf { it.durationMs }
            else -> (newestEndMs ?: totalMs) - totalMs
        }
        // History that does not join this playlist cannot be listed again.
        if (ownOverlap == null) own.entries.clear()
        own.entries.tailMap(segments.last().sequence, false).clear()
        var startMs = firstStartMs
        val current = segments.map { segment ->
            Entry(key, segment.uri, segment.info, segment.dateTime, segment.discontinuity,
                discontinuityOffset + segment.relativeDiscontinuity, startMs, startMs + segment.durationMs)
                .also { startMs = it.endMs; own.entries[segment.sequence] = it }
        }
        // Retained segments, newest first, while contiguous, cached and
        // consistent with the discontinuity numbering of the next one.
        val prepended = ArrayDeque<Entry>()
        var next = current.first()
        var sequence = first.sequence - 1
        var size = playlist.lines.sumOf { it.length + 1 }
        while (prepended.size < MAX_PREPENDED && sequence >= 0 && sequence >= own.firstEmitted) {
            val candidates = listOf(own) + renditions.values.filter { it !== own }.sortedByDescending { it.used }
            val entry = candidates.firstNotNullOfOrNull { rendition ->
                rendition.entries[sequence]?.takeIf { cache.cached(it.uri) } } ?: break
            if (entry.discontinuityNumber != next.discontinuityNumber - (if (next.discontinuity) 1 else 0)) break
            size += lines(entry).sumOf { it.length + 1 }
            if (size > MAX_TEXT) break
            prepended.addFirst(entry)
            next = entry
            sequence--
        }
        val firstOut = first.sequence - prepended.size
        own.firstEmitted = firstOut
        own.entries.headMap(firstOut, false).clear()
        val oldest = prepended.firstOrNull() ?: current.first()
        val discontinuitySequence = oldest.discontinuityNumber - (if (oldest.discontinuity) 1 else 0)
        if (discontinuitySequence < 0) return null
        newestEndMs = maxOf(newestEndMs ?: Long.MIN_VALUE, current.last().endMs)
        prune()

        cache.publish((prepended + current).associate { entry ->
            entry.uri to NativeLiveSegmentCache.Segment(HLS_PERIOD, 0, C.TRACK_TYPE_DEFAULT, entry.rendition, 0,
                Math.multiplyExact(entry.startMs, 1000L), entry.startMs, entry.endMs, false)
        }, totalMs, rendition = key)

        val out = ArrayList<String>(playlist.lines.size + prepended.size * 4 + 2)
        // Both sequence tags precede every segment, retained ones included.
        val sequencesAt = minOf(playlist.sequenceLines.firstOrNull() ?: 1, playlist.firstSegmentLine)
        for ((index, line) in playlist.lines.withIndex()) {
            if (index == sequencesAt) {
                out += "$MEDIA_SEQUENCE$firstOut"
                out += "$DISCONTINUITY_SEQUENCE$discontinuitySequence"
            }
            if (index == playlist.firstSegmentLine) prepended.forEach { out += lines(it) }
            if (index !in playlist.sequenceLines) out += line
        }
        return out.joinToString("\n", postfix = "\n")
    }

    // Entries older than any span the cache could keep are dropped.
    private fun prune() {
        val horizon = (newestEndMs ?: return) - LIVE_REWIND_MAX_SPAN_MS - 60_000
        for (rendition in renditions.values) {
            rendition.entries.values.removeAll { it.endMs < horizon }
            while (rendition.entries.size > MAX_ENTRIES) rendition.entries.pollFirstEntry()
        }
    }

    private fun lines(entry: Entry): List<String> = listOfNotNull(
        DISCONTINUITY.takeIf { entry.discontinuity }, entry.dateTime, entry.info, entry.uri.toString())

    companion object {
        private const val HLS_PERIOD = "hls"
        private const val MAX_RENDITIONS = 16
        private const val MAX_ENTRIES = 2048
        private const val MAX_PREPENDED = 1024
        // The player refuses a rewritten playlist over 512 KiB.
        private const val MAX_TEXT = 480 * 1024
        private const val MEDIA_SEQUENCE = "#EXT-X-MEDIA-SEQUENCE:"
        private const val DISCONTINUITY_SEQUENCE = "#EXT-X-DISCONTINUITY-SEQUENCE:"
        private const val DISCONTINUITY = "#EXT-X-DISCONTINUITY"
        private val UNSUPPORTED = listOf("#EXT-X-STREAM-INF", "#EXT-X-I-FRAME-STREAM-INF", "#EXT-X-ENDLIST",
            "#EXT-X-PLAYLIST-TYPE", "#EXT-X-MAP", "#EXT-X-BYTERANGE", "#EXT-X-PART", "#EXT-X-PRELOAD-HINT",
            "#EXT-X-SKIP", "#EXT-X-SERVER-CONTROL", "#EXT-X-RENDITION-REPORT", "#EXT-X-KEY", "#EXT-X-I-FRAMES-ONLY",
            "#EXT-X-GAP")
        private val NUMBER = Regex("[0-9]{1,18}")
        private val DURATION = Regex("[0-9]{1,5}(\\.[0-9]{1,9})?")
        // Tags that begin a media segment's block; retained segments go first.
        private val SEGMENT_TAGS = listOf("#EXTINF:", "#EXT-X-PROGRAM-DATE-TIME:", "#EXT-X-DATERANGE:")

        private fun parse(base: URI, text: String): Playlist? {
            if (!text.startsWith("#EXTM3U")) return null
            val lines = text.lines().map { it.trimEnd() }
            var mediaSequence: Long? = null
            var discontinuitySequence: Long? = null
            var target = false
            val sequenceLines = ArrayList<Int>()
            var firstSegmentLine = -1
            var info: String? = null
            var durationMs = 0L
            var dateTime: String? = null
            var discontinuity = false
            var relative = 0L
            val segments = ArrayList<Segment>()
            for ((index, line) in lines.withIndex()) {
                if (line.isBlank()) continue
                if (UNSUPPORTED.any { line.startsWith(it) }) return null
                fun startsSegment() { if (firstSegmentLine < 0) firstSegmentLine = index }
                when {
                    line.startsWith(MEDIA_SEQUENCE) -> {
                        if (segments.isNotEmpty() || mediaSequence != null) return null
                        mediaSequence = line.removePrefix(MEDIA_SEQUENCE).trim().takeIf(NUMBER::matches)?.toLong() ?: return null
                        sequenceLines += index
                    }
                    line.startsWith(DISCONTINUITY_SEQUENCE) -> {
                        if (segments.isNotEmpty() || discontinuitySequence != null) return null
                        discontinuitySequence = line.removePrefix(DISCONTINUITY_SEQUENCE).trim().takeIf(NUMBER::matches)
                            ?.toLong() ?: return null
                        sequenceLines += index
                    }
                    line.startsWith("#EXT-X-TARGETDURATION:") -> target = true
                    line == DISCONTINUITY -> { startsSegment(); discontinuity = true; relative++ }
                    line.startsWith("#EXTINF:") -> {
                        startsSegment()
                        if (info != null) return null
                        val seconds = line.removePrefix("#EXTINF:").substringBefore(',').trim()
                        if (!DURATION.matches(seconds) || line.length > 1024) return null
                        durationMs = BigDecimal(seconds).movePointRight(3).toLong()
                        if (durationMs !in 1..600_000) return null
                        info = line
                    }
                    line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> {
                        startsSegment()
                        if (line.length > 256) return null
                        dateTime = line
                    }
                    SEGMENT_TAGS.any { line.startsWith(it) } -> startsSegment()
                    line.startsWith("#") -> Unit
                    else -> {
                        val segmentInfo = info ?: return null
                        if (line.length > 4096 || segments.size >= 4096) return null
                        val uri = base.resolve(line.trim())
                        if (!uri.isAbsolute) return null
                        segments += Segment((mediaSequence ?: 0L) + segments.size, uri, segmentInfo, durationMs, dateTime,
                            discontinuity, relative)
                        info = null; dateTime = null; discontinuity = false
                    }
                }
            }
            if (!target || segments.isEmpty() || info != null || firstSegmentLine < 0) return null
            if ((mediaSequence ?: 0L) > Long.MAX_VALUE / 2) return null
            return Playlist(lines, firstSegmentLine, sequenceLines, discontinuitySequence, segments)
        }
    }
}
