package net.fstab.tachiai.platform.media

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.CookieHandler
import java.net.ConnectException
import java.net.URI
import java.net.URL
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import net.fstab.tachiai.platform.network.ProviderTransportEvidence
import net.fstab.tachiai.platform.network.ProviderTransportException
import net.fstab.tachiai.platform.network.ProviderTransportFailure
import net.fstab.tachiai.platform.network.ProviderTransportPhase
import org.junit.Assert.*
import org.junit.Test

@UnstableApi
class BoundedMediaRequestsTest {
    private val uri = URI("https://media.ttvnw.net/fixture.m3u8?token=synthetic")
    private class Connection(url: URL, val status: Int = 200, val length: Long = -1,
        val body: ByteArray = "#EXTM3U\n".toByteArray(), val beforeResponse: () -> Unit = {},
        val stream: InputStream? = null, val errorBody: ByteArray? = null,
        val disconnectFailure: Exception? = null) : HttpsURLConnection(url) {
        var disconnected = false
        var bodyRead = false
        var errorRead = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true; disconnectFailure?.let { throw it } }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode(): Int { beforeResponse(); return status }
        override fun getContentLengthLong() = length
        override fun getInputStream(): InputStream { bodyRead = true; return stream ?: ByteArrayInputStream(body) }
        override fun getErrorStream(): InputStream? {
            errorRead = true
            return errorBody?.let { ByteArrayInputStream(it) }
        }
    }
    private fun requests(connection: Connection, canRequest: () -> Boolean = { true },
        clock: () -> Long = { 1_000L }, events: MutableList<NativeMediaEvent> = mutableListOf()) =
        BoundedMediaRequests(NativePlaybackBudget(120_000, { true }), { it.host == uri.host },
            { event, _ -> events += event }, canRequest, { connection }, clock)

    @Test fun `originating open and read transport failures reach observers as safe categories`() {
        val secret = "https://private.example/path?token=synthetic-secret"
        val failures = listOf(
            UnknownHostException(secret) to NativeMediaRequestFailure.DNS,
            ConnectException(secret) to NativeMediaRequestFailure.CONNECT,
            SocketTimeoutException(secret) to NativeMediaRequestFailure.TIMEOUT,
            SSLHandshakeException(secret) to NativeMediaRequestFailure.TLS,
            IOException(secret) to NativeMediaRequestFailure.IO,
            ProviderTransportException(ProviderTransportEvidence(ProviderTransportFailure.TIMEOUT,
                ProviderTransportPhase.RESPONSE)) to NativeMediaRequestFailure.TIMEOUT,
        )
        for ((original, expected) in failures) {
            for (opening in listOf(true, false)) {
                val seen = mutableListOf<Pair<NativeFailureStage, Throwable>>()
                val connection = Connection(uri.toURL(), beforeResponse = { if (opening) throw original },
                    stream = object : InputStream() {
                        override fun read(): Int = throw original
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = read()
                    })
                val requests = BoundedMediaRequests(NativePlaybackBudget(120_000, { true }), { true }, { _, _ -> },
                    openConnection = { connection }, onFailure = { stage, error -> seen += stage to error })
                val source = requests.create(C.DATA_TYPE_MEDIA)
                val outward = assertThrows(IOException::class.java) {
                    source.openUri(uri)
                    source.read(ByteArray(1), 0, 1)
                }
                assertEquals(if (opening) "Native media request failed" else "Native media read failed", outward.message)
                assertNull(outward.cause); assertTrue(outward.suppressed.isEmpty())
                val (stage, observed) = seen.single()
                assertEquals(if (opening) NativeFailureStage.MEDIA_REQUEST_OPEN else NativeFailureStage.MEDIA_REQUEST_READ, stage)
                assertEquals(expected, (observed as NativeMediaRequestException).failure)
                assertNull(observed.cause); assertTrue(observed.suppressed.isEmpty())
                assertFalse(observed.stackTraceToString().contains(secret))
                assertTrue(connection.disconnected)
            }
        }
    }

    @Test fun `post read request deadline admission refusal and inactive playback remain distinct`() {
        for (expected in listOf(NativeMediaRequestFailure.REQUEST_DEADLINE,
                NativeMediaRequestFailure.REQUEST_ADMISSION_REFUSED, NativeMediaRequestFailure.PLAYBACK_INACTIVE)) {
            var now = 1_000L
            var admitted = true
            val budget = NativePlaybackBudget(120_000, { true })
            val connection = Connection(uri.toURL(), stream = object : ByteArrayInputStream(byteArrayOf(1)) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    when (expected) {
                        NativeMediaRequestFailure.REQUEST_DEADLINE -> now += 30_000
                        NativeMediaRequestFailure.REQUEST_ADMISSION_REFUSED -> admitted = false
                        NativeMediaRequestFailure.PLAYBACK_INACTIVE -> budget.stop()
                        else -> fail("Unexpected fixture category")
                    }
                    return super.read(buffer, offset, length)
                }
            })
            val seen = mutableListOf<Pair<NativeFailureStage, NativeMediaRequestFailure>>()
            val source = BoundedMediaRequests(budget, { true }, { _, _ -> }, canRequest = { admitted },
                openConnection = { connection }, clockMs = { now }, onFailure = { stage, error ->
                    seen += stage to (error as NativeMediaRequestException).failure
                }).create(C.DATA_TYPE_MEDIA)
            source.openUri(uri)
            val error = assertThrows(IOException::class.java) { source.read(ByteArray(1), 0, 1) }
            assertEquals("Native media read failed", error.message)
            assertNull(error.cause)
            assertEquals(listOf(NativeFailureStage.MEDIA_REQUEST_READ to expected), seen)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun `admission refusal before dispatch is observed without opening a connection`() {
        val seen = mutableListOf<Pair<NativeFailureStage, NativeMediaRequestFailure>>()
        val requests = BoundedMediaRequests(NativePlaybackBudget(120_000, { true }), { true }, { _, _ -> },
            canRequest = { false }, openConnection = { throw AssertionError("Refused request opened") },
            onFailure = { stage, error -> seen += stage to (error as NativeMediaRequestException).failure })
        assertThrows(IOException::class.java) { requests.create(C.DATA_TYPE_MEDIA).openUri(uri) }
        assertEquals(listOf(NativeFailureStage.MEDIA_REQUEST_OPEN to NativeMediaRequestFailure.REQUEST_ADMISSION_REFUSED), seen)
    }

    @Test fun `expired admission deadline is not mistaken for the fixed request deadline or owner cancellation`() {
        var now = 1_000L
        val admissionDeadline = 2_000L
        val budget = NativePlaybackBudget.continuous({ true })
        val connection = Connection(uri.toURL(), stream = object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                now = admissionDeadline
                return super.read(buffer, offset, length)
            }
        })
        val seen = mutableListOf<Pair<NativeFailureStage, NativeMediaRequestFailure>>()
        val source = BoundedMediaRequests(budget, { true }, { _, _ -> },
            canRequest = { now < admissionDeadline }, openConnection = { connection }, clockMs = { now },
            onFailure = { stage, error -> seen += stage to (error as NativeMediaRequestException).failure })
            .create(C.DATA_TYPE_MEDIA)
        source.openUri(uri)
        assertThrows(IOException::class.java) { source.read(ByteArray(1), 0, 1) }
        assertTrue(budget.active)
        assertTrue(now - 1_000L < 30_000L)
        assertEquals(listOf(NativeFailureStage.MEDIA_REQUEST_READ to NativeMediaRequestFailure.REQUEST_ADMISSION_REFUSED), seen)
        assertTrue(connection.disconnected)
    }

    @Test fun `read origin is observed before independently failing stream cleanup`() {
        val origin = SocketTimeoutException("Synthetic private signed URL")
        val cleanup = IllegalStateException("Synthetic private cleanup detail")
        val stream = object : InputStream() {
            override fun read(): Int = throw origin
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = read()
            override fun close() { throw cleanup }
        }
        val connection = Connection(uri.toURL(), stream = stream)
        val seen = mutableListOf<Pair<NativeFailureStage, Throwable>>()
        val source = BoundedMediaRequests(NativePlaybackBudget(120_000, { true }), { true }, { _, _ -> },
            openConnection = { connection }, onFailure = { stage, error -> seen += stage to error }).create(C.DATA_TYPE_MEDIA)
        source.openUri(uri)
        val outward = assertThrows(IOException::class.java) { source.read(ByteArray(1), 0, 1) }
        assertEquals("Native media cleanup unconfirmed", outward.message)
        assertNull(outward.cause); assertTrue(outward.suppressed.isEmpty())
        assertEquals(listOf(NativeFailureStage.MEDIA_REQUEST_READ, NativeFailureStage.MEDIA_DISCONNECT), seen.map { it.first })
        assertEquals(NativeMediaRequestFailure.TIMEOUT, (seen[0].second as NativeMediaRequestException).failure)
        assertSame(cleanup, seen[1].second)
        assertTrue(connection.disconnected)
    }

    @Test fun `open origin is observed before independently failing connection cleanup`() {
        val cleanup = IllegalStateException("Synthetic private cleanup detail")
        val connection = Connection(uri.toURL(), beforeResponse = { throw UnknownHostException("Synthetic private host") },
            disconnectFailure = cleanup)
        val seen = mutableListOf<Pair<NativeFailureStage, Throwable>>()
        val source = BoundedMediaRequests(NativePlaybackBudget(120_000, { true }), { true }, { _, _ -> },
            openConnection = { connection }, onFailure = { stage, error -> seen += stage to error }).create(C.DATA_TYPE_MEDIA)
        val outward = assertThrows(IOException::class.java) { source.openUri(uri) }
        assertEquals("Native media cleanup unconfirmed", outward.message)
        assertNull(outward.cause); assertTrue(outward.suppressed.isEmpty())
        assertEquals(listOf(NativeFailureStage.MEDIA_REQUEST_OPEN, NativeFailureStage.MEDIA_DISCONNECT), seen.map { it.first })
        assertEquals(NativeMediaRequestFailure.DNS, (seen[0].second as NativeMediaRequestException).failure)
        assertSame(cleanup, seen[1].second)
        assertTrue(connection.disconnected)
    }

    @Test fun `rejected manifest read origin survives failing finally cleanup without duplicate origins`() {
        val origin = ProviderTransportException(ProviderTransportEvidence(ProviderTransportFailure.CONNECT))
        val cleanup = AssertionError("Synthetic private cleanup detail")
        val connection = Connection(uri.toURL(), status = 404)
        val seen = mutableListOf<Pair<NativeFailureStage, Throwable>>()
        val source = BoundedMediaRequests(NativePlaybackBudget(120_000, { true }), { true }, { _, _ -> },
            openConnection = { connection }, openErrorInput = { object : InputStream() {
                override fun read(): Int = throw origin
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = read()
                override fun close() { throw cleanup }
            } }, onFailure = { stage, error -> seen += stage to error }).create(C.DATA_TYPE_MANIFEST)
        val outward = assertThrows(IOException::class.java) { source.openUri(uri) }
        assertEquals("Native media request failed", outward.message)
        assertNull(outward.cause); assertTrue(outward.suppressed.isEmpty())
        assertEquals(listOf(NativeFailureStage.MEDIA_REQUEST_OPEN, NativeFailureStage.MEDIA_DISCONNECT), seen.map { it.first })
        assertEquals(NativeMediaRequestFailure.CONNECT, (seen[0].second as NativeMediaRequestException).failure)
        assertSame(cleanup, seen[1].second)
        assertTrue(connection.disconnected)
    }

    @Test fun `active media cancellation preserves the original disconnect failure and reports its stage`() {
        val error = IllegalStateException("synthetic token must not be rendered by observer")
        val connection = Connection(uri.toURL(), disconnectFailure = error)
        val seen = mutableListOf<Pair<NativeFailureStage, Throwable>>()
        val requests = BoundedMediaRequests(NativePlaybackBudget(120_000, { true }), { true }, { _, _ -> },
            openConnection = { connection }, onFailure = { stage, value -> seen += stage to value })
        requests.create(C.DATA_TYPE_MANIFEST).openUri(uri)
        assertSame(error, assertThrows(IllegalStateException::class.java) { requests.close() })
        assertTrue(connection.disconnected)
        assertEquals(listOf(NativeFailureStage.MEDIA_DISCONNECT to error), seen)
    }

    @Test fun `media sends no authorization client or cookie headers`() {
        val connection = Connection(uri.toURL())
        val requests = requests(connection)
        val source = requests.create(C.DATA_TYPE_MANIFEST)
        source.openUri(uri)
        assertEquals("GET", connection.requestMethod)
        for (header in listOf("Authorization", "Client-ID", "Cookie")) assertNull(connection.getRequestProperty(header))
        assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertEquals(5_000, connection.connectTimeout)
        assertEquals(10_000, connection.readTimeout)
        source.close()
        assertTrue(connection.disconnected)
    }

    @Test fun `redirects and rejected statuses cannot fetch next destination or response body`() {
        for (status in listOf(301, 302, 307, 308, 401, 403, 404)) {
            val connection = Connection(uri.toURL(), status = status)
            val requests = requests(connection)
            val error = assertThrows(IOException::class.java) { requests.create(C.DATA_TYPE_MANIFEST).openUri(uri) }
            assertEquals("Native media request failed", error.message)
            assertNull(error.cause)
            assertFalse(connection.instanceFollowRedirects)
            assertFalse(connection.bodyRead)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun `unallowlisted destinations and encryption keys fail before a connection opens`() {
        val events = mutableListOf<NativeMediaEvent>()
        val requests = BoundedMediaRequests(NativePlaybackBudget(120_000, { true }), { false },
            { event, _ -> events += event }, openConnection = { throw AssertionError("Forbidden request opened") })
        assertThrows(IOException::class.java) { requests.create(C.DATA_TYPE_DRM).openUri(uri) }
        assertTrue(events.contains(NativeMediaEvent.ENCRYPTION_UNSUPPORTED))
        assertThrows(IOException::class.java) { requests.create(C.DATA_TYPE_MANIFEST).openUri(uri) }
        assertTrue(events.contains(NativeMediaEvent.SOURCE_NOT_ALLOWLISTED))
    }

    @Test fun `a process cookie handler prevents media dispatch rather than sharing cookies`() {
        val original = CookieHandler.getDefault()
        try {
            CookieHandler.setDefault(object : CookieHandler() {
                override fun get(uri: URI, headers: Map<String, List<String>>): Map<String, List<String>> =
                    throw AssertionError("Cookie store read")
                override fun put(uri: URI, headers: Map<String, List<String>>) = Unit
            })
            val requests = BoundedMediaRequests(NativePlaybackBudget(120_000, { true }), { true }, { _, _ -> },
                openConnection = { throw AssertionError("Cookie-bearing request opened") })
            assertThrows(IOException::class.java) { requests.create(C.DATA_TYPE_MANIFEST).openUri(uri) }
        } finally { CookieHandler.setDefault(original) }
    }

    @Test fun `both declared and streamed manifests are size bounded`() {
        val oversized = ByteArray(512 * 1024 + 1)
        for (length in listOf(-1L, oversized.size.toLong())) {
            val connection = Connection(uri.toURL(), length = length, body = oversized)
            val source = requests(connection).create(C.DATA_TYPE_MANIFEST)
            assertThrows(IOException::class.java) {
                source.openUri(uri)
                source.read(oversized, 0, oversized.size)
            }
            source.close()
            assertTrue(connection.disconnected)
        }
    }

    @Test fun `closing during response setup disconnects and prevents body acceptance`() {
        lateinit var requests: BoundedMediaRequests
        val connection = Connection(uri.toURL(), beforeResponse = { requests.close() })
        requests = requests(connection)
        assertThrows(IOException::class.java) { requests.create(C.DATA_TYPE_MANIFEST).openUri(uri) }
        assertTrue(connection.disconnected)
        assertFalse(connection.bodyRead)
        assertThrows(IOException::class.java) { requests.create(C.DATA_TYPE_MANIFEST).openUri(uri) }
    }

    @Test fun `declared media size and requested ranges are bounded without reading the body`() {
        for (length in listOf(-1L, 32 * 1024 * 1024L + 1)) {
            val connection = Connection(uri.toURL(), length = length)
            val source = requests(connection).create(C.DATA_TYPE_MEDIA)
            assertThrows(IOException::class.java) {
                source.openUri(uri, length = if (length < 0) 32 * 1024 * 1024L + 1 else -1)
            }
            assertFalse(connection.bodyRead)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun `acceptance and per response deadlines are checked after blocking reads too`() {
        var now = 1_000L
        val stream = object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                now += 30_000
                return super.read(buffer, offset, length)
            }
        }
        val connection = Connection(uri.toURL(), stream = stream)
        val source = requests(connection, clock = { now }).create(C.DATA_TYPE_MANIFEST)
        source.openUri(uri)
        assertThrows(IOException::class.java) { source.read(ByteArray(1), 0, 1) }
        source.close()
        assertTrue(connection.disconnected)

        val denied = requests(Connection(uri.toURL()), canRequest = { false }).create(C.DATA_TYPE_MANIFEST)
        assertThrows(IOException::class.java) { denied.openUri(uri) }
    }

    @Test fun `media byte ranges are explicit and must be honored`() {
        val connection = Connection(uri.toURL(), status = 206, length = 2, body = byteArrayOf(1, 2))
        val source = requests(connection).create(C.DATA_TYPE_MEDIA)
        assertEquals(2L, source.openUri(uri, position = 5, length = 2))
        assertEquals("bytes=5-6", connection.getRequestProperty("Range"))
        val buffer = ByteArray(8)
        assertEquals(2, source.read(buffer, 0, 8))
        assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, 8))
        source.close()
        assertThrows(IOException::class.java) {
            requests(Connection(uri.toURL(), status = 200)).create(C.DATA_TYPE_MEDIA).openUri(uri, position = 5)
        }
    }

    @Test fun `provider exceptions lose their message cause and signed source`() {
        val connection = Connection(uri.toURL(), stream = object : InputStream() {
            override fun read(): Int = throw IOException("Sensitive provider detail $uri")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = read()
        })
        val source = requests(connection).create(C.DATA_TYPE_MEDIA)
        source.openUri(uri)
        val error = assertThrows(IOException::class.java) { source.read(ByteArray(1), 0, 1) }
        assertEquals("Native media read failed", error.message)
        assertNull(error.cause)
        source.close()
    }

    @Test fun `only a bounded manifest 404 body reaches the rejection classifier`() {
        val body = "[{\"error_code\":\"transcode_does_not_exist\"}]".toByteArray()
        for (status in listOf(302, 403, 404)) {
            val connection = Connection(uri.toURL(), status = status, errorBody = body)
            var called = false
            val requests = BoundedMediaRequests(NativePlaybackBudget(120_000, { true }), { true }, { _, _ -> },
                openConnection = { connection }, onManifestRejection = { http, text ->
                    called = true
                    assertEquals(404, http)
                    assertEquals(body.toString(Charsets.UTF_8), text)
                })
            assertThrows(IOException::class.java) { requests.create(C.DATA_TYPE_MANIFEST).openUri(uri) }
            assertEquals(status == 404, called)
            assertEquals(status == 404, connection.errorRead)
            assertFalse(connection.bodyRead)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun `oversized rejected manifests and media errors never reach classification`() {
        for (announced in listOf(-1L, 4097L)) {
            val connection = Connection(uri.toURL(), status = 404, length = announced, errorBody = ByteArray(4097))
            val requests = BoundedMediaRequests(NativePlaybackBudget(120_000, { true }), { true }, { _, _ -> },
                openConnection = { connection }, onManifestRejection = { _, _ -> throw AssertionError("Oversized body classified") })
            assertThrows(IOException::class.java) { requests.create(C.DATA_TYPE_MANIFEST).openUri(uri) }
            assertTrue(connection.disconnected)
            assertEquals(announced < 0, connection.errorRead)
        }
        val connection = Connection(uri.toURL(), status = 404, errorBody = "fixture".toByteArray())
        val requests = BoundedMediaRequests(NativePlaybackBudget(120_000, { true }), { true }, { _, _ -> },
            openConnection = { connection }, onManifestRejection = { _, _ -> throw AssertionError("Media error classified") })
        assertThrows(IOException::class.java) { requests.create(C.DATA_TYPE_MEDIA).openUri(uri) }
        assertFalse(connection.errorRead)
        assertTrue(connection.disconnected)
    }

    @Test fun streamCloseErrorStillDisconnectsAndCannotBeLaunderedByOuterReceipt() {
        var closes = 0; val error = AssertionError("fixture")
        val stream = object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun close() { closes++; if (closes == 1) throw error }
        }
        val connection = Connection(uri.toURL(), stream = stream)
        val receipt = NativeCleanupReceipt()
        val stages = mutableListOf<NativeFailureStage>()
        val failures = mutableListOf<Throwable>()
        val requests = BoundedMediaRequests(NativePlaybackBudget(120_000, { true }), { true }, { _, _ -> },
            openConnection = { receipt.allocate(20, { connection }) { it.disconnect() } },
            openInput = { receipt.input { it.inputStream } },
            onFailure = { stage, failure -> stages.add(stage); failures.add(failure); receipt.failed() })
        val source = requests.create(C.DATA_TYPE_MANIFEST); source.openUri(uri)
        val outward = assertThrows(IOException::class.java) { source.close() }
        assertEquals("Native media cleanup unconfirmed", outward.message)
        assertNull(outward.cause); assertTrue(outward.suppressed.isEmpty())
        assertTrue(connection.disconnected); assertEquals(listOf(NativeFailureStage.MEDIA_DISCONNECT), stages)
        assertSame(error, failures.single())
        val active = BoundedMediaRequests::class.java.getDeclaredField("active").apply { isAccessible = true }
        assertTrue((active.get(requests) as Set<*>).isEmpty())
        assertThrows(IOException::class.java) { source.close() }
        assertThrows(IOException::class.java) { receipt.close() }
        assertEquals(1, closes); assertFalse(receipt.confirmed)
    }

    @Test fun rejectedManifestStreamCloseErrorIsPrivatelyObservedAndCannotEscapeLoader() {
        val error = AssertionError("Synthetic private close detail")
        val receipt = NativeCleanupReceipt()
        val seen = mutableListOf<Throwable>()
        val connection = Connection(uri.toURL(), status = 404)
        val requests = BoundedMediaRequests(NativePlaybackBudget(120_000, { true }), { true }, { _, _ -> },
            openConnection = { receipt.allocate(20, { connection }) { it.disconnect() } },
            openErrorInput = { receipt.optionalInput { object : ByteArrayInputStream(byteArrayOf()) {
                override fun close() { throw error }
            } } }, onFailure = { _, failure -> seen.add(failure); receipt.failed() })
        val outward = assertThrows(IOException::class.java) { requests.create(C.DATA_TYPE_MANIFEST).openUri(uri) }
        assertEquals("Native media request failed", outward.message)
        assertNull(outward.cause); assertTrue(outward.suppressed.isEmpty())
        assertSame(error, seen.single()); assertTrue(connection.disconnected)
        assertThrows(IOException::class.java) { receipt.close() }; assertFalse(receipt.confirmed)
    }

}
