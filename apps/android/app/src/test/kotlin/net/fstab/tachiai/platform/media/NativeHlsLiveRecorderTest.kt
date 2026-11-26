package net.fstab.tachiai.platform.media

import java.net.URI
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import net.fstab.tachiai.provider.twitch.twitchLowLatencyPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHlsLiveRecorderTest {
    private class InlineExecutor : AbstractExecutorService() {
        var shutdown = false
        override fun execute(command: Runnable) { if (shutdown) throw java.util.concurrent.RejectedExecutionException() else command.run() }
        override fun shutdown() { shutdown = true }
        override fun shutdownNow(): List<Runnable> { shutdown = true; return emptyList() }
        override fun isShutdown() = shutdown
        override fun isTerminated() = shutdown
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private val playlist = "https://edge.example/v1/index-live.m3u8"
    private fun segment(index: Long, rendition: String = "v1") = URI("https://edge.example/$rendition/seg-$index.ts")

    // A Twitch-shaped live playlist of two-second segments [first, last], with
    // optional discontinuities before the listed sequence numbers.
    private fun text(first: Long, last: Long, rendition: String = "v1", discontinuities: Set<Long> = emptySet(),
        discontinuitySequence: Long? = null, prefetch: Int = 0, durations: Map<Long, String> = emptyMap()) = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:$first\n")
        discontinuitySequence?.let { append("#EXT-X-DISCONTINUITY-SEQUENCE:$it\n") }
        append("#EXT-X-TWITCH-ELAPSED-SECS:${first * 2}.000\n")
        for (index in first..last) {
            if (index in discontinuities) append("#EXT-X-DISCONTINUITY\n")
            append("#EXT-X-PROGRAM-DATE-TIME:2026-11-25T12:00:${"%02d".format(index % 60)}.000Z\n")
            append("#EXTINF:${durations[index] ?: "2.000"},live\n")
            append("https://edge.example/$rendition/seg-$index.ts\n")
        }
        for (hint in 1..prefetch) append("#EXT-X-TWITCH-PREFETCH:https://edge.example/$rendition/seg-${last + hint}.ts\n")
    }

    private fun tag(output: String, name: String): Long =
        output.lines().single { it.startsWith("$name:") }.substringAfter(':').toLong()
    private fun uris(output: String): List<String> = output.lines().filter { it.startsWith("https://") }

    private fun fixture(): Pair<NativeLiveSegmentCache, NativeHlsLiveRecorder> {
        val cache = NativeLiveSegmentCache(executor = InlineExecutor())
        return cache to NativeHlsLiveRecorder(cache)
    }

    @Test fun `segments the window dropped stay listed while cached`() {
        val (cache, recorder) = fixture()
        val first = recorder.rewrite(playlist, text(100, 104))
        assertEquals(100L, tag(first, "#EXT-X-MEDIA-SEQUENCE"))
        assertEquals(0L, tag(first, "#EXT-X-DISCONTINUITY-SEQUENCE"))
        assertEquals((100L..104).map { segment(it).toString() }, uris(first))
        // The player fetched what it played; the cache keeps declared bodies.
        (100L..104).forEach { cache.put(segment(it), byteArrayOf(1)) }
        val slid = recorder.rewrite(playlist, text(103, 107))
        assertEquals(100L, tag(slid, "#EXT-X-MEDIA-SEQUENCE"))
        assertEquals((100L..107).map { segment(it).toString() }, uris(slid))
        // Retained entries keep their date and duration, ahead of the provider's tags.
        val lines = slid.lines()
        val retained = lines.indexOf(segment(100).toString())
        assertEquals("#EXTINF:2.000,live", lines[retained - 1])
        assertTrue(lines[retained - 2].startsWith("#EXT-X-PROGRAM-DATE-TIME:"))
        assertTrue(lines.indexOf("#EXT-X-TWITCH-ELAPSED-SECS:206.000") < retained)
        assertEquals(1, lines.count { it.startsWith("#EXT-X-MEDIA-SEQUENCE") })
        assertTrue(cache.accepts(segment(100)))
        assertTrue(cache.accepts(segment(107)))
    }

    @Test fun `the playlist start never moves back and a cache gap ends retention`() {
        val (cache, recorder) = fixture()
        recorder.rewrite(playlist, text(100, 104))
        (101L..103).forEach { cache.put(segment(it), byteArrayOf(1)) }
        // 100 is not cached, so the next playlist starts at 101.
        assertEquals(101L, tag(recorder.rewrite(playlist, text(103, 107)), "#EXT-X-MEDIA-SEQUENCE"))
        // A late recording of 100 cannot move the start back: the player would
        // treat an older-starting playlist as stale.
        cache.put(segment(100), byteArrayOf(1))
        assertEquals(101L, tag(recorder.rewrite(playlist, text(104, 108)), "#EXT-X-MEDIA-SEQUENCE"))
        // 104 was never cached: nothing older than it can be listed.
        assertEquals(105L, tag(recorder.rewrite(playlist, text(105, 109)), "#EXT-X-MEDIA-SEQUENCE"))
        assertEquals(105L, tag(recorder.rewrite(playlist, text(105, 110)), "#EXT-X-MEDIA-SEQUENCE"))
    }

    @Test fun `prefetch hints stay at the live edge and are replaced by the real segment`() {
        val (cache, recorder) = fixture()
        val hinted = recorder.rewrite(playlist, twitchLowLatencyPlaylist(text(100, 104, prefetch = 2)))
        assertEquals((100L..106).map { segment(it).toString() }, uris(hinted))
        assertTrue(hinted.trimEnd().endsWith(segment(106).toString()))
        (100L..106).forEach { cache.put(segment(it), byteArrayOf(1)) }
        // 105 became real with its own duration; one hint remains, one vanished.
        val next = recorder.rewrite(playlist, twitchLowLatencyPlaylist(
            text(102, 105, prefetch = 1, durations = mapOf(105L to "1.968"))))
        assertEquals(100L, tag(next, "#EXT-X-MEDIA-SEQUENCE"))
        assertEquals((100L..106).map { segment(it).toString() }, uris(next))
        assertTrue(next.contains("#EXTINF:1.968,live\n${segment(105)}"))
        // Later the hint-era entries are retained with their real durations.
        val later = recorder.rewrite(playlist, twitchLowLatencyPlaylist(text(106, 110, prefetch = 2)))
        assertTrue(later.contains("#EXTINF:1.968,live\n${segment(105)}"))
        assertEquals(100L, tag(later, "#EXT-X-MEDIA-SEQUENCE"))
    }

    @Test fun `retained discontinuities keep one numbering`() {
        val (cache, recorder) = fixture()
        // An ad break begins at 102 and ends at 104.
        val first = recorder.rewrite(playlist, text(100, 105, discontinuities = setOf(102, 104), discontinuitySequence = 7))
        assertEquals(7L, tag(first, "#EXT-X-DISCONTINUITY-SEQUENCE"))
        (100L..105).forEach { cache.put(segment(it), byteArrayOf(1)) }
        // The provider dropped 100..102 and counted the dropped discontinuity.
        val slid = recorder.rewrite(playlist, text(103, 107, discontinuities = setOf(104), discontinuitySequence = 8))
        assertEquals(100L, tag(slid, "#EXT-X-MEDIA-SEQUENCE"))
        assertEquals(7L, tag(slid, "#EXT-X-DISCONTINUITY-SEQUENCE"))
        val lines = slid.lines()
        assertEquals("#EXT-X-DISCONTINUITY", lines[lines.indexOf(segment(102).toString()) - 3])
        assertEquals(2, lines.count { it == "#EXT-X-DISCONTINUITY" })
    }

    @Test fun `a provider without a discontinuity sequence is numbered from its overlap`() {
        val (cache, recorder) = fixture()
        recorder.rewrite(playlist, text(100, 105, discontinuities = setOf(103)))
        (100L..105).forEach { cache.put(segment(it), byteArrayOf(1)) }
        // Without the tag, the provider's numbering restarts at the window.
        val slid = recorder.rewrite(playlist, text(104, 108))
        assertEquals(100L, tag(slid, "#EXT-X-MEDIA-SEQUENCE"))
        assertEquals(0L, tag(slid, "#EXT-X-DISCONTINUITY-SEQUENCE"))
        // Nothing retained: the discontinuity left with 103, so the provider's
        // own segments are numbered 1.
        val evicted = NativeHlsLiveRecorder(NativeLiveSegmentCache(executor = InlineExecutor()))
        evicted.rewrite(playlist, text(100, 105, discontinuities = setOf(103)))
        assertEquals(1L, tag(evicted.rewrite(playlist, text(104, 108)), "#EXT-X-DISCONTINUITY-SEQUENCE"))
    }

    @Test fun `another rendition's recording stands in after an adaptive switch`() {
        val (cache, recorder) = fixture()
        recorder.rewrite(playlist, text(100, 104))
        (100L..104).forEach { cache.put(segment(it), byteArrayOf(1)) }
        recorder.rewrite(playlist, text(102, 106))
        val other = "https://edge.example/v2/index-live.m3u8"
        val switched = recorder.rewrite(other, text(103, 107, rendition = "v2"))
        assertEquals(100L, tag(switched, "#EXT-X-MEDIA-SEQUENCE"))
        assertEquals((100L..102).map { segment(it).toString() } + (103L..107).map { segment(it, "v2").toString() },
            uris(switched))
    }

    @Test fun `the cache records due segments of the refreshed rendition`() {
        val fetched = mutableListOf<URI>()
        val (cache, recorder) = fixture()
        cache.attach { fetched += it }
        // The refreshed playlist is the rendition the player follows, even
        // before (or instead of) its own segment requests naming it. A 30 s
        // window: segments 7.5 s old or older are recorded.
        recorder.rewrite(playlist, text(100, 114))
        assertEquals((100L..110).map(::segment), fetched)
        // Unrecorded segments stay due on the next refresh while declared.
        fetched.clear()
        recorder.rewrite(playlist, text(101, 115))
        assertEquals((101L..111).map(::segment), fetched)
    }

    @Test fun `other shapes are left unchanged`() {
        val (_, recorder) = fixture()
        val live = text(100, 104)
        for (unsupported in listOf(
            "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nhttps://edge.example/v1/index-live.m3u8\n",
            live + "#EXT-X-ENDLIST\n",
            live.replace("#EXT-X-VERSION:3", "#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:EVENT"),
            live.replace("#EXT-X-VERSION:3", "#EXT-X-VERSION:3\n#EXT-X-MAP:URI=\"init.mp4\""),
            live.replace("#EXTINF:2.000,live\nhttps://edge.example/v1/seg-101.ts",
                "#EXTINF:2.000,live\n#EXT-X-BYTERANGE:100@0\nhttps://edge.example/v1/seg-101.ts"),
            live.replace("#EXT-X-TARGETDURATION:6\n", ""),
            live.replace("#EXTINF:2.000,live\nhttps://edge.example/v1/seg-101.ts", "https://edge.example/v1/seg-101.ts"),
            live.replace("#EXTINF:2.000,live\nhttps://edge.example/v1/seg-101.ts", "#EXTINF:abc,\nhttps://edge.example/v1/seg-101.ts"),
            "not a playlist",
        )) assertSame(unsupported, recorder.rewrite(playlist, unsupported))
    }

    @Test fun `a restarted sequence keeps nothing from before`() {
        val (cache, recorder) = fixture()
        recorder.rewrite(playlist, text(100, 104))
        (100L..104).forEach { cache.put(segment(it), byteArrayOf(1)) }
        recorder.rewrite(playlist, text(102, 106))
        val restarted = recorder.rewrite(playlist, text(0, 4))
        assertEquals(0L, tag(restarted, "#EXT-X-MEDIA-SEQUENCE"))
        assertEquals((0L..4).map { segment(it).toString() }, uris(restarted))
        assertFalse(restarted.contains(segment(100).toString()))
    }

    // A player that plays near live, then is skipped back behind Twitch's
    // window, paused while refreshes continue, and resumed (the 2026-11-25
    // SHIELD collapse). It reads every segment through the cache, which tees
    // network bodies; the cache records what the player does not fetch.
    private class Feed(val cache: NativeLiveSegmentCache, val recorder: NativeHlsLiveRecorder) {
        var readNext = 0L
        val windows = mutableListOf<NativeHlsLiveRecorder.Refresh>()
        // A player read: a cached body, else the network body the cache tees.
        fun read(uri: URI) {
            cache.lease(uri, player = true)?.close() ?: cache.put(uri, byteArrayOf(1))
        }
        fun refresh(playlist: String, first: Long, last: Long, rendition: String, readUpTo: Long?) {
            recorder.rewrite(playlist, buildString {
                append("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:$first\n")
                for (index in first..last) append("#EXTINF:2.000,live\nhttps://edge.example/$rendition/seg-$index.ts\n")
            })
            windows += checkNotNull(recorder.lastRefresh)
            // The player reads the rendition it last listed for each sequence.
            if (readUpTo != null) while (readNext <= readUpTo) {
                val listed = if (readNext >= first) URI("https://edge.example/$rendition/seg-$readNext.ts")
                    else URI("https://edge.example/v1/seg-$readNext.ts")
                read(listed); readNext++
            }
        }
    }

    private fun feed(): Feed {
        val cache = NativeLiveSegmentCache(executor = InlineExecutor())
        cache.attach { uri -> cache.put(uri, byteArrayOf(1)) }
        return Feed(cache, NativeHlsLiveRecorder(cache, log = {}))
    }

    @Test fun `a feed skipped back, paused and resumed keeps its history`() {
        val feed = feed()
        feed.readNext = 100
        // Play near live: a 15-segment (30 s) window, the player 3 behind.
        for (last in 114L..180L) feed.refresh(playlist, last - 14, last, "v1", last - 3)
        // Skip back a minute and pause: the player reads nothing new, and is
        // reported further behind live as refreshes continue.
        feed.readNext = 148
        for (last in 181L..195L) {
            feed.cache.setBehindLiveMs(70_000 + (last - 180) * 2_000)
            feed.refresh(playlist, last - 14, last, "v1", null)
        }
        // Resume: the player reads on from its buffered point.
        for (last in 196L..240L) feed.refresh(playlist, last - 14, last, "v1", feed.readNext + 1)
        assertCollapseFree(feed)
    }

    @Test fun `an adaptive switch while behind the window keeps recording the refreshed rendition`() {
        val feed = feed()
        feed.readNext = 100
        for (last in 114L..180L) feed.refresh(playlist, last - 14, last, "v1", last - 3)
        feed.readNext = 148
        feed.cache.setBehindLiveMs(70_000)
        // Media3 now refreshes another variant's playlist while it plays the
        // first variant's recorded stand-ins behind the window.
        val other = "https://edge.example/v2/index-live.m3u8"
        for (last in 181L..260L) feed.refresh(other, last - 14, last, "v2", feed.readNext + 1)
        assertCollapseFree(feed)
        assertEquals(2, feed.windows.last().rendition)
    }

    private fun assertCollapseFree(feed: Feed) {
        var previous: NativeHlsLiveRecorder.Refresh? = null
        // The first refresh has nothing older yet.
        for (window in feed.windows.drop(1)) {
            assertTrue("history stopped: $window", window.stop !in setOf(NativeHlsLiveRecorder.Stop.NOT_CACHED,
                NativeHlsLiveRecorder.Stop.NOT_DECLARED, NativeHlsLiveRecorder.Stop.DISCONTINUITY))
            previous?.let { before -> assertTrue("history shrank: $before -> $window",
                window.stop == NativeHlsLiveRecorder.Stop.HORIZON ||
                    window.retained + window.providerSegments >= before.retained + before.providerSegments) }
            previous = window
        }
        feed.cache.diagnostics().let { assertEquals(0L, it.recordSkipped + it.recordFetchFailed + it.recordNotStored) }
        // The listed history still reaches back past the player.
        assertTrue(feed.windows.last().firstSequence <= feed.readNext)
    }

    // Runs queued recordings only when the test lets it: a recorder that can
    // fetch at most a fixed number of segments per playlist refresh.
    private class SlowExecutor : AbstractExecutorService() {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue.addLast(command) }
        fun run(count: Int) = repeat(count) { queue.removeFirstOrNull()?.run() }
        override fun shutdown() = Unit
        override fun shutdownNow(): List<Runnable> = emptyList()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    // The 2026-11-26 SHIELD collapse: a Twitch-like 30 s window of 2 s
    // segments, a feed held about 70 s behind live while adaptive selection
    // keeps refreshing two variants in turn, and a recorder that fetches one
    // segment per refresh (real time, no faster).
    @Test fun `a held feed keeps its history while renditions alternate and recording is slow`() {
        val executor = SlowExecutor()
        val cache = NativeLiveSegmentCache(executor = executor)
        cache.attach { uri -> cache.put(uri, byteArrayOf(1)) }
        val feed = Feed(cache, NativeHlsLiveRecorder(cache, log = {}))
        val v3 = "https://edge.example/v3/index-live.m3u8"
        val v4 = "https://edge.example/v4/index-live.m3u8"
        feed.readNext = 1000
        // Near live on v3: the player fetches its own segments.
        for (last in 1015L..1060L) {
            feed.refresh(v3, last - 15, last, "v3", last - 3)
            executor.run(1)
        }
        // Held 70 s behind: the player reads nothing new while the selection
        // alternates between v4 and v3 every refresh.
        val held = 1060L - 3 - 35
        cache.setBehindLiveMs(70_000)
        for (last in 1061L..1160L) {
            if (last % 2 == 0L) feed.refresh(v4, last - 15, last, "v4", null)
            else feed.refresh(v3, last - 15, last, "v3", null)
            cache.setBehindLiveMs(70_000 + (last - 1060) * 2_000)
            executor.run(1)
        }
        // History starts at the first segment ever listed (1000) and never
        // breaks for want of a recording.
        for (window in feed.windows.drop(1)) {
            assertTrue("history stopped: $window", window.stop != NativeHlsLiveRecorder.Stop.NOT_CACHED &&
                (window.stop != NativeHlsLiveRecorder.Stop.NOT_DECLARED || window.firstSequence == 1000L))
            assertTrue("history no longer reaches the held point: $window", window.firstSequence <= held)
        }
        val counts = cache.diagnostics()
        assertEquals(0L, counts.recordSkipped + counts.recordFetchFailed + counts.recordNotStored)
        assertTrue(counts.inFlight <= 2)
    }

    @Test fun `diagnostics give closed reasons and counts without urls`() {
        val log = mutableListOf<String>()
        val cache = NativeLiveSegmentCache(executor = InlineExecutor())
        val recorder = NativeHlsLiveRecorder(cache, log = { log += it }, clockMs = { 0L })
        recorder.rewrite(playlist, text(100, 104))
        (101L..104).forEach { cache.put(segment(it), byteArrayOf(1)) }
        recorder.rewrite(playlist, text(103, 107))
        assertEquals(NativeHlsLiveRecorder.Stop.NOT_CACHED, recorder.lastRefresh?.stop)
        assertEquals(2, recorder.lastRefresh?.retained)
        assertEquals(4_000L, recorder.lastRefresh?.retainedMs)
        assertEquals(101L, recorder.lastRefresh?.firstSequence)
        recorder.rewrite(playlist, text(103, 108))
        assertEquals(NativeHlsLiveRecorder.Stop.PREVIOUS_START, recorder.lastRefresh?.stop)
        // An unchanged reason within 10 s is not repeated.
        recorder.rewrite(playlist, text(103, 109))
        assertEquals(3, log.size)
        assertTrue(log.all { it.startsWith("rewrite rendition=1 ") && "://" !in it && "edge.example" !in it })
        assertTrue(log[1], log[1].contains("retained=2 retainedMs=4000 firstSeq=101 discSeq=0 stop=NOT_CACHED"))
    }
}
