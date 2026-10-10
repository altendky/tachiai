package net.fstab.tachiai.provider.twitch.catalog

import java.io.IOException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.platform.diagnostics.*
import org.junit.Assert.*
import org.junit.Test

class TwitchHelixRouteTest {
    private fun profile() = parseConnectionProfile("http://proxy.example.test:3128".toByteArray())
    private fun request() = TwitchHelixRequest.following("123")
    private class FakeTransport(private val action: () -> TwitchHelixResponse = { TwitchHelixResponse(200) }) : TwitchHelixTransport {
        var closed = false
        override fun execute(accessToken: String, request: TwitchHelixRequest) = action()
        override fun close() { closed = true }
    }

    @Test fun everyMetadataRequestOwnsAClosedRouteAndRejectsArbitraryUrls() {
        var closes = 0
        val raw = mutableListOf<FakeTransport>()
        val routes = mutableListOf<RouteSession>()
        val owned = OwnedTwitchHelixTransport(profile(), { true },
            createRoute = { selected, preparation -> RouteSession.createTwitchCatalog(selected, preparation) { _, security ->
                assertEquals("id.twitch.tv,api.twitch.tv", security.allowedHosts)
                object : RouteBackend { override val proxyPort = 12345; override fun close() { closes++ } }
            }.also(routes::add) },
            createTransport = { open, gate -> FakeTransport {
                assertTrue(gate())
                listOf("https://api.twitch.tv/helix/clips?id=123", "https://api.twitch.tv/helix/users?access_token=private",
                    "https://api.twitch.tv/helix/users?id=123#fragment", "https://gql.twitch.tv/",
                    "http://api.twitch.tv/helix/users?id=123").forEach {
                    assertThrows(IllegalStateException::class.java) { open(URL(it)) }
                }
                open(URL("https://api.twitch.tv/helix/users?id=123")).disconnect() // No network.
                TwitchHelixResponse(200)
            }.also(raw::add) })
        owned.execute("fixture-access", request()); owned.execute("fixture-access", request())
        assertEquals(2, closes)
        assertTrue(raw.all { it.closed })
        routes.forEach { assertThrows(IOException::class.java) { it.open(URL("https://api.twitch.tv/helix/users?id=123")) } }
        owned.close()
    }

    @Test fun failedImportedPreparationDoesNotCreateProviderTransportOrFallback() {
        var calls = 0
        val owned = OwnedTwitchHelixTransport(profile(), { true },
            createRoute = { _, _ -> throw IOException("fixture unavailable") },
            createTransport = { _, _ -> calls++; FakeTransport() })
        assertThrows(IOException::class.java) { owned.execute("fixture-access", request()) }
        assertEquals(0, calls)
        owned.close()
    }

    @Test fun unavailableOwnerWithNoResolvedProfileCannotStartSystemRequests() {
        var routes = 0
        val owned = OwnedTwitchHelixTransport(null, { false },
            createRoute = { _, _ -> routes++; RouteSession.createTwitchCatalog(null) })
        assertThrows(IOException::class.java) { owned.execute("fixture-access", request()) }
        assertEquals(0, routes)
        owned.close()
    }

    @Test fun admissionLossDuringResponseRejectsResultAndStillClosesResources() {
        var admitted = true
        val raw = FakeTransport { admitted = false; TwitchHelixResponse(200) }
        val route = RouteSession.createTwitchCatalog(null)
        val owned = OwnedTwitchHelixTransport(null, { admitted },
            createRoute = { _, _ -> route }, createTransport = { _, _ -> raw })
        assertThrows(IOException::class.java) { owned.execute("fixture-access", request()) }
        assertTrue(raw.closed)
        assertThrows(IOException::class.java) { route.open(URL("https://api.twitch.tv/helix/users?id=123")) }
        owned.close()
    }

    @Test fun closeCancelsPreparationAndRejectsLateRouteWithoutPublishing() {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(1)
        var calls = 0
        var failure: Throwable? = null
        val owned = OwnedTwitchHelixTransport(null, { true },
            createRoute = { _, preparation ->
                preparation.onCancel { cancelled.countDown() }.use {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                }
                RouteSession.createTwitchCatalog(null)
            }, createTransport = { _, _ -> calls++; FakeTransport() })
        val worker = thread { try { owned.execute("fixture-access", request()) } catch (error: Throwable) { failure = error } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        owned.close()
        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
        release.countDown(); worker.join(5000)
        assertFalse(worker.isAlive)
        assertTrue(failure is IOException)
        assertEquals(0, calls)
    }

    @Test fun repeatedCancellationCoalescesInterruptionAndRejectsLateResponse() {
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        var cancellations = 0
        var failure: Throwable? = null
        val raw = object : TwitchHelixTransport {
            override fun execute(accessToken: String, request: TwitchHelixRequest): TwitchHelixResponse {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return TwitchHelixResponse(200)
            }
            override fun cancelActiveRequest() { cancellations++; interrupted.countDown() }
            override fun close() = Unit
        }
        val owned = OwnedTwitchHelixTransport(null, { true }, createTransport = { _, _ -> raw })
        val worker = thread { try { owned.execute("fixture-access", request()) } catch (error: Throwable) { failure = error } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        repeat(100) { owned.cancelActiveRequest() }
        assertTrue(interrupted.await(5, TimeUnit.SECONDS))
        release.countDown(); worker.join(5000)
        assertFalse(worker.isAlive)
        assertEquals(1, cancellations)
        assertTrue(failure is IOException)
        owned.close()
    }

    @Test fun uncertainCleanupPermanentlyBlocksTheBinding() {
        var creates = 0
        var cleanupFailures = 0
        val owned = OwnedTwitchHelixTransport(profile(), { true },
            createRoute = { selected, preparation -> creates++
                RouteSession.createTwitchCatalog(selected, preparation) { _, _ -> object : RouteBackend {
                    override val proxyPort = 12345
                    override fun close() { error("fixture cleanup") }
                } }
            }, createTransport = { _, _ -> FakeTransport() }, onCleanupFailure = { cleanupFailures++ })
        val failure = assertThrows(IOException::class.java) { owned.execute("fixture-access", request()) }
        assertEquals("Catalog route cleanup failed", failure.message)
        assertThrows(IllegalStateException::class.java) { owned.execute("fixture-access", request()) }
        assertEquals(1, creates)
        assertEquals(1, cleanupFailures)
    }

    @Test fun asynchronousInterruptionFailureIsRecordedAndBlocksSubsequentRequests() {
        val entered = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val observations = mutableListOf<FailureObservation>()
        var failure: Throwable? = null
        val raw = object : TwitchHelixTransport {
            override fun execute(accessToken: String, request: TwitchHelixRequest): TwitchHelixResponse {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return TwitchHelixResponse(200)
            }
            override fun cancelActiveRequest() { error("private fixture failure") }
            override fun close() = Unit
        }
        val owned = OwnedTwitchHelixTransport(null, { true }, createTransport = { _, _ -> raw },
            onCleanupFailure = { failed.countDown() }, diagnostics = FailureReporter(observations::add))
        val worker = thread { try { owned.execute("fixture-access", request()) } catch (error: Throwable) { failure = error } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        owned.cancelActiveRequest()
        assertTrue(failed.await(5, TimeUnit.SECONDS))
        assertEquals(FailureStage.CATALOG_CLOSE, observations.single().stage)
        assertFalse(observations.toString().contains("private fixture failure"))
        assertThrows(IllegalStateException::class.java) { owned.execute("fixture-access", request()) }
        release.countDown(); worker.join(5000)
        assertFalse(worker.isAlive)
        assertTrue(failure is IOException)
        owned.close()
    }
}
