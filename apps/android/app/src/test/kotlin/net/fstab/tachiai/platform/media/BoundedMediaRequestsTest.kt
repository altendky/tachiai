package net.fstab.tachiai.platform.media

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.CookieHandler
import java.net.URI
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
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
}
