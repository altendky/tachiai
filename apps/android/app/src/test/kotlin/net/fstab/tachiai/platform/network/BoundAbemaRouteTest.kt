package net.fstab.tachiai.platform.network

import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import javax.net.SocketFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import net.fstab.tachiai.provider.abema.account.AbemaApprovalBrowserPolicy

// No native/provider dial or Android Network is used. These tests verify the
// actual session/client policy and ownership around an explicitly supplied seam.
class BoundAbemaRouteTest {
    private class Egress : RouteNetworkEgress {
        var active = true
        var clean = true
        var cancels = 0
        var factoryReads = 0
        val hosts = mutableListOf<String>()
        private val sockets = object : SocketFactory() {
            override fun createSocket(): Socket = throw IOException("Owned fixture does not dial")
            override fun createSocket(host: String, port: Int): Socket = createSocket()
            override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = createSocket()
            override fun createSocket(host: InetAddress, port: Int): Socket = createSocket()
            override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = createSocket()
        }
        override val socketFactory get() = sockets.also { checkActive(); factoryReads++ }
        override fun checkActive() { if (!active) throw IOException("Network egress refused") }
        override fun isActive() = active
        override fun resolveAddresses(host: String, timeoutMillis: Long): List<InetAddress> {
            checkActive(); assertEquals(10_000, timeoutMillis); hosts.add(host)
            return listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        }
        override fun resolve(host: String?, timeoutMillis: Long) = error("Unexpected native resolution")
        override fun bindSocket(fd: Int) = error("Unexpected native binding")
        override fun cancel() { cancels++; active = false }
        override fun isCleanupConfirmed() = clean
        override fun reportCleanupUnconfirmed() { clean = false; active = false }
        override fun closeOwnedSockets() = Unit
    }
    private class Backend(private val invalidPort: Boolean = false, private val fatalPort: Boolean = false,
        private val failClose: Boolean = false, private val holdClose: () -> Unit = {}) : RouteBackend {
        var closes = 0
        override val proxyPort: Int get() {
            if (fatalPort) throw AssertionError("Owned constructor refusal")
            return if (invalidPort) 0 else 12345
        }
        override fun close() { closes++; holdClose(); if (failClose) throw IOException("Owned close refusal") }
    }
    private fun profile() = parseConnectionProfile("http://fixture:private@proxy.example.test:3128".toByteArray())
    private fun client(route: RouteSession) = RouteSession::class.java.getDeclaredField("client")
        .apply { isAccessible = true }.get(route) as OkHttpClient

    @Test fun directSystemUsesOnlySuppliedDnsAndSocketFactoryWithUnchangedAccountSecurity() {
        for (bootstrap in listOf(false, true)) {
            val network = Egress(); var allocated = 0
            val route = if (bootstrap) RouteSession.createBoundAbemaAccountBootstrap(null, network) { _, _ -> allocated++; Backend() }
                else RouteSession.createBoundAbemaAccountSession(null, network) { _, _ -> allocated++; Backend() }
            try {
                assertTrue(route.isSystem); assertFalse(route.hasProxyBridge)
                val http = client(route)
                assertSame(network.socketFactory, http.socketFactory)
                assertEquals(listOf("127.0.0.1"), http.dns.lookup("api.p-c3-e.abema-tv.com").map { it.hostAddress })
                assertEquals(listOf("api.p-c3-e.abema-tv.com"), network.hosts)
                assertSame(Proxy.NO_PROXY, http.proxy)
                assertSame(CookieJar.NO_COOKIES, http.cookieJar); assertSame(Authenticator.NONE, http.authenticator)
                assertSame(Authenticator.NONE, http.proxyAuthenticator)
                assertSame(OkHttpClient().hostnameVerifier, http.hostnameVerifier)
                assertFalse(http.followRedirects); assertFalse(http.followSslRedirects); assertFalse(http.retryOnConnectionFailure)
                route.open(URL("https://abema.tv/api/auth/oauth/token")).disconnect()
                assertThrows(IllegalArgumentException::class.java) { route.open(URL("https://auth.abema.tv/")) }
                network.active = false
                assertThrows(IOException::class.java) { route.open(URL("https://abema.tv/api/auth/oauth/token")) }
                assertThrows(IOException::class.java) { http.dns.lookup("abema.tv") }
                assertEquals(1, network.hosts.size)
            } finally { route.close() }
            assertEquals(0, allocated); assertEquals(1, network.cancels)
        }
    }

    @Test fun importedAndApprovalBridgesDoNotBindTheirLoopbackClientToTheExternalNetwork() {
        for (approval in listOf(false, true)) for (selected in listOf(null, profile())) {
            if (!approval && selected == null) continue
            val network = Egress(); val backend = Backend(); var allocated = 0
            val make: (ConnectionProfile?, RouteProxySecurity) -> RouteBackend = { actual, security ->
                allocated++; assertSame(selected, actual)
                assertEquals(if (approval) AbemaApprovalBrowserPolicy.HOSTS else "abema.tv,api.p-c3-e.abema-tv.com", security.allowedHosts)
                backend
            }
            val route = if (approval) RouteSession.createBoundAbemaApprovalBrowser(selected, network, createBackend = make)
                else RouteSession.createBoundAbemaAccountSession(selected, network, createBackend = make)
            assertTrue(route.hasProxyBridge); assertEquals(selected == null, route.isSystem)
            assertEquals(Proxy.Type.HTTP, client(route).proxy?.type())
            assertEquals(0, network.factoryReads); assertTrue(network.hosts.isEmpty())
            route.close(); route.close()
            assertEquals(1, allocated); assertEquals(1, backend.closes); assertEquals(1, network.cancels)
        }
    }

    @Test fun unsupportedBoundProfilesRefuseBeforeBackendOrSocketFactoryAllocation() {
        for (protocol in listOf(OpenVpnRouteProtocol, OpenConnectRouteProtocol)) {
            val network = Egress(); val preparation = RoutePreparation(); var allocated = 0
            val selected = ConnectionProfile(protocol, "fixture", "Owned fixture configuration")
            assertThrows(IllegalArgumentException::class.java) {
                RouteSession.createBoundAbemaApprovalBrowser(selected, network, preparation, createBackend = { _, _ -> allocated++; Backend() })
            }
            assertEquals(0, allocated); assertEquals(0, network.factoryReads); assertEquals(1, network.cancels)
            assertTrue(preparation.cleanupConfirmed)
        }
    }

    @Test fun preparationCancellationRetiresReadyBoundDnsBeforeCleanupAndRemovesItsHook() {
        val network = Egress(); val preparation = RoutePreparation()
        val route = RouteSession.createBoundAbemaAccountSession(null, network, preparation)
        assertTrue(preparation.cancel()); assertFalse(network.active)
        assertThrows(IOException::class.java) { client(route).dns.lookup("abema.tv") }
        route.close()
        val after = network.cancels; preparation.cancel(); assertEquals(after, network.cancels)
    }

    @Test fun unresolvedNetworkCleanupIsStickyEvenAfterItsWorkerLaterFinishes() {
        val network = Egress().apply { clean = false }; val backend = Backend()
        val route = RouteSession.createBoundAbemaApprovalBrowser(null, network) { _, _ -> backend }
        assertThrows(IOException::class.java, route::close)
        assertEquals(1, backend.closes); network.clean = true
        assertThrows(IOException::class.java, route::close)
        assertEquals(1, backend.closes)
    }

    @Test fun standaloneCloseFailureRetainsTheReturnedRouteAndItsBackendOwner() {
        val network = Egress(); val backend = Backend(failClose = true)
        val route = RouteSession.createBoundAbemaApprovalBrowser(null, network) { _, _ -> backend }
        assertThrows(IOException::class.java, route::close)
        assertThrows(IOException::class.java, route::close)
        assertEquals(1, backend.closes); assertFalse(network.clean)
        val retained = RouteSession::class.java.getDeclaredField("retainedNetworkOwners").apply { isAccessible = true }
            .get(null) as Iterable<*>
        assertTrue(retained.any { (it as List<*>).any { owner -> owner === route } })
    }

    @Test fun aConcurrentCloseCannotConfirmAnOwnerWhoseBackendIsStillClosing() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val backend = Backend(holdClose = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) })
        val network = Egress()
        val route = RouteSession.createBoundAbemaApprovalBrowser(null, network) { _, _ -> backend }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val first = worker.submit<Unit> { route.close() }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertThrows(IOException::class.java, route::close)
            assertFalse(first.isDone); release.countDown()
            assertTrue(assertThrows(ExecutionException::class.java) { first.get(2, TimeUnit.SECONDS) }.cause is IOException)
            assertThrows(IOException::class.java, route::close)
            assertEquals(1, backend.closes)
        } finally { release.countDown(); worker.shutdownNow() }
    }

    @Test fun constructorRollbackClosesExactlyOnceAndRetainsUnconfirmedResources() {
        for (fatal in listOf(false, true)) for (failed in listOf(false, true)) {
            val network = Egress(); val preparation = RoutePreparation()
            val backend = Backend(invalidPort = !fatal, fatalPort = fatal, failClose = failed)
            assertThrows(if (fatal) AssertionError::class.java else IOException::class.java) {
                RouteSession.createBoundAbemaApprovalBrowser(null, network, preparation, createBackend = { _, _ -> backend })
            }
            assertEquals(1, backend.closes)
            assertEquals(!fatal && !failed, preparation.cleanupConfirmed)
            if (fatal || failed) {
                val retained = RouteSession::class.java.getDeclaredField("retainedNetworkOwners").apply { isAccessible = true }
                    .get(null) as Iterable<*>
                assertTrue(retained.any { (it as List<*>).any { owner -> owner === network } })
            }
        }
    }
}
