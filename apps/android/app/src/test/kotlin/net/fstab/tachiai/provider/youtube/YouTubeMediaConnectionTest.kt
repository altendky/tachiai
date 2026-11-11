package net.fstab.tachiai.provider.youtube

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.*
import org.junit.Test

class YouTubeMediaConnectionTest {
    private class Fake(url: URL, val status: Int = 206, val location: String? = null) : HttpsURLConnection(url) {
        val sent = linkedMapOf<String, String>()
        var bodyOpened = false
        var disconnects = 0
        override fun connect() {}
        override fun disconnect() { disconnects++ }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getResponseMessage() = "fixture"
        override fun getHeaderField(name: String?) = if (name.equals("Location", true)) location else null
        override fun getHeaderFields(): Map<String, List<String>> = location?.let { mapOf("Location" to listOf(it)) } ?: emptyMap()
        override fun getContentLengthLong() = 3L
        override fun getContentLength() = 3
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf(1, 2, 3)).also { bodyOpened = true }
        override fun getErrorStream() = null
        override fun setRequestProperty(key: String, value: String) { sent[key] = value }
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }

    private val initial = URL("https://rr1.googlevideo.com/videoplayback")

    @Test fun verifiedRedirectPreservesOnlyRangeAndEncodingWithoutReadingRedirectBody() {
        val first = Fake(initial, 302, "https://rr2.googlevideo.com/videoplayback")
        val last = Fake(URL(first.location), 206)
        val opened = mutableListOf<Fake>()
        val connection = YouTubeMediaConnection(initial, { true }) { url ->
            (if (url.host == "rr1.googlevideo.com") first else last).also { opened.add(it) }
        }
        assertTrue("Construction must not open a connection", opened.isEmpty())
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 5000
        connection.readTimeout = 10000
        connection.setRequestProperty("Range", "bytes=100-199")
        connection.setRequestProperty("Accept-Encoding", "identity")
        assertEquals(206, connection.responseCode)
        assertEquals(2, opened.size)
        assertFalse(first.bodyOpened)
        assertFalse(first.instanceFollowRedirects)
        assertFalse(last.instanceFollowRedirects)
        for (request in opened) assertEquals(mapOf("Range" to "bytes=100-199", "Accept-Encoding" to "identity"), request.sent)
        assertEquals(5000, last.connectTimeout)
        assertEquals(10000, last.readTimeout)
        assertEquals(3L, connection.contentLengthLong)
        assertEquals(1, connection.inputStream.read())
        connection.disconnect()
        assertEquals(1, first.disconnects)
        assertEquals(1, last.disconnects)
    }

    @Test fun redirectDestinationsAreRejectedBeforeAnotherConnectionIsAllocated() {
        for (destination in listOf(
            "http://rr2.googlevideo.com/video", "https://googlevideo.com.evil.example/video",
            "https://evilgooglevideo.com/video", "https://user@rr2.googlevideo.com/video",
            "https://rr2.googlevideo.com:444/video", "https://rr2.googlevideo.com/video#fragment",
        )) {
            var opened = 0
            val first = Fake(initial, 302, destination)
            val connection = YouTubeMediaConnection(initial, { true }) { opened++; first }
            expectIo { connection.responseCode }
            assertEquals(1, opened)
            assertFalse(first.bodyOpened)
            connection.disconnect()
            assertEquals(1, first.disconnects)
        }
    }

    @Test fun redirectLoopStopsAfterFiveHopsAndClosesEveryConnection() {
        val opened = mutableListOf<Fake>()
        val connection = YouTubeMediaConnection(initial, { true }) { url ->
            Fake(url, 307, initial.toString()).also { opened.add(it) }
        }
        expectIo { connection.responseCode }
        assertEquals(6, opened.size)
        connection.disconnect()
        assertTrue(opened.all { it.disconnects == 1 && !it.bodyOpened })
    }

    @Test fun credentialsAndMethodsCannotReachTheTransport() {
        var opened = 0
        val connection = YouTubeMediaConnection(initial, { true }) { opened++; Fake(it) }
        for ((name, value) in listOf("Authorization" to "Bearer fixture", "Cookie" to "fixture=1", "Range" to "bytes=0-\r\nCookie:fixture=1")) {
            try { connection.setRequestProperty(name, value); fail("Unsafe header accepted") }
            catch (_: IllegalArgumentException) { }
        }
        connection.requestMethod = "POST"
        expectIo { connection.responseCode }
        assertEquals(0, opened)
        connection.disconnect()
    }

    @Test fun endedOwnershipRejectsBeforeOpening() {
        var opened = 0
        val connection = YouTubeMediaConnection(initial, { false }) { opened++; Fake(it) }
        expectIo { connection.responseCode }
        assertEquals(0, opened)
        connection.disconnect()
    }

    @Test fun disconnectOwnsAConnectionThatFinishesAllocationAfterCancellation() {
        val entered = CountDownLatch(1)
        val finishAllocation = CountDownLatch(1)
        val disconnected = AtomicBoolean(false)
        val workerFailure = AtomicReference<Throwable?>()
        val fake = Fake(initial)
        val connection = YouTubeMediaConnection(initial, { true }) {
            entered.countDown()
            check(finishAllocation.await(3, TimeUnit.SECONDS))
            fake
        }
        val resolver = Thread {
            try { expectIo { connection.responseCode } } catch (error: Throwable) { workerFailure.set(error) }
        }.apply { start() }
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        val closer = Thread {
            try { connection.disconnect(); disconnected.set(true) } catch (error: Throwable) { workerFailure.set(error) }
        }.apply { start() }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (closer.state != Thread.State.WAITING && closer.isAlive && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals("Disconnect must wait for its reserved allocation", Thread.State.WAITING, closer.state)
            assertFalse("Allocation must remain owned until it returns", disconnected.get())
        } finally { finishAllocation.countDown() }
        resolver.join(3000)
        closer.join(3000)
        assertFalse(resolver.isAlive)
        assertFalse(closer.isAlive)
        workerFailure.get()?.let { throw it }
        assertTrue(disconnected.get())
        assertEquals(1, fake.disconnects)
        assertFalse(fake.bodyOpened)
    }

    private fun expectIo(action: () -> Unit) {
        try { action(); fail("Expected bounded transport refusal") } catch (_: IOException) { }
    }
}
