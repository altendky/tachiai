package net.fstab.tachiai.platform.network

import java.io.IOException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import net.fstab.tachiai.platform.diagnostics.FailureCategory
import net.fstab.tachiai.platform.diagnostics.FailureObservation
import net.fstab.tachiai.platform.diagnostics.FailureReporter
import net.fstab.tachiai.platform.diagnostics.FailureStage
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

class RouteSessionTest {
    @Suppress("UNCHECKED_CAST")
    private fun ownedConnections(route: RouteSession): MutableSet<RoutedHttpsConnection> =
        RouteSession::class.java.getDeclaredField("connections").apply { isAccessible = true }
            .get(route) as MutableSet<RoutedHttpsConnection>

    private fun ownResponse(connection: RoutedHttpsConnection, source: BufferedSource): Response {
        val response = Response.Builder().request(Request.Builder().url(connection.url).build())
            .protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(object : ResponseBody() {
                override fun contentType() = null
                override fun contentLength() = 1L
                override fun source() = source
            }).build()
        RoutedHttpsConnection::class.java.getDeclaredField("reply").apply { isAccessible = true }.set(connection, response)
        return response
    }

    @Test fun abemaMetadataPurposeUsesAnOwnedAnonymousClientAndOnlyItsHost() {
        val profile = parseConnectionProfile("http://proxy.example.test:3128".toByteArray())
        for (selected in listOf(null, profile)) {
            val backend = FakeBackend()
            val route = RouteSession.createAbemaCatalog(selected) { _, security ->
                assertEquals("api.abema.io", security.allowedHosts)
                backend
            }
            val connection = route.open(URL("https://api.abema.io/v1/channels"))
            assertTrue(connection is RoutedHttpsConnection)
            assertEquals(selected != null, connection.usingProxy())
            val field = RouteSession::class.java.getDeclaredField("client").apply { isAccessible = true }
            val client = field.get(route) as OkHttpClient
            assertSame(okhttp3.CookieJar.NO_COOKIES, client.cookieJar)
            assertSame(okhttp3.Authenticator.NONE, client.authenticator)
            if (selected == null) {
                assertSame(java.net.Proxy.NO_PROXY, client.proxy)
                assertSame(okhttp3.Authenticator.NONE, client.proxyAuthenticator)
            }
            assertFalse(client.followRedirects)
            assertFalse(client.retryOnConnectionFailure)
            listOf("abema.tv", "api.p-c3-e.abema-tv.com", "api.twitch.tv", "sub.api.abema.io", "api.abema.io.evil.invalid").forEach {
                assertThrows(IllegalArgumentException::class.java) { route.open(URL("https://$it/")) }
            }
            connection.disconnect(); route.close()
            assertEquals(if (selected == null) 0 else 1, backend.closes)
            assertThrows(IOException::class.java) { route.open(URL("https://api.abema.io/v1/channels")) }
        }
    }

    private class FakeBackend(override val proxyPort: Int = 12345, private val failCleanup: Boolean = false) : RouteBackend {
        var closes = 0
        override fun close() { closes++; if (failCleanup) error("private cleanup detail") }
    }

    @Test fun catalogPurposeConfinesBothSystemAndImportedRoutesWithoutExpandingPlayback() {
        val profile = parseConnectionProfile("http://proxy.example.test:3128".toByteArray())
        for (selected in listOf(null, profile)) {
            val backend = FakeBackend()
            var security: RouteProxySecurity? = null
            val route = RouteSession.createTwitchCatalog(selected) { _, parameters ->
                security = parameters; backend
            }
            try {
                if (selected != null) assertEquals("id.twitch.tv,api.twitch.tv", security?.allowedHosts)
                for (host in listOf("id.twitch.tv", "api.twitch.tv")) {
                    route.open(URL("https://$host/fixture")).disconnect() // No network.
                }
                for (host in listOf("example.invalid", "gql.twitch.tv", "abema.tv",
                    "api.twitch.tv.evil.invalid", "sub.api.twitch.tv")) {
                    assertThrows(IllegalArgumentException::class.java) { route.open(URL("https://$host/")) }
                }
            } finally { route.close() }
            assertEquals(if (selected == null) 0 else 1, backend.closes)
        }
        var legacy: RouteProxySecurity? = null
        RouteSession.create(profile) { _, security -> legacy = security; FakeBackend() }.close()
        assertFalse(checkNotNull(legacy).allowedHosts.split(',').contains("api.twitch.tv"))
        assertTrue(checkNotNull(legacy).allowedHosts.split(',').contains("gql.twitch.tv"))
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

    @Test fun creationAndBackendCleanupPreserveStagesWithoutChangingPublicErrors() {
        val observations = mutableListOf<FailureObservation>()
        val preparation = RoutePreparation(FailureReporter(observations::add))
        val profile = parseConnectionProfile("http://proxy.example.test:3128".toByteArray())
        val setup = assertThrows(IOException::class.java) {
            RouteSession.create(profile, preparation) { _, _ -> error("private-password") }
        }
        assertNull(setup.cause)
        assertEquals(listOf(FailureStage.ROUTE_CREATE), observations.map { it.stage })
        assertEquals(FailureCategory.ILLEGAL_STATE, observations.single().failure.category)

        val backend = FakeBackend(failCleanup = true)
        val route = RouteSession.create(profile, preparation) { _, _ -> backend }
        val cleanup = assertThrows(IOException::class.java) { route.close() }
        assertNull(cleanup.cause)
        assertEquals(listOf(FailureStage.ROUTE_CREATE, FailureStage.ROUTE_BACKEND_CLOSE), observations.map { it.stage })
        route.close()
        assertEquals(1, backend.closes)
        assertEquals(2, observations.size)
    }

    @Test fun disconnectAssertionErrorStillClosesOtherConnectionsAndBackendAndBlocksReuse() {
        val observations = mutableListOf<FailureObservation>()
        val preparation = RoutePreparation(FailureReporter(observations::add))
        val profile = parseConnectionProfile("http://proxy.example.test:3128".toByteArray())
        val backend = FakeBackend()
        val route = RouteSession.create(profile, preparation) { _, _ -> backend }
        val client = OkHttpClient()
        var otherClosed = false
        val failing = RoutedHttpsConnection(URL("https://example.invalid/"), client, { true }) {
            throw AssertionError("private-native-error")
        }
        val other = RoutedHttpsConnection(URL("https://example.invalid/"), client, { true }) { otherClosed = true }
        // Populate the owned set without opening sockets or adding a production-only test seam.
        val connections = ownedConnections(route)
        connections.add(failing)
        connections.add(other)
        val cleanup = assertThrows(IOException::class.java) { route.close() }
        assertEquals("Route cleanup failed", cleanup.message)
        assertTrue(otherClosed)
        assertEquals(1, backend.closes)
        assertEquals(setOf(failing), connections)
        assertEquals(listOf(FailureStage.ROUTE_DISCONNECT), observations.map { it.stage })
        assertEquals(FailureCategory.OTHER, observations.single().failure.category)
        assertThrows(IOException::class.java) { route.open(URL("https://example.invalid/")) }
        route.close()
        assertEquals(setOf(failing), connections)
        assertEquals(1, backend.closes)
    }

    @Test fun heldResponseBodyRetainsActualConnectionAndReplyAfterRouteCloseTimeout() {
        val route = RouteSession.createAbemaGuestSchedule(null)
        val connections = ownedConnections(route)
        val connection = RoutedHttpsConnection(URL("https://abema.tv/fixture"), OkHttpClient(), { true }) { connections.remove(it) }
        connections.add(connection)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val closes = AtomicInteger()
        val response = ownResponse(connection, object : ForwardingSource(Buffer().writeUtf8("x")) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                return super.read(sink, byteCount)
            }
            override fun close() { closes.incrementAndGet(); super.close() }
        }.buffer())
        val input = connection.inputStream
        val readFailure = AtomicReference<Throwable?>()
        val worker = Thread { try { input.read() } catch (error: Throwable) { readFailure.set(error) } }
        try {
            worker.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertThrows(IOException::class.java) { route.close() }
            assertEquals(setOf(connection), connections); assertEquals(0, closes.get())
            assertSame(response, RoutedHttpsConnection::class.java.getDeclaredField("reply").apply { isAccessible = true }.get(connection))
            route.close(); assertEquals(setOf(connection), connections); assertEquals(0, closes.get())
            release.countDown(); worker.join(5000); assertFalse(worker.isAlive)
            assertTrue(readFailure.get() is IOException)
            connection.disconnect()
            assertTrue(connections.isEmpty()); assertEquals(1, closes.get())
        } finally { release.countDown(); worker.join(5000); connection.disconnect(); route.close() }
    }

    @Test fun throwingResponseBodyRetainsActualConnectionAndReplyUntilDisconnectIsConfirmed() {
        val route = RouteSession.createAbemaGuestSchedule(null)
        val connections = ownedConnections(route)
        val connection = RoutedHttpsConnection(URL("https://abema.tv/fixture"), OkHttpClient(), { true }) { connections.remove(it) }
        connections.add(connection)
        var refuses = true; var closes = 0
        val body = Buffer().writeUtf8("x")
        val response = ownResponse(connection, object : BufferedSource by body {
            override fun close() { closes++; if (refuses) throw AssertionError("Fixture body close failure"); body.close() }
        })
        connection.inputStream
        try {
            assertThrows(IOException::class.java) { route.close() }
            assertEquals(setOf(connection), connections); assertEquals(1, closes)
            assertSame(response, RoutedHttpsConnection::class.java.getDeclaredField("reply").apply { isAccessible = true }.get(connection))
            route.close(); assertEquals(setOf(connection), connections); assertEquals(1, closes)
            refuses = false; connection.disconnect()
            assertTrue(connections.isEmpty()); assertEquals(2, closes)
        } finally { refuses = false; connection.disconnect(); route.close() }
    }

    @Test fun rollbackAssertionErrorDoesNotReplaceOriginalSetupFailureAndRemainsBlocked() {
        val observations = mutableListOf<FailureObservation>()
        val preparation = RoutePreparation(FailureReporter(observations::add))
        val backend = object : RouteBackend {
            override val proxyPort = 0
            override fun close() { throw AssertionError("private-native-error") }
        }
        val profile = parseConnectionProfile("http://proxy.example.test:3128".toByteArray())
        val setup = assertThrows(IOException::class.java) { RouteSession.create(profile, preparation) { _, _ -> backend } }
        assertEquals("Imported route could not be initialized", setup.message)
        assertNull(setup.cause)
        assertFalse(preparation.cleanupConfirmed)
        assertEquals(listOf(FailureStage.ROUTE_CREATE, FailureStage.ROUTE_CREATE_ROLLBACK), observations.map { it.stage })
        assertEquals(FailureCategory.OTHER, observations.last().failure.category)
    }
}
