package net.fstab.tachiai.provider.abema.account

import java.io.InputStream
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.platform.network.RouteBackend
import net.fstab.tachiai.platform.network.RouteSession
import net.fstab.tachiai.platform.network.parseConnectionProfile
import org.junit.Assert.*
import org.junit.Test

// Fixed public request and owned receipts only: no DNS, provider, device,
// account, cookie jar or real CONNECT traffic is used by these fixtures.
class AbemaApprovalRegionCheckTest {
    private class Connection(
        private val status: Int = 200,
        private val allowOrigin: String? = "*",
        private val response: (() -> Unit)? = null,
        private val close: (() -> Unit)? = null,
        url: String = AbemaApprovalRegionCheck.URI,
    ) : HttpsURLConnection(URL(url)) {
        var disconnects = 0
        var bodyReads = 0
        val readHeaders = mutableListOf<String?>()
        override fun getResponseCode(): Int { response?.invoke(); return status }
        override fun getHeaderField(name: String?): String? {
            readHeaders.add(name)
            check(name == "Access-Control-Allow-Origin") { "PRIVATE UNREQUESTED HEADER" }
            return allowOrigin
        }
        override fun getInputStream(): InputStream { bodyReads++; error("PRIVATE BODY") }
        override fun getErrorStream(): InputStream { bodyReads++; error("PRIVATE ERROR BODY") }
        override fun disconnect() { disconnects++; close?.invoke() }
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
    private fun adapter(connection: Connection, gate: () -> Boolean = { true },
        clock: () -> Long = { 1000 }, deadline: Long = 20_000, wait: Long = 1000,
        onOpen: (URL) -> Unit = {}): AbemaApprovalRegionCheck = AbemaApprovalRegionCheck(
        { url -> onOpen(url); connection }, gate, clock, deadline, wait)
    private fun failure(result: AbemaApprovalRegionResult) = (result as AbemaApprovalRegionResult.Failure).category
    private fun retains(connection: Connection): Boolean {
        val retained = AbemaApprovalRegionCheck::class.java.getDeclaredField("retained").apply { isAccessible = true }.get(null) as Iterable<*>
        return retained.any { handle ->
            handle != null && handle.javaClass.getDeclaredField("connection").apply { isAccessible = true }.get(handle) === connection
        }
    }

    @Test fun productionConstructorRequiresSavedRouteAndKeepsRouteOwnershipWithCaller() {
        val system = RouteSession.createAbemaAccountSession(null)
        try {
            val error = assertThrows(IllegalArgumentException::class.java) {
                AbemaApprovalRegionCheck(system, { true }, { 1000 }, 20_000)
            }
            assertEquals("ABEMA region check requires a saved route", error.message)
        } finally { system.close() }
        var backendCloses = 0
        val backend = object : RouteBackend {
            override val proxyPort = 12345
            override fun close() { backendCloses++ }
        }
        val route = RouteSession.createAbemaApprovalBrowser(
            parseConnectionProfile("http://fixture:private@proxy.example.test:3128".toByteArray())) { _, _ -> backend }
        val check = AbemaApprovalRegionCheck(route, { true }, { 1000 }, 20_000)
        assertTrue(check.cleanupConfirmed); assertTrue(check.closeConfirmed())
        assertEquals(0, backendCloses)
        route.close(); assertEquals(1, backendCloses)
    }

    @Test fun canonicalCookieFreeGetPreservesRealProviderStatusesWithoutOpeningBodies() {
        for (status in listOf(200, 204, 401, 403, 404, 500)) {
            val connection = Connection(status)
            var opened = 0
            val check = adapter(connection, onOpen = { url ->
                opened++; assertEquals(AbemaApprovalRegionCheck.URI, url.toExternalForm())
            })
            val result = check.check("https://abema.tv") as AbemaApprovalRegionResult.Reply
            assertEquals(status, result.status)
            assertEquals(mapOf("Access-Control-Allow-Origin" to "*"), result.headers)
            assertEquals("GET", connection.requestMethod)
            assertFalse(connection.instanceFollowRedirects); assertFalse(connection.useCaches); assertFalse(connection.doOutput)
            assertEquals(mapOf("Accept" to listOf("*/*"), "Origin" to listOf("https://abema.tv")), connection.requestProperties)
            assertEquals(1, opened); assertEquals(1, connection.disconnects); assertEquals(0, connection.bodyReads)
            assertEquals(listOf("Access-Control-Allow-Origin"), connection.readHeaders)
            assertTrue(check.cleanupConfirmed); assertTrue(check.closeConfirmed()); assertTrue(check.closeConfirmed())
            assertEquals(1, connection.disconnects)
        }
    }

    @Test fun onlyCanonicalFirstPartyOriginCanBeSentAndOnlySafeServerCorsCanReturn() {
        for (origin in listOf("https://abema.tv/", "https://abema.tv:443", "http://abema.tv", "https://evil.invalid",
            "https://abema.tv\r\nCookie: PRIVATE", "null")) {
            var opened = 0
            val check = adapter(Connection(), onOpen = { opened++ })
            assertEquals(AbemaApprovalRegionFailure.REFUSED_REQUEST, failure(check.check(origin)))
            assertEquals(0, opened); assertTrue(check.closeConfirmed())
        }
        for (cors in listOf(null, "*", "https://abema.tv", "https://auth.abema.tv")) {
            val connection = Connection(allowOrigin = cors)
            val result = adapter(connection).check() as AbemaApprovalRegionResult.Reply
            assertEquals(if (cors == null) emptyMap() else mapOf("Access-Control-Allow-Origin" to cors), result.headers)
            assertNull(connection.getRequestProperty("Origin"))
        }
        val invalid = Connection(allowOrigin = "https://abema.tv\r\nSet-Cookie: PRIVATE")
        val check = adapter(invalid)
        assertEquals(AbemaApprovalRegionFailure.INVALID_RESPONSE, failure(check.check()))
        assertEquals(1, invalid.disconnects); assertEquals(0, invalid.bodyReads)
    }

    @Test fun redirectsAndWrongReturnedResourceNeverReadLocationFollowOrReadBody() {
        for (status in listOf(300, 301, 302, 303, 304, 307, 308, 399)) {
            val connection = Connection(status)
            var opened = 0
            val result = adapter(connection, onOpen = { opened++ }).check()
            assertEquals(AbemaApprovalRegionFailure.REDIRECT_REFUSED, failure(result))
            assertEquals(1, opened); assertEquals(1, connection.disconnects)
            assertTrue(connection.readHeaders.isEmpty()); assertEquals(0, connection.bodyReads)
        }
        for (url in listOf("https://ds-glb-linear-abematv.akamaized.net/other",
            "https://ds-glb-linear-abematv.akamaized.net/region?fixture=value", "https://abema.tv/")) {
            val connection = Connection(url = url)
            assertEquals(AbemaApprovalRegionFailure.REFUSED_REQUEST, failure(adapter(connection).check()))
            assertEquals(1, connection.disconnects); assertTrue(connection.readHeaders.isEmpty())
        }
    }

    @Test fun ownerGateIsCheckedBeforeAllocationAndAfterProviderResult() {
        var opened = 0
        assertEquals(AbemaApprovalRegionFailure.OWNER_NOT_CURRENT,
            failure(adapter(Connection(), gate = { false }, onOpen = { opened++ }).check()))
        assertEquals(0, opened)
        var owned = true
        val connection = Connection(response = { owned = false })
        assertEquals(AbemaApprovalRegionFailure.OWNER_NOT_CURRENT,
            failure(adapter(connection, gate = { owned }).check()))
        assertEquals(1, connection.disconnects); assertTrue(connection.readHeaders.isEmpty())
    }

    @Test fun originalDeadlineAndBackwardClockRefuseAllocationOrLateResults() {
        for (now in listOf(-1L, 20_000L, 20_001L)) {
            var opened = 0
            assertEquals(AbemaApprovalRegionFailure.EXPIRED,
                failure(adapter(Connection(), clock = { now }, onOpen = { opened++ }).check()))
            assertEquals(0, opened)
        }
        for (late in listOf(999L, 20_000L)) {
            val clock = AtomicLong(1000)
            val connection = Connection(response = { clock.set(late) })
            assertEquals(AbemaApprovalRegionFailure.EXPIRED,
                failure(adapter(connection, clock = clock::get).check()))
            assertEquals(1, connection.disconnects); assertTrue(connection.readHeaders.isEmpty())
        }
        val clock = AtomicLong(1000); var opened = 0
        assertEquals(AbemaApprovalRegionFailure.EXPIRED,
            failure(adapter(Connection(), gate = { clock.set(20_000); true }, clock = clock::get,
                onOpen = { opened++ }).check()))
        assertEquals(0, opened)
    }

    @Test fun originalDeadlineWatchdogCancelsBlockedStatusRequestAndClosesExactlyOnce() {
        val began = System.nanoTime()
        val entered = CountDownLatch(1); val disconnected = CountDownLatch(1)
        val connection = Connection(response = {
            entered.countDown(); check(disconnected.await(3, TimeUnit.SECONDS))
        }, close = { disconnected.countDown() })
        val check = adapter(connection, clock = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) }, deadline = 200)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<AbemaApprovalRegionResult> { check.check() }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertEquals(AbemaApprovalRegionFailure.EXPIRED, failure(result.get(1, TimeUnit.SECONDS)))
            assertEquals(1, connection.disconnects); assertTrue(check.closeConfirmed())
            assertEquals(1, connection.disconnects)
        } finally { check.cancel(); worker.shutdownNow() }
    }

    @Test fun nonblockingLifecycleCancellationStopsActiveRequestWithoutLateSuccessOrRepeatedClose() {
        val entered = CountDownLatch(1); val disconnected = CountDownLatch(1)
        val connection = Connection(response = {
            entered.countDown(); check(disconnected.await(3, TimeUnit.SECONDS))
        }, close = { disconnected.countDown() })
        val check = adapter(connection)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<AbemaApprovalRegionResult> { check.check() }
            assertTrue(entered.await(1, TimeUnit.SECONDS)); check.cancel()
            assertEquals(AbemaApprovalRegionFailure.CANCELLED, failure(result.get(1, TimeUnit.SECONDS)))
            repeat(2) { assertTrue(check.closeConfirmed()) }
            assertEquals(1, connection.disconnects)
            assertEquals(AbemaApprovalRegionFailure.CANCELLED, failure(check.check()))
        } finally { check.cancel(); worker.shutdownNow() }
    }

    @Test fun privateTransportErrorsBecomeClosedCategoriesWithConfirmedCleanup() {
        val connection = Connection(response = { throw AssertionError("PRIVATE URL COOKIE BODY") })
        val check = adapter(connection)
        val result = check.check()
        assertEquals(AbemaApprovalRegionFailure.TRANSPORT_FAILED, failure(result))
        assertFalse(result.toString().contains("PRIVATE")); assertFalse(check.toString().contains("PRIVATE"))
        assertEquals(1, connection.disconnects); assertEquals(0, connection.bodyReads)
        assertTrue(check.cleanupConfirmed)
    }

    @Test fun failedDisconnectRetainsActualConnectionAndCannotReleaseRouteOrRetryClose() {
        val connection = Connection(close = { throw AssertionError("PRIVATE DISCONNECT DETAIL") })
        val check = adapter(connection, wait = 50)
        val result = check.check()
        assertEquals(AbemaApprovalRegionFailure.CLEANUP_FAILED, failure(result))
        assertFalse(result.toString().contains("PRIVATE")); assertFalse(check.cleanupConfirmed)
        assertTrue(retains(connection))
        repeat(2) { assertFalse(check.closeConfirmed()) }
        assertEquals(1, connection.disconnects)
        assertEquals(AbemaApprovalRegionFailure.CLEANUP_FAILED, failure(check.check()))
    }

    @Test fun timedOutDisconnectStaysUnconfirmedAndRetainedAfterLateCompletion() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val finished = CountDownLatch(1)
        val connection = Connection(close = {
            entered.countDown()
            try { check(release.await(3, TimeUnit.SECONDS)) } finally { finished.countDown() }
        })
        val check = adapter(connection, wait = 50)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<AbemaApprovalRegionResult> { check.check() }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertEquals(AbemaApprovalRegionFailure.CLEANUP_FAILED, failure(result.get(1, TimeUnit.SECONDS)))
            assertTrue(retains(connection)); assertFalse(check.cleanupConfirmed)
            release.countDown(); assertTrue(finished.await(1, TimeUnit.SECONDS))
            repeat(2) { assertFalse(check.closeConfirmed()) }
            assertEquals(1, connection.disconnects)
        } finally { release.countDown(); check.cancel(); worker.shutdownNow() }
    }
}
