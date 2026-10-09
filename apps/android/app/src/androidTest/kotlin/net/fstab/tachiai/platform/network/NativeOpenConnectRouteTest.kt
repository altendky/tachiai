package net.fstab.tachiai.platform.network

import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import routebridge.Route
import routebridge.RoutePreparation as NativeRoutePreparation
import routebridge.Routebridge

// Opt-in, actual JNI proof using short-lived credentials for two owned ocserv
// containers. The orchestrator verifies a disposable AVD before staging files.
// Ordinary instrumentation skips this class; no provider or saved route is used.
class NativeOpenConnectRouteTest {
    private val root = "/data/local/tmp/tachiai-openconnect-owned"
    private val user = "synthetic_local_user"
    private val password = "synthetic_local_password"
    private val realm = "synthetic_local_realm"

    @Before fun requireOwnedFixture() {
        val supplied = InstrumentationRegistry.getArguments().getString("openconnectOwnedRoot")
        assumeTrue("Requires explicitly staged owned emulator fixture", supplied != null)
        assertEquals("Only the fixed owned fixture path is accepted", root, supplied)
        assertNotSame("Native setup and cleanup run off main", Looper.getMainLooper(), Looper.myLooper())
    }

    private fun fixture(name: String): ByteArray {
        require(name in setOf(
            "a-endpoint.txt", "b-endpoint.txt", "wrong-name-endpoint.txt",
            "a-ca.pem", "b-ca.pem", "a-cert.pem", "b-cert.pem", "a-key.pem", "b-key.pem",
            "a-origin-ca.pem", "b-origin-ca.pem",
        ))
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cat $root/$name")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (output.size() <= CONNECTION_IMPORT_LIMIT) {
                val count = input.read(buffer, 0, minOf(buffer.size, CONNECTION_IMPORT_LIMIT + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            val value = output.toByteArray()
            assertTrue("Owned fixture is present and bounded", value.isNotEmpty() && value.size <= CONNECTION_IMPORT_LIMIT)
            value
        }
    }

    private fun text(name: String) = fixture(name).toString(Charsets.UTF_8)

    private fun prepare(name: String, token: NativeRoutePreparation, wrongCa: Boolean = false, wrongName: Boolean = false) =
        Routebridge.newOpenConnectPrepared(
            text(if (wrongName) "wrong-name-endpoint.txt" else "$name-endpoint.txt"),
            "10.0.2.2", text(if (wrongCa) "b-ca.pem" else "$name-ca.pem"),
            text("$name-cert.pem"), text("$name-key.pem"), user, password, realm, "owned-route.test", token,
        )

    @Test(timeout = 20_000) fun actualFactoryRejectsPreCancellationAndMalformedConfiguration() {
        val cancelled = Routebridge.newRoutePreparation()
        cancelled.cancel()
        val stopped = Routebridge.newOpenConnectPrepared("", "", "", "", "", user, password, realm, "owned-route.test", cancelled)
        assertEquals(4L, stopped.code)
        assertNull(stopped.route)
        val token = Routebridge.newRoutePreparation()
        try {
            val invalid = Routebridge.newOpenConnectPrepared("", "not-numeric", "", "", "", user, password, realm, "owned-route.test", token)
            assertEquals(1L, invalid.code)
            assertNull(invalid.route)
        } finally { token.cancel() }
        val nativeToken = Routebridge.newRoutePreparation()
        try {
            // Valid certificate inputs reach the native certificate-only
            // configuration gate, which must refuse plaintext endpoints.
            val invalid = Routebridge.newOpenConnectPrepared(
                text("a-endpoint.txt").replace("https://", "http://"), "10.0.2.2", text("a-ca.pem"),
                text("a-cert.pem"), text("a-key.pem"), user, password, realm, "owned-route.test", nativeToken,
            )
            try {
                assertEquals(1L, invalid.code)
                assertNull(invalid.route)
            } finally { invalid.route?.close() }
        } finally { nativeToken.cancel() }
    }

    @Test(timeout = 80_000) fun actualGatewayTlsRejectsWrongCaAndWrongCertificateName() {
        for (wrongCa in listOf(true, false)) {
            val token = Routebridge.newRoutePreparation()
            try {
                val result = prepare("a", token, wrongCa = wrongCa, wrongName = !wrongCa)
                try {
                    // The opaque native ABI reserves code 2; TLS/auth/connect
                    // refusals currently share the fixed connection code 3.
                    assertEquals("Gateway TLS refusal has a fixed result", 3L, result.code)
                    assertNull("Failed TLS returns no route", result.route)
                } finally { result.route?.close() }
            } finally { token.cancel() }
        }
    }

    private fun client(route: Route, ca: ByteArray): OkHttpClient {
        assertTrue("Actual JNI listener port is bounded", route.port in 1L..65535L)
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ca.inputStream())
        store.setCertificateEntry("owned-origin", certificate)
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            .trustManagers.filterIsInstance<X509TrustManager>().single()
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", route.port.toInt())))
            .proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null ||
                    response.challenges().none { it.realm == realm }) null
                else response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(user, password)).build()
            }
            .sslSocketFactory(tls.socketFactory, trust)
            // Use the ordinary hostname verifier and a separate origin CA.
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(8, TimeUnit.SECONDS).build()
    }

    private fun checkBody(client: OkHttpClient, identity: String, timeoutMillis: Long? = null) {
        val call = client.newCall(Request.Builder().url("https://owned-route.test/").build())
        timeoutMillis?.let { call.timeout().timeout(it, TimeUnit.MILLISECONDS) }
        call.execute().use { response ->
            assertEquals(200, response.code)
            val expected = identity.repeat(4096).toByteArray(Charsets.US_ASCII)
            assertTrue("Owned response exercises multiple packets", expected.size >= 40 * 1024)
            assertArrayEquals(expected, checkNotNull(response.body).bytes())
        }
    }

    private fun awaitOwnedOrigin(client: OkHttpClient, identity: String) {
        // ocserv assigns its server-side inner address after authentication;
        // the owned origin can bind only then. Retry fixture startup for 3 s.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (true) {
            try {
                val remainingMillis = maxOf(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))
                checkBody(client, identity, minOf(remainingMillis, 500))
                return
            } catch (failure: IOException) {
                if (failure is SSLHandshakeException || System.nanoTime() >= deadline) throw failure
                Thread.sleep(50)
            }
        }
    }

    @Test(timeout = 90_000) fun actualEncryptedRoutesKeepIndependentIdentitiesAndCancellationScopes() {
        val tokens = listOf(Routebridge.newRoutePreparation(), Routebridge.newRoutePreparation())
        val routes = mutableListOf<Route>()
        val clients = mutableListOf<OkHttpClient>()
        val reader = Executors.newSingleThreadExecutor()
        try {
            for ((index, name) in listOf("a", "b").withIndex()) {
                val result = prepare(name, tokens[index])
                result.route?.let { routes.add(it) }
                assertEquals("Owned gateway prepares through actual JNI", 0L, result.code)
                assertEquals(index + 1, routes.size)
                clients.add(client(routes[index], fixture("$name-origin-ca.pem")))
            }
            awaitOwnedOrigin(clients[0], "identity-A")
            awaitOwnedOrigin(clients[1], "identity-B")
            checkBody(clients[0], "identity-A")
            checkBody(clients[1], "identity-B")
            val wrongTrust = client(routes[0], fixture("b-origin-ca.pem"))
            try {
                assertThrows(SSLHandshakeException::class.java) { checkBody(wrongTrust, "identity-A") }
            } finally { wrongTrust.connectionPool.evictAll(); wrongTrust.dispatcher.executorService.shutdownNow() }
            clients[0].newCall(Request.Builder().url("https://owned-route.test/stream").build()).execute().use { response ->
                assertEquals(200, response.code)
                val stream = checkNotNull(response.body).byteStream()
                val first = ByteArray(11)
                var position = 0
                while (position < first.size) {
                    val count = stream.read(first, position, first.size - position)
                    assertTrue("Owned stream transfers before cancellation", count > 0)
                    position += count
                }
                assertEquals("identity-A\n", first.toString(Charsets.US_ASCII))
                val ended = reader.submit<Boolean> {
                    val buffer = ByteArray(1024)
                    try { while (stream.read(buffer) != -1) Unit; false } catch (_: IOException) { true }
                }
                val started = System.nanoTime()
                tokens[0].cancel()
                assertTrue("Cancellation signal does not wait for native teardown", System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(100))
                assertTrue("Incomplete encrypted traffic fails after cancellation", ended.get(3, TimeUnit.SECONDS))
            }
            routes[0].close()
            routes[0].close()
            checkBody(clients[1], "identity-B")
        } finally {
            tokens.forEach { it.cancel() }
            clients.forEach { it.dispatcher.cancelAll(); it.connectionPool.evictAll(); it.dispatcher.executorService.shutdownNow() }
            reader.shutdownNow()
            var cleanupFailed = false
            routes.forEach { if (runCatching { it.close() }.isFailure) cleanupFailed = true }
            assertFalse("Owned native cleanup was confirmed for every route", cleanupFailed)
        }
    }
}
