package net.fstab.tachiai.provider.abema

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL
import java.net.CookieHandler
import java.net.CookieManager
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.*
import org.junit.Test

class AbemaMyListHttpTransportTest {
    private val url = abemaMyListRequestUri().toURL()
    private val bytes = """{"mylist":[{"contentId":"fixture-1","contentType":"CONTENT_TYPE_PROGRAM"}],"dataSet":{"programs":[{"id":"fixture-1","title":"Fixture episode"}]}}""".toByteArray()
    private class Hostile : Error() {
        override val message: String get() = throw AssertionError("Throwable message was inspected")
        override fun toString(): String = throw AssertionError("Throwable text was inspected")
    }
    private class Connection(
        url: URL,
        val bytes: ByteArray,
        val status: Int = 200,
        val length: Long = -1,
        val mime: String? = "application/json; charset=utf-8",
        val retry: String? = null,
        val beforeStatus: () -> Unit = {},
        val beforeRead: () -> Unit = {},
        val beforeStream: () -> Unit = {},
        val streamClose: () -> Unit = {},
        val disconnectAction: () -> Unit = {},
    ) : HttpsURLConnection(url) {
        val statuses = AtomicInteger()
        val streams = AtomicInteger()
        val streamCloses = AtomicInteger()
        val disconnects = AtomicInteger()
        val errors = AtomicInteger()
        val retryReads = AtomicInteger()
        override fun connect() = Unit
        override fun disconnect() { disconnects.incrementAndGet(); disconnectAction() }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode(): Int { statuses.incrementAndGet(); beforeStatus(); return status }
        override fun getContentLengthLong() = length
        override fun getContentType() = mime
        override fun getHeaderField(name: String?): String? {
            if (name == "Retry-After") { retryReads.incrementAndGet(); return retry }
            throw AssertionError("Unexpected response header")
        }
        override fun getErrorStream(): InputStream { errors.incrementAndGet(); throw Hostile() }
        override fun getInputStream(): InputStream {
            streams.incrementAndGet(); beforeStream()
            return object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    beforeRead(); return super.read(buffer, offset, length)
                }
                override fun close() { streamCloses.incrementAndGet(); streamClose(); super.close() }
            }
        }
    }
    private fun failure(expected: AbemaCollectionFailure, work: () -> Unit): AbemaCollectionException {
        val error = assertThrows(AbemaCollectionException::class.java, work)
        assertEquals(expected, error.category); assertEquals(expected.name, error.message)
        assertNull(error.cause); assertEquals(0, error.stackTrace.size); assertEquals(0, error.suppressed.size)
        return error
    }

    @Test fun fixedOneGetUsesOnlyPrivatelySuppliedBearerAndClosesBeforePublicProjectionEscapes() {
        val requested = abemaMyListRequestUri("next+/=&page=2").toURL()
        val order = mutableListOf<String>()
        val connection = Connection(requested, bytes, length = bytes.size.toLong(),
            streamClose = { order.add("stream") }, disconnectAction = { order.add("connection") })
        var opens = 0; var bearerReads = 0
        val http = AbemaMyListHttpTransport(open = { opens++; assertEquals(requested.toExternalForm(), it.toExternalForm()); connection },
            bearer = { bearerReads++; "fixture-private-bearer" })
        val response = http.fetchMyList("next+/=&page=2")
        assertEquals("fixture-1", response.page!!.entries.single().resource.identity)
        assertEquals(listOf("stream", "connection"), order)
        assertEquals(1, opens); assertEquals(1, bearerReads)
        assertEquals("GET", connection.requestMethod)
        assertEquals("application/json", connection.getRequestProperty("Accept"))
        assertEquals("Bearer fixture-private-bearer", connection.getRequestProperty("Authorization"))
        listOf("Cookie", "Proxy-Authorization", "Client-ID").forEach { assertNull(connection.getRequestProperty(it)) }
        assertFalse(connection.doOutput); assertFalse(connection.instanceFollowRedirects); assertFalse(connection.useCaches)
        assertEquals(10_000, connection.connectTimeout); assertEquals(10_000, connection.readTimeout)
        http.close(); assertEquals(1, connection.disconnects.get()); assertEquals(1, connection.streamCloses.get())
        assertEquals("AbemaMyListHttpTransport(redacted)", http.toString())
    }

    @Test fun wrongHostPathQueryAndTokenInputCannotDispatchOrAdoptARedirect() {
        for (address in listOf("https://api.abema.io/v1/mylist", "https://abema.tv/v1/mylist",
            "https://api.p-c3-e.abema-tv.com/v1/users/private-fixture", "https://api.p-c3-e.abema-tv.com/v1/mylist?limit=100",
            "https://api.p-c3-e.abema-tv.com:443/v1/mylist", "https://api.p-c3-e.abema-tv.com.evil.test/v1/mylist")) {
            val connection = Connection(URL(address), bytes)
            failure(AbemaCollectionFailure.POLICY_REFUSED) { AbemaMyListHttpTransport({ connection }, { "fixture" }).fetchMyList(null) }
            assertEquals(0, connection.statuses.get()); assertEquals(1, connection.disconnects.get())
        }
        for (token in listOf(null, "", "a b", "x\nheader", "あ", "x".repeat(6145))) {
            failure(AbemaCollectionFailure.POLICY_REFUSED) {
                AbemaMyListHttpTransport({ throw AssertionError("Invalid token dispatched") }, { token }).fetchMyList(null)
            }
        }
        failure(AbemaCollectionFailure.POLICY_REFUSED) {
            AbemaMyListHttpTransport({ throw AssertionError("Invalid continuation dispatched") }, { "fixture" }).fetchMyList("x\n")
        }
    }

    @Test fun everyNon200BodyRemainsUnreadAndOnly429ReadsBoundedRetryHeader() {
        for (status in listOf(201, 204, 301, 302, 307, 308, 400, 401, 403, 404, 410, 429, 500, 503)) {
            val connection = Connection(url, byteArrayOf(), status = status, mime = null, retry = "9999999999")
            val response = AbemaMyListHttpTransport({ connection }, { "fixture" }, wallMs = { 1_000 }).use { it.fetchMyList(null) }
            assertEquals(status, response.status); assertNull(response.page)
            assertEquals(0, connection.streams.get()); assertEquals(0, connection.errors.get())
            assertEquals(if (status == 429) 1 else 0, connection.retryReads.get())
            assertEquals(if (status == 429) 86_401_000L else null, response.retryAtEpochMs)
            assertEquals(1, connection.disconnects.get())
        }
        for (status in listOf(-1, 0, 99, 600)) {
            val connection = Connection(url, bytes, status = status)
            failure(AbemaCollectionFailure.INVALID_RESPONSE) { AbemaMyListHttpTransport({ connection }, { "fixture" }).fetchMyList(null) }
            assertEquals(0, connection.streams.get())
        }
    }

    @Test fun utf8JsonMimeAndAnnouncedOrStreamedBoundsAreStrict() {
        for (mime in listOf(null, "text/html", "application/json; charset=latin1", "application/json; charset=utf-8; charset=utf-8")) {
            val connection = Connection(url, bytes, mime = mime)
            failure(AbemaCollectionFailure.INVALID_RESPONSE) { AbemaMyListHttpTransport({ connection }, { "fixture" }).fetchMyList(null) }
            assertEquals(0, connection.streams.get())
        }
        for (malformed in listOf(byteArrayOf(0xc3.toByte(), 0x28), "{mylist:[]}".toByteArray(),
            "{\"mylist\":[],\"mylist\":[]}".toByteArray(), "{\"mylist\":[]}{}".toByteArray())) {
            val connection = Connection(url, malformed)
            failure(AbemaCollectionFailure.INVALID_RESPONSE) { AbemaMyListHttpTransport({ connection }, { "fixture" }).fetchMyList(null) }
            assertEquals(1, connection.streamCloses.get()); assertEquals(1, connection.disconnects.get())
        }
        for (length in listOf(-2L, 0L, bytes.size - 1L, bytes.size + 1L)) {
            failure(AbemaCollectionFailure.INVALID_RESPONSE) { AbemaMyListHttpTransport({ Connection(url, bytes, length = length) }, { "fixture" }).fetchMyList(null) }
        }
        val announced = Connection(url, bytes, length = ABEMA_MY_LIST_RESPONSE_LIMIT + 1L)
        failure(AbemaCollectionFailure.RESPONSE_LIMIT) { AbemaMyListHttpTransport({ announced }, { "fixture" }).fetchMyList(null) }
        assertEquals(0, announced.streams.get())
        val streamed = Connection(url, ByteArray(ABEMA_MY_LIST_RESPONSE_LIMIT + 1) { 32 })
        failure(AbemaCollectionFailure.RESPONSE_LIMIT) { AbemaMyListHttpTransport({ streamed }, { "fixture" }).fetchMyList(null) }
        assertEquals(1, streamed.streamCloses.get()); assertEquals(1, streamed.disconnects.get())
    }

    @Test fun ownerChecksBeforeAndAfterBearerStatusReadAndCleanupRejectLateResults() {
        var allowed = false; var reads = 0
        failure(AbemaCollectionFailure.CANCELLED) {
            AbemaMyListHttpTransport({ throw AssertionError("Owner loss dispatched") }, { reads++; "fixture" }, { allowed }).fetchMyList(null)
        }
        assertEquals(0, reads)
        allowed = true
        failure(AbemaCollectionFailure.CANCELLED) {
            AbemaMyListHttpTransport({ throw AssertionError("Bearer-time owner loss dispatched") }, { allowed = false; "fixture" }, { allowed }).fetchMyList(null)
        }
        for (phase in listOf("status", "read", "cleanup")) {
            allowed = true
            val connection = Connection(url, bytes, beforeStatus = { if (phase == "status") allowed = false },
                beforeRead = { if (phase == "read") allowed = false }, disconnectAction = { if (phase == "cleanup") allowed = false })
            failure(AbemaCollectionFailure.CANCELLED) { AbemaMyListHttpTransport({ connection }, { "fixture" }, { allowed }).fetchMyList(null) }
            assertEquals(1, connection.disconnects.get())
        }
    }

    @Test fun monotonicDeadlineAndRollbackCoverResponseReadAndCleanup() {
        for (phase in listOf("status", "read", "cleanup")) for (late in listOf(30_000L, -1L)) {
            val clock = AtomicLong(0)
            val connection = Connection(url, bytes, beforeStatus = { if (phase == "status") clock.set(late) },
                beforeRead = { if (phase == "read") clock.set(late) }, disconnectAction = { if (phase == "cleanup") clock.set(late) })
            failure(AbemaCollectionFailure.TIME_LIMIT_REACHED) {
                AbemaMyListHttpTransport({ connection }, { "fixture" }, clockMs = { clock.get() }).fetchMyList(null)
            }
            assertEquals(1, connection.disconnects.get())
        }
    }

    @Test fun cancellationDuringOpeningAndReadingDiscardsLateResourcesAndCanStartAFreshRequest() {
        lateinit var opening: AbemaMyListHttpTransport
        val first = Connection(url, bytes)
        val next = Connection(url, bytes)
        var calls = 0
        opening = AbemaMyListHttpTransport({ if (calls++ == 0) { opening.cancelActiveRequest(); first } else next }, { "fixture" })
        failure(AbemaCollectionFailure.CANCELLED) { opening.fetchMyList(null) }
        assertEquals(0, first.statuses.get()); assertEquals(1, first.disconnects.get())
        assertEquals(200, opening.fetchMyList(null).status); opening.close()
        assertEquals(1, next.disconnects.get())
        lateinit var reading: AbemaMyListHttpTransport
        var cancelled = false
        val stream = Connection(url, bytes, beforeRead = { if (!cancelled) { cancelled = true; reading.cancelActiveRequest() } })
        reading = AbemaMyListHttpTransport({ stream }, { "fixture" })
        failure(AbemaCollectionFailure.CANCELLED) { reading.fetchMyList(null) }
        assertEquals(1, stream.streamCloses.get()); assertEquals(1, stream.disconnects.get())
        reading.close()
    }

    @Test fun cookiePolicyNeverFallsBackAndConfirmedCloseRefusesAnInFlightAllocation() {
        val previous = CookieHandler.getDefault()
        val cookie = CookieManager()
        try {
            CookieHandler.setDefault(cookie)
            failure(AbemaCollectionFailure.COOKIE_HANDLER_REFUSED) {
                AbemaMyListHttpTransport({ throw AssertionError("Cookie policy dispatched") }, { "fixture" }).fetchMyList(null)
            }
            assertSame(cookie, CookieHandler.getDefault())
        } finally { CookieHandler.setDefault(previous) }
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val connection = Connection(url, bytes)
        val http = AbemaMyListHttpTransport({ entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)); connection }, { "fixture" })
        val result = AtomicReference<Throwable?>()
        val worker = Thread { try { http.fetchMyList(null) } catch (error: Throwable) { result.set(error) } }
        worker.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
        failure(AbemaCollectionFailure.CLEANUP_FAILED) { http.close() }
        finish.countDown(); worker.join(5_000); assertFalse(worker.isAlive)
        assertEquals(AbemaCollectionFailure.CLEANUP_FAILED, (result.get() as AbemaCollectionException).category)
        assertEquals(0, connection.statuses.get()); assertEquals(1, connection.disconnects.get())
        failure(AbemaCollectionFailure.CLEANUP_FAILED) { http.fetchMyList(null) }
        assertEquals(1, connection.disconnects.get())
    }

    @Test fun fatalReadAndHostileDiagnosticCallbacksExposeOnlyClosedCategories() {
        val reports = mutableListOf<AbemaCollectionFailure>()
        val connection = Connection(url, bytes, beforeRead = { throw Hostile() })
        failure(AbemaCollectionFailure.NETWORK_FAILED) {
            AbemaMyListHttpTransport({ connection }, { "fixture" }, onFailure = { reports.add(it); throw Hostile() }).fetchMyList(null)
        }
        assertEquals(listOf(AbemaCollectionFailure.NETWORK_FAILED), reports)
        assertEquals(1, connection.streamCloses.get()); assertEquals(1, connection.disconnects.get())
        failure(AbemaCollectionFailure.NETWORK_FAILED) {
            AbemaMyListHttpTransport({ throw Hostile() }, { "fixture" }).fetchMyList(null)
        }
    }

    @Test fun fatalStreamOrDisconnectCleanupIsStickyRetainedAndNeverRepeated() {
        for (stream in listOf(false, true)) {
            val reports = mutableListOf<AbemaCollectionFailure>()
            val connection = Connection(url, bytes, streamClose = { if (stream) throw Hostile() },
                disconnectAction = { if (!stream) throw Hostile() })
            val http = AbemaMyListHttpTransport({ connection }, { "fixture" }, onFailure = { reports.add(it) })
            failure(AbemaCollectionFailure.CLEANUP_FAILED) { http.fetchMyList(null) }
            failure(AbemaCollectionFailure.CLEANUP_FAILED) { http.close() }
            failure(AbemaCollectionFailure.CLEANUP_FAILED) { http.fetchMyList(null) }
            assertEquals(1, connection.streamCloses.get()); assertEquals(1, connection.disconnects.get())
            assertTrue(reports.contains(AbemaCollectionFailure.CLEANUP_FAILED))
        }
    }

    @Test fun heldCleanupAttemptsDisconnectAndLateSuccessCannotReopenPoisonedAdmission() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val disconnected = CountDownLatch(1)
        val connection = Connection(url, bytes, streamClose = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) },
            disconnectAction = { disconnected.countDown() })
        val http = AbemaMyListHttpTransport({ connection }, { "fixture" }, cleanupWaitMs = 50)
        try {
            failure(AbemaCollectionFailure.CLEANUP_FAILED) { http.fetchMyList(null) }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); assertTrue(disconnected.await(5, TimeUnit.SECONDS))
        } finally { release.countDown() }
        failure(AbemaCollectionFailure.CLEANUP_FAILED) { http.close() }
        failure(AbemaCollectionFailure.CLEANUP_FAILED) { http.fetchMyList(null) }
        assertEquals(1, connection.streamCloses.get()); assertEquals(1, connection.disconnects.get())
    }

    @Test fun reentrantCleanupCannotBeMistakenForConfirmedNoOpAndConcurrentCancelJoinsOneReceipt() {
        lateinit var reentrant: AbemaMyListHttpTransport
        val first = Connection(url, bytes, disconnectAction = { reentrant.cancelActiveRequest() })
        reentrant = AbemaMyListHttpTransport({ first }, { "fixture" })
        failure(AbemaCollectionFailure.CLEANUP_FAILED) { reentrant.fetchMyList(null) }
        assertEquals(1, first.disconnects.get())
        val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val second = Connection(url, bytes, beforeRead = { entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)) })
        val concurrent = AbemaMyListHttpTransport({ second }, { "fixture" })
        val result = AtomicReference<Throwable?>()
        val worker = Thread { try { concurrent.fetchMyList(null) } catch (error: Throwable) { result.set(error) } }
        worker.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
        val cancels = List(2) { Thread { concurrent.cancelActiveRequest() } }
        cancels.forEach { it.start() }; cancels.forEach { it.join(5_000); assertFalse(it.isAlive) }
        finish.countDown(); worker.join(5_000); assertFalse(worker.isAlive)
        assertEquals(AbemaCollectionFailure.CANCELLED, (result.get() as AbemaCollectionException).category)
        assertEquals(1, second.streamCloses.get()); assertEquals(1, second.disconnects.get())
        concurrent.close()
    }
}
