package net.fstab.tachiai.provider.twitch.catalog

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.provider.twitch.SMART_TV_TWITCH_CLIENT_ID
import org.junit.Assert.*
import org.junit.Test

class TwitchContinueWatchingHttpTransportTest {
    private class HostileError : Error() {
        var inspected = false
        override val message: String get() { inspected = true; throw AssertionError("Private error") }
        override fun toString(): String { inspected = true; throw AssertionError("Private error") }
    }
    private class Connection(url: URL = URL(TWITCH_CONTINUE_WATCHING_URL), val status: Int = 200,
        var bytes: ByteArray = """{"data":{"currentUser":{"id":"9000","viewedVideos":{"edges":[]}}}}""".toByteArray()) : HttpsURLConnection(url) {
        val sent = ByteArrayOutputStream()
        var opened = 0; var disconnected = 0; var inputCloses = 0
        var mediaType = "application/json; charset=UTF-8"
        var advertisedLength: Long? = null
        var responseHook: () -> Unit = {}; var readHook: () -> Unit = {}; var disconnectHook: () -> Unit = {}
        var inputCloseHook: () -> Unit = {}
        override fun getResponseCode(): Int { responseHook(); return status }
        override fun getContentType() = mediaType
        override fun getContentLengthLong() = advertisedLength ?: bytes.size.toLong()
        override fun getInputStream(): InputStream { opened++; return object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int): Int { readHook(); return super.read(b, off, len) }
            override fun close() { inputCloses++; inputCloseHook(); super.close() }
        } }
        override fun getErrorStream(): InputStream = error("Refusal body must stay closed")
        override fun getOutputStream() = sent
        override fun disconnect() { disconnected++; disconnectHook() }
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getPeerPrincipal(): Principal? = null
        override fun getLocalPrincipal(): Principal? = null
    }
    private fun transport(connection: Connection, gate: () -> Boolean = { true }, clock: () -> Long = { 1000L }, wait: Long = 50) =
        TwitchContinueWatchingHttpTransport({ connection }, gate, clock, wait)
    private fun failure(t: TwitchContinueWatchingTransport, category: TwitchContinueWatchingFailure) = assertEquals(category,
        assertThrows(TwitchContinueWatchingException::class.java) { t.fetch("grant-token", "9000", 2_000_000_000_000L) }.category)
    @Test fun fixedPostUsesOnlySmartTvMaintainedOAuthGrantAndCleansAllOwnersBeforeReturn() {
        val connection = Connection(); val t = transport(connection)
        assertTrue(t.fetch("grant-token", "9000", 2_000_000_000_000L).page!!.entries.isEmpty())
        assertEquals("POST", connection.requestMethod)
        assertEquals(SMART_TV_TWITCH_CLIENT_ID, connection.getRequestProperty("Client-ID"))
        assertEquals("OAuth grant-token", connection.getRequestProperty("Authorization"))
        assertNull(connection.getRequestProperty("Cookie")); assertNull(connection.getRequestProperty("Client-Integrity"))
        assertNull(connection.getRequestProperty("Device-ID")); assertFalse(connection.instanceFollowRedirects); assertFalse(connection.useCaches)
        assertArrayEquals(twitchContinueWatchingRequestBody, connection.sent.toByteArray())
        assertEquals(1, connection.inputCloses); assertEquals(1, connection.disconnected)
        assertTrue(t.cleanupConfirmed); t.close(); assertEquals(1, connection.disconnected)
    }
    @Test fun refusalRedirectAndServiceBodiesStayUnreadAndNoClientIdentityRetryOccurs() {
        for (status in listOf(401, 403, 302, 429, 500, 201)) {
            val c = Connection(status = status); val t = transport(c)
            assertEquals(status, t.fetch("grant-token", "9000", 0).status)
            assertEquals(0, c.opened); assertEquals(1, c.disconnected); assertTrue(t.cleanupConfirmed)
        }
    }
    @Test fun strictUtf8ContentTypeBodyBoundsDuplicateFieldsAndGraphqlErrorsFailClosed() {
        for (case in 0..5) {
            val c = Connection()
            when (case) {
                0 -> c.bytes = byteArrayOf(0xff.toByte())
                1 -> c.mediaType = "text/html"
                2 -> c.bytes = ByteArray(TWITCH_CONTINUE_WATCHING_BYTES + 1)
                3 -> c.bytes = """{"data":{},"data":{}}""".toByteArray()
                4 -> c.bytes = """{"data":{"currentUser":null},"errors":[{"message":"private"}]}""".toByteArray()
                5 -> c.advertisedLength = c.bytes.size + 1L
            }
            val category = assertThrows(TwitchContinueWatchingException::class.java) { transport(c).fetch("grant-token", "9000", 0) }.category
            assertTrue(category in setOf(TwitchContinueWatchingFailure.INVALID_RESPONSE, TwitchContinueWatchingFailure.RESPONSE_LIMIT,
                TwitchContinueWatchingFailure.NETWORK_FAILED))
            assertEquals(1, c.disconnected)
        }
        val c = Connection(bytes = ByteArray(TWITCH_CONTINUE_WATCHING_BYTES + 1)); c.advertisedLength = -1
        failure(transport(c), TwitchContinueWatchingFailure.RESPONSE_LIMIT); assertEquals(1, c.inputCloses)
    }
    @Test fun ownerLossHeldReadStatusCancellationCleanupLateOwnerAndMonotonicDeadlineSuppressResponses() {
        for (case in 0..3) {
            var allowed = true; var now = 1000L
            val c = Connection(); val t = transport(c, { allowed }, { now })
            when (case) {
                0 -> c.readHook = { allowed = false }
                1 -> c.responseHook = t::cancelActiveRequest
                2 -> c.disconnectHook = { allowed = false }
                3 -> c.responseHook = { now += 30_000 }
            }
            failure(t, TwitchContinueWatchingFailure.CANCELLED); assertEquals(1, c.disconnected)
        }
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val c = Connection(); val t = transport(c)
        c.readHook = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<TwitchContinueWatchingFailure> {
                try { t.fetch("grant-token", "9000", 0); error("Late result") } catch (e: TwitchContinueWatchingException) { e.category }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); t.close(); release.countDown()
            assertEquals(TwitchContinueWatchingFailure.CANCELLED, future.get(5, TimeUnit.SECONDS))
        } finally { release.countDown(); executor.shutdownNow() }
    }
    @Test fun hostileErrorsAreNeverInspectedAndUncertainActualHandleIsRetainedWithoutRetry() {
        val c = Connection(); val hostile = HostileError(); c.disconnectHook = { throw hostile }
        val t = transport(c); failure(t, TwitchContinueWatchingFailure.CLEANUP_FAILED)
        assertFalse(hostile.inspected); assertFalse(t.cleanupConfirmed)
        val handle = t.javaClass.getDeclaredField("active").apply { isAccessible = true }.get(t)!!
        val captured = handle.javaClass.getDeclaredField("connection").apply { isAccessible = true }.get(handle)
        assertSame(c, captured)
        c.disconnectHook = {}
        failure(t, TwitchContinueWatchingFailure.CLEANUP_FAILED); assertEquals(1, c.disconnected)
        t.close(); assertFalse(t.cleanupConfirmed)
    }
    @Test fun ownerCallbackFailureAfterCleanupIsClosedAndItsPrivateErrorIsNeverExposed() {
        val c = Connection(); val hostile = HostileError(); var throwOwner = false
        c.disconnectHook = { throwOwner = true }
        val t = transport(c, { if (throwOwner) throw hostile else true })
        failure(t, TwitchContinueWatchingFailure.CANCELLED)
        assertFalse(hostile.inspected); assertTrue(t.cleanupConfirmed); assertEquals(1, c.disconnected)
    }
    @Test fun wrongEndpointAndDeniedAdmissionCannotBeReplacedWithAnotherUrlOrBlankClient() {
        val c = Connection(URL("https://gql.twitch.tv/gql?other=1")); failure(transport(c), TwitchContinueWatchingFailure.POLICY_REFUSED)
        assertEquals(0, c.opened); assertEquals(1, c.disconnected)
        val denied = Connection(); failure(transport(denied, { false }), TwitchContinueWatchingFailure.CANCELLED)
        assertEquals(0, denied.disconnected); assertEquals(0, denied.sent.size())
    }
}
