package net.fstab.tachiai.platform.network

import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.SocketFactory
import javax.net.ssl.SSLSocket
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

// Real TCP CONNECT and TLS, but deliberately no provider endpoint, account,
// imported file or native tunnel. Native bridge/WireGuard have separate tests.
class RoutedHttpsConnectionTest {
    private val username = "fixture_local_user"
    private val password = "fixture_local_password"
    private val realm = "fixture_local_realm"
    private val url = URL("https://127.0.0.1/fixture?token=synthetic")

    private inner class ProxyFixture(private val refusal: Boolean = false, private val block: Boolean = false,
        private val redirect: Boolean = false, private val user: String = username,
        private val secret: String = password, private val authRealm: String = realm,
        private val slowBody: Boolean = false) : AutoCloseable {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        val accepted = CountDownLatch(1)
        val connectHeaders = AtomicReference<List<String>>()
        val originHeaders = AtomicReference<List<String>>()
        val body = AtomicReference<ByteArray>()
        val outbound = CopyOnWriteArrayList<InetSocketAddress>()
        val observeBodyRead = AtomicBoolean()
        val bodyReadStarted = CountDownLatch(1)
        val cancelledBodyRead = CountDownLatch(1)
        val releaseCancelledBodyRead = CountDownLatch(1)
        val responseBodyCloses = AtomicInteger()
        private val current = AtomicReference<Socket?>()
        private val certificate = HeldCertificate.Builder().commonName("route fixture")
            .addSubjectAlternativeName("127.0.0.1").build()
        private val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        private val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        private val worker = Thread {
            try {
                val socket = server.accept().also { current.set(it); it.soTimeout = 5_000 }
                val input = BufferedInputStream(socket.getInputStream())
                val headers = readHeaders(input)
                connectHeaders.set(headers)
                accepted.countDown()
                if (block) { while (input.read() >= 0) Unit; return@Thread }
                val authenticated = headers.any { it == "Proxy-Authorization: ${Credentials.basic(user, secret)}" }
                if (refusal || !authenticated) {
                    socket.getOutputStream().write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"$authRealm\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return@Thread
                }
                assertEquals("CONNECT 127.0.0.1:443 HTTP/1.1", headers.first())
                socket.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                val tls = serverCertificates.sslSocketFactory().createSocket(socket, "127.0.0.1", socket.port, true) as SSLSocket
                current.set(tls)
                tls.useClientMode = false
                tls.startHandshake()
                val originInput = BufferedInputStream(tls.getInputStream())
                val origin = readHeaders(originInput)
                originHeaders.set(origin)
                val length = origin.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                val received = ByteArray(length)
                var offset = 0
                while (offset < length) { val count = originInput.read(received, offset, length - offset); if (count < 0) throw IOException(); offset += count }
                body.set(received)
                val response = if (redirect) "HTTP/1.1 302 Found\r\nLocation: https://other.example/never\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    else "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\nConnection: close\r\n\r\n" + if (slowBody) "" else "ok"
                tls.getOutputStream().write(response.toByteArray())
                tls.getOutputStream().flush()
                if (slowBody) while (originInput.read() >= 0) { /* Wait for the cancelled client to close. */ }
            } catch (_: IOException) {
                // Expected for bad certificates, cancellation and close. The
                // test asserts the client's result instead of treating this as success.
            } finally { current.getAndSet(null)?.close() }
        }.apply { isDaemon = true; start() }

        fun client(trusted: Boolean = true): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
                .socketFactory(object : SocketFactory() {
                    override fun createSocket(): Socket = object : Socket() {
                        override fun getInputStream() = object : FilterInputStream(super.getInputStream()) {
                            private fun <T> observed(read: () -> T): T {
                                val watching = observeBodyRead.get()
                                if (watching) bodyReadStarted.countDown()
                                return try { read() } catch (error: IOException) {
                                    if (watching) {
                                        // Keep a genuinely cancelled TCP/TLS read in its
                                        // unwinding phase until the test permits closure.
                                        cancelledBodyRead.countDown()
                                        check(releaseCancelledBodyRead.await(3, TimeUnit.SECONDS))
                                    }
                                    throw error
                                }
                            }
                            override fun read(): Int = observed { `in`.read() }
                            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                                observed { `in`.read(bytes, offset, length) }
                        }
                        override fun connect(endpoint: java.net.SocketAddress, timeout: Int) {
                            outbound += endpoint as InetSocketAddress
                            super.connect(endpoint, timeout)
                        }
                    }
                    override fun createSocket(host: String, port: Int) = createSocket().apply { connect(InetSocketAddress(host, port)) }
                    override fun createSocket(host: InetAddress, port: Int) = createSocket().apply { connect(InetSocketAddress(host, port)) }
                    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int) =
                        createSocket().apply { bind(InetSocketAddress(local, localPort)); connect(InetSocketAddress(host, port)) }
                    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int) =
                        createSocket().apply { bind(InetSocketAddress(local, localPort)); connect(InetSocketAddress(host, port)) }
                })
                .proxyAuthenticator(routeProxyAuthenticator(user, secret, authRealm, port))
                .authenticator(Authenticator.NONE).followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    val body = response.body
                    val source = object : ForwardingSource(body.source()) {
                        override fun close() { responseBodyCloses.incrementAndGet(); super.close() }
                    }.buffer()
                    response.newBuilder().body(source.asResponseBody(body.contentType(), body.contentLength())).build()
                }
            if (trusted) builder.sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            return builder.build()
        }

        override fun close() {
            releaseCancelledBodyRead.countDown()
            current.getAndSet(null)?.close(); server.close(); worker.join(3_000)
            assertFalse("Fixture worker must finish", worker.isAlive)
        }
    }

    private fun readHeaders(input: BufferedInputStream): List<String> {
        val result = mutableListOf<String>()
        var total = 0
        while (true) {
            val line = StringBuilder()
            while (true) {
                val value = input.read(); if (value < 0) throw IOException()
                if (++total > 8192) throw IOException()
                if (value == '\n'.code) break
                if (value != '\r'.code) line.append(value.toChar())
            }
            if (line.isEmpty()) return result
            result += line.toString()
        }
    }

    @Test fun actualConnectKeepsProxyCredentialsOutOfTlsOrigin() {
        ProxyFixture().use { fixture ->
            val connection = RoutedHttpsConnection(url, fixture.client(), { true })
            connection.setRequestProperty("Authorization", "OAuth fixture-origin-token")
            try {
                assertEquals(200, connection.responseCode)
                assertEquals("ok", connection.inputStream.readBytes().decodeToString())
                assertEquals(2L, connection.contentLengthLong)
                assertNotNull(connection.cipherSuite)
                assertTrue(connection.usingProxy())
                assertTrue(fixture.connectHeaders.get().any { it.startsWith("Proxy-Authorization:") })
                val origin = fixture.originHeaders.get()
                assertTrue(origin.contains("Authorization: OAuth fixture-origin-token"))
                assertTrue(origin.contains("Accept-Encoding: identity"))
                assertFalse(origin.any { it.contains(username) || it.contains(password) || it.startsWith("Proxy-Authorization:", true) })
                assertFalse(connection.toString().contains("synthetic"))
            } finally { connection.disconnect() }
        }
    }

    @Test fun actualTlsRejectsUnknownCertificateWithoutAnOriginRequest() {
        ProxyFixture().use { fixture ->
            val connection = RoutedHttpsConnection(url, fixture.client(trusted = false), { true })
            val error = assertThrows(IOException::class.java) { connection.responseCode }
            assertEquals("Routed HTTPS request failed", error.message)
            assertNull(error.cause)
            assertNull(fixture.originHeaders.get())
            assertTrue(fixture.outbound.isNotEmpty())
            assertTrue("Every socket must remain on this proxy; no port-443 direct attempt", fixture.outbound.all { it.port == fixture.port })
            connection.disconnect()
        }
    }

    @Test fun refusedProxyHasNoDirectFallback() {
        ProxyFixture(refusal = true).use { fixture ->
            val connection = RoutedHttpsConnection(url, fixture.client(), { true })
            assertThrows(IOException::class.java) { connection.responseCode }
            assertTrue(fixture.accepted.await(1, TimeUnit.SECONDS))
            assertNull(fixture.originHeaders.get())
            assertTrue(fixture.outbound.isNotEmpty())
            assertTrue("A refused proxy must never trigger a direct attempt", fixture.outbound.all { it.port == fixture.port })
            connection.disconnect()
        }
    }

    @Test fun disconnectCancelsBlockedConnectAndClosesFutureRequests() {
        ProxyFixture(block = true).use { fixture ->
            val connection = RoutedHttpsConnection(url, fixture.client(), { true })
            val failure = AtomicReference<Throwable?>()
            val worker = Thread { try { connection.responseCode } catch (error: Throwable) { failure.set(error) } }
            worker.start()
            assertTrue(fixture.accepted.await(3, TimeUnit.SECONDS))
            connection.disconnect()
            worker.join(3_000)
            assertFalse(worker.isAlive)
            assertTrue(failure.get() is IOException)
            assertThrows(IOException::class.java) { connection.responseCode }
        }
    }

    @Test fun disconnectWaitsForCancelledBodyReadBeforeClosingResponse() {
        ProxyFixture(slowBody = true).use { fixture ->
            val client = fixture.client()
            val unregisters = AtomicInteger()
            val connection = RoutedHttpsConnection(url, client, { true }) { unregisters.incrementAndGet() }
            assertEquals(200, connection.responseCode)
            val input = connection.inputStream
            fixture.observeBodyRead.set(true)
            val readFailure = AtomicReference<Throwable?>()
            val disconnectFailure = AtomicReference<Throwable?>()
            val streamCloseFailure = AtomicReference<Throwable?>()
            val disconnected = CountDownLatch(1)
            val reader = Thread { try { input.read() } catch (error: Throwable) { readFailure.set(error) } }
            val closer = Thread {
                try { connection.disconnect() } catch (error: Throwable) { disconnectFailure.set(error) }
                finally { disconnected.countDown() }
            }
            val streamCloser = Thread { try { input.close() } catch (error: Throwable) { streamCloseFailure.set(error) } }
            try {
                reader.start()
                assertTrue(fixture.bodyReadStarted.await(3, TimeUnit.SECONDS))
                closer.start()
                assertTrue(fixture.cancelledBodyRead.await(3, TimeUnit.SECONDS))
                streamCloser.start()
                if (disconnected.await(100, TimeUnit.MILLISECONDS)) {
                    assertNull("Concurrent body closure must not fail", disconnectFailure.get())
                    fail("Response closure must wait for its active reader")
                }
            } finally {
                fixture.releaseCancelledBodyRead.countDown()
                reader.join(3_000); closer.join(3_000); streamCloser.join(3_000)
                connection.disconnect()
            }
            assertFalse(reader.isAlive); assertFalse(closer.isAlive); assertFalse(streamCloser.isAlive)
            assertNull("Disconnect must not race the OkHttp body", disconnectFailure.get())
            assertTrue(readFailure.get() is IOException)
            assertNull(streamCloseFailure.get())
            assertEquals(1, fixture.responseBodyCloses.get())
            assertEquals(1, unregisters.get())
            assertThrows(IOException::class.java) { input.read() }
            assertThrows(IOException::class.java) { connection.responseCode }
            input.close(); connection.disconnect()
            assertEquals(1, unregisters.get())
            client.connectionPool.evictAll()
            assertEquals(0, client.connectionPool.connectionCount())
        }
    }

    @Test fun responseOwnershipTimeoutRetainsUnconfirmedConnectionForCleanupRetry() {
        ProxyFixture(slowBody = true).use { fixture ->
            val unregisters = AtomicInteger()
            val connection = RoutedHttpsConnection(url, fixture.client(), { true }) { unregisters.incrementAndGet() }
            assertEquals(200, connection.responseCode)
            val input = connection.inputStream
            fixture.observeBodyRead.set(true)
            val failure = AtomicReference<Throwable?>()
            val reader = Thread { try { input.read() } catch (error: Throwable) { failure.set(error) } }
            try {
                reader.start()
                assertTrue(fixture.bodyReadStarted.await(3, TimeUnit.SECONDS))
                val started = System.nanoTime()
                val error = assertThrows(IOException::class.java) { connection.disconnect() }
                val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                assertEquals("Routed response ownership could not be confirmed", error.message)
                assertTrue("Ownership waiting must be bounded", elapsedMs < 2_000)
                assertEquals(0, unregisters.get())
                assertEquals(0, fixture.responseBodyCloses.get())
                assertThrows(IOException::class.java) { connection.responseCode }
            } finally {
                fixture.releaseCancelledBodyRead.countDown()
                reader.join(3_000)
                connection.disconnect()
            }
            assertFalse(reader.isAlive)
            assertTrue(failure.get() is IOException)
            assertEquals(1, fixture.responseBodyCloses.get())
            assertEquals(1, unregisters.get())
            assertThrows(IOException::class.java) { input.read() }
            input.close(); connection.disconnect()
            assertEquals(1, fixture.responseBodyCloses.get())
            assertEquals(1, unregisters.get())
        }
    }

    @Test fun redirectsRemainResponsesAndBodiesAreBoundedBeforeConnecting() {
        ProxyFixture(redirect = true).use { fixture ->
            val connection = RoutedHttpsConnection(url, fixture.client(), { true })
            try {
                assertEquals(302, connection.responseCode)
                assertEquals("https://other.example/never", connection.getHeaderField("Location"))
                assertFalse(connection.instanceFollowRedirects)
            } finally { connection.disconnect() }
        }
        val connection = RoutedHttpsConnection(url, OkHttpClient(), { true })
        connection.requestMethod = "POST"; connection.doOutput = true
        assertThrows(IOException::class.java) { connection.outputStream.write(ByteArray(65 * 1024)) }
        connection.disconnect()
    }

    @Test fun callerCannotInjectProxyCredentialsOrTlsOverrides() {
        val connection = RoutedHttpsConnection(url, OkHttpClient(), { true })
        assertThrows(IllegalArgumentException::class.java) { connection.setRequestProperty("Proxy-Authorization".lowercase(), "fixture") }
        assertThrows(IllegalArgumentException::class.java) { connection.addRequestProperty("Proxy-Authenticate", "fixture") }
        assertThrows(IllegalArgumentException::class.java) { connection.setRequestProperty("Accept", "text/plain\r\nInjected: yes") }
        assertThrows(UnsupportedOperationException::class.java) { connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true } }
        connection.disconnect()
    }

    @Test fun requestBodyAndHeadersStillReachTheOrigin() {
        ProxyFixture().use { fixture ->
            val connection = RoutedHttpsConnection(url, fixture.client(), { true })
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setFixedLengthStreamingMode(2)
            connection.outputStream.use { it.write("{}".toByteArray()) }
            try {
                assertEquals(200, connection.responseCode)
                assertArrayEquals("{}".toByteArray(), fixture.body.get())
                assertTrue(fixture.originHeaders.get().first().startsWith("POST /fixture?token=synthetic "))
            } finally { connection.disconnect() }
        }
    }

    @Test fun twoClientsKeepSeparateProxySocketsAndCredentials() {
        ProxyFixture().use { first ->
            ProxyFixture(user = "second_fixture_user", secret = "second_fixture_password", authRealm = "second_fixture_realm").use { second ->
                val firstConnection = RoutedHttpsConnection(url, first.client(), { true })
                val secondConnection = RoutedHttpsConnection(url, second.client(), { true })
                try {
                    assertEquals(200, firstConnection.responseCode)
                    assertEquals(200, secondConnection.responseCode)
                    assertTrue(first.connectHeaders.get().contains("Proxy-Authorization: ${Credentials.basic(username, password)}"))
                    assertTrue(second.connectHeaders.get().contains("Proxy-Authorization: ${Credentials.basic("second_fixture_user", "second_fixture_password")}"))
                    assertTrue(first.outbound.all { it.port == first.port })
                    assertTrue(second.outbound.all { it.port == second.port })
                } finally { firstConnection.disconnect(); secondConnection.disconnect() }
            }
        }
    }

    @Test fun inactiveRouteRefusesBeforeAnyNetworkRequestAndDisconnectIsIdempotent() {
        var disconnected = 0
        val connection = RoutedHttpsConnection(url, OkHttpClient(), { false }) { disconnected++ }
        assertThrows(IOException::class.java) { connection.responseCode }
        connection.disconnect(); connection.disconnect()
        assertEquals(1, disconnected)
    }
}
