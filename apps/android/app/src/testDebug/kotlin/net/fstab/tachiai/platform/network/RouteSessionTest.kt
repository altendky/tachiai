package net.fstab.tachiai.platform.network

import java.io.IOException
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class RouteSessionTest {
    @Test fun systemIsExplicitAndClosedSessionCannotFallback() {
        val route = RouteSession.create(null)
        assertTrue(route.isSystem)
        assertEquals(0, route.proxyPort)
        assertFalse(route.matchesProxyChallenge("127.0.0.1", "anything"))
        val connection = route.open(URL("https://example.invalid/")) // Creation only; no network.
        assertFalse(connection.usingProxy())
        connection.disconnect()
        route.close(); route.close()
        assertThrows(IOException::class.java) { route.open(URL("https://example.invalid/")) }
        assertFalse(route.toString().contains("password"))
    }
}
