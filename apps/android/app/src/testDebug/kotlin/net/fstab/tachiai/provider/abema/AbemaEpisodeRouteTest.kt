package net.fstab.tachiai.provider.abema

import java.io.IOException
import java.net.Proxy
import java.net.URL
import net.fstab.tachiai.platform.network.*
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

// Connection objects/configuration only. No connect/read/provider request.
class AbemaEpisodeRouteTest {
    private val endpoint = URL("https://api.p-c3-e.abema-tv.com/v1/video/programs/394-72_s10_p8510")
    private fun profile() = parseConnectionProfile("http://fixture:private@proxy.example.test:3128".toByteArray())
    private class Backend(private val failClose: Boolean = false) : RouteBackend {
        override val proxyPort = 12345
        var closes = 0
        override fun close() { closes++; if (failClose) error("Fixture cleanup") }
    }

    @Test fun episodePurposeUsesOwnedAnonymousClientForSystemAndExactImportedHostOnly() {
        for (selected in listOf(null, profile())) {
            val backend = Backend()
            var creations = 0
            val route = RouteSession.createAbemaEpisodeCatalog(selected) { actual, security ->
                creations++
                assertSame(selected, actual)
                assertEquals("api.p-c3-e.abema-tv.com", security.allowedHosts)
                backend
            }
            try {
                val connection = route.open(endpoint)
                assertTrue(connection is RoutedHttpsConnection)
                assertEquals(selected != null, connection.usingProxy())
                val field = RouteSession::class.java.getDeclaredField("client").apply { isAccessible = true }
                val client = field.get(route) as OkHttpClient
                assertSame(CookieJar.NO_COOKIES, client.cookieJar)
                assertSame(Authenticator.NONE, client.authenticator)
                assertFalse(client.followRedirects); assertFalse(client.followSslRedirects)
                assertFalse(client.retryOnConnectionFailure)
                if (selected == null) {
                    assertTrue(route.isSystem); assertEquals(0, creations)
                    assertSame(Proxy.NO_PROXY, client.proxy)
                    assertSame(Authenticator.NONE, client.proxyAuthenticator)
                } else {
                    assertFalse(route.isSystem); assertEquals(1, creations)
                    assertEquals(Proxy.Type.HTTP, client.proxy?.type())
                    assertNotSame(Authenticator.NONE, client.proxyAuthenticator)
                }
                for (host in listOf("api.abema.io", "abema.tv", "auth.abema.tv", "license.p-c3-e.abema-tv.com",
                    "media.p-c3-e.abema-tv.com", "api.p-c3-e.abema-tv.com.evil.invalid",
                    "sub.api.p-c3-e.abema-tv.com", "other.invalid")) {
                    assertThrows(IllegalArgumentException::class.java) { route.open(URL("https://$host/")) }
                }
                for (url in listOf("http://api.p-c3-e.abema-tv.com/", "https://fixture@api.p-c3-e.abema-tv.com/",
                    "https://api.p-c3-e.abema-tv.com/#fragment", "https://api.p-c3-e.abema-tv.com:444/")) {
                    assertThrows(IllegalArgumentException::class.java) { route.open(URL(url)) }
                }
                connection.disconnect()
            } finally { route.close() }
            route.close()
            assertEquals(if (selected == null) 0 else 1, backend.closes)
            assertThrows(IOException::class.java) { route.open(endpoint) }
        }
        // The candidate factory does not widen historical anonymous channels.
        RouteSession.createAbemaCatalog(null).use { historical ->
            assertThrows(IllegalArgumentException::class.java) { historical.open(endpoint) }
        }
        RouteSession.createAbemaGuest(null).use { guest ->
            assertThrows(IllegalArgumentException::class.java) { guest.open(endpoint) }
        }
    }

    @Test fun failedOrCancelledImportedPreparationCannotBecomeSystemAndRollsBackLateBackend() {
        val selected = profile()
        var creations = 0
        assertThrows(IOException::class.java) {
            RouteSession.createAbemaEpisodeCatalog(selected) { _, _ -> creations++; error("Fixture preparation") }
        }
        assertEquals(1, creations)
        val before = RoutePreparation().apply { cancel() }
        for (choice in listOf(null, selected)) {
            assertThrows(IOException::class.java) {
                RouteSession.createAbemaEpisodeCatalog(choice, before) { _, _ -> creations++; Backend() }
            }
        }
        assertEquals(1, creations)
        val during = RoutePreparation()
        val backend = Backend()
        assertThrows(IOException::class.java) {
            RouteSession.createAbemaEpisodeCatalog(selected, during) { actual, security ->
                assertSame(selected, actual); assertEquals("api.p-c3-e.abema-tv.com", security.allowedHosts)
                during.cancel(); backend
            }
        }
        assertEquals(1, backend.closes)
    }

    @Test fun uncertainCleanupStillClosesOnceAndPreventsReuseOrSystemFallback() {
        val backend = Backend(failClose = true)
        val route = RouteSession.createAbemaEpisodeCatalog(profile()) { _, _ -> backend }
        route.open(endpoint) // Undispatched connection also belongs to this route.
        assertThrows(IOException::class.java) { route.close() }
        route.close(); assertEquals(1, backend.closes)
        assertThrows(IOException::class.java) { route.open(endpoint) }
        assertThrows(IOException::class.java) { route.open(URL("https://api.abema.io/v1/channels")) }
    }
}
