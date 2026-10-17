package net.fstab.tachiai.platform.network

import java.io.IOException
import java.net.Proxy
import java.net.URL
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import net.fstab.tachiai.provider.abema.account.AbemaApprovalBrowserPolicy

// Route ownership and connection creation only. No provider, DNS, proxy dial,
// browser cookies or private account state is accessed by these fixtures.
class AbemaApprovalBrowserRouteTest {
    private fun profile() = parseConnectionProfile("http://fixture:private@proxy.example.test:3128".toByteArray())
    private class Backend(override val proxyPort: Int = 12345, private val failClose: Boolean = false) : RouteBackend {
        var closes = 0
        override fun close() { closes++; if (failClose) error("private fixture cleanup detail") }
    }

    @Test fun bothSelectedModesAdmitOnlyApprovalOriginsAndOwnTheirPrivateClient() {
        for (selected in listOf(null, profile())) {
            val backend = Backend(); var created = 0; var retained = 0
            val route = RouteSession.createAbemaApprovalBrowser(selected, ownBackend = {
                retained++; assertSame(backend, it); it
            }, createSystemBackend = { security ->
                assertNull(selected); created++
                assertEquals(AbemaApprovalBrowserPolicy.HOSTS, security.allowedHosts); backend
            }) { actual, security ->
                created++; assertSame(selected, actual)
                assertEquals(AbemaApprovalBrowserPolicy.HOSTS, security.allowedHosts); backend
            }
            try {
                assertEquals(selected == null, route.isSystem)
                assertTrue(route.hasProxyBridge); assertTrue(route.isAbemaApprovalBridge)
                for (url in listOf("https://abema.tv/account/change-from-device?fixture=code",
                    "https://auth.abema.tv:443/login/email", "https://api.p-c3-e.abema-tv.com/v1/users/fixture",
                    "https://ds-glb-linear-abematv.akamaized.net/region", "https://ds-linear-abematv.akamaized.net/region",
                    "https://gateway.p-c2-x.abema-tv.com/web", "https://new-api.abema-tv.com/resource?fixture=value")) {
                    val connection = route.open(URL(url))
                    assertTrue(connection is RoutedHttpsConnection)
                    assertTrue(connection.usingProxy()); connection.disconnect()
                }
                val field = RouteSession::class.java.getDeclaredField("client").apply { isAccessible = true }
                val client = field.get(route) as OkHttpClient
                assertSame(CookieJar.NO_COOKIES, client.cookieJar)
                assertSame(Authenticator.NONE, client.authenticator)
                assertNotSame(Authenticator.NONE, client.proxyAuthenticator)
                assertFalse(client.followRedirects); assertFalse(client.followSslRedirects)
                assertFalse(client.retryOnConnectionFailure)
                assertEquals(Proxy.Type.HTTP, client.proxy?.type())
                assertTrue(route.matchesProxyChallenge("127.0.0.1", route.proxyRealm))
                assertFalse(route.matchesProxyChallenge("auth.abema.tv", route.proxyRealm))
                assertFalse(route.matchesProxyChallenge("127.0.0.1", "other-realm"))
                for (url in listOf("http://abema.tv/", "https://user@abema.tv/", "https://abema.tv:0443/",
                    "https://abema.tv:444/", "https://abema.tv/#fixture", "https://abema.tv/a/%2e%2e/login",
                    "https://auth.abema.tv.evil.invalid/", "https://api.abema.io/", "https://id.twitch.tv/",
                    "https://ds-glb-linear-abematv.akamaized.net/channel/fixture/playlist.m3u8",
                    "https://ds-glb-linear-abematv.akamaized.net/region?fixture=value",
                    "https://ds-linear-abematv.akamaized.net/region?fixture=value",
                    "https://ds-linear-abematv.akamaized.net/media", "https://ds-linear-abematv.akamaized.net:443/region",
                    "https://abema-tv.com/", "https://fakeabema-tv.com/", "https://new-api.abema-tv.com.other.invalid/"))
                    assertThrows(IllegalArgumentException::class.java) { route.open(URL(url)) }
            } finally { route.close() }
            route.close()
            assertEquals(1, created); assertEquals(created, retained); assertEquals(created, backend.closes)
            assertFalse(route.matchesProxyChallenge("127.0.0.1", route.proxyRealm))
            assertThrows(IOException::class.java) { route.open(URL("https://abema.tv/account")) }
        }
    }

    @Test fun cancelledSystemApprovalNeverAllocatesOrRetains() {
        var allocated = 0; var retained = 0
        val preparation = RoutePreparation().apply { cancel() }
        assertThrows(IOException::class.java) {
            RouteSession.createAbemaApprovalBrowser(null, preparation, ownBackend = { retained++; it },
                createSystemBackend = { allocated++; Backend() }) { _, _ ->
                allocated++; Backend()
            }
        }
        assertEquals(0, allocated); assertEquals(0, retained)
    }

    @Test fun nativeAccountPurposesKeepTheirNarrowerHosts() {
        for (selected in listOf(null, profile())) {
            val route = RouteSession.createAbemaAccountSession(selected) { _, security ->
                assertEquals("abema.tv,api.p-c3-e.abema-tv.com", security.allowedHosts); Backend()
            }
            try {
                assertEquals(selected == null, route.isSystem)
                assertEquals(selected != null, route.hasProxyBridge)
                assertFalse(route.isAbemaApprovalBridge)
                val connection = route.open(URL("https://abema.tv/api/auth/sessions"))
                assertTrue(connection is RoutedHttpsConnection)
                assertEquals(selected != null, connection.usingProxy()); connection.disconnect()
                assertThrows(IllegalArgumentException::class.java) { route.open(URL("https://auth.abema.tv/login/email")) }
                assertThrows(IllegalArgumentException::class.java) { route.open(URL("https://ds-glb-linear-abematv.akamaized.net/region")) }
                assertThrows(IllegalArgumentException::class.java) { route.open(URL("https://gateway.p-c2-x.abema-tv.com/web")) }
            }
            finally { route.close() }
        }
        val system = RouteSession.create(null) { _, _ -> error("System must not allocate") }
        try {
            assertTrue(system.isSystem)
            assertFalse(system.hasProxyBridge); assertFalse(system.isAbemaApprovalBridge)
            assertFalse(system.matchesProxyChallenge("127.0.0.1", ""))
            val connection = system.open(URL("https://example.invalid/"))
            assertFalse(connection.usingProxy()); connection.disconnect()
        } finally { system.close() }
        RouteSession.create(profile()) { _, security ->
            assertFalse(security.allowedHosts.split(',').contains("auth.abema.tv")); Backend()
        }.close()
    }

    @Test fun cancellationBeforeCreationNeverAllocatesOrFallsBack() {
        val preparation = RoutePreparation().apply { cancel() }
        assertThrows(IOException::class.java) {
            RouteSession.createAbemaApprovalBrowser(profile(), preparation, ownBackend = { error("Must not retain") }) { _, _ ->
                error("Must not allocate")
            }
        }
    }

    @Test fun backendOwnershipPrecedesCancellationAndInvalidPortRollback() {
        for (selected in listOf(null, profile())) {
            for (port in listOf(12345, 0, 65536)) {
                val preparation = RoutePreparation(); val allocated = Backend(port)
                var owned: RouteBackend? = null
                fun create(): RouteBackend {
                    if (port == 12345) preparation.cancel()
                    return allocated
                }
                assertThrows(IOException::class.java) {
                    RouteSession.createAbemaApprovalBrowser(selected, preparation, ownBackend = { backend ->
                        assertSame(allocated, backend)
                        object : RouteBackend {
                            override val proxyPort get() = backend.proxyPort
                            override fun close() = backend.close()
                        }.also { owned = it }
                    }, createSystemBackend = { create() }) { _, _ -> create() }
                }
                assertNotNull(owned); assertEquals(1, allocated.closes)
                assertTrue(preparation.cleanupConfirmed)
            }
        }
    }

    @Test fun failedRollbackLeavesRetainedOwnerAndUnconfirmedPreparation() {
        for (selected in listOf(null, profile())) {
            val preparation = RoutePreparation(); val allocated = Backend(0, failClose = true)
            var owned: RouteBackend? = null
            val error = assertThrows(IOException::class.java) {
                RouteSession.createAbemaApprovalBrowser(selected, preparation, ownBackend = { owned = it; it },
                    createSystemBackend = { allocated }) { _, _ -> allocated }
            }
            assertSame(allocated, owned); assertEquals(1, allocated.closes)
            assertFalse(preparation.cleanupConfirmed); assertTrue(preparation.isCancelled)
            assertNull(error.cause)
            assertEquals(if (selected == null) "System approval route could not be initialized" else "Imported route could not be initialized", error.message)
            assertFalse(error.toString().contains("private fixture"))
        }
    }

    @Test fun importedFailureNeverCallsSystemFactory() {
        var systemAllocations = 0
        val error = assertThrows(IOException::class.java) {
            RouteSession.createAbemaApprovalBrowser(profile(), createSystemBackend = { systemAllocations++; Backend() }) { _, _ ->
                throw IOException("private imported failure")
            }
        }
        assertEquals(0, systemAllocations); assertNull(error.cause)
        assertEquals("Imported route could not be initialized", error.message)
    }

    @Test fun systemFailureNeverCallsImportedFactoryOrRetainsUnallocatedBackend() {
        var importedAllocations = 0; var retained = 0
        val preparation = RoutePreparation()
        val error = assertThrows(IOException::class.java) {
            RouteSession.createAbemaApprovalBrowser(null, preparation, ownBackend = { retained++; it },
                createSystemBackend = { throw IOException("private System failure") }) { _, _ ->
                importedAllocations++; Backend()
            }
        }
        assertEquals(0, importedAllocations); assertEquals(0, retained)
        assertTrue(preparation.cleanupConfirmed); assertNull(error.cause)
        assertEquals("System approval route could not be initialized", error.message)
        assertFalse(error.toString().contains("private System failure"))
    }
}
