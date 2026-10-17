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

// Route objects only; no connection is dispatched or provider contacted.
class AbemaGuestEpisodeRouteTest {
    private val endpoint = URL(AbemaGuestBootstrapPolicy.EPISODE)
    private fun profile() = parseConnectionProfile("http://fixture:private@proxy.example.test:3128".toByteArray())
    private class Backend(private val failClose: Boolean = false) : RouteBackend {
        override val proxyPort = 12345
        var closes = 0
        override fun close() { closes++; if (failClose) error("Fixture cleanup") }
    }

    @Test fun guestEpisodePurposeUsesOwnedClientsAndOnlyGuestAndCurrentAPIHosts() {
        for (selected in listOf(null, profile())) {
            val backend = Backend()
            var creations = 0
            val route = RouteSession.createAbemaGuestEpisode(selected) { actual, security ->
                creations++
                assertSame(selected, actual)
                assertEquals("abema.tv,api.p-c3-e.abema-tv.com", security.allowedHosts)
                backend
            }
            try {
                for (url in listOf(URL(AbemaGuestBootstrapPolicy.GUEST), endpoint)) {
                    val connection = route.open(url)
                    assertTrue(connection is RoutedHttpsConnection)
                    assertEquals(selected != null, connection.usingProxy())
                    connection.disconnect()
                }
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
                for (host in listOf("api.abema.io", "auth.abema.tv", "license.p-c3-e.abema-tv.com",
                    "media.p-c3-e.abema-tv.com", "api.p-c3-e.abema-tv.com.evil.invalid", "other.invalid"))
                    assertThrows(IllegalArgumentException::class.java) { route.open(URL("https://$host/")) }
            } finally { route.close() }
            route.close(); assertEquals(if (selected == null) 0 else 1, backend.closes)
            assertThrows(IOException::class.java) { route.open(endpoint) }
        }
        // Episode mode does not broaden original guest downloads/runtime hosts.
        RouteSession.createAbemaGuest(null).use { guest ->
            assertThrows(IllegalArgumentException::class.java) { guest.open(endpoint) }
        }
    }

    @Test fun failedCancelledAndUncertainCleanupNeverFallsBackOrAllowsReuse() {
        val selected = profile()
        assertThrows(IOException::class.java) {
            RouteSession.createAbemaGuestEpisode(selected) { _, _ -> error("Fixture preparation") }
        }
        val before = RoutePreparation().apply { cancel() }
        for (choice in listOf(null, selected))
            assertThrows(IOException::class.java) {
                RouteSession.createAbemaGuestEpisode(choice, before) { _, _ -> error("Must not create backend") }
            }
        val during = RoutePreparation(); val cancelled = Backend()
        assertThrows(IOException::class.java) {
            RouteSession.createAbemaGuestEpisode(selected, during) { _, _ -> during.cancel(); cancelled }
        }
        assertEquals(1, cancelled.closes)
        val failed = Backend(failClose = true)
        val route = RouteSession.createAbemaGuestEpisode(selected) { _, _ -> failed }
        route.open(endpoint)
        assertThrows(IOException::class.java) { route.close() }
        route.close(); assertEquals(1, failed.closes)
        assertThrows(IOException::class.java) { route.open(endpoint) }
        assertThrows(IOException::class.java) { route.open(URL(AbemaGuestBootstrapPolicy.GUEST)) }
    }
}
