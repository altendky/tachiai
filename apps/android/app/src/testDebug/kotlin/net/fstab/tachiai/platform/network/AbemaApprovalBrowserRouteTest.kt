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

    @Test fun importedRouteAdmitsOnlyApprovalOriginsAndOwnsItsNativeClient() {
        val selected = profile(); val backend = Backend(); var created = 0; var retained = 0
        val route = RouteSession.createAbemaApprovalBrowser(selected, ownBackend = {
            retained++; assertSame(backend, it); it
        }) { actual, security ->
            created++; assertSame(selected, actual)
            assertEquals(AbemaApprovalBrowserPolicy.HOSTS, security.allowedHosts); backend
        }
        try {
            assertFalse(route.isSystem)
            for (url in listOf("https://abema.tv/account/change-from-device?fixture=code",
                "https://auth.abema.tv:443/login/email", "https://api.p-c3-e.abema-tv.com/v1/users/fixture")) {
                val connection = route.open(URL(url))
                assertTrue(connection is RoutedHttpsConnection)
                assertTrue(connection.usingProxy()); connection.disconnect()
            }
            val field = RouteSession::class.java.getDeclaredField("client").apply { isAccessible = true }
            val client = field.get(route) as OkHttpClient
            assertSame(CookieJar.NO_COOKIES, client.cookieJar)
            assertSame(Authenticator.NONE, client.authenticator)
            assertFalse(client.followRedirects); assertFalse(client.followSslRedirects)
            assertFalse(client.retryOnConnectionFailure)
            assertEquals(Proxy.Type.HTTP, client.proxy?.type())
            assertTrue(route.matchesProxyChallenge("127.0.0.1", route.proxyRealm))
            assertFalse(route.matchesProxyChallenge("auth.abema.tv", route.proxyRealm))
            assertFalse(route.matchesProxyChallenge("127.0.0.1", "other-realm"))
            for (url in listOf("http://abema.tv/", "https://user@abema.tv/", "https://abema.tv:0443/",
                "https://abema.tv:444/", "https://abema.tv/#fixture", "https://abema.tv/a/%2e%2e/login",
                "https://auth.abema.tv.evil.invalid/", "https://api.abema.io/", "https://id.twitch.tv/",
                "https://license.p-c3-e.abema-tv.com/", "https://image.p-c2-x.abema-tv.com/"))
                assertThrows(IllegalArgumentException::class.java) { route.open(URL(url)) }
        } finally { route.close() }
        route.close()
        assertEquals(1, created); assertEquals(created, retained); assertEquals(created, backend.closes)
        assertFalse(route.matchesProxyChallenge("127.0.0.1", route.proxyRealm))
        assertThrows(IOException::class.java) { route.open(URL("https://abema.tv/account")) }
    }

    @Test fun systemApprovalIsRefusedBeforeBackendAllocationOrRetention() {
        var allocated = 0; var retained = 0
        for (preparation in listOf(RoutePreparation(), RoutePreparation().apply { cancel() })) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                RouteSession.createAbemaApprovalBrowser(null, preparation, ownBackend = { retained++; it }) { _, _ ->
                    allocated++; Backend()
                }
            }
            assertEquals("ABEMA approval browser requires a saved route", error.message)
        }
        assertEquals(0, allocated); assertEquals(0, retained)
    }

    @Test fun nativeAccountPurposesKeepTheirNarrowerHosts() {
        for (selected in listOf(null, profile())) {
            val route = RouteSession.createAbemaAccountSession(selected) { _, security ->
                assertEquals("abema.tv,api.p-c3-e.abema-tv.com", security.allowedHosts); Backend()
            }
            try {
                val connection = route.open(URL("https://abema.tv/api/auth/sessions"))
                assertTrue(connection is RoutedHttpsConnection)
                assertEquals(selected != null, connection.usingProxy()); connection.disconnect()
                assertThrows(IllegalArgumentException::class.java) { route.open(URL("https://auth.abema.tv/login/email")) }
            }
            finally { route.close() }
        }
        val system = RouteSession.create(null) { _, _ -> error("System must not allocate") }
        try {
            assertTrue(system.isSystem)
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
        for (port in listOf(12345, 0, 65536)) {
            val preparation = RoutePreparation(); val allocated = Backend(port)
            var owned: RouteBackend? = null
            assertThrows(IOException::class.java) {
                RouteSession.createAbemaApprovalBrowser(profile(), preparation, ownBackend = { backend ->
                    assertSame(allocated, backend)
                    object : RouteBackend {
                        override val proxyPort get() = backend.proxyPort
                        override fun close() = backend.close()
                    }.also { owned = it }
                }) { _, _ ->
                    if (port == 12345) preparation.cancel()
                    allocated
                }
            }
            assertNotNull(owned); assertEquals(1, allocated.closes)
            assertTrue(preparation.cleanupConfirmed)
        }
    }

    @Test fun failedRollbackLeavesRetainedOwnerAndUnconfirmedPreparation() {
        val preparation = RoutePreparation(); val allocated = Backend(0, failClose = true)
        var owned: RouteBackend? = null
        val error = assertThrows(IOException::class.java) {
            RouteSession.createAbemaApprovalBrowser(profile(), preparation, ownBackend = { owned = it; it }) { _, _ -> allocated }
        }
        assertSame(allocated, owned); assertEquals(1, allocated.closes)
        assertFalse(preparation.cleanupConfirmed); assertTrue(preparation.isCancelled)
        assertNull(error.cause); assertEquals("Imported route could not be initialized", error.message)
        assertFalse(error.toString().contains("private fixture"))
    }
}
