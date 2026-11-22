package net.fstab.tachiai.platform.media

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.URI
import java.time.Instant
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

// #568: a live manifest keeps timeline entries the provider dropped while the
// rendition in use has them cached, and no further back than a cache gap.
@UnstableApi
@RunWith(AndroidJUnit4::class)
class BoundedDashManifestRetentionTest {
    private class InlineExecutor : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow(): List<Runnable> = emptyList()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private val manifest = URI("https://linear-abematv.akamaized.net/channel/abema-news/manifest.mpd")
    private val base = "https://linear-abematv.akamaized.net/channel/abema-news/"
    private val availability = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()

    // Five-second segments starting at `first` seconds, twelve of them (60 s).
    private fun mpd(first: Int) = """
        <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" type="dynamic" availabilityStartTime="2026-01-01T00:00:00Z"
          minimumUpdatePeriod="PT5S" timeShiftBufferDepth="PT60S" minBufferTime="PT2S">
          <Period id="p1" start="PT0S">
            <AdaptationSet mimeType="video/mp4" codecs="avc1.4d401f">
              <SegmentTemplate timescale="1" initialization="init-${'$'}RepresentationID${'$'}.mp4"
                media="${'$'}RepresentationID${'$'}-${'$'}Time${'$'}.m4s">
                <SegmentTimeline><S t="$first" d="5" r="11"/></SegmentTimeline>
              </SegmentTemplate>
              <Representation id="high" bandwidth="4000000" width="1920" height="1080"/>
              <Representation id="low" bandwidth="1000000" width="640" height="360"/>
            </AdaptationSet>
          </Period>
        </MPD>
    """.trimIndent()

    private fun segment(rendition: String, seconds: Int) = URI("$base$rendition-$seconds.m4s")

    private fun run(cachedSeconds: List<Int>): Pair<Long, DeclaredDashMediaPolicy> {
        val cache = NativeLiveSegmentCache(executor = InlineExecutor())
        val policy = DeclaredDashMediaPolicy(manifest) { true }
        var nowMs = availability + 100_000
        val parser = BoundedDashManifestParser(manifest, policy, { true }, { true }, { _, _ -> },
            wallMs = { nowMs }, liveCache = cache)
        val uri = Uri.parse(manifest.toString())
        parser.parse(uri, mpd(40).byteInputStream())
        cache.notePlayerRequest(segment("high", 40))
        cachedSeconds.forEach { cache.put(segment("high", it), byteArrayOf(1)) }
        nowMs += 10_000
        parser.parse(uri, mpd(50).byteInputStream())
        // A third refresh, so the allowlist's retained previous snapshot is
        // itself a post-retention declaration.
        nowMs += 5_000
        val refreshed = parser.parse(uri, mpd(55).byteInputStream())
        val index = checkNotNull(refreshed.getPeriod(0).adaptationSets.single().representations.first().index)
        assertEquals(60_000L + RETAINED_WINDOW_MS, refreshed.timeShiftBufferDepthMs)
        return index.getTimeUs(index.firstSegmentNum) to policy
    }

    @Test fun droppedEntriesOfTheCachedRenditionStayInTheManifestAndAllowlist() {
        val (firstUs, policy) = run(listOf(40, 45, 50, 55, 60))
        assertEquals(40_000_000L, firstUs)
        assertTrue(policy.allows(segment("high", 40)))
        assertTrue(policy.allows(segment("high", 45)))
    }

    @Test fun aGapInTheCacheEndsRetention() {
        val (firstUs, policy) = run(listOf(40, 50, 55, 60))
        assertEquals(50_000_000L, firstUs)
        assertFalse(policy.allows(segment("high", 40)))
        assertFalse(policy.allows(segment("high", 45)))
    }

    @Test fun nothingIsRetainedWithoutACache() {
        val policy = DeclaredDashMediaPolicy(manifest) { true }
        var nowMs = availability + 100_000
        val parser = BoundedDashManifestParser(manifest, policy, { true }, { true }, { _, _ -> }, wallMs = { nowMs })
        val uri = Uri.parse(manifest.toString())
        parser.parse(uri, mpd(40).byteInputStream())
        nowMs += 10_000
        val refreshed = parser.parse(uri, mpd(50).byteInputStream())
        val index = checkNotNull(refreshed.getPeriod(0).adaptationSets.single().representations.first().index)
        assertEquals(50_000_000L, index.getTimeUs(index.firstSegmentNum))
        assertEquals(60_000L, refreshed.timeShiftBufferDepthMs)
    }
}
