package net.fstab.tachiai.platform.media

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.*
import org.junit.Test

@UnstableApi
class NativeLiveSegmentCacheTransportTest {
    private class InlineExecutor : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow(): List<Runnable> = emptyList()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private class Connection(url: URL, val body: ByteArray, val length: Long = body.size.toLong(),
        val status: Int = 200) : HttpsURLConnection(url) {
        var range: String? = null
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode() = status
        override fun getContentLengthLong() = length
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
        override fun setRequestProperty(key: String, value: String) { if (key == "Range") range = value }
    }

    private val segment = URI("https://cdn.example/p1/v1/3.m4s")
    private fun cache() = NativeLiveSegmentCache(executor = InlineExecutor()).also {
        it.publish(mapOf(segment to NativeLiveSegmentCache.Segment("p1", 0, C.TRACK_TYPE_VIDEO, "v1", 1, 15_000_000,
            1_000_015_000, 1_000_020_000, false)), 60_000)
    }
    private fun read(source: BoundedMediaRequests.Source): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4)
        while (true) { val count = source.read(buffer, 0, buffer.size); if (count == C.RESULT_END_OF_INPUT) break; out.write(buffer, 0, count) }
        return out.toByteArray()
    }

    @Test fun aCompleteUnrangedResponseIsCachedAndLaterServedWithoutTheNetwork() {
        val cache = cache()
        var opened = 0
        val requests = BoundedMediaRequests(NativePlaybackBudget(120_000, { true }), { true }, { _, _ -> },
            openConnection = { opened++; Connection(it, "segment".toByteArray()) }, cache = cache)
        val first = requests.create(C.DATA_TYPE_MEDIA)
        first.openUri(segment); assertEquals("segment", String(read(first))); first.close()
        assertEquals(1, opened)
        assertEquals(7L, cache.bytes)
        val second = requests.create(C.DATA_TYPE_MEDIA)
        assertEquals(7L, second.openUri(segment))
        assertEquals("segment", String(read(second))); second.close()
        assertEquals("served from memory", 1, opened)
        val ranged = requests.create(C.DATA_TYPE_MEDIA)
        assertEquals(3L, ranged.openUri(segment, position = 2, length = 3))
        assertEquals("gme", String(read(ranged))); ranged.close()
        assertEquals(1, opened)
    }

    @Test fun rangedTruncatedUndeclaredOrRefusedResponsesAreNotCached() {
        val cache = cache()
        val requests = BoundedMediaRequests(NativePlaybackBudget(120_000, { true }), { true }, { _, _ -> },
            openConnection = { url -> when {
                url.path.contains("short") -> Connection(url, "seg".toByteArray(), length = 7)
                else -> Connection(url, "segment".toByteArray(), status = 206)
            } }, cache = cache)
        val ranged = requests.create(C.DATA_TYPE_MEDIA)
        ranged.openUri(segment, position = 1); read(ranged); ranged.close()
        assertEquals(0L, cache.bytes)
        val undeclared = requests.create(C.DATA_TYPE_MEDIA)
        undeclared.openUri(URI("https://cdn.example/short/9.m4s")); read(undeclared); undeclared.close()
        assertEquals(0L, cache.bytes)
    }

    @Test fun aCacheHitStillRequiresTheAllowlistAndAnActiveBudget() {
        val cache = cache()
        cache.put(segment, "segment".toByteArray())
        val refused = BoundedMediaRequests(NativePlaybackBudget(120_000, { true }), { false }, { _, _ -> },
            openConnection = { error("no network") }, cache = cache)
        assertThrows(java.io.IOException::class.java) { refused.create(C.DATA_TYPE_MEDIA).openUri(segment) }
        val stopped = NativePlaybackBudget(120_000, { true }).also { it.stop() }
        val inactive = BoundedMediaRequests(stopped, { true }, { _, _ -> }, openConnection = { error("no network") }, cache = cache)
        assertThrows(java.io.IOException::class.java) { inactive.create(C.DATA_TYPE_MEDIA).openUri(segment) }
    }
}
