package net.fstab.tachiai.provider.abema.account

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.platform.network.*
import org.junit.Assert.*
import org.junit.Test

class AbemaBoundAccountTransportTest {
    private class Egress : RouteNetworkEgress {
        @Volatile var active = true
        @Volatile var clean = true
        val cancelled = CountDownLatch(1)
        override val socketFactory get() = SocketFactory.getDefault()
        override fun checkActive() { if (!active) throw IOException("Network egress refused") }
        override fun isActive() = active
        override fun resolveAddresses(host: String, timeoutMillis: Long): List<InetAddress> = error("No fixture DNS")
        override fun resolve(host: String?, timeoutMillis: Long): String = error("No fixture DNS")
        override fun bindSocket(fd: Int) = error("No fixture native socket")
        override fun cancel() { active = false; cancelled.countDown() }
        override fun isCleanupConfirmed() = clean
        override fun reportCleanupUnconfirmed() { clean = false; cancel() }
        override fun closeOwnedSockets() = Unit
    }
    private class Connection(private val response: () -> Unit = {}) : HttpsURLConnection(URL("https://api.p-c3-e.abema-tv.com/v1/users/guest")) {
        var disconnects = 0
        override fun getResponseCode(): Int { response(); return 200 }
        override fun getContentType() = "application/json"
        override fun getContentLengthLong() = -1L
        override fun getInputStream() = ByteArrayInputStream("""{"profile":{"userId":"guest"}}""".toByteArray())
        override fun disconnect() { disconnects++ }
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
    private class Backend : RouteBackend {
        override val proxyPort = 12345
        var closes = 0
        override fun close() { closes++ }
    }

    @Test fun explicitlyBoundImportedRequestCannotUseTheOrdinaryBackendFactory() {
        val egress = Egress(); val backend = Backend(); val connection = Connection(); var bound = 0; var poisoned = 0
        val selected = parseConnectionProfile("http://proxy.example.test:3128".toByteArray())
        val owned = OwnedAbemaAccountTransport(selected, { true }, { poisoned++ },
            createBackend = { _, _, _ -> error("No default routing fallback") },
            makeHttpTransport = { _, gate -> AbemaAccountHttpTransport({ connection }, gate, { 1000 }, { 1000000 }) },
            networkEgressFactory = { egress }, createBoundBackend = { profile, _, _, actual ->
                bound++; assertSame(selected, profile); assertSame(egress, actual); backend
            })
        assertTrue(owned.profile("guest", "fixture-bearer") is AbemaAccountProfileResult.Identity)
        assertEquals(1, bound); assertEquals(1, backend.closes); assertEquals(1, connection.disconnects)
        assertFalse(egress.active); assertTrue(owned.cleanupConfirmed); assertEquals(0, poisoned)
        owned.close(); assertEquals(1, backend.closes)
    }

    @Test fun cancellingAnActiveRequestRetiresItsNetworkBeforeWaitingForHttp() {
        val egress = Egress(); val entered = CountDownLatch(1); val connection = Connection {
            entered.countDown()
            check(egress.cancelled.await(2, TimeUnit.SECONDS))
            throw IOException("Owned request cancelled")
        }
        val owned = OwnedAbemaAccountTransport(null, { true }, { fail("Cleanup is known") },
            makeHttpTransport = { _, gate -> AbemaAccountHttpTransport({ connection }, gate, { 1000 }, { 1000000 }) },
            networkEgressFactory = { egress })
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<AbemaAccountFailure> {
                try { owned.profile("guest", "fixture-bearer"); error("Must cancel") }
                catch (error: AbemaAccountException) { error.category }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS)); owned.cancelActiveRequest()
            assertEquals(AbemaAccountFailure.CANCELLED, result.get(2, TimeUnit.SECONDS))
            assertTrue(owned.cleanupConfirmed); assertFalse(egress.active)
            owned.close()
        } finally { egress.cancel(); worker.shutdownNow() }
    }

    @Test fun aLateResolverCompletionCannotUpgradeTheFirstOwnedCleanupFailure() {
        val egress = Egress().apply { clean = false }; val connection = Connection(); var poisoned = 0
        val owned = OwnedAbemaAccountTransport(null, { true }, { poisoned++ },
            makeHttpTransport = { _, gate -> AbemaAccountHttpTransport({ connection }, gate, { 1000 }, { 1000000 }) },
            networkEgressFactory = { egress })
        val failure = assertThrows(AbemaAccountException::class.java) { owned.profile("guest", "fixture-bearer") }
        assertEquals(AbemaAccountFailure.CLEANUP_FAILED, failure.category); assertNull(failure.cause)
        assertEquals(1, poisoned); assertFalse(owned.cleanupConfirmed); assertFalse(egress.active)
        val retained = OwnedAbemaAccountTransport::class.java.getDeclaredField("retained").apply { isAccessible = true }
            .get(null) as Iterable<*>
        assertTrue(retained.any { (it as Collection<*>).any { resource -> resource === egress } })
        egress.clean = true
        assertEquals(AbemaAccountFailure.CLEANUP_FAILED, assertThrows(AbemaAccountException::class.java, owned::close).category)
        assertFalse(owned.cleanupConfirmed); assertEquals(1, connection.disconnects)
    }
}
