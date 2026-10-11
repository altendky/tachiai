package net.fstab.tachiai.platform.network

import java.io.IOException
import java.net.Proxy
import java.net.URL
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

// Connection creation only; these fixtures never connect to a provider or proxy.
class AbemaAccountRouteTest {
    private fun profile() = parseConnectionProfile("http://fixture:private@proxy.example.test:3128".toByteArray())
    private class Backend : RouteBackend {
        override val proxyPort = 12345
        var closes = 0
        override fun close() { closes++ }
    }
    private fun create(bootstrap: Boolean, selected: ConnectionProfile?, preparation: RoutePreparation = RoutePreparation(),
        backend: (ConnectionProfile, RouteProxySecurity) -> RouteBackend,
    ) = if (bootstrap) RouteSession.createAbemaAccountBootstrap(selected, preparation, backend)
        else RouteSession.createAbemaAccountSession(selected, preparation, backend)

    @Test fun bothPurposesOwnCredentialFreeClosedSystemAndImportedTransports() {
        for (bootstrap in listOf(false, true)) for (selected in listOf(null, profile())) {
            val backend = Backend(); var creations = 0
            val route = create(bootstrap, selected) { actual, security ->
                creations++; assertSame(selected, actual)
                assertEquals("abema.tv,api.p-c3-e.abema-tv.com", security.allowedHosts); backend
            }
            try {
                for (url in listOf("https://abema.tv/api/auth/login/guest", "https://abema.tv/api/auth/oauth/token",
                    "https://api.p-c3-e.abema-tv.com/v1/users/FIXTURE_USER")) {
                    val connection = route.open(URL(url))
                    assertTrue(connection is RoutedHttpsConnection)
                    assertEquals(selected != null, connection.usingProxy()); connection.disconnect()
                }
                val field = RouteSession::class.java.getDeclaredField("client").apply { isAccessible = true }
                val client = field.get(route) as OkHttpClient
                assertSame(CookieJar.NO_COOKIES, client.cookieJar)
                assertSame(Authenticator.NONE, client.authenticator)
                assertFalse(client.followRedirects); assertFalse(client.followSslRedirects)
                assertFalse(client.retryOnConnectionFailure)
                if (selected == null) {
                    assertSame(Proxy.NO_PROXY, client.proxy)
                    assertSame(Authenticator.NONE, client.proxyAuthenticator)
                } else assertEquals(Proxy.Type.HTTP, client.proxy?.type())
                for (host in listOf("api.abema.io", "auth.abema.tv", "license.p-c3-e.abema-tv.com",
                    "media.p-c3-e.abema-tv.com", "other.abema.tv", "api.p-c3-e.abema-tv.com.evil.invalid"))
                    assertThrows(IllegalArgumentException::class.java) { route.open(URL("https://$host/")) }
                for (url in listOf("http://abema.tv/", "https://user@abema.tv/", "https://abema.tv:444/", "https://abema.tv/#private"))
                    assertThrows(IllegalArgumentException::class.java) { route.open(URL(url)) }
            } finally { route.close() }
            route.close()
            assertEquals(if (selected == null) 0 else 1, creations)
            assertEquals(if (selected == null) 0 else 1, backend.closes)
            assertThrows(IOException::class.java) { route.open(URL("https://abema.tv/api/auth/oauth/token")) }
        }
    }

    @Test fun cancellationClosesPreparedBackendsWithoutAnotherPurposeOrFallback() {
        for (bootstrap in listOf(false, true)) {
            val selected = profile(); val before = RoutePreparation().apply { cancel() }
            for (choice in listOf(null, selected)) assertThrows(IOException::class.java) {
                create(bootstrap, choice, before) { _, _ -> error("Must not allocate") }
            }
            val during = RoutePreparation(); val backend = Backend()
            assertThrows(IOException::class.java) {
                create(bootstrap, selected, during) { _, _ -> during.cancel(); backend }
            }
            assertEquals(1, backend.closes)
            assertThrows(IOException::class.java) {
                create(bootstrap, selected) { _, _ -> error("Synthetic preparation failure") }
            }
        }
    }
}
