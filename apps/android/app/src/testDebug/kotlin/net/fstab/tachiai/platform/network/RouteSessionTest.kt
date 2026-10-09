package net.fstab.tachiai.platform.network

import java.io.IOException
import java.net.URL
import org.junit.Assert.*
import org.junit.Test

class RouteSessionTest {
    private class FakeBackend(override val proxyPort: Int = 12345, private val failCleanup: Boolean = false) : RouteBackend {
        var closes = 0
        override fun close() { closes++; if (failCleanup) error("private cleanup detail") }
    }

    @Test fun systemIsExplicitAndClosedSessionCannotFallback() {
        val route = RouteSession.create(null) { _, _ -> error("System route must not initialize a backend") }
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

    @Test fun importedSessionUsesBackendContractAndKeepsRuntimeSecretsPrivate() {
        val profile = parseConnectionProfile("http://user:private-password@proxy.example.test:3128".toByteArray())
        val backend = FakeBackend()
        var security: RouteProxySecurity? = null
        val route = RouteSession.create(profile) { selected, parameters ->
            assertSame(profile, selected)
            security = parameters
            backend
        }
        try {
            assertFalse(route.isSystem)
            assertEquals(12345, route.proxyPort)
            assertTrue(route.matchesProxyChallenge("127.0.0.1", route.proxyRealm))
            assertFalse(route.matchesProxyChallenge("localhost", route.proxyRealm))
            assertFalse(route.matchesProxyChallenge("127.0.0.1", "wrong"))
            val parameters = checkNotNull(security)
            assertTrue(listOf(parameters.username, parameters.password, parameters.realm).all { it.length >= 16 })
            assertEquals(3, listOf(parameters.username, parameters.password, parameters.realm).distinct().size)
            assertFalse(parameters.toString().contains(parameters.password))
            assertFalse(route.toString().contains("private-password"))
            listOf("http://example.invalid/", "https://user@example.invalid/", "https://example.invalid/#secret",
                "https://example.invalid:444/").forEach { url ->
                assertThrows(IllegalArgumentException::class.java) { route.open(URL(url)) }
            }
            val connection = route.open(URL("https://example.invalid/"))
            assertTrue(connection.usingProxy()) // No connect/read or network request.
        } finally { route.close() }
        route.close()
        assertEquals(1, backend.closes)
        assertFalse(route.matchesProxyChallenge("127.0.0.1", route.proxyRealm))
        assertThrows(IOException::class.java) { route.open(URL("https://example.invalid/")) }
    }

    @Test fun invalidBackendPortsAreClosedAndNeverFallBack() {
        listOf(0, -1, 65536).forEach { port ->
            val backend = FakeBackend(port)
            val error = assertThrows(IOException::class.java) {
                RouteSession.create(parseConnectionProfile("http://proxy.example.test:3128".toByteArray())) { _, _ -> backend }
            }
            assertEquals(1, backend.closes)
            assertNull(error.cause)
            assertEquals("Imported route could not be initialized", error.message)
        }
    }

    @Test fun backendCreationAndCleanupErrorsAreRedacted() {
        val profile = parseConnectionProfile("http://proxy.example.test:3128".toByteArray())
        val error = assertThrows(IOException::class.java) {
            RouteSession.create(profile) { _, _ -> error("private-password") }
        }
        assertNull(error.cause)
        assertFalse(error.toString().contains("private-password"))
        val backend = FakeBackend(failCleanup = true)
        val route = RouteSession.create(profile) { _, _ -> backend }
        val cleanup = assertThrows(IOException::class.java) { route.close() }
        assertEquals("Route cleanup failed", cleanup.message)
        assertNull(cleanup.cause)
        route.close()
        assertEquals(1, backend.closes)
        assertThrows(IOException::class.java) { route.open(URL("https://example.invalid/")) }
    }
}
