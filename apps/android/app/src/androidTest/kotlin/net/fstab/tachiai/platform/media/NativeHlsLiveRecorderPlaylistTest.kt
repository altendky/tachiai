package net.fstab.tachiai.platform.media

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.URI
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// #618: Media3 reads a recorder-extended live playlist as one sliding window
// that starts earlier: each refresh is newer, sequence and discontinuity
// numbers match the provider's, and retained segments keep their durations.
@UnstableApi
@RunWith(AndroidJUnit4::class)
class NativeHlsLiveRecorderPlaylistTest {
    private class InlineExecutor : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow(): List<Runnable> = emptyList()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private val playlist = "https://video-weaver.example.hls.ttvnw.net/v1/playlist/live.m3u8"

    private fun text(first: Long, last: Long, discontinuities: Set<Long>, discontinuitySequence: Long) = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:$first\n")
        append("#EXT-X-DISCONTINUITY-SEQUENCE:$discontinuitySequence\n")
        for (index in first..last) {
            if (index in discontinuities) append("#EXT-X-DISCONTINUITY\n")
            append("#EXT-X-PROGRAM-DATE-TIME:2026-11-25T12:%02d:%02d.000Z\n".format(index * 2 / 60 % 60, index * 2 % 60))
            append("#EXTINF:2.000,live\nhttps://video-edge.example.abs.hls.ttvnw.net/v1/segment/$index.ts\n")
        }
    }

    private fun parse(text: String): HlsMediaPlaylist =
        HlsPlaylistParser().parse(Uri.parse(playlist), text.byteInputStream()) as HlsMediaPlaylist

    @Test fun retainedSegmentsExtendTheWindowAndEachRefreshIsNewer() {
        val cache = NativeLiveSegmentCache(executor = InlineExecutor())
        val recorder = NativeHlsLiveRecorder(cache)
        val first = parse(recorder.rewrite(playlist, text(100, 114, setOf(110), 3)))
        (100L..114).forEach { cache.put(URI("https://video-edge.example.abs.hls.ttvnw.net/v1/segment/$it.ts"), byteArrayOf(1)) }
        // The discontinuity at 110 left the provider's window.
        val second = parse(recorder.rewrite(playlist, text(111, 125, emptySet(), 4)))
        assertTrue(second.isNewerThan(first))
        assertEquals(100L, second.mediaSequence)
        assertEquals(26, second.segments.size)
        assertEquals(3, second.discontinuitySequence)
        assertEquals(52_000_000L, second.durationUs)
        // Segment 111 keeps the provider's discontinuity number 4.
        assertEquals(1, second.segments[11].relativeDiscontinuitySequence)
        assertEquals(first.startTimeUs, second.startTimeUs)
        val third = parse(recorder.rewrite(playlist, text(112, 126, emptySet(), 4)))
        assertTrue(third.isNewerThan(second))
        assertEquals(100L, third.mediaSequence)
    }
}
