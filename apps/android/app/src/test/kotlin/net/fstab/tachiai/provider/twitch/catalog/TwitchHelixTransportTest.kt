package net.fstab.tachiai.provider.twitch.catalog

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.net.URLDecoder
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import net.fstab.tachiai.provider.twitch.*
import org.junit.Assert.*
import org.junit.Test

class TwitchHelixTransportTest {
    private class Connection(url: URL, val status: Int = 200, val body: ByteArray = "fixture".toByteArray(),
        val length: Long = -1, val reset: String? = null, val onStatus: () -> Unit = {},
        val onDisconnect: () -> Unit = {}) : HttpsURLConnection(url) {
        var disconnected = false
        var reads = 0
        override fun connect() = Unit
        override fun disconnect() { disconnected = true; onDisconnect() }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode(): Int { onStatus(); return status }
        override fun getContentLengthLong() = length
        override fun getHeaderField(name: String): String? = if (name == "Ratelimit-Reset") reset else null
        override fun getInputStream(): InputStream { reads++; return ByteArrayInputStream(body) }
        override fun getErrorStream(): InputStream = error("Error bodies must not be read")
        fun query(): List<Pair<String, String>> = url.query.split('&').map { field ->
            val pair = field.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
        }
    }
    private fun transport(open: (URL) -> HttpsURLConnection) = TwitchHelixHttpTransport(open = open, decode = { mapOf("data" to emptyList<Any>()) })

    @Test fun AllOperationsUseFixedGetPathsSmartTvClientAndEncodedPublicQueryOnly() {
        val opened = mutableListOf<Connection>()
        val http = transport { Connection(it).also(opened::add) }
        val cursor = "opaque/&+=?"
        val requests = listOf(TwitchHelixRequest.following("123", cursor), TwitchHelixRequest.search("sumo & bouts", cursor),
            TwitchHelixRequest.usersById(listOf("123", "456")), TwitchHelixRequest.userByLogin("sumo"),
            TwitchHelixRequest.streams(), TwitchHelixRequest.streams(listOf("123", "456")),
            TwitchHelixRequest.video("789"), TwitchHelixRequest.videos("123", cursor),
            TwitchHelixRequest.schedule("123", 1_000_123))
        requests.forEach { http.execute("fixture-access", it) }
        assertEquals(listOf("/helix/channels/followed", "/helix/search/channels", "/helix/users", "/helix/users",
            "/helix/streams", "/helix/streams", "/helix/videos", "/helix/videos", "/helix/schedule"), opened.map { it.url.path })
        assertEquals(listOf("user_id" to "123", "first" to "20", "after" to cursor), opened[0].query())
        assertEquals(listOf("query" to "sumo & bouts", "live_only" to "false", "first" to "20", "after" to cursor), opened[1].query())
        assertEquals(listOf("id" to "123", "id" to "456"), opened[2].query())
        assertEquals(listOf("id" to "789"), opened[6].query())
        assertEquals(listOf("user_id" to "123", "type" to "all", "sort" to "time", "first" to "20", "after" to cursor), opened[7].query())
        assertEquals(listOf("broadcaster_id" to "123", "start_time" to "1970-01-01T00:16:40.123Z", "first" to "20"), opened[8].query())
        opened.forEach {
            assertTrue(validTwitchHelixUrl(it.url)); assertEquals("GET", it.requestMethod)
            assertEquals("api.twitch.tv", it.url.host); assertEquals("https", it.url.protocol)
            assertEquals(SMART_TV_TWITCH_CLIENT_ID, it.getRequestProperty("Client-Id"))
            assertEquals("Bearer fixture-access", it.getRequestProperty("Authorization"))
            assertNull(it.getRequestProperty("Cookie")); assertFalse(it.doOutput); assertFalse(it.instanceFollowRedirects)
            assertFalse(it.useCaches); assertEquals(10_000, it.connectTimeout); assertEquals(15_000, it.readTimeout)
            assertFalse(it.url.toExternalForm().contains("fixture-access")); assertTrue(it.disconnected)
        }
        assertFalse(requests.first().toString().contains(cursor)); assertFalse(http.toString().contains("fixture-access"))
    }

    @Test fun IndependentRouteGuardRejectsUnapprovedAuthoritiesPathsAndQueryShapes() {
        listOf("http://api.twitch.tv/helix/streams?first=20", "https://api.twitch.tv.evil.test/helix/streams?first=20",
            "https://user:private@api.twitch.tv/helix/streams?first=20", "https://api.twitch.tv:444/helix/streams?first=20",
            "https://api.twitch.tv/helix/streams?first=20#private", "https://api.twitch.tv/helix/streams/key?first=20",
            "https://api.twitch.tv/helix/streams?first=20&token=private", "https://api.twitch.tv/helix/streams?first=100",
            "https://api.twitch.tv/helix/streams?first=20&first=20", "https://api.twitch.tv/helix/users?id=123&login=sumo",
            "https://api.twitch.tv/helix/users", "https://api.twitch.tv/helix/users?login=sumo&after=cursor",
            "https://api.twitch.tv/helix/videos?id=123&user_id=456", "https://api.twitch.tv/helix/videos?id=123&id=456",
            "https://api.twitch.tv/helix/search/channels?query=sumo&live_only=true&first=20",
            "https://api.twitch.tv/helix/channels/followed?user_id=0123&first=20",
            "https://api.twitch.tv/helix/streams?first=20&after=%0ACookie%3Aprivate",
            "https://api.twitch.tv/helix/%73treams?first=20", "https://gql.twitch.tv/helix/streams?first=20").forEach {
            assertFalse(validTwitchHelixUrl(URL(it)))
        }
    }

    @Test fun InvalidPublicQueryAndTokenInputIsRefusedBeforeOpeningAnything() {
        listOf("", "0", "0123", "a", "1".repeat(33), "123\n").forEach { id ->
            assertThrows(TwitchHelixException::class.java) { TwitchHelixRequest.following(id) }
            assertThrows(TwitchHelixException::class.java) { TwitchHelixRequest.video(id) }
            assertThrows(TwitchHelixException::class.java) { TwitchHelixRequest.schedule(id, 1_000_123) }
        }
        assertThrows(TwitchHelixException::class.java) { TwitchHelixRequest.usersById(emptyList()) }
        assertThrows(TwitchHelixException::class.java) { TwitchHelixRequest.streams(List(101) { "123" }) }
        assertThrows(TwitchHelixException::class.java) { TwitchHelixRequest.userByLogin("Sumo") }
        listOf("", " private ", "query\u200b", "query\n", "a".repeat(161)).forEach {
            assertThrows(TwitchHelixException::class.java) { TwitchHelixRequest.search(it) }
        }
        listOf("", "a".repeat(2049), "private\n").forEach {
            assertThrows(TwitchHelixException::class.java) { TwitchHelixRequest.streams(after = it) }
        }
        var opened = false
        val http = transport { opened = true; Connection(it) }
        listOf("", "a".repeat(2049), "private\r\nCookie:value", "é").forEach {
            assertEquals(TwitchHelixFailure.INVALID_INPUT,
                assertThrows(TwitchHelixException::class.java) { http.execute(it, TwitchHelixRequest.streams()) }.failure)
        }
        assertFalse(opened)
    }

    @Test fun ScheduleRouteAllowsOnlyItsBoundedCanonicalTimestampAndSinglePage() {
        val base = "https://api.twitch.tv/helix/schedule?broadcaster_id=123&start_time=1970-01-01T00%3A16%3A40.123Z&first=20"
        assertTrue(validTwitchHelixUrl(URL(base)))
        listOf("$base&after=cursor", "$base&token=private", "$base&broadcaster_id=456", "$base&start_time=private",
            base.replace("first=20", "first=25"), base.replace("broadcaster_id=123", "user_id=123"),
            base.replace("broadcaster_id=123", "broadcaster_id=0123"), base.replace("00%3A16%3A40.123Z", "00%3A16%3A40.123000Z"),
            base.replace("1970-01-01T00%3A16%3A40.123Z", "1970-01-01T01%3A16%3A40.123%2B01%3A00"),
            base.replace("1970-01-01T00%3A16%3A40.123Z", "1969-12-31T23%3A59%3A59Z"),
            base.replace("1970-01-01T00%3A16%3A40.123Z", "%2B10000-01-01T00%3A00%3A00Z"),
            base.replace("1970-01-01T00%3A16%3A40.123Z", "not-a-date"),
            base.replace("/helix/schedule", "/helix/%73chedule"), "$base#private").forEach {
            assertFalse("Schedule URL must be reproducible by the fixed operation", validTwitchHelixUrl(URL(it)))
        }
        listOf(0L, 253_402_300_799_999L).forEach { assertTrue(validTwitchHelixUrl(TwitchHelixRequest.schedule("123", it).url)) }
        listOf(-1L, 253_402_300_800_000L, Long.MAX_VALUE).forEach {
            assertEquals(TwitchHelixFailure.INVALID_INPUT,
                assertThrows(TwitchHelixException::class.java) { TwitchHelixRequest.schedule("123", it) }.failure)
        }
    }

    @Test fun RedirectAndErrorStatusesAreReturnedWithoutReadingBodiesOrRetrying() {
        listOf(302, 400, 401, 403, 404, 503).forEach { status ->
            var opens = 0; var decodes = 0
            val request = Connection(TwitchHelixRequest.streams().url, status)
            val http = TwitchHelixHttpTransport(open = { opens++; request }, decode = { decodes++; error("Must not decode") })
            val response = http.execute("fixture-access", TwitchHelixRequest.streams())
            assertEquals(status, response.status); assertTrue(response.fields.isEmpty()); assertNull(response.retryAtEpochMs)
            assertEquals(1, opens); assertEquals(0, decodes); assertEquals(0, request.reads); assertTrue(request.disconnected)
        }
    }

    @Test fun RateLimitsExposeBoundedResetDeadlineWithoutWaitingOrReadingPrivateErrorText() {
        val now = 1_000_000L
        mapOf<String?, Long>("1020" to 1_020_000L, "1" to now, "99999999999999999999" to now + 60_000,
            Long.MAX_VALUE.toString() to now + 86_400_000, null to now + 60_000, "private" to now + 60_000,
            " 1020" to now + 60_000).forEach { (header, deadline) ->
            val request = Connection(TwitchHelixRequest.streams().url, 429, reset = header)
            val http = TwitchHelixHttpTransport(open = { request }, decode = { error("Must not decode") }, wallMs = { now })
            val result = http.execute("fixture-access", TwitchHelixRequest.streams())
            assertEquals(deadline, result.retryAtEpochMs); assertEquals(0, request.reads)
        }
    }

    @Test fun AdvertisedAndStreamedOversizeAreRefusedAndMalformedDecoderTextNeverEscapes() {
        listOf(-1L, (TWITCH_HELIX_RESPONSE_LIMIT + 1).toLong()).forEach { length ->
            var decoded = false
            val request = Connection(TwitchHelixRequest.streams().url, body = ByteArray(TWITCH_HELIX_RESPONSE_LIMIT + 1), length = length)
            val http = TwitchHelixHttpTransport(open = { request }, decode = { decoded = true; emptyMap() })
            assertEquals(TwitchHelixFailure.INVALID_RESPONSE,
                assertThrows(TwitchHelixException::class.java) { http.execute("fixture-access", TwitchHelixRequest.streams()) }.failure)
            assertFalse(decoded); assertTrue(request.disconnected)
        }
        val malformed = TwitchHelixHttpTransport(open = { Connection(it) }, decode = { throw IllegalStateException("fixture-private-response") })
        val error = assertThrows(TwitchHelixException::class.java) { malformed.execute("fixture-access", TwitchHelixRequest.streams()) }
        assertNull(error.cause); assertFalse(error.toString().contains("fixture-private"))
    }

    @Test fun TotalRequestBudgetAndForegroundGateDenyLatePublication() {
        var now = 0L; var decoded = false
        val failures = mutableListOf<TwitchHelixHttpFailure>()
        val request = Connection(TwitchHelixRequest.streams().url, onStatus = { now = 30_001 })
        val http = TwitchHelixHttpTransport(open = { request }, decode = { decoded = true; emptyMap() }, clockMs = { now }, onFailure = failures::add)
        assertEquals(DeviceNetworkFailure.BUDGET,
            assertThrows(TwitchHelixNetworkException::class.java) { http.execute("fixture-access", TwitchHelixRequest.streams()) }.category)
        assertFalse(decoded); assertTrue(request.disconnected)
        assertEquals(listOf(TwitchHelixHttpFailure(TwitchHelixOperation.STREAMS, DeviceRequestStage.STATUS, DeviceNetworkFailure.BUDGET, 30_001)), failures)
        var opened = false
        assertThrows(DeviceRequestPaused::class.java) {
            TwitchHelixHttpTransport(open = { opened = true; Connection(it) }, canRequest = { false }).execute("fixture-access", TwitchHelixRequest.streams())
        }
        assertFalse(opened)
    }

    @Test fun NetworkExceptionsAndDiagnosticsDiscardPrivateCauseMessages() {
        val failures = mutableListOf<TwitchHelixHttpFailure>()
        val http = TwitchHelixHttpTransport(open = { throw SSLHandshakeException("fixture-private https://secret.test/?token=private") }, onFailure = failures::add)
        val error = assertThrows(TwitchHelixNetworkException::class.java) { http.execute("fixture-access", TwitchHelixRequest.streams()) }
        assertEquals(DeviceNetworkFailure.TLS, error.category); assertNull(error.cause)
        assertFalse(error.toString().contains("private")); assertFalse(failures.toString().contains("private"))
    }

    @Test fun PauseDuringBlockedCreationRejectsLateHandleThenAllowsNewRequest() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val worker = Executors.newSingleThreadExecutor()
        val late = Connection(TwitchHelixRequest.streams().url); var opens = 0
        val http = transport { url -> if (++opens == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); late } else Connection(url) }
        try {
            val pending = worker.submit<TwitchHelixResponse> { http.execute("fixture-access", TwitchHelixRequest.streams()) }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); http.cancelActiveRequest(); release.countDown()
            assertTrue(assertThrows(ExecutionException::class.java) { pending.get(5, TimeUnit.SECONDS) }.cause is DeviceRequestPaused)
            assertTrue(late.disconnected); assertEquals(0, late.reads)
            assertEquals(200, http.execute("fixture-access", TwitchHelixRequest.streams()).status)
        } finally { release.countDown(); http.close(); worker.shutdownNow() }
    }

    @Test fun CloseDuringBlockedCreationPreventsBothLatePublicationAndFutureConnections() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val worker = Executors.newSingleThreadExecutor()
        val late = Connection(TwitchHelixRequest.streams().url); var opens = 0
        val http = transport { opens++; entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); late }
        try {
            val pending = worker.submit<TwitchHelixResponse> { http.execute("fixture-access", TwitchHelixRequest.streams()) }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); http.close(); release.countDown()
            assertTrue(assertThrows(ExecutionException::class.java) { pending.get(5, TimeUnit.SECONDS) }.cause is TwitchHelixNetworkException)
            assertTrue(late.disconnected)
            assertThrows(TwitchHelixNetworkException::class.java) { http.execute("fixture-access", TwitchHelixRequest.streams()) }
            assertEquals(1, opens)
        } finally { release.countDown(); worker.shutdownNow() }
    }

    @Test fun ConcurrentExecutionCannotReleaseTheOriginalRequestReservation() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val worker = Executors.newSingleThreadExecutor()
        var opens = 0
        val http = transport { url -> opens++; Connection(url, onStatus = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }) }
        try {
            val pending = worker.submit<TwitchHelixResponse> { http.execute("fixture-access", TwitchHelixRequest.streams()) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            repeat(2) { assertThrows(TwitchHelixNetworkException::class.java) { http.execute("fixture-access", TwitchHelixRequest.streams()) } }
            assertEquals(1, opens); release.countDown(); assertEquals(200, pending.get(5, TimeUnit.SECONDS).status)
        } finally { release.countDown(); http.close(); worker.shutdownNow() }
    }

    @Test fun DisconnectIoDuringFastPauseResumeIsStillClassifiedAsPause() {
        lateinit var http: TwitchHelixHttpTransport
        var foreground = true
        val request = Connection(TwitchHelixRequest.streams().url, onStatus = {
            foreground = false; http.cancelActiveRequest(); foreground = true; throw IOException("fixture-private-disconnect")
        })
        http = TwitchHelixHttpTransport(open = { request }, canRequest = { foreground })
        assertThrows(DeviceRequestPaused::class.java) { http.execute("fixture-access", TwitchHelixRequest.streams()) }
        assertTrue(request.disconnected)
    }
}
