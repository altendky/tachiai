package net.fstab.tachiai.provider.abema

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.URL
import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.*
import org.junit.Test

class AbemaPublicBundleHttpTest {
    private val bytes = "synthetic public module registration".toByteArray()
    private fun identity() = AbemaPublicBundleIdentity(
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, bytes.size)
    private class Connection(url: URL, val body: ByteArray, val status: Int = 200,
        val length: Long = -1, val mime: String? = "application/javascript; charset=utf-8",
        val beforeResponse: () -> Unit = {}, val beforeRead: () -> Unit = {},
        val closeStream: () -> Unit = {}, val disconnectAction: () -> Unit = {}) : HttpsURLConnection(url) {
        var disconnected = false
        var bodyRead = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true; disconnectAction() }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode(): Int { beforeResponse(); return status }
        override fun getContentLengthLong() = length
        override fun getContentType() = mime
        override fun getInputStream(): InputStream {
            bodyRead = true
            return object : ByteArrayInputStream(body) {
                override fun close() { closeStream() }
                override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                    beforeRead()
                    return super.read(buffer, offset, count)
                }
            }
        }
    }

    private fun failure(category: AbemaBundleFailure, operation: () -> Unit) {
        val error = assertThrows(AbemaBundleException::class.java, operation)
        assertEquals(category, error.category)
        assertNull(error.message)
        assertNull(error.cause)
        assertEquals(0, error.stackTrace.size)
    }

    private fun assertOwned(http: AbemaPublicBundleHttp, connection: HttpsURLConnection) {
        assertSame(connection, AbemaPublicBundleHttp::class.java.getDeclaredField("active").apply { isAccessible = true }.get(http))
        assertFalse(http.cleanupConfirmed)
    }

    @Test fun `download uses exact GET verified pin and no credential headers`() {
        val connection = Connection(identity().uri.toURL(), bytes, length = bytes.size.toLong())
        AbemaPublicBundleHttp(identity = identity(), open = {
            assertEquals(identity().uri.toURL(), it); connection
        }).use { assertArrayEquals(bytes, it.fetch()) }
        assertEquals("GET", connection.requestMethod)
        assertEquals("application/javascript, text/javascript", connection.getRequestProperty("Accept"))
        assertNull(connection.getRequestProperty("Authorization"))
        assertNull(connection.getRequestProperty("Cookie"))
        assertNull(connection.getRequestProperty("Client-ID"))
        assertFalse(connection.doOutput)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertEquals(10_000, connection.connectTimeout)
        assertEquals(10_000, connection.readTimeout)
        assertTrue(connection.disconnected)
    }

    @Test fun `redirect and HTTP failures never read responses`() {
        listOf(301, 302, 304, 401, 403, 404, 500).forEach { status ->
            val connection = Connection(identity().uri.toURL(), bytes, status = status)
            failure(AbemaBundleFailure.HTTP_REFUSED) {
                AbemaPublicBundleHttp(identity = identity(), open = { connection }).use { it.fetch() }
            }
            assertFalse(connection.bodyRead)
            assertFalse(connection.instanceFollowRedirects)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun `unexpected content types are rejected before reading`() {
        listOf(null, "text/html", "application/json", "text/plain").forEach { mime ->
            val connection = Connection(identity().uri.toURL(), bytes, mime = mime)
            failure(AbemaBundleFailure.ASSET_TYPE_REFUSED) {
                AbemaPublicBundleHttp(identity = identity(), open = { connection }).use { it.fetch() }
            }
            assertFalse(connection.bodyRead)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun `announced and streamed size plus hash mismatches refuse`() {
        listOf(0L, bytes.size.toLong() + 1, identity().maximumBytes.toLong() + 1).forEach { length ->
            val connection = Connection(identity().uri.toURL(), bytes, length = length)
            failure(AbemaBundleFailure.SIZE_REFUSED) {
                AbemaPublicBundleHttp(identity = identity(), open = { connection }).use { it.fetch() }
            }
            assertFalse(connection.bodyRead)
        }
        listOf(ByteArray(bytes.size - 1), ByteArray(identity().maximumBytes + 1)).forEach { body ->
            val connection = Connection(identity().uri.toURL(), body)
            failure(AbemaBundleFailure.SIZE_REFUSED) {
                AbemaPublicBundleHttp(identity = identity(), open = { connection }).use { it.fetch() }
            }
            assertTrue(connection.disconnected)
        }
        val connection = Connection(identity().uri.toURL(), ByteArray(bytes.size))
        failure(AbemaBundleFailure.PIN_REFUSED) {
            AbemaPublicBundleHttp(identity = identity(), open = { connection }).use { it.fetch() }
        }
    }

    @Test fun `background close and interrupted states prevent dispatch`() {
        val background = AbemaPublicBundleHttp(canRun = { false }, open = { throw AssertionError("background dispatched") })
        failure(AbemaBundleFailure.CANCELLED) { background.fetch() }
        val closed = AbemaPublicBundleHttp(open = { throw AssertionError("closed dispatched") })
        closed.close()
        failure(AbemaBundleFailure.CANCELLED) { closed.fetch() }
        Thread.currentThread().interrupt()
        try {
            failure(AbemaBundleFailure.CANCELLED) {
                AbemaPublicBundleHttp(open = { throw AssertionError("interrupted dispatched") }).fetch()
            }
        } finally { Thread.interrupted() }
    }

    @Test fun `closing active request prevents return and disconnects`() {
        lateinit var http: AbemaPublicBundleHttp
        val connection = Connection(identity().uri.toURL(), bytes, beforeResponse = { http.close() })
        http = AbemaPublicBundleHttp(identity = identity(), open = { connection })
        failure(AbemaBundleFailure.CANCELLED) { http.fetch() }
        assertTrue(connection.disconnected)
        assertFalse(connection.bodyRead)
    }

    @Test fun `cancellation while streaming and time budget reject late bytes`() {
        var allowed = true
        val connection = Connection(identity().uri.toURL(), bytes, beforeRead = { allowed = false })
        failure(AbemaBundleFailure.CANCELLED) {
            AbemaPublicBundleHttp(canRun = { allowed }, identity = identity(), open = { connection }).use { it.fetch() }
        }
        var now = 0L
        val expired = Connection(identity().uri.toURL(), bytes, beforeResponse = { now = 30_000 })
        failure(AbemaBundleFailure.TIME_LIMIT_REACHED) {
            AbemaPublicBundleHttp(identity = identity(), open = { expired }, clockMs = { now }).use { it.fetch() }
        }
        assertTrue(expired.disconnected)
        assertFalse(expired.bodyRead)
    }

    @Test fun `process CookieHandler is refused and never changed`() {
        val previous = CookieHandler.getDefault()
        val handler = CookieManager()
        try {
            CookieHandler.setDefault(handler)
            failure(AbemaBundleFailure.COOKIE_HANDLER_REFUSED) {
                AbemaPublicBundleHttp(open = { throw AssertionError("cookie transport dispatched") }).fetch()
            }
            assertSame(handler, CookieHandler.getDefault())
        } finally { CookieHandler.setDefault(previous) }
    }

    @Test fun `network failures have only closed category and no details`() {
        failure(AbemaBundleFailure.NETWORK_FAILED) {
            AbemaPublicBundleHttp(open = { throw IOException("PRIVATE_SENTINEL") }).fetch()
        }
    }

    @Test fun `connection to another URL is closed before dispatch`() {
        val connection = Connection(URL("https://example.test/fixture"), bytes)
        failure(AbemaBundleFailure.POLICY_REFUSED) {
            AbemaPublicBundleHttp(identity = identity(), open = { connection }).use { it.fetch() }
        }
        assertTrue(connection.disconnected)
        assertFalse(connection.bodyRead)
    }

    @Test fun streamCloseErrorNotifiesOwnerAttemptsDisconnectAndRetainsFirstFailure() {
        var streamCloses = 0
        val error = AssertionError("fixture")
        val connection = Connection(identity().uri.toURL(), bytes, closeStream = {
            streamCloses++; if (streamCloses == 1) throw error
        })
        val receipt = net.fstab.tachiai.platform.media.NativeCleanupReceipt()
        val seen = mutableListOf<Throwable>()
        val http = AbemaPublicBundleHttp(identity = identity(),
            open = { receipt.allocate(10, { connection }) { it.disconnect() } },
            openInput = { receipt.input { it.inputStream } },
            onFailure = { receipt.failed(); seen.add(it) })
        assertSame(error, assertThrows(AssertionError::class.java) { http.fetch() })
        assertTrue(connection.disconnected); assertSame(error, seen.single())
        assertOwned(http, connection)
        assertNotNull(AbemaPublicBundleHttp::class.java.getDeclaredField("activeInput").apply { isAccessible = true }.get(http))
        failure(AbemaBundleFailure.NETWORK_FAILED) { http.close() }
        assertOwned(http, connection)
        assertThrows(IOException::class.java) { receipt.close() }
        assertFalse(receipt.confirmed); assertEquals(1, streamCloses)
    }
    @Test fun failedDisconnectRetainsActualHandleAndCannotBecomeConfirmedOnRepeatedClose() {
        var disconnects = 0; val error = AssertionError("fixture")
        val connection = Connection(identity().uri.toURL(), bytes, status = 503,
            disconnectAction = { disconnects++; if (disconnects == 1) throw error })
        val receipt = net.fstab.tachiai.platform.media.NativeCleanupReceipt()
        val seen = mutableListOf<Throwable>()
        val http = AbemaPublicBundleHttp(identity = identity(),
            open = { receipt.allocate(10, { connection }) { it.disconnect() } },
            onFailure = { receipt.failed(); seen.add(it) })
        failure(AbemaBundleFailure.NETWORK_FAILED) { http.fetch() }
        assertFalse(connection.bodyRead); assertSame(error, seen.single())
        assertOwned(http, connection)
        failure(AbemaBundleFailure.NETWORK_FAILED) { http.close() }
        failure(AbemaBundleFailure.NETWORK_FAILED) { http.close() }
        assertEquals(1, disconnects); assertOwned(http, connection); assertEquals(1, seen.size)
        assertThrows(IOException::class.java) { receipt.close() }
        assertEquals(2, disconnects); assertFalse(receipt.confirmed)
        assertOwned(http, connection)
    }

    @Test fun heldBodyCloseRetainsActualHttpAndInputUntilTheBodyReceiptCompletes() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val disconnects = AtomicInteger()
        val connection = Connection(identity().uri.toURL(), bytes, closeStream = {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
        }, disconnectAction = { disconnects.incrementAndGet() })
        val http = AbemaPublicBundleHttp(identity = identity(), open = { connection })
        val result = AtomicReference<Throwable?>()
        val worker = Thread { try { http.fetch() } catch (error: Throwable) { result.set(error) } }
        try {
            worker.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertOwned(http, connection)
            failure(AbemaBundleFailure.NETWORK_FAILED) { http.close() }
            assertOwned(http, connection); assertEquals(1, disconnects.get())
            assertNotNull(AbemaPublicBundleHttp::class.java.getDeclaredField("activeInput").apply { isAccessible = true }.get(http))
            failure(AbemaBundleFailure.NETWORK_FAILED) { http.close() }
            assertEquals(1, disconnects.get())
            release.countDown(); worker.join(5000); assertFalse(worker.isAlive)
            assertEquals(AbemaBundleFailure.CANCELLED, (result.get() as AbemaBundleException).category)
            http.close(); assertTrue(http.cleanupConfirmed); assertEquals(1, disconnects.get())
        } finally { release.countDown(); worker.join(5000) }
    }

    @Test fun heldDisconnectHasOneClaimAndConcurrentCloseCannotReleaseItsActualHttp() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val disconnects = AtomicInteger()
        val connection = Connection(identity().uri.toURL(), bytes, disconnectAction = {
            disconnects.incrementAndGet(); entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
        })
        val http = AbemaPublicBundleHttp(identity = identity(), open = { connection })
        val result = AtomicReference<Throwable?>()
        val worker = Thread { try { http.fetch() } catch (error: Throwable) { result.set(error) } }
        try {
            worker.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertOwned(http, connection)
            failure(AbemaBundleFailure.NETWORK_FAILED) { http.close() }
            failure(AbemaBundleFailure.NETWORK_FAILED) { http.close() }
            assertOwned(http, connection); assertEquals(1, disconnects.get())
            release.countDown(); worker.join(5000); assertFalse(worker.isAlive); assertNull(result.get())
            http.close(); assertTrue(http.cleanupConfirmed); assertEquals(1, disconnects.get())
        } finally { release.countDown(); worker.join(5000) }
    }

    @Test fun closeDuringOpenCannotConfirmPendingOwnershipAndLateHttpIsClosedOnce() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val disconnects = AtomicInteger()
        val connection = Connection(identity().uri.toURL(), bytes, disconnectAction = { disconnects.incrementAndGet() })
        val http = AbemaPublicBundleHttp(identity = identity(), open = {
            entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); connection
        })
        val result = AtomicReference<Throwable?>()
        val worker = Thread { try { http.fetch() } catch (error: Throwable) { result.set(error) } }
        try {
            worker.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
            http.close(); assertFalse(http.cleanupConfirmed); assertEquals(0, disconnects.get())
            release.countDown(); worker.join(5000); assertFalse(worker.isAlive)
            assertEquals(AbemaBundleFailure.CANCELLED, (result.get() as AbemaBundleException).category)
            http.close(); assertTrue(http.cleanupConfirmed); assertEquals(1, disconnects.get()); assertFalse(connection.bodyRead)
        } finally { release.countDown(); worker.join(5000) }
    }

}
