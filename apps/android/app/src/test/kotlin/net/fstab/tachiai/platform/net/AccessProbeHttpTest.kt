package net.fstab.tachiai.platform.net

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.net.URI
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.*
import org.junit.Test

class AccessProbeHttpTest {
    private val twitch = URI("https://gql.twitch.tv/gql")
    private val client = "ownpublicclient12345"
    private class Connection(url: URL, val status: Int = 200, val body: ByteArray = "{}".toByteArray(),
        val length: Long = -1, val beforeResponse: () -> Unit = {}) : HttpsURLConnection(url) {
        val written = ByteArrayOutputStream()
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode(): Int { beforeResponse(); return status }
        override fun getContentLengthLong() = length
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getErrorStream() = inputStream
        override fun getOutputStream() = written
    }

    @Test fun `only exact endpoints and the advertised CDN MPD family are accepted`() {
        assertTrue(allowedAccessProbeUri(AccessProbeEndpoint.TWITCH_ACCESS, twitch))
        assertTrue(allowedAccessProbeUri(AccessProbeEndpoint.ABEMA_CHANNELS, URI("https://api.abema.io/v1/channels")))
        assertTrue(allowedAccessProbeUri(AccessProbeEndpoint.ABEMA_DASH, URI("https://linear-abematv.akamaized.net/channel/manifest.mpd")))
        listOf("http://gql.twitch.tv/gql", "https://gql.twitch.tv/gql?token=x", "https://user@gql.twitch.tv/gql",
            "https://gql.twitch.tv:444/gql", "https://gql.twitch.tv/gql#x", "https://gql.twitch.tv.evil/gql").forEach {
            assertFalse(allowedAccessProbeUri(AccessProbeEndpoint.TWITCH_ACCESS, URI(it)))
        }
        listOf("https://linear-abematv.akamaized.net/x.m3u8", "https://linear-abematv.akamaized.net/x.mpd?token=x",
            "https://other.abema.io/x.mpd", "https://linear-abematv.akamaized.net/x.mpd#x").forEach {
            assertFalse(allowedAccessProbeUri(AccessProbeEndpoint.ABEMA_DASH, URI(it)))
        }
    }

    @Test fun `Twitch headers are confined to its fixed endpoint and never contain browser cookies`() {
        val connection = Connection(twitch.toURL())
        val http = AccessProbeHttp(open = { connection })
        assertEquals(200, http.exchange(AccessProbeEndpoint.TWITCH_ACCESS, twitch, "{}".toByteArray(), "transient", client) { status, _ -> status })
        assertEquals("OAuth transient", connection.getRequestProperty("Authorization"))
        assertEquals(client, connection.getRequestProperty("Client-ID"))
        assertEquals(null, connection.getRequestProperty("Cookie"))
        assertEquals("POST", connection.requestMethod)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertTrue(connection.disconnected)
        assertThrows(IllegalArgumentException::class.java) {
            http.exchange(AccessProbeEndpoint.ABEMA_CHANNELS, URI("https://api.abema.io/v1/channels"), token = "transient") { _, _ -> Unit }
        }
    }

    @Test fun `ABEMA requests have no auth client body or modified source`() {
        val uri = URI("https://linear-abematv.akamaized.net/allowed/manifest.mpd")
        val connection = Connection(uri.toURL())
        AccessProbeHttp(open = { assertEquals(uri.toURL(), it); connection })
            .exchange(AccessProbeEndpoint.ABEMA_DASH, uri) { _, _ -> Unit }
        assertEquals("GET", connection.requestMethod)
        assertEquals(null, connection.getRequestProperty("Authorization"))
        assertEquals(null, connection.getRequestProperty("Client-ID"))
        assertEquals(0, connection.written.size())
    }

    @Test fun `HLS has its own exact host and extension policy without weakening DASH`() {
        val valid = URI("https://linear-abematv.akamaized.net/channel/abema-news/playlist.m3u8")
        assertTrue(allowedAccessProbeUri(AccessProbeEndpoint.ABEMA_HLS, valid))
        assertFalse(allowedAccessProbeUri(AccessProbeEndpoint.ABEMA_DASH, valid))
        listOf("http://linear-abematv.akamaized.net/x.m3u8", "https://other.example/x.m3u8",
            "https://user@linear-abematv.akamaized.net/x.m3u8", "https://linear-abematv.akamaized.net:444/x.m3u8",
            "https://linear-abematv.akamaized.net/x.m3u8?token=fixture", "https://linear-abematv.akamaized.net/x.m3u8#fragment",
            "https://linear-abematv.akamaized.net/x.mpd", "https://linear-abematv.akamaized.net/key").forEach {
            assertFalse(allowedAccessProbeUri(AccessProbeEndpoint.ABEMA_HLS, URI(it)))
        }
    }

    @Test fun `HLS denies all Twitch request modes and sends no credential or body`() {
        val uri = URI("https://linear-abematv.akamaized.net/channel/abema-news/playlist.m3u8")
        val connection = Connection(uri.toURL(), body = "#EXTM3U".toByteArray())
        AccessProbeHttp(open = { connection }).use { http ->
            http.exchange(AccessProbeEndpoint.ABEMA_HLS, uri) { _, _ -> Unit }
        }
        assertEquals("application/vnd.apple.mpegurl", connection.getRequestProperty("Accept"))
        assertEquals("GET", connection.requestMethod)
        assertNull(connection.getRequestProperty("Authorization"))
        assertNull(connection.getRequestProperty("Client-ID"))
        assertNull(connection.getRequestProperty("Cookie"))
        assertEquals(0, connection.written.size())
        assertTrue(connection.disconnected)
        val forbidden = AccessProbeHttp(open = { throw AssertionError("invalid HLS request opened") })
        assertThrows(IllegalArgumentException::class.java) { forbidden.exchange(AccessProbeEndpoint.ABEMA_HLS, uri, token = "fixture") { _, _ -> throw AssertionError("invalid HLS classified") } }
        assertThrows(IllegalArgumentException::class.java) { forbidden.exchange(AccessProbeEndpoint.ABEMA_HLS, uri, body = "{}".toByteArray()) { _, _ -> throw AssertionError("invalid HLS classified") } }
        assertThrows(IllegalArgumentException::class.java) { forbidden.exchange(AccessProbeEndpoint.ABEMA_HLS, uri, clientId = client) { _, _ -> throw AssertionError("invalid HLS classified") } }
        assertThrows(IllegalArgumentException::class.java) { forbidden.exchange(AccessProbeEndpoint.ABEMA_HLS, uri, inspectTwitchErrors = true) { _, _ -> throw AssertionError("invalid HLS classified") } }
    }

    @Test fun `HLS announced and streamed responses stay within the manifest bound`() {
        val uri = URI("https://linear-abematv.akamaized.net/channel/abema-news/playlist.m3u8")
        listOf(-1L, 262145L).forEach { length ->
            val connection = Connection(uri.toURL(), body = ByteArray(262145), length = length)
            assertThrows(InterruptedIOException::class.java) {
                AccessProbeHttp(open = { connection }).use { http ->
                    http.exchange(AccessProbeEndpoint.ABEMA_HLS, uri) { _, _ -> throw AssertionError("oversize HLS classified") }
                }
            }
            assertTrue(connection.disconnected)
        }
    }

    @Test fun `HLS rejected responses do not read bodies or follow redirects`() {
        val uri = URI("https://linear-abematv.akamaized.net/channel/abema-news/playlist.m3u8")
        listOf(302, 401, 403).forEach { status ->
            val connection = Connection(uri.toURL(), status, "provider body".toByteArray())
            AccessProbeHttp(open = { connection }).use { http ->
                http.exchange(AccessProbeEndpoint.ABEMA_HLS, uri) { actual, body ->
                    assertEquals(status, actual)
                    assertEquals("", body)
                }
            }
            assertFalse(connection.instanceFollowRedirects)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun `blank client comparison sends an empty header and keeps OAuth on the fixed endpoint`() {
        val connection = Connection(twitch.toURL(), status = 401)
        AccessProbeHttp(open = { connection }).exchange(AccessProbeEndpoint.TWITCH_ACCESS, twitch,
            "{}".toByteArray(), "fixture-token", client, inspectTwitchErrors = true,
            blankTwitchClientHeader = true) { status, _ -> assertEquals(401, status) }
        assertEquals("", connection.getRequestProperty("Client-ID"))
        assertEquals("OAuth fixture-token", connection.getRequestProperty("Authorization"))
        assertNull(connection.getRequestProperty("Cookie"))
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
    }

    @Test fun `blank client cannot be enabled anonymously outside diagnostics or for ABEMA`() {
        val http = AccessProbeHttp(open = { throw AssertionError("invalid comparison opened") })
        assertThrows(IllegalArgumentException::class.java) {
            http.exchange(AccessProbeEndpoint.TWITCH_ACCESS, twitch, "{}".toByteArray(), clientId = client,
                inspectTwitchErrors = true, blankTwitchClientHeader = true) { _, _ -> Unit }
        }
        assertThrows(IllegalArgumentException::class.java) {
            http.exchange(AccessProbeEndpoint.TWITCH_ACCESS, twitch, "{}".toByteArray(), "fixture", client,
                blankTwitchClientHeader = true) { _, _ -> Unit }
        }
        assertThrows(IllegalArgumentException::class.java) {
            http.exchange(AccessProbeEndpoint.TWITCH_ACCESS, twitch, token = "fixture", clientId = client,
                inspectTwitchErrors = true, blankTwitchClientHeader = true) { _, _ -> Unit }
        }
        assertThrows(IllegalArgumentException::class.java) {
            http.exchange(AccessProbeEndpoint.ABEMA_CHANNELS, URI("https://api.abema.io/v1/channels"),
                blankTwitchClientHeader = true) { _, _ -> Unit }
        }
        assertThrows(IllegalArgumentException::class.java) {
            http.exchange(AccessProbeEndpoint.TWITCH_ACCESS, twitch, "{}".toByteArray(), "fixture", "",
                inspectTwitchErrors = true, blankTwitchClientHeader = true) { _, _ -> Unit }
        }
    }

    @Test fun `redirects and denied responses are not read or followed`() {
        listOf(302, 401, 403).forEach { status ->
            val connection = Connection(twitch.toURL(), status, "private provider body".toByteArray())
            AccessProbeHttp(open = { connection }).exchange(AccessProbeEndpoint.TWITCH_ACCESS, twitch, clientId = client) { actual, body ->
                assertEquals(status, actual)
                assertEquals("", body)
            }
            assertFalse(connection.instanceFollowRedirects)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun `error diagnostics opt in only for Twitch and still reject redirects and other statuses`() {
        listOf(200, 400, 401, 403, 302, 429, 500).forEach { status ->
            val connection = Connection(twitch.toURL(), status, "fixture".toByteArray())
            AccessProbeHttp(open = { connection }).exchange(AccessProbeEndpoint.TWITCH_ACCESS, twitch,
                clientId = client, inspectTwitchErrors = true) { actual, body ->
                assertEquals(status, actual)
                assertEquals(if (status in listOf(200, 400, 401, 403)) "fixture" else "", body)
            }
            assertFalse(connection.instanceFollowRedirects)
            assertTrue(connection.disconnected)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccessProbeHttp(open = { throw AssertionError("ABEMA diagnostic opened") })
                .exchange(AccessProbeEndpoint.ABEMA_CHANNELS, URI("https://api.abema.io/v1/channels"),
                    inspectTwitchErrors = true) { _, _ -> Unit }
        }
    }

    @Test fun `diagnostic errors have a smaller announced and streamed response bound`() {
        listOf(-1L, 16385L).forEach { length ->
            val connection = Connection(twitch.toURL(), status = 401, body = ByteArray(16385), length = length)
            assertThrows(InterruptedIOException::class.java) {
                AccessProbeHttp(open = { connection }).exchange(AccessProbeEndpoint.TWITCH_ACCESS, twitch,
                    clientId = client, inspectTwitchErrors = true) { _, _ -> throw AssertionError("oversize classified") }
            }
            assertTrue(connection.disconnected)
        }
    }

    @Test fun `closing opted in denied response prevents classification`() {
        lateinit var http: AccessProbeHttp
        val connection = Connection(twitch.toURL(), status = 401, beforeResponse = { http.close() })
        http = AccessProbeHttp(open = { connection })
        assertThrows(InterruptedIOException::class.java) {
            http.exchange(AccessProbeEndpoint.TWITCH_ACCESS, twitch, clientId = client,
                inspectTwitchErrors = true) { _, _ -> throw AssertionError("cancelled error classified") }
        }
        assertTrue(connection.disconnected)
    }

    @Test fun `both announced and streamed oversized responses are bounded`() {
        listOf(-1L, 65537L).forEach { length ->
            val connection = Connection(twitch.toURL(), body = ByteArray(65537), length = length)
            assertThrows(InterruptedIOException::class.java) {
                AccessProbeHttp(open = { connection }).exchange(AccessProbeEndpoint.TWITCH_ACCESS, twitch, clientId = client) { _, _ ->
                    throw AssertionError("oversized response reached classifier")
                }
            }
            assertTrue(connection.disconnected)
        }
    }

    @Test fun `close prevents dispatch and closes an active response before classification`() {
        val closed = AccessProbeHttp(open = { throw AssertionError("closed request opened") })
        closed.close()
        assertThrows(InterruptedIOException::class.java) {
            closed.exchange(AccessProbeEndpoint.TWITCH_ACCESS, twitch, clientId = client) { _, _ -> Unit }
        }
        lateinit var active: AccessProbeHttp
        val connection = Connection(twitch.toURL(), beforeResponse = { active.close() })
        active = AccessProbeHttp(open = { connection })
        assertThrows(InterruptedIOException::class.java) {
            active.exchange(AccessProbeEndpoint.TWITCH_ACCESS, twitch, clientId = client) { _, _ -> throw AssertionError("cancelled response classified") }
        }
        assertTrue(connection.disconnected)
    }

    @Test fun `background time budget and header injection prevent network dispatch`() {
        val background = AccessProbeHttp(canRequest = { false }, open = { throw AssertionError("background opened") })
        assertThrows(InterruptedIOException::class.java) {
            background.exchange(AccessProbeEndpoint.TWITCH_ACCESS, twitch, clientId = client) { _, _ -> Unit }
        }
        var now = 0L
        val expired = AccessProbeHttp(clockMs = { now.also { now += 30000 } }, open = { throw AssertionError("expired opened") })
        assertThrows(InterruptedIOException::class.java) {
            expired.exchange(AccessProbeEndpoint.TWITCH_ACCESS, twitch, clientId = client) { _, _ -> Unit }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccessProbeHttp(open = { throw AssertionError("malformed token opened") })
                .exchange(AccessProbeEndpoint.TWITCH_ACCESS, twitch, token = "x\r\nCookie: y", clientId = client) { _, _ -> Unit }
        }
    }
}
