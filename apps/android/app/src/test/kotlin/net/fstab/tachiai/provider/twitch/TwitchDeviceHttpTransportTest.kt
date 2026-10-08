package net.fstab.tachiai.provider.twitch

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assert.assertSame
import org.junit.Test

class TwitchDeviceHttpTransportTest {
    private class Connection(
        url: URL,
        val status: Int = 200,
        val body: ByteArray = "{}".toByteArray(),
        val length: Long = -1,
        val onResponse: () -> Unit = {},
        val onDisconnect: () -> Unit = {},
        val writeError: IOException? = null,
        val readError: IOException? = null,
    ) : HttpsURLConnection(url) {
        val written = ByteArrayOutputStream()
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true; onDisconnect() }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode(): Int { onResponse(); return status }
        override fun getContentLengthLong() = length
        override fun getInputStream(): InputStream {
            readError?.let { throw it }
            return ByteArrayInputStream(body)
        }
        override fun getErrorStream(): InputStream = inputStream
        override fun getOutputStream(): ByteArrayOutputStream {
            writeError?.let { throw it }
            return written
        }
    }

    @Test fun `fixed endpoints forms and OAuth header use no secret or browser cookie`() {
        val connections = mutableListOf<Connection>()
        val events = mutableListOf<String>()
        val transport = TwitchDeviceHttpTransport(
            open = { Connection(it).also(connections::add) }, decode = { emptyMap() },
            onHttpStatus = { endpoint, status -> events += "${endpoint.name}:$status" },
        )
        transport.device(TACHIAI_TWITCH_CLIENT_ID)
        transport.poll(TACHIAI_TWITCH_CLIENT_ID, "device+secret&value")
        transport.validate("access-secret")
        assertEquals(listOf("/oauth2/device", "/oauth2/token", "/oauth2/validate"), connections.map { it.url.path })
        connections.forEach {
            assertEquals("https", it.url.protocol)
            assertEquals("id.twitch.tv", it.url.host)
            assertFalse(it.instanceFollowRedirects)
            assertFalse(it.useCaches)
            assertTrue(it.connectTimeout > 0)
            assertTrue(it.readTimeout > 0)
            assertTrue(it.disconnected)
            assertEquals(null, it.getRequestProperty("Cookie"))
            assertFalse(it.written.toString("UTF-8").contains("client_secret"))
        }
        assertTrue(connections[0].written.toString("UTF-8").endsWith("&scopes="))
        assertTrue(connections[1].written.toString("UTF-8").contains("device_code=device%2Bsecret%26value"))
        assertTrue(connections[1].written.toString("UTF-8").contains("scopes="))
        assertEquals("GET", connections[2].requestMethod)
        assertEquals("OAuth access-secret", connections[2].getRequestProperty("Authorization"))
        assertEquals(listOf("DEVICE:200", "TOKEN:200", "VALIDATE:200"), events)
    }

    @Test fun `both advertised and streamed oversized bodies are rejected and disconnected`() {
        listOf(-1L, (DEVICE_RESPONSE_LIMIT + 1).toLong()).forEach { length ->
            val connection = Connection(URL("https://id.twitch.tv/oauth2/device"),
                body = ByteArray(DEVICE_RESPONSE_LIMIT + 1), length = length)
            var decoded = false
            val transport = TwitchDeviceHttpTransport(open = { connection }, decode = { decoded = true; emptyMap() })
            assertThrows(InvalidDeviceResponse::class.java) { transport.device(TACHIAI_TWITCH_CLIENT_ID) }
            assertFalse(decoded)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun `redirect response is never followed or decoded`() {
        val connection = Connection(URL("https://id.twitch.tv/oauth2/device"), status = 302)
        var decoded = false
        val transport = TwitchDeviceHttpTransport(open = { connection }, decode = { decoded = true; emptyMap() })
        assertEquals(302, transport.device(TACHIAI_TWITCH_CLIENT_ID).status)
        assertFalse(decoded)
        assertTrue(connection.disconnected)
    }

    @Test fun `HTTP 400 pending body is decoded and repeated status logs are deduplicated`() {
        val events = mutableListOf<String>()
        val transport = TwitchDeviceHttpTransport(
            open = { Connection(it, status = 400, body = "pending".toByteArray()) },
            decode = { assertEquals("pending", it); mapOf("message" to "authorization_pending") },
            onHttpStatus = { endpoint, status -> events += "${endpoint.name}:$status" },
        )
        repeat(3) { assertEquals("authorization_pending", transport.poll(TACHIAI_TWITCH_CLIENT_ID, "code").fields["message"]) }
        assertEquals(listOf("TOKEN:400"), events)
    }

    @Test fun `close is idempotent and prevents any future connection`() {
        var opened = false
        val transport = TwitchDeviceHttpTransport(open = { opened = true; Connection(it) }, decode = { emptyMap() })
        transport.close()
        transport.close()
        assertThrows(IllegalStateException::class.java) { transport.device(TACHIAI_TWITCH_CLIENT_ID) }
        assertFalse(opened)
    }

    @Test fun `initial device rejection publishes only a closed category not polling responses`() {
        val categories = mutableListOf<DeviceRejection>()
        val transport = TwitchDeviceHttpTransport(
            open = { Connection(it, status = 400) },
            decode = { mapOf("message" to "invalid client id", "private-field" to "never expose") },
            onDeviceRejection = categories::add,
        )
        transport.device(PROVIDER_TWITCH_CLIENT_ID)
        transport.poll(PROVIDER_TWITCH_CLIENT_ID, "synthetic-device")
        assertEquals(listOf(DeviceRejection.CLIENT_REJECTION_REPORTED), categories)
        assertFalse(categories.toString().contains("never expose"))
        transport.close()
    }

    @Test fun `malformed token cannot become an HTTP header`() {
        val connection = Connection(URL("https://id.twitch.tv/oauth2/validate"))
        val transport = TwitchDeviceHttpTransport(open = { connection }, decode = { emptyMap() })
        assertThrows(IllegalArgumentException::class.java) { transport.validate("secret\r\nCookie: value") }
        assertEquals(null, connection.getRequestProperty("Authorization"))
        assertTrue(connection.disconnected)
    }

    @Test fun `close disconnects an active blocked request and suppresses decoding`() {
        val arrived = CountDownLatch(1)
        val released = CountDownLatch(1)
        val connection = Connection(URL("https://id.twitch.tv/oauth2/device"),
            onResponse = { arrived.countDown(); check(released.await(5, TimeUnit.SECONDS)) },
            onDisconnect = { released.countDown() })
        var decoded = false
        val failures = mutableListOf<DeviceHttpFailure>()
        val transport = TwitchDeviceHttpTransport(open = { connection }, decode = { decoded = true; emptyMap() },
            onFailure = failures::add)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<DeviceAuthResponse> { transport.device(TACHIAI_TWITCH_CLIENT_ID) }
            assertTrue(arrived.await(5, TimeUnit.SECONDS))
            transport.close()
            val failure = assertThrows(ExecutionException::class.java) { result.get(5, TimeUnit.SECONDS) }
            assertTrue(failure.cause is InterruptedIOException)
            assertFalse(decoded)
            assertTrue(connection.disconnected)
            assertTrue(failures.isEmpty())
        } finally {
            transport.close()
            executor.shutdownNow()
        }
    }

    @Test fun `close during connection factory cannot leave an uncancelled connection`() {
        val connection = Connection(URL("https://id.twitch.tv/oauth2/device"))
        lateinit var transport: TwitchDeviceHttpTransport
        transport = TwitchDeviceHttpTransport(open = { transport.close(); connection }, decode = { emptyMap() })
        assertThrows(InterruptedIOException::class.java) { transport.device(TACHIAI_TWITCH_CLIENT_ID) }
        assertTrue(connection.disconnected)
    }

    @Test fun `total response budget rejects a slow exchange despite successful status`() {
        var now = 0L
        val connection = Connection(URL("https://id.twitch.tv/oauth2/device"), onResponse = { now = 30_001 })
        var decoded = false
        val failures = mutableListOf<DeviceHttpFailure>()
        val transport = TwitchDeviceHttpTransport(open = { connection }, decode = { decoded = true; emptyMap() },
            clockMs = { now }, onFailure = failures::add)
        assertThrows(InterruptedIOException::class.java) { transport.device(TACHIAI_TWITCH_CLIENT_ID) }
        assertFalse(decoded)
        assertTrue(connection.disconnected)
        assertEquals(listOf(DeviceHttpFailure(DeviceAuthEndpoint.DEVICE, DeviceRequestStage.STATUS,
            DeviceNetworkFailure.BUDGET, 30_001)), failures)
    }

    @Test fun `network classifications use type only and never exception text`() {
        val cases = mapOf(
            UnknownHostException("private diagnostic text") to DeviceNetworkFailure.DNS,
            SocketTimeoutException("private diagnostic text") to DeviceNetworkFailure.TIMEOUT,
            SSLHandshakeException("private diagnostic text") to DeviceNetworkFailure.TLS,
            ConnectException("private diagnostic text") to DeviceNetworkFailure.CONNECTION,
            InterruptedIOException("private diagnostic text") to DeviceNetworkFailure.INTERRUPTED,
            IOException("private diagnostic text") to DeviceNetworkFailure.IO,
        )
        cases.forEach { (error, category) -> assertEquals(category, deviceNetworkFailure(error)) }
    }

    @Test fun `opening failure is diagnosed without retaining the original exception`() {
        val error = UnknownHostException("private diagnostic text")
        val failures = mutableListOf<DeviceHttpFailure>()
        val transport = TwitchDeviceHttpTransport(open = { throw error }, clockMs = { 1000 },
            onFailure = failures::add)
        assertSame(error, assertThrows(UnknownHostException::class.java) { transport.device(TACHIAI_TWITCH_CLIENT_ID) })
        assertEquals(listOf(DeviceHttpFailure(DeviceAuthEndpoint.DEVICE, DeviceRequestStage.OPEN,
            DeviceNetworkFailure.DNS, 0)), failures)
        assertFalse(failures.toString().contains("private diagnostic text"))
    }

    @Test fun `send status and response failures retain fixed stage and bounded elapsed time`() {
        listOf(DeviceRequestStage.WRITE, DeviceRequestStage.STATUS, DeviceRequestStage.READ).forEach { stage ->
            val error = SocketTimeoutException("private diagnostic text")
            val failures = mutableListOf<DeviceHttpFailure>()
            var now = 1000L
            val connection = Connection(URL("https://id.twitch.tv/oauth2/token"),
                writeError = if (stage == DeviceRequestStage.WRITE) error else null,
                onResponse = { now = 1200; if (stage == DeviceRequestStage.STATUS) throw error },
                readError = if (stage == DeviceRequestStage.READ) error else null)
            val transport = TwitchDeviceHttpTransport(open = { connection }, decode = { emptyMap() },
                clockMs = { now }, onFailure = failures::add)
            assertSame(error, assertThrows(SocketTimeoutException::class.java) {
                transport.poll(TACHIAI_TWITCH_CLIENT_ID, "fixture-code")
            })
            assertEquals(listOf(DeviceHttpFailure(DeviceAuthEndpoint.TOKEN, stage,
                DeviceNetworkFailure.TIMEOUT, if (stage == DeviceRequestStage.WRITE) 0 else 200)), failures)
            assertTrue(connection.disconnected)
            assertFalse(failures.toString().contains("private diagnostic text"))
        }
    }

    @Test fun `background gate prevents token send and validation connection before I O`() {
        listOf(false, true).forEach { validate ->
            var statuses = 0
            val connection = Connection(URL("https://id.twitch.tv/oauth2/token"), onResponse = { statuses++ })
            val failures = mutableListOf<DeviceHttpFailure>()
            val transport = TwitchDeviceHttpTransport(open = { connection }, decode = { emptyMap() },
                clockMs = { 0 }, onFailure = failures::add, canRequest = { false })
            assertThrows(DeviceRequestPaused::class.java) {
                if (validate) transport.validate("fixture-token") else transport.poll(TACHIAI_TWITCH_CLIENT_ID, "fixture-code")
            }
            assertEquals(0, statuses)
            assertEquals(0, connection.written.size())
            assertEquals(DeviceNetworkFailure.BACKGROUND, failures.single().category)
            assertEquals(if (validate) DeviceRequestStage.STATUS else DeviceRequestStage.WRITE, failures.single().stage)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun `sent token request is not aborted when background begins during response`() {
        var foreground = true
        val connection = Connection(URL("https://id.twitch.tv/oauth2/token"), onResponse = { foreground = false })
        val transport = TwitchDeviceHttpTransport(open = { connection }, decode = { mapOf("fixture" to true) },
            canRequest = { foreground })
        assertEquals(true, transport.poll(TACHIAI_TWITCH_CLIENT_ID, "fixture-code").fields["fixture"])
        assertTrue(connection.written.size() > 0)
        assertTrue(connection.disconnected)
    }
}
