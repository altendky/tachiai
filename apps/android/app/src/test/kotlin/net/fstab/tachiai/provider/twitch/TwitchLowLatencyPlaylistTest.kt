package net.fstab.tachiai.provider.twitch

import org.junit.Assert.*
import org.junit.Test

class TwitchLowLatencyPlaylistTest {
    private val head = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:6
        #EXT-X-MEDIA-SEQUENCE:100
        #EXT-X-PROGRAM-DATE-TIME:2026-11-22T03:00:00.000Z
        #EXTINF:2.000,live
        https://video-edge.example/v1/segment/100.ts
        #EXT-X-PROGRAM-DATE-TIME:2026-11-22T03:00:02.000Z
        #EXTINF:2.002,live
        https://video-edge.example/v1/segment/101.ts
    """.trimIndent()

    @Test fun trailingHintsBecomeSegmentsWithTheLastDuration() {
        val rewritten = twitchLowLatencyPlaylist(head + """

            #EXT-X-TWITCH-PREFETCH:https://video-edge.example/v1/segment/102.ts
            #EXT-X-TWITCH-PREFETCH:https://video-edge.example/v1/segment/103.ts
        """.trimIndent().let { "\n$it" })
        val lines = rewritten.lines()
        assertFalse(rewritten.contains("#EXT-X-TWITCH-PREFETCH"))
        assertEquals(listOf("#EXTINF:2.002,", "https://video-edge.example/v1/segment/102.ts",
            "#EXTINF:2.002,", "https://video-edge.example/v1/segment/103.ts", ""), lines.takeLast(5))
        assertTrue(rewritten.startsWith(head))
    }

    @Test fun playlistsWithoutTrailingWellFormedHintsAreUnchanged() {
        val hint = "#EXT-X-TWITCH-PREFETCH:https://video-edge.example/v1/segment/102.ts"
        for (text in listOf(
            head,
            "$head\n#EXT-X-ENDLIST\n$hint",
            // A hint followed by a real segment is not a trailing hint.
            "$head\n$hint\n#EXTINF:2.000,live\nhttps://video-edge.example/v1/segment/103.ts",
            "$head\n#EXT-X-TWITCH-PREFETCH:",
            "$head\n#EXT-X-TWITCH-PREFETCH:https://video-edge.example/a b.ts",
            "$head\n#EXT-X-TWITCH-PREFETCH:#EXT-X-ENDLIST",
            "#EXTM3U\n$hint",
            head.replace("#EXTINF:2.002,live", "#EXTINF:abc,live") + "\n$hint",
        )) assertSame(text, twitchLowLatencyPlaylist(text))
    }
}
