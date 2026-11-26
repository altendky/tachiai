package net.fstab.tachiai.platform.media

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

// #618: the rewind buffer's recorder fetches in the background. Its refusals
// and failures must not latch onto the feed: on the emulator a healthy ABEMA
// feed ended labeled MEDIA_BLOCKED (SOURCE_NOT_ALLOWLISTED, http=0), which
// also blocks automatic recovery.
@UnstableApi
class NativeLiveRecorderIsolationTest {
    private class Connection(url: URL, val status: Int = 200, val body: ByteArray = "segment".toByteArray()) :
        HttpsURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode() = status
        override fun getContentLengthLong() = body.size.toLong()
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }

    // Runs only when asked, like the cache's single background thread
    // falling behind.
    private class QueuedExecutor : AbstractExecutorService() {
        val queue = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { queue.addLast(command) }
        fun runAll() { while (queue.isNotEmpty()) queue.removeFirst().run() }
        override fun shutdown() = Unit
        override fun shutdownNow(): List<Runnable> = emptyList()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private val segment = URI("https://cdn.example/p1/v1/3.m4s")

    private class Requests(allowed: Boolean, status: Int = 200) {
        val events = mutableListOf<NativeMediaEvent>()
        val failures = mutableListOf<NativeFailureStage>()
        val requests = BoundedMediaRequests(NativePlaybackBudget(120_000, { true }), { allowed }, { event, _ -> events += event },
            openConnection = { Connection(it, status) }, onFailure = { stage, _ -> failures += stage })
    }

    private fun drain(source: BoundedMediaRequests.Source) {
        val buffer = ByteArray(64)
        while (source.read(buffer, 0, buffer.size) != C.RESULT_END_OF_INPUT) Unit
    }

    @Test fun aRecorderRefusedByThePolicyReportsNothingButIsStillRefused() {
        val recorder = Requests(allowed = false)
        assertThrows(IOException::class.java) { recorder.requests.create(C.DATA_TYPE_MEDIA, recorder = true).openUri(segment) }
        assertTrue(recorder.events.isEmpty())
        assertTrue(recorder.failures.isEmpty())
        // The player's own request is still the feed's refusal.
        val player = Requests(allowed = false)
        assertThrows(IOException::class.java) { player.requests.create(C.DATA_TYPE_MEDIA).openUri(segment) }
        assertEquals(NativeMediaEvent.SOURCE_NOT_ALLOWLISTED, player.events.first())
        assertTrue(player.failures.isNotEmpty())
    }

    @Test fun aRecorderHttpFailureIsNotTheFeeds() {
        val recorder = Requests(allowed = true, status = 403)
        assertThrows(IOException::class.java) { recorder.requests.create(C.DATA_TYPE_MEDIA, recorder = true).openUri(segment) }
        assertTrue(recorder.events.none { it == NativeMediaEvent.HTTP_REJECTED || it == NativeMediaEvent.REQUEST_FAILED })
        assertTrue(recorder.failures.isEmpty())
        val player = Requests(allowed = true, status = 403)
        assertThrows(IOException::class.java) { player.requests.create(C.DATA_TYPE_MEDIA).openUri(segment) }
        assertTrue(NativeMediaEvent.HTTP_REJECTED in player.events)
    }

    @Test fun aRecordingDoesNotUseUpThePlayersOnceOnlyEvents() {
        val both = Requests(allowed = true)
        val recording = both.requests.create(C.DATA_TYPE_MEDIA, recorder = true)
        recording.openUri(segment); drain(recording); recording.close()
        assertTrue(both.events.isEmpty())
        val playing = both.requests.create(C.DATA_TYPE_MEDIA)
        playing.openUri(segment); drain(playing); playing.close()
        assertTrue(NativeMediaEvent.MEDIA_BYTES in both.events)
    }

    @Test fun aQueuedRecordingThatLeftTheManifestIsNotFetched() {
        val executor = QueuedExecutor()
        val cache = NativeLiveSegmentCache(executor = executor)
        val fetched = mutableListOf<URI>()
        cache.attach { fetched += it }
        val base = 1_800_000_000_000L
        fun window(first: Int, last: Int) = (first..last).associate { index ->
            URI("https://cdn.example/p1/v1/$index.m4s") to NativeLiveSegmentCache.Segment("p1", 0, C.TRACK_TYPE_VIDEO, "v1",
                1, index * 5_000_000L, base + index * 5_000L, base + (index + 1) * 5_000L, false)
        }
        cache.publish(window(0, 11), 60_000)
        cache.notePlayerRequest(URI("https://cdn.example/p1/v1/8.m4s"))
        cache.publish(window(0, 11), 60_000)
        assertTrue(executor.queue.isNotEmpty())
        // The queue falls behind while the provider's window moves on: the
        // oldest due segments leave the manifest before their turn.
        cache.publish(window(3, 14), 60_000)
        executor.runAll()
        assertTrue(fetched.none { it.path.endsWith("/0.m4s") || it.path.endsWith("/1.m4s") || it.path.endsWith("/2.m4s") })
        assertTrue(fetched.isNotEmpty())
    }
}
