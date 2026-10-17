package net.fstab.tachiai.platform.network

import java.io.IOException
import java.net.Proxy
import java.net.URL
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

// Construction only: no provider/proxy connection or protected record access.
class AbemaAccountCollectionRouteTest {
    private fun profile() = parseConnectionProfile("http://fixture:private@proxy.example.test:3128".toByteArray())
    private class Backend : RouteBackend {
        override val proxyPort = 12345
        var closes = 0
        override fun close() { closes++ }
    }

    @Test fun systemAndImportedCollectionsUseOnlyTheClosedCredentialFreeOrigin() {
        for (selected in listOf(null, profile())) {
            val backend = Backend(); var creations = 0
            val route = RouteSession.createAbemaAccountCollections(selected) { actual, security ->
                creations++; assertSame(selected, actual)
                assertEquals("api.p-c3-e.abema-tv.com", security.allowedHosts); backend
            }
            val url = URL("https://api.p-c3-e.abema-tv.com/v1/mylist?limit=20")
            try {
                val connection = route.open(url)
                assertTrue(connection is RoutedHttpsConnection)
                assertEquals(selected != null, connection.usingProxy()); connection.disconnect()
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
                for (host in listOf("abema.tv", "api.abema.io", "auth.abema.tv", "license.p-c3-e.abema-tv.com",
                    "media.p-c3-e.abema-tv.com", "api.p-c3-e.abema-tv.com.evil.invalid"))
                    assertThrows(IllegalArgumentException::class.java) { route.open(URL("https://$host/")) }
                for (other in listOf("http://api.p-c3-e.abema-tv.com/", "https://user@api.p-c3-e.abema-tv.com/",
                    "https://api.p-c3-e.abema-tv.com:444/", "https://api.p-c3-e.abema-tv.com/#private"))
                    assertThrows(IllegalArgumentException::class.java) { route.open(URL(other)) }
            } finally { route.close() }
            route.close()
            assertEquals(if (selected == null) 0 else 1, creations)
            assertEquals(if (selected == null) 0 else 1, backend.closes)
            assertThrows(IOException::class.java) { route.open(url) }
        }
    }

    @Test fun cancelledPreparationCannotAllocateOrFallBackAndClosesItsReturnedBackend() {
        val selected = profile(); val before = RoutePreparation().apply { cancel() }
        for (choice in listOf(null, selected)) assertThrows(IOException::class.java) {
            RouteSession.createAbemaAccountCollections(choice, before) { _, _ -> error("Must not allocate") }
        }
        val during = RoutePreparation(); val backend = Backend()
        assertThrows(IOException::class.java) {
            RouteSession.createAbemaAccountCollections(selected, during) { _, _ -> during.cancel(); backend }
        }
        assertEquals(1, backend.closes)
        assertThrows(IOException::class.java) {
            RouteSession.createAbemaAccountCollections(selected) { _, _ -> error("Synthetic preparation failure") }
        }
    }
}
