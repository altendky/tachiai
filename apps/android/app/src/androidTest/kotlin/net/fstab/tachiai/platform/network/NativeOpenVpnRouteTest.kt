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

// Opt-in actual JNI proof with short-lived, synthetic owned server credentials.
// The orchestrator verifies the disposable AVD before staging these files.
// Ordinary instrumentation runs skip this class; no provider/store access occurs.
class NativeOpenVpnRouteTest {
    private val root = "/data/local/tmp/tachiai-openvpn-owned"
    private val user = "synthetic_local_user"
    private val password = "synthetic_local_password"
    private val realm = "synthetic_local_realm"

    @Before fun requireOwnedFixture() {
        val supplied = InstrumentationRegistry.getArguments().getString("openvpnOwnedRoot")
        assumeTrue("Requires explicitly staged owned emulator fixture", supplied != null)
        assertEquals("Only the fixed owned fixture path is accepted", root, supplied)
        assertNotSame("Native setup and cleanup run off main", Looper.getMainLooper(), Looper.myLooper())
    }

    private fun fixture(name: String): ByteArray {
        require(name in setOf("a.ovpn", "b.ovpn", "wrong-ca.ovpn", "wrong-name.ovpn", "a-origin-ca.pem", "b-origin-ca.pem"))
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cat $root/$name")
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
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
        return bytes
    }

    private fun prepare(profile: String, token: NativeRoutePreparation) = Routebridge.newOpenVPNPrepared(
        profile, user, password, realm, "origin.owned-route.test", token,
    )

    @Test(timeout = 20_000) fun actualFactoryRejectsPreCancellationAndMalformedConfiguration() {
        val cancelled = Routebridge.newRoutePreparation()
        cancelled.cancel()
        val stopped = prepare("invalid synthetic configuration", cancelled)
        assertEquals(4L, stopped.code)
        assertNull(stopped.route)
        val token = Routebridge.newRoutePreparation()
        try {
            val invalid = prepare("invalid synthetic configuration", token)
            assertEquals(1L, invalid.code)
            assertNull(invalid.route)
        } finally { token.cancel() }
    }

    @Test(timeout = 30_000) fun actualGatewayTlsRejectsWrongCaAndWrongCertificateName() {
        for (name in listOf("wrong-ca.ovpn", "wrong-name.ovpn")) {
            val token = Routebridge.newRoutePreparation()
            try {
                val result = prepare(fixture(name).toString(Charsets.UTF_8), token)
                try {
                    assertEquals("Gateway TLS refusal has a fixed result", 2L, result.code)
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
            // Keep OkHttp's ordinary hostname verifier; no TLS bypass is used.
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .callTimeout(8, TimeUnit.SECONDS).build()
    }

    private fun checkBody(client: OkHttpClient, identity: String) {
        client.newCall(Request.Builder().url("https://origin.owned-route.test/").build()).execute().use { response ->
            assertEquals(200, response.code)
            val expected = identity.repeat(4096).toByteArray(Charsets.US_ASCII)
            assertTrue("Owned response exercises multiple packets", expected.size >= 40 * 1024)
            assertArrayEquals(expected, checkNotNull(response.body).bytes())
        }
    }

    @Test(timeout = 60_000) fun actualEncryptedRoutesKeepIndependentIdentitiesAndCancellationScopes() {
        val tokens = listOf(Routebridge.newRoutePreparation(), Routebridge.newRoutePreparation())
        val routes = mutableListOf<Route>()
        val clients = mutableListOf<OkHttpClient>()
        val reader = Executors.newSingleThreadExecutor()
        try {
            for ((index, name) in listOf("a", "b").withIndex()) {
                val result = prepare(fixture("$name.ovpn").toString(Charsets.UTF_8), tokens[index])
                result.route?.let { routes.add(it) }
                assertEquals("Owned gateway prepares through actual JNI", 0L, result.code)
                assertEquals(index + 1, routes.size)
                clients.add(client(routes[index], fixture("$name-origin-ca.pem")))
            }
            checkBody(clients[0], "identity-A")
            checkBody(clients[1], "identity-B")
            val wrongTrust = client(routes[0], fixture("b-origin-ca.pem"))
            try {
                assertThrows(SSLHandshakeException::class.java) { checkBody(wrongTrust, "identity-A") }
            } finally { wrongTrust.connectionPool.evictAll(); wrongTrust.dispatcher.executorService.shutdownNow() }
            clients[0].newCall(Request.Builder().url("https://origin.owned-route.test/stream").build()).execute().use { response ->
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
                    try { while (stream.read() != -1) Unit; false } catch (_: IOException) { true }
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
