package net.fstab.tachiai.provider.abema

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.URL
import java.security.cert.Certificate
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.*
import org.junit.Test
import net.fstab.tachiai.platform.network.*

class AbemaChannelHttpTransportTest {
    @Test fun channelReadKeepsTypedTransportFailureWhileMalformedResponsesRemainUnclassified() {
        val evidence = ProviderTransportEvidence(ProviderTransportFailure.DNS)
        val connection = Connection(url, body, beforeResponse = {
            throw ProviderTransportException(evidence)
        })
        val transport = AbemaChannelHttpTransport(open = { connection })
        val error = failure(AbemaChannelFailure.NETWORK_FAILED) { transport.fetch() }
        assertEquals(evidence, error.transportFailure); assertEquals(1, connection.disconnects)
        transport.close()
        val malformed = AbemaChannelHttpTransport(open = { Connection(url, "not json".toByteArray()) })
        assertNull(failure(AbemaChannelFailure.INVALID_RESPONSE) { malformed.fetch() }.transportFailure)
        malformed.close()
    }

    private val url = URL("https://api.abema.io/v1/channels")
    private val body = "{\"channels\":[{\"id\":\"sumo\",\"name\":\"相撲\",\"viewingOpened\":true}]}".toByteArray()

    private class Connection(
        url: URL,
        private val body: ByteArray,
        private val status: Int = 200,
        private val length: Long = -1,
        private val mime: String? = "application/json; charset=utf-8",
        private val retryAfter: String? = null,
        private val chunkSize: Int = 8192,
        private val beforeResponse: () -> Unit = {},
        private val beforeRead: () -> Unit = {},
        private val beforeStreamClose: () -> Unit = {},
        private val onDisconnect: () -> Unit = {},
    ) : HttpsURLConnection(url) {
        var disconnects = 0
        var statusReads = 0
        var bodyRead = false
        var errorRead = false
        var retryReads = 0
        override fun connect() = Unit
        override fun disconnect() { disconnects++; onDisconnect() }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode(): Int { statusReads++; beforeResponse(); return status }
        override fun getContentLengthLong() = length
        override fun getContentType() = mime
        override fun getHeaderField(name: String?): String? {
            if (name == "Retry-After") { retryReads++; return retryAfter }
            return null
        }
        override fun getErrorStream(): InputStream { errorRead = true; return ByteArrayInputStream(body) }
        override fun getInputStream(): InputStream {
            bodyRead = true
            return object : ByteArrayInputStream(body) {
                override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                    beforeRead()
                    return super.read(buffer, offset, minOf(count, chunkSize))
                }
                override fun close() { beforeStreamClose(); super.close() }
            }
        }
    }

    private fun failure(category: AbemaChannelFailure, operation: () -> Unit): AbemaChannelException {
        val error = assertThrows(AbemaChannelException::class.java, operation)
        assertEquals(category, error.category)
        assertEquals(category.name, error.message)
        assertNull(error.cause)
        assertEquals(0, error.suppressed.size)
        assertEquals(0, error.stackTrace.size)
        assertFalse(error.toString().contains("PRIVATE_SENTINEL"))
        return error
    }

    @Test fun exactGetUsesTheRequiredOpenerAndNeverAddsAccountHeaders() {
        val connection = Connection(url, body, length = body.size.toLong())
        var opens = 0
        AbemaChannelHttpTransport(open = { requested -> opens++; assertEquals(url.toExternalForm(), requested.toExternalForm()); connection }).use {
            val response = it.fetch()
            assertEquals(200, response.status)
            assertEquals("sumo", response.entries.single().resource.identity)
            assertNull(response.retryAtEpochMs)
            assertEquals("AbemaChannelHttpTransport(redacted)", it.toString())
        }
        assertEquals(1, opens)
        assertEquals("GET", connection.requestMethod)
        assertEquals("application/json", connection.getRequestProperty("Accept"))
        listOf("Authorization", "Cookie", "Proxy-Authorization", "Client-ID").forEach {
            assertNull(connection.getRequestProperty(it))
        }
        assertFalse(connection.doOutput)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertEquals(10_000, connection.connectTimeout)
        assertEquals(10_000, connection.readTimeout)
        assertEquals(1, connection.disconnects)
    }

    @Test fun endpointValidatorRejectsCredentialQueryRedirectTraversalAndAuthorityVariants() {
        assertTrue(validAbemaChannelUrl(url))
        listOf("http://api.abema.io/v1/channels", "https://api.abema.io:443/v1/channels",
            "https://api.abema.io:8443/v1/channels", "https://user:password@api.abema.io/v1/channels",
            "https://api.abema.io/v1/channels?token=PRIVATE_SENTINEL", "https://api.abema.io/v1/channels?",
            "https://api.abema.io/v1/channels#fragment", "https://api.abema.io/v1/channels#",
            "https://api.abema.io/v1/%63hannels", "https://api.abema.io/v1/../v1/channels",
            "https://api.abema.io/v1/channels/", "https://api.abema.io/v1/channels/sumo",
            "https://api.abema.io.evil.test/v1/channels", "https://sub.api.abema.io/v1/channels",
            "https://api.abema.io./v1/channels", "https://abema.tv/v1/channels").forEach {
            assertFalse(it, validAbemaChannelUrl(URL(it)))
        }
        val foreign = Connection(URL("https://example.test/PRIVATE_SENTINEL"), body)
        failure(AbemaChannelFailure.POLICY_REFUSED) { AbemaChannelHttpTransport(open = { foreign }).fetch() }
        assertEquals(0, foreign.statusReads)
        assertFalse(foreign.bodyRead)
        assertEquals(1, foreign.disconnects)
    }

    @Test fun non200StatusesNeverReadSuccessOrErrorBodiesOrFollowRedirects() {
        listOf(201, 204, 301, 302, 304, 307, 308, 400, 401, 403, 404, 429, 500, 503).forEach { status ->
            val connection = Connection(url, "PRIVATE_SENTINEL".toByteArray(), status = status)
            val response = AbemaChannelHttpTransport(open = { connection }).use { it.fetch() }
            assertEquals(status, response.status)
            assertTrue(response.entries.isEmpty())
            assertFalse(connection.bodyRead)
            assertFalse(connection.errorRead)
            assertFalse(connection.instanceFollowRedirects)
            assertEquals(if (status == 429) 1 else 0, connection.retryReads)
            assertEquals(1, connection.disconnects)
        }
        listOf(-1, 0, 99, 600).forEach { status ->
            val connection = Connection(url, body, status = status)
            failure(AbemaChannelFailure.INVALID_RESPONSE) { AbemaChannelHttpTransport(open = { connection }).fetch() }
            assertFalse(connection.bodyRead)
        }
    }

    @Test fun retryAfterSecondsDatesAndOverflowStayWithinOneDay() {
        val now = 1_600_000_000_000L
        fun retry(header: String?, clock: Long = now): Long? = AbemaChannelHttpTransport(
            open = { Connection(url, body, status = 429, retryAfter = header) }, wallMs = { clock },
        ).use { it.fetch().retryAtEpochMs }
        assertEquals(now + 5000, retry("5"))
        assertEquals(now, retry("0"))
        assertEquals(now + 5000, retry(" 005 "))
        assertEquals(now + 86_400_000, retry("9999999999"))
        fun date(offset: Long) = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now + offset).atZone(ZoneOffset.UTC))
        assertEquals(now + 120_000, retry(date(120_000)))
        assertEquals(now, retry(date(-120_000)))
        assertEquals(now + 86_400_000, retry(date(172_800_000)))
        assertEquals(Long.MAX_VALUE, retry("5", Long.MAX_VALUE - 1))
        assertEquals(5000L, retry("5", -1))
        listOf(null, "", "-1", "1.5", "PRIVATE_SENTINEL", "9".repeat(20), " ".repeat(129)).forEach {
            assertNull(retry(it))
        }
    }

    @Test fun contentTypeRequiresJsonAndAnUnambiguousUtf8Charset() {
        listOf("application/json", "APPLICATION/JSON; CHARSET=\"utf-8\"", "application/json; charset=utf8").forEach { mime ->
            val connection = Connection(url, body, mime = mime)
            assertEquals(1, AbemaChannelHttpTransport(open = { connection }).use { it.fetch().entries.size })
        }
        listOf(null, "text/plain", "text/html", "application/javascript", "application/json; charset=latin1",
            "application/json; charset=utf-16", "application/json; charset=utf-8; charset=utf-8",
            "application/json; charset=\"utf-8", "application/json; profile=PRIVATE_SENTINEL").forEach { mime ->
            val connection = Connection(url, body, mime = mime)
            failure(AbemaChannelFailure.INVALID_RESPONSE) { AbemaChannelHttpTransport(open = { connection }).fetch() }
            assertFalse(connection.bodyRead)
            assertEquals(1, connection.disconnects)
        }
    }

    @Test fun malformedUtf8JsonAndResponseShapeNeverReturnMetadata() {
        val invalidUtf8 = "{\"channels\":[{\"id\":\"sumo\",\"name\":\"".toByteArray() +
            byteArrayOf(0xc3.toByte(), 0x28) + "\"}]}".toByteArray()
        listOf(invalidUtf8, "PRIVATE_SENTINEL".toByteArray(), "{}".toByteArray(),
            "{channels:[]}".toByteArray(), "{\"channels\":[]}{}".toByteArray()).forEach { bytes ->
            val connection = Connection(url, bytes)
            failure(AbemaChannelFailure.INVALID_RESPONSE) { AbemaChannelHttpTransport(open = { connection }).fetch() }
            assertEquals(1, connection.disconnects)
        }
    }

    @Test fun announcedStreamedAndMultibyteByteBoundsAreEnforced() {
        val announced = Connection(url, body, length = ABEMA_CHANNEL_RESPONSE_LIMIT + 1L)
        failure(AbemaChannelFailure.RESPONSE_LIMIT) { AbemaChannelHttpTransport(open = { announced }).fetch() }
        assertFalse(announced.bodyRead)
        val oversized = Connection(url, ByteArray(ABEMA_CHANNEL_RESPONSE_LIMIT + 1) { ' '.code.toByte() })
        failure(AbemaChannelFailure.RESPONSE_LIMIT) { AbemaChannelHttpTransport(open = { oversized }).fetch() }
        assertTrue(oversized.bodyRead)
        assertEquals(1, oversized.disconnects)
        val prefix = "{\"channels\":[],\"ignored\":\""
        val exact = (prefix + "a".repeat(ABEMA_CHANNEL_RESPONSE_LIMIT - prefix.length - 2) + "\"}").toByteArray()
        assertEquals(ABEMA_CHANNEL_RESPONSE_LIMIT, exact.size)
        assertTrue(AbemaChannelHttpTransport(open = { Connection(url, exact) }).use { it.fetch().entries.isEmpty() })
        val unicode = (prefix + "相".repeat(ABEMA_CHANNEL_RESPONSE_LIMIT / 3) + "\"}").toByteArray()
        failure(AbemaChannelFailure.RESPONSE_LIMIT) { AbemaChannelHttpTransport(open = { Connection(url, unicode) }).fetch() }
    }

    @Test fun contradictoryContentLengthsAreRejectedRatherThanTrusted() {
        listOf(-2L, 0L).forEach { length ->
            val connection = Connection(url, body, length = length)
            failure(AbemaChannelFailure.INVALID_RESPONSE) { AbemaChannelHttpTransport(open = { connection }).fetch() }
            assertFalse(connection.bodyRead)
        }
        listOf(body.size - 1L, body.size + 1L).forEach { length ->
            val connection = Connection(url, body, length = length)
            failure(AbemaChannelFailure.INVALID_RESPONSE) { AbemaChannelHttpTransport(open = { connection }).fetch() }
            assertEquals(1, connection.disconnects)
        }
    }

    @Test fun closedOwnerLostAndInterruptedRequestsDoNotOpenAConnection() {
        val denied = AbemaChannelHttpTransport(open = { throw AssertionError("owner loss dispatched") }, canRequest = { false })
        failure(AbemaChannelFailure.CANCELLED) { denied.fetch() }
        val closed = AbemaChannelHttpTransport(open = { throw AssertionError("closed dispatched") })
        closed.close()
        failure(AbemaChannelFailure.CANCELLED) { closed.fetch() }
        Thread.currentThread().interrupt()
        try {
            failure(AbemaChannelFailure.CANCELLED) { AbemaChannelHttpTransport(open = { throw AssertionError("interrupt dispatched") }).fetch() }
        } finally { Thread.interrupted() }
    }

    @Test fun closeDuringOpeningAndStatusDiscardsLateConnectionsBeforeBodyRead() {
        lateinit var opening: AbemaChannelHttpTransport
        val first = Connection(url, body)
        opening = AbemaChannelHttpTransport(open = { opening.close(); first })
        failure(AbemaChannelFailure.CANCELLED) { opening.fetch() }
        assertEquals(0, first.statusReads)
        assertEquals(1, first.disconnects)
        lateinit var status: AbemaChannelHttpTransport
        val second = Connection(url, body, beforeResponse = { status.close() })
        status = AbemaChannelHttpTransport(open = { second })
        failure(AbemaChannelFailure.CANCELLED) { status.fetch() }
        assertFalse(second.bodyRead)
        assertEquals(1, second.disconnects)
    }

    @Test fun ownerLossDuringReadingDiscardsBytesAndReleasesTheConnection() {
        var allowed = true
        val connection = Connection(url, body, beforeRead = { allowed = false })
        failure(AbemaChannelFailure.CANCELLED) { AbemaChannelHttpTransport(open = { connection }, canRequest = { allowed }).fetch() }
        assertTrue(connection.bodyRead)
        assertEquals(1, connection.disconnects)
    }

    @Test fun ownerLossOrTimeoutDuringCleanupCannotPublishAnOtherwiseValidResponse() {
        var allowed = true
        val lost = Connection(url, body, onDisconnect = { allowed = false })
        failure(AbemaChannelFailure.CANCELLED) { AbemaChannelHttpTransport(open = { lost }, canRequest = { allowed }).fetch() }
        assertEquals(1, lost.disconnects)
        var now = 0L
        val late = Connection(url, body, onDisconnect = { now = 30_000 })
        failure(AbemaChannelFailure.TIME_LIMIT_REACHED) { AbemaChannelHttpTransport(open = { late }, clockMs = { now }).fetch() }
        assertEquals(1, late.disconnects)
    }

    @Test fun cancellationDoesNotPublishOldBytesAndAFreshRequestCanProceed() {
        lateinit var http: AbemaChannelHttpTransport
        val cancelled = Connection(url, body, beforeRead = { http.cancelActiveRequest() })
        val replacement = Connection(url, body)
        var calls = 0
        http = AbemaChannelHttpTransport(open = { if (calls++ == 0) cancelled else replacement })
        failure(AbemaChannelFailure.CANCELLED) { http.fetch() }
        assertEquals(1, cancelled.disconnects)
        assertEquals("sumo", http.fetch().entries.single().resource.identity)
        assertEquals(1, replacement.disconnects)
        http.close()
    }

    @Test fun totalBudgetAndMonotonicRollbackRefuseLateResponses() {
        for (late in listOf(30_000L, -1L)) {
            var now = 0L
            val connection = Connection(url, body, beforeResponse = { now = late })
            failure(AbemaChannelFailure.TIME_LIMIT_REACHED) {
                AbemaChannelHttpTransport(open = { connection }, clockMs = { now }).fetch()
            }
            assertFalse(connection.bodyRead)
            assertEquals(1, connection.disconnects)
        }
        var now = 0L
        val streamed = Connection(url, body, chunkSize = 10, beforeRead = { now += 10_000 })
        failure(AbemaChannelFailure.TIME_LIMIT_REACHED) { AbemaChannelHttpTransport(open = { streamed }, clockMs = { now }).fetch() }
        assertTrue(streamed.bodyRead)
        assertEquals(1, streamed.disconnects)
    }

    @Test fun globalCookieHandlerIsRefusedBeforeOpeningAndNeverChanged() {
        val previous = CookieHandler.getDefault()
        val handler = CookieManager()
        try {
            CookieHandler.setDefault(handler)
            failure(AbemaChannelFailure.COOKIE_HANDLER_REFUSED) {
                AbemaChannelHttpTransport(open = { throw AssertionError("cookie handler dispatched") }).fetch()
            }
            assertSame(handler, CookieHandler.getDefault())
        } finally { CookieHandler.setDefault(previous) }
    }

    @Test fun aCookieHandlerAppearingAtStatusOrDuringReadInvalidatesTheResult() {
        val previous = CookieHandler.getDefault()
        val handler = CookieManager()
        try {
            CookieHandler.setDefault(null)
            val status = Connection(url, body, beforeResponse = { CookieHandler.setDefault(handler) })
            failure(AbemaChannelFailure.COOKIE_HANDLER_REFUSED) { AbemaChannelHttpTransport(open = { status }).fetch() }
            assertFalse(status.bodyRead)
            assertSame(handler, CookieHandler.getDefault())
            CookieHandler.setDefault(null)
            val read = Connection(url, body, beforeRead = { CookieHandler.setDefault(handler) })
            failure(AbemaChannelFailure.COOKIE_HANDLER_REFUSED) { AbemaChannelHttpTransport(open = { read }).fetch() }
            assertEquals(1, read.disconnects)
            assertSame(handler, CookieHandler.getDefault())
        } finally { CookieHandler.setDefault(previous) }
    }

    @Test fun connectionAndStreamErrorsExposeOnlySafeCategoriesToDiagnostics() {
        val reports = mutableListOf<Exception>()
        failure(AbemaChannelFailure.NETWORK_FAILED) {
            AbemaChannelHttpTransport(open = { throw IOException("PRIVATE_SENTINEL") }, onFailure = { reports.add(it) }).fetch()
        }
        val read = Connection(url, body, beforeRead = { throw IOException("PRIVATE_SENTINEL") })
        failure(AbemaChannelFailure.NETWORK_FAILED) {
            AbemaChannelHttpTransport(open = { read }, onFailure = { reports.add(it); error("PRIVATE_SENTINEL") }).fetch()
        }
        val closing = Connection(url, body, beforeStreamClose = { throw IOException("PRIVATE_SENTINEL") })
        failure(AbemaChannelFailure.NETWORK_FAILED) { AbemaChannelHttpTransport(open = { closing }).fetch() }
        assertEquals(2, reports.size)
        reports.forEach {
            assertTrue(it is AbemaChannelException)
            assertNull(it.cause)
            assertEquals(0, it.suppressed.size)
            assertFalse(it.toString().contains("PRIVATE_SENTINEL"))
        }
        assertEquals(1, read.disconnects)
        assertEquals(1, closing.disconnects)
    }

    @Test fun streamCleanupCannotAttachPrivateSuppressedErrorsToCancellation() {
        var allowed = true
        val connection = Connection(url, body, beforeRead = { allowed = false },
            beforeStreamClose = { throw IOException("PRIVATE_SENTINEL") })
        failure(AbemaChannelFailure.CANCELLED) {
            AbemaChannelHttpTransport(open = { connection }, canRequest = { allowed }).fetch()
        }
        assertEquals(1, connection.disconnects)
    }

    @Test fun disconnectFailurePoisonsTheTransportWithoutRetryingCleanupOrReturningMetadata() {
        val connection = Connection(url, body, onDisconnect = { throw IOException("PRIVATE_SENTINEL") })
        val reports = mutableListOf<Exception>()
        var opens = 0
        val http = AbemaChannelHttpTransport(open = { opens++; connection }, onFailure = { reports.add(it) })
        failure(AbemaChannelFailure.CLEANUP_FAILED) { http.fetch() }
        failure(AbemaChannelFailure.CLEANUP_FAILED) { http.fetch() }
        assertEquals(1, opens)
        assertEquals(1, connection.disconnects)
        assertTrue(reports.all { (it as AbemaChannelException).category == AbemaChannelFailure.CLEANUP_FAILED })
        assertTrue(reports.all { it.cause == null && !it.toString().contains("PRIVATE_SENTINEL") })
        http.close()
        assertEquals(1, connection.disconnects)
    }

    @Test fun concurrentFetchCannotStealTheActiveReservation() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newSingleThreadExecutor()
        var opens = 0
        val connection = Connection(url, body, beforeResponse = {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
        })
        val http = AbemaChannelHttpTransport(open = { opens++; connection })
        try {
            val work = workers.submit<AbemaChannelResponse> { http.fetch() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            failure(AbemaChannelFailure.POLICY_REFUSED) { http.fetch() }
            release.countDown()
            assertEquals("sumo", work.get(5, TimeUnit.SECONDS).entries.single().resource.identity)
            assertEquals(1, opens)
            assertEquals(1, connection.disconnects)
        } finally { release.countDown(); http.close(); workers.shutdownNow() }
    }

    @Test fun overlappingCloseWaitsForOneDisconnectAndRejectsItsLateStatus() = overlappingClose(false)

    @Test fun interruptedRequestStillJoinsConcurrentCleanupAndKeepsItsInterrupt() = overlappingClose(true)

    @Test fun failedConcurrentDisconnectPoisonsBothCallersWithoutASecondDisconnect() = overlappingClose(false, true)

    private fun overlappingClose(interruptCleanup: Boolean, cleanupFails: Boolean = false) {
        val entered = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val disconnectEntered = CountDownLatch(1)
        val releaseDisconnect = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val interruptedAtExit = AtomicBoolean(false)
        val workers = Executors.newFixedThreadPool(2)
        val connection = Connection(url, body, beforeResponse = {
            entered.countDown(); check(releaseResponse.await(5, TimeUnit.SECONDS))
        }, onDisconnect = {
            releaseResponse.countDown(); disconnectEntered.countDown()
            check(releaseDisconnect.await(5, TimeUnit.SECONDS))
            if (cleanupFails) throw IOException("PRIVATE_SENTINEL")
        })
        val http = AbemaChannelHttpTransport(open = { connection }, onFailure = {
            if ((it as AbemaChannelException).category == AbemaChannelFailure.CANCELLED) {
                if (interruptCleanup) Thread.currentThread().interrupt()
                cancelled.countDown()
            }
        })
        try {
            val work = workers.submit<AbemaChannelResponse> {
                try { http.fetch() } finally { interruptedAtExit.set(Thread.currentThread().isInterrupted) }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val closing = workers.submit { http.close() }
            assertTrue(disconnectEntered.await(5, TimeUnit.SECONDS))
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { work.get(100, TimeUnit.MILLISECONDS) }
            releaseDisconnect.countDown()
            if (cleanupFails) {
                val closeError = assertThrows(ExecutionException::class.java) { closing.get(5, TimeUnit.SECONDS) }
                assertEquals(AbemaChannelFailure.CLEANUP_FAILED, (closeError.cause as AbemaChannelException).category)
            } else closing.get(5, TimeUnit.SECONDS)
            val error = assertThrows(ExecutionException::class.java) { work.get(5, TimeUnit.SECONDS) }
            assertEquals(if (cleanupFails) AbemaChannelFailure.CLEANUP_FAILED else AbemaChannelFailure.CANCELLED,
                (error.cause as AbemaChannelException).category)
            assertEquals(interruptCleanup, interruptedAtExit.get())
            assertFalse(connection.bodyRead)
            assertEquals(1, connection.disconnects)
            if (cleanupFails) failure(AbemaChannelFailure.CLEANUP_FAILED) { http.fetch() }
        } finally { releaseResponse.countDown(); releaseDisconnect.countDown(); http.close(); workers.shutdownNow() }
    }

    @Test fun aConcurrentDisconnectThatNeverConfirmsWithinTheBoundPoisonsFreshAdmission() {
        val entered = CountDownLatch(1)
        val releaseResponse = CountDownLatch(1)
        val releaseDisconnect = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        val connection = Connection(url, body, beforeResponse = {
            entered.countDown(); check(releaseResponse.await(5, TimeUnit.SECONDS))
        }, onDisconnect = {
            releaseResponse.countDown(); check(releaseDisconnect.await(15, TimeUnit.SECONDS))
        })
        val http = AbemaChannelHttpTransport(open = { connection }, onFailure = {
            if ((it as AbemaChannelException).category == AbemaChannelFailure.CANCELLED) cancelled.countDown()
        })
        try {
            val work = workers.submit<AbemaChannelResponse> { http.fetch() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val closing = workers.submit { http.close() }
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            val error = assertThrows(ExecutionException::class.java) { work.get(10, TimeUnit.SECONDS) }
            assertEquals(AbemaChannelFailure.CLEANUP_FAILED, (error.cause as AbemaChannelException).category)
            failure(AbemaChannelFailure.CLEANUP_FAILED) { http.fetch() }
            assertFalse(connection.bodyRead)
            assertEquals(1, connection.disconnects)
            releaseDisconnect.countDown(); closing.get(5, TimeUnit.SECONDS)
        } finally { releaseResponse.countDown(); releaseDisconnect.countDown(); http.close(); workers.shutdownNow() }
    }

    @Test(timeout = 5_000) fun sameThreadReentrantCancellationCannotDeadlockOrDisconnectTwice() {
        lateinit var http: AbemaChannelHttpTransport
        val connection = Connection(url, body, onDisconnect = { http.cancelActiveRequest() })
        http = AbemaChannelHttpTransport(open = { connection })
        failure(AbemaChannelFailure.CLEANUP_FAILED) { http.fetch() }
        failure(AbemaChannelFailure.CLEANUP_FAILED) { http.fetch() }
        assertEquals(1, connection.disconnects)
        http.close()
    }

    @Test fun closeSignalsAnActiveRequestAndRejectsItsLateStatus() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val workers = Executors.newSingleThreadExecutor()
        val connection = Connection(url, body, beforeResponse = {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
        }, onDisconnect = { release.countDown() })
        val http = AbemaChannelHttpTransport(open = { connection })
        try {
            val work = workers.submit<AbemaChannelResponse> { http.fetch() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            http.close()
            val error = assertThrows(ExecutionException::class.java) { work.get(5, TimeUnit.SECONDS) }
            assertEquals(AbemaChannelFailure.CANCELLED, (error.cause as AbemaChannelException).category)
            assertFalse(connection.bodyRead)
            assertEquals(1, connection.disconnects)
        } finally { release.countDown(); http.close(); workers.shutdownNow() }
    }
}
