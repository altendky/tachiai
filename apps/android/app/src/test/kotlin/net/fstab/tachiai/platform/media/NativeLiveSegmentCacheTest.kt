package net.fstab.tachiai.platform.media

import androidx.media3.common.C
import java.net.URI
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class NativeLiveSegmentCacheTest {
    // Runs recordings inline so tests are deterministic.
    private class InlineExecutor : AbstractExecutorService() {
        var shutdown = false
        override fun execute(command: Runnable) { if (shutdown) throw java.util.concurrent.RejectedExecutionException() else command.run() }
        override fun shutdown() { shutdown = true }
        override fun shutdownNow(): List<Runnable> { shutdown = true; return emptyList() }
        override fun isShutdown() = shutdown
        override fun isTerminated() = shutdown
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private val base = 1_800_000_000_000L
    private fun uri(rendition: String, index: Int, period: String = "p1") = URI("https://cdn.example/$period/$rendition/$index.m4s")
    private fun init(rendition: String, period: String = "p1") = URI("https://cdn.example/$period/$rendition/init.mp4")
    // Five-second segments [first, last] for each rendition; times relative to base.
    private fun window(first: Int, last: Int, renditions: List<Pair<String, Int>> = listOf("v1" to 4_000_000, "v2" to 1_000_000),
        period: String = "p1", track: Int = C.TRACK_TYPE_VIDEO): Map<URI, NativeLiveSegmentCache.Segment> = buildMap {
        for ((rendition, bandwidth) in renditions) {
            put(init(rendition, period), NativeLiveSegmentCache.Segment(period, 0, track, rendition, bandwidth, 0, 0, 0, true))
            for (index in first..last) put(uri(rendition, index, period), NativeLiveSegmentCache.Segment(period, 0, track,
                rendition, bandwidth, index * 5_000_000L, base + index * 5_000L, base + (index + 1) * 5_000L, false))
        }
    }

    @Test fun servesOnlyDeclaredCompleteBodiesAndNotesThePlayersRendition() {
        val cache = NativeLiveSegmentCache(executor = InlineExecutor())
        cache.publish(window(0, 11), 60_000)
        cache.put(URI("https://cdn.example/undeclared.m4s"), byteArrayOf(1))
        assertNull(cache.lease(URI("https://cdn.example/undeclared.m4s"), player = true))
        cache.put(uri("v1", 3), byteArrayOf(1, 2, 3))
        val body = checkNotNull(cache.lease(uri("v1", 3), player = true)).body
        assertEquals(3, body.remaining())
        assertTrue(body.isReadOnly)
        assertEquals(setOf(15_000_000L), cache.cachedStartsUs("p1", 0))
        assertEquals(3L, cache.bytes)
    }

    @Test fun recordsTheRenditionInUseShortlyBeforeItLeavesTheProviderWindow() {
        val fetched = mutableListOf<URI>()
        val cache = NativeLiveSegmentCache(executor = InlineExecutor())
        cache.attach { fetched += it }
        cache.publish(window(0, 11), 60_000)
        assertTrue("nothing is recorded before the player picks a rendition", fetched.isEmpty())
        cache.notePlayerRequest(uri("v1", 2))
        cache.publish(window(0, 11), 60_000)
        // Newest end is base+60 s; segments ending at or before base+30 s are due,
        // from the player's first request onward, plus the rendition's initialization.
        assertEquals(listOf(init("v1")) + (2..5).map { uri("v1", it) }, fetched)
        assertTrue(fetched.none { it.path.contains("/v2/") })
    }

    @Test fun keepsTheProviderWindowPlusRetentionAndEvictsOldestFirst() {
        val cache = NativeLiveSegmentCache(retentionMs = 20_000, executor = InlineExecutor())
        cache.publish(window(0, 11), 60_000)
        cache.notePlayerRequest(uri("v1", 0))
        (0..11).forEach { cache.put(uri("v1", it), ByteArray(10)) }
        // Twelve segments later the provider window starts at segment 12. With
        // 20 s of retention, segments ending at or after 40 s remain (7..11).
        cache.publish(window(12, 23), 60_000)
        assertEquals((7..11).map { it * 5_000_000L }.toSet(), cache.cachedStartsUs("p1", 0))
        val bytesCapped = NativeLiveSegmentCache(maxBytes = 25, executor = InlineExecutor())
        bytesCapped.publish(window(0, 11), 60_000)
        bytesCapped.notePlayerRequest(uri("v1", 0))
        bytesCapped.put(init("v1"), ByteArray(5))
        (0..3).forEach { bytesCapped.put(uri("v1", it), ByteArray(10)) }
        // 25 bytes hold the 5-byte initialization and the two newest 10-byte segments.
        assertEquals(setOf(10_000_000L, 15_000_000L), bytesCapped.cachedStartsUs("p1", 0))
        assertNotNull("initialization survives the byte cap", bytesCapped.lease(init("v1"), player = false))
    }

    @Test fun followsTheSameRenditionIntoANewPeriodOrTheClosestBandwidth() {
        val cache = NativeLiveSegmentCache(executor = InlineExecutor())
        cache.publish(window(0, 11) + window(0, 3, listOf("ad-hi" to 3_500_000, "ad-lo" to 900_000), period = "ad"), 60_000)
        cache.notePlayerRequest(uri("v1", 1))
        cache.put(uri("ad-hi", 0, "ad"), byteArrayOf(1))
        cache.put(uri("ad-lo", 1, "ad"), byteArrayOf(1))
        assertEquals(setOf(0L), cache.cachedStartsUs("ad", 0))
        assertFalse(cache.periodRetainable("ad"))
        (0..3).forEach { cache.put(uri("ad-hi", it, "ad"), byteArrayOf(1)) }
        cache.put(init("ad-hi", "ad"), byteArrayOf(1))
        assertTrue(cache.periodRetainable("ad"))
    }

    @Test fun closingDropsEverythingAndRefusesLaterWork() {
        val executor = InlineExecutor()
        val cache = NativeLiveSegmentCache(executor = executor)
        cache.publish(window(0, 11), 60_000)
        cache.put(uri("v1", 1), byteArrayOf(1))
        cache.close()
        assertTrue(executor.shutdown)
        assertEquals(0L, cache.bytes)
        assertNull(cache.lease(uri("v1", 1), player = true))
        cache.put(uri("v1", 2), byteArrayOf(1))
        assertEquals(0L, cache.bytes)
        assertTrue(cache.cachedStartsUs("p1", 0).isEmpty())
    }
}
