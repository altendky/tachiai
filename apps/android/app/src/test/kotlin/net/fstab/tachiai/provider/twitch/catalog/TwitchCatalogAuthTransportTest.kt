package net.fstab.tachiai.provider.twitch.catalog

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
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

class TwitchCatalogAuthTransportTest {
    private class Connection(url: URL, val status: Int = 200, val body: ByteArray = "{}".toByteArray(),
        val length: Long = -1, val onStatus: () -> Unit = {}, val onDisconnect: () -> Unit = {}) : HttpsURLConnection(url) {
        val written = ByteArrayOutputStream()
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true; onDisconnect() }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode(): Int { onStatus(); return status }
        override fun getContentLengthLong() = length
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
        override fun getErrorStream(): InputStream = inputStream
        override fun getOutputStream() = written
        fun form() = written.toString("UTF-8").split("&").associate { part ->
            val pair = part.split("=", limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
        }
    }

    @Test fun DevicePollValidationAndRefreshUseOnlySmartTvClientExactScopeAndEncodedCredentials() {
        val requests = mutableListOf<Connection>()
        val transport = TwitchCatalogAuthTransport(open = { Connection(it).also(requests::add) }, decode = { emptyMap() })
        transport.device()
        transport.poll("fixture-device%&/+")
        transport.validate("fixture-access")
        transport.refresh("fixture-refresh%&/+=")
        assertEquals(listOf("/oauth2/device", "/oauth2/token", "/oauth2/validate", "/oauth2/token"), requests.map { it.url.path })
        assertEquals(mapOf("client_id" to SMART_TV_TWITCH_CLIENT_ID, "scopes" to TWITCH_CATALOG_SCOPE), requests[0].form())
        assertEquals(mapOf("client_id" to SMART_TV_TWITCH_CLIENT_ID, "scopes" to TWITCH_CATALOG_SCOPE,
            "device_code" to "fixture-device%&/+", "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"), requests[1].form())
        assertEquals(mapOf("client_id" to SMART_TV_TWITCH_CLIENT_ID, "grant_type" to "refresh_token",
            "refresh_token" to "fixture-refresh%&/+="), requests[3].form())
        assertEquals("GET", requests[2].requestMethod)
        assertEquals("OAuth fixture-access", requests[2].getRequestProperty("Authorization"))
        requests.forEach {
            assertEquals("https", it.url.protocol)
            assertEquals("id.twitch.tv", it.url.host)
            assertFalse(it.instanceFollowRedirects)
            assertFalse(it.useCaches)
            assertEquals(10_000, it.connectTimeout)
            assertEquals(15_000, it.readTimeout)
            assertTrue(it.disconnected)
            assertNull(it.getRequestProperty("Cookie"))
            assertFalse(it.written.toString("UTF-8").contains("client_secret"))
        }
        assertFalse(transport.toString().contains("fixture"))
    }

    @Test fun UnsafeOrOversizedInputIsRefusedBeforeOpeningAnything() {
        var opened = false
        val transport = TwitchCatalogAuthTransport(open = { opened = true; Connection(it) }, decode = { emptyMap() })
        listOf("", "private\r\nCookie: value", "a".repeat(2049), "é").forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { transport.poll(input) }
            assertThrows(IllegalArgumentException::class.java) { transport.validate(input) }
            assertThrows(IllegalArgumentException::class.java) { transport.refresh(input) }
        }
        assertFalse(opened)
    }

    @Test fun RedirectsUnauthorizedAndRateLimitsAreNotDecodedOrFollowed() {
        listOf(302, 401, 429, 503).forEach { status ->
            val request = Connection(URL("https://id.twitch.tv/oauth2/token"), status = status)
            var decoded = false
            val transport = TwitchCatalogAuthTransport(open = { request }, decode = { decoded = true; emptyMap() })
            val response = transport.refresh("fixture-refresh")
            assertEquals(status, response.status)
            assertTrue(response.fields.isEmpty())
            assertFalse(decoded)
            assertFalse(request.instanceFollowRedirects)
            assertTrue(request.disconnected)
        }
    }

    @Test fun PendingResponsesAreDecodedButAdvertisedAndStreamedOversizeBodiesAreRefused() {
        val pending = Connection(URL("https://id.twitch.tv/oauth2/token"), status = 400, body = "pending".toByteArray())
        val transport = TwitchCatalogAuthTransport(open = { pending }, decode = {
            assertEquals("pending", it); mapOf("message" to "authorization_pending")
        })
        assertEquals("authorization_pending", transport.poll("fixture-code").fields["message"])
        listOf(-1L, (DEVICE_RESPONSE_LIMIT + 1).toLong()).forEach { length ->
            val request = Connection(URL("https://id.twitch.tv/oauth2/device"),
                body = ByteArray(DEVICE_RESPONSE_LIMIT + 1), length = length)
            var decoded = false
            val oversized = TwitchCatalogAuthTransport(open = { request }, decode = { decoded = true; emptyMap() })
            assertEquals(TwitchCatalogAuthFailure.INVALID_RESPONSE,
                assertThrows(TwitchCatalogAuthException::class.java) { oversized.device() }.failure)
            assertFalse(decoded)
            assertTrue(request.disconnected)
        }
    }

    @Test fun TotalBudgetAndForegroundGateRejectLateOrBackgroundRequests() {
        var now = 0L
        var decoded = false
        val failures = mutableListOf<TwitchCatalogHttpFailure>()
        val request = Connection(URL("https://id.twitch.tv/oauth2/device"), onStatus = { now = 30_001 })
        val transport = TwitchCatalogAuthTransport(open = { request }, decode = { decoded = true; emptyMap() },
            clockMs = { now }, onFailure = failures::add)
        assertEquals(DeviceNetworkFailure.BUDGET,
            assertThrows(TwitchCatalogNetworkException::class.java) { transport.device() }.category)
        assertEquals(listOf(TwitchCatalogHttpFailure(TwitchCatalogAuthEndpoint.DEVICE, DeviceRequestStage.STATUS,
            DeviceNetworkFailure.BUDGET, 30_001)), failures)
        assertFalse(decoded)
        assertTrue(request.disconnected)
        var opened = false
        val background = TwitchCatalogAuthTransport(open = { opened = true; Connection(it) },
            decode = { emptyMap() }, canRequest = { false })
        assertThrows(DeviceRequestPaused::class.java) { background.device() }
        assertFalse(opened)
    }

    @Test fun NetworkDiagnosticsAndExceptionsContainOnlyClosedCategories() {
        listOf(SocketTimeoutException("private refresh credential") to DeviceNetworkFailure.TIMEOUT,
            SSLHandshakeException("private access credential") to DeviceNetworkFailure.TLS,
            IOException("private provider response") to DeviceNetworkFailure.IO).forEach { (original, category) ->
            val failures = mutableListOf<TwitchCatalogHttpFailure>()
            val transport = TwitchCatalogAuthTransport(open = { throw original }, decode = { emptyMap() }, onFailure = failures::add)
            val error = assertThrows(TwitchCatalogNetworkException::class.java) { transport.refresh("fixture-refresh") }
            assertEquals(category, error.category)
            assertNull(error.cause)
            assertFalse(error.toString().contains("private"))
            assertFalse(failures.toString().contains("private"))
        }
    }

    @Test fun CloseCancelsActiveWorkPreventsNewRequestsAndNeverPublishesItsResponse() {
        val arrived = CountDownLatch(1)
        val released = CountDownLatch(1)
        val request = Connection(URL("https://id.twitch.tv/oauth2/device"),
            onStatus = { arrived.countDown(); check(released.await(5, TimeUnit.SECONDS)) },
            onDisconnect = { released.countDown() })
        var decoded = false
        var openings = 0
        val failures = mutableListOf<TwitchCatalogHttpFailure>()
        val transport = TwitchCatalogAuthTransport(open = { openings++; request }, decode = { decoded = true; emptyMap() }, onFailure = failures::add)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<DeviceAuthResponse> { transport.device() }
            assertTrue(arrived.await(5, TimeUnit.SECONDS))
            // Concurrent callers cannot replace the active request's cancellation handle.
            assertThrows(IllegalStateException::class.java) { transport.refresh("fixture-refresh") }
            transport.close()
            val failure = assertThrows(ExecutionException::class.java) { result.get(5, TimeUnit.SECONDS) }
            assertTrue(failure.cause is TwitchCatalogNetworkException)
            transport.close()
            assertThrows(TwitchCatalogNetworkException::class.java) { transport.device() }
            assertEquals(1, openings)
            assertFalse(decoded)
            assertTrue(request.disconnected)
            assertTrue(failures.isEmpty())
        } finally { transport.close(); executor.shutdownNow() }
    }

    @Test fun ReentrantCloseDuringConnectionCreationCannotLeakTheCreatedHandle() {
        val request = Connection(URL("https://id.twitch.tv/oauth2/device"))
        lateinit var transport: TwitchCatalogAuthTransport
        transport = TwitchCatalogAuthTransport(open = { transport.close(); request }, decode = { emptyMap() })
        assertThrows(TwitchCatalogNetworkException::class.java) { transport.device() }
        assertTrue(request.disconnected)
    }

    @Test fun PauseDisconnectFailureIsNonterminalAndAForegroundRequestCanResume() {
        val arrived = CountDownLatch(1)
        val released = CountDownLatch(1)
        var foreground = true
        val first = Connection(URL("https://id.twitch.tv/oauth2/token"),
            onStatus = { arrived.countDown(); check(released.await(5, TimeUnit.SECONDS)); throw IOException("private disconnected request") },
            onDisconnect = { released.countDown() })
        val resumed = Connection(URL("https://id.twitch.tv/oauth2/token"))
        var openings = 0
        val transport = TwitchCatalogAuthTransport(open = { if (openings++ == 0) first else resumed },
            decode = { emptyMap() }, canRequest = { foreground })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit<DeviceAuthResponse> { transport.poll("fixture-code") }
            assertTrue(arrived.await(5, TimeUnit.SECONDS))
            foreground = false
            transport.cancelActiveRequest()
            // A fast foreground return cannot turn the interrupted response into success.
            foreground = true
            val failure = assertThrows(ExecutionException::class.java) { pending.get(5, TimeUnit.SECONDS) }
            assertTrue(failure.cause is DeviceRequestPaused)
            assertFalse(failure.cause.toString().contains("private"))
            assertTrue(first.disconnected)
            assertEquals(200, transport.poll("fixture-code").status)
            assertTrue(resumed.disconnected)
        } finally { transport.close(); executor.shutdownNow() }
    }

    @Test fun BlockingConnectionCreationDoesNotHoldCancellationLockOrRegisterLateHandles() {
        for (close in listOf(false, true)) {
            val arrived = CountDownLatch(1)
            val released = CountDownLatch(1)
            val late = Connection(URL("https://id.twitch.tv/oauth2/device"))
            var decoded = false
            val transport = TwitchCatalogAuthTransport(open = {
                arrived.countDown(); check(released.await(5, TimeUnit.SECONDS)); late
            }, decode = { decoded = true; emptyMap() })
            val executor = Executors.newSingleThreadExecutor()
            try {
                val pending = executor.submit<DeviceAuthResponse> { transport.device() }
                assertTrue(arrived.await(5, TimeUnit.SECONDS))
                // These calls must return while open() is still blocked.
                if (close) transport.close() else transport.cancelActiveRequest()
                released.countDown()
                val failure = assertThrows(ExecutionException::class.java) { pending.get(5, TimeUnit.SECONDS) }
                assertTrue(if (close) failure.cause is TwitchCatalogNetworkException else failure.cause is DeviceRequestPaused)
                assertTrue(late.disconnected)
                assertFalse(decoded)
            } finally { released.countDown(); transport.close(); executor.shutdownNow() }
        }
    }
}
