package net.fstab.tachiai.provider.twitch

// Twitch live low latency, approved by the user on 2026-11-22.
//
// With `fast_bread=true`, Twitch media playlists end with up to a few
// `#EXT-X-TWITCH-PREFETCH:<uri>` hints: segments still being produced, which
// stream as they are encoded. Media3 does not understand that tag, so each
// trailing hint becomes an ordinary segment with the last segment's duration,
// as streamlink's low-latency mode does. Media3 tracks segments by media
// sequence, so a hint that later appears as a real segment is not fetched again.

// The player holds itself this far behind the live edge, instead of Media3's
// default of three target durations (18 s for Twitch's 6 s target).
internal const val TWITCH_LIVE_TARGET_OFFSET_MS = 6_000L

private const val PREFETCH = "#EXT-X-TWITCH-PREFETCH:"
private val segmentDuration = Regex("[0-9]{1,3}(\\.[0-9]{1,6})?")

// Unexpected shapes leave the playlist unchanged rather than guessing.
internal fun twitchLowLatencyPlaylist(text: String): String {
    if (!text.contains(PREFETCH) || text.contains("#EXT-X-ENDLIST")) return text
    val lines = text.lines()
    val first = lines.indexOfFirst { it.startsWith(PREFETCH) }
    // Hints must trail every real segment.
    if (lines.drop(first).any { it.isNotBlank() && !it.startsWith(PREFETCH) }) return text
    val duration = lines.take(first).lastOrNull { it.startsWith("#EXTINF:") }
        ?.removePrefix("#EXTINF:")?.substringBefore(',')?.takeIf { segmentDuration.matches(it) } ?: return text
    val out = ArrayList<String>(lines.size + 4)
    out.addAll(lines.take(first))
    for (line in lines.drop(first)) {
        if (line.isBlank()) continue
        val uri = line.removePrefix(PREFETCH).trim()
        if (uri.isEmpty() || uri.length > 2048 || uri.any { it.isWhitespace() || it.code < 32 } || uri.startsWith("#")) return text
        out += "#EXTINF:$duration,"
        out += uri
    }
    return out.joinToString("\n", postfix = "\n")
}
