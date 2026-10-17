package net.fstab.tachiai.provider.twitch.catalog

import java.io.IOException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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

    @Test fun FailedHttpCloseRetainsTheDependentBackendInsteadOfReleasingItsRoute() {
        var backendCloses = 0
        var httpCloses = 0
        var cleanupFailures = 0
        var creates = 0
        val raw = object : TwitchHelixTransport {
            override fun execute(accessToken: String, request: TwitchHelixRequest) = TwitchHelixResponse(200)
            override fun close() { httpCloses++; throw IOException("fixture-private-http-close") }
        }
        val owned = OwnedTwitchHelixTransport(profile(), { true },
            createRoute = { selected, preparation ->
                creates++
                RouteSession.createTwitchCatalog(selected, preparation) { _, _ -> object : RouteBackend {
                    override val proxyPort = 12345
                    override fun close() { backendCloses++ }
                } }
            }, createTransport = { _, _ -> raw }, onCleanupFailure = { cleanupFailures++ })
        val failure = assertThrows(IOException::class.java) { owned.execute("fixture-access", request()) }
        assertFalse(failure.toString().contains("fixture-private"))
        assertEquals(1, httpCloses)
        assertEquals(0, backendCloses)
        assertEquals(1, cleanupFailures)
        owned.close()
        assertThrows(IllegalStateException::class.java) { owned.execute("fixture-access", request()) }
        assertEquals(1, creates)
        assertEquals(0, backendCloses)
    }

    @Test fun HeldCancellationMustSettleBeforeBackendReleaseAndLateSuccessCannotUnpoison() {
        val instanceId = UUID.randomUUID().toString()
        val entered = CountDownLatch(1); val reply = CountDownLatch(1)
        val cancelling = CountDownLatch(1); val release = CountDownLatch(1); val cancelled = CountDownLatch(1)
        val backendCloses = AtomicInteger(); val failure = AtomicReference<Throwable?>()
        val raw = object : TwitchHelixTransport {
            override fun execute(accessToken: String, request: TwitchHelixRequest): TwitchHelixResponse {
                entered.countDown(); check(reply.await(5, TimeUnit.SECONDS)); return TwitchHelixResponse(200)
            }
            override fun cancelActiveRequest() {
                cancelling.countDown()
                try { check(release.await(5, TimeUnit.SECONDS)) } finally { cancelled.countDown() }
            }
            override fun close() = Unit
        }
        val owned = OwnedTwitchHelixTransport(profile(), { true }, instanceId = instanceId, cleanupWaitMs = 20,
            createTransport = { _, _ -> raw }, createBackend = { _, _, _ -> object : RouteBackend {
                override val proxyPort = 12345
                override fun close() { backendCloses.incrementAndGet() }
            } })
        val worker = thread { try { owned.execute("fixture-access", request()) } catch (error: Throwable) { failure.set(error) } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            owned.cancelActiveRequest(); assertTrue(cancelling.await(5, TimeUnit.SECONDS))
            reply.countDown(); worker.join(5000)
            assertFalse(worker.isAlive)
            assertTrue(failure.get() is IOException)
            assertEquals(0, backendCloses.get())
            assertFalse(owned.cleanupConfirmed)
            assertTrue(TwitchContinueWatchingAdmission.poisoned(instanceId))
            release.countDown(); assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            owned.close()
            assertFalse(owned.cleanupConfirmed)
            assertEquals(0, backendCloses.get())
        } finally {
            reply.countDown(); release.countDown(); worker.join(5000)
            assertTrue(cancelled.await(5, TimeUnit.SECONDS)); assertFalse(worker.isAlive)
        }
    }

    @Test fun PoisonedInstanceRejectsFreshFacadeWhileAnotherInstanceStillCleansNormally() {
        val instanceId = UUID.randomUUID().toString(); var backendCloses = 0; var creates = 0
        val owned = OwnedTwitchHelixTransport(profile(), { true }, instanceId = instanceId,
            createTransport = { _, _ -> object : TwitchHelixTransport {
                override fun execute(accessToken: String, request: TwitchHelixRequest) = TwitchHelixResponse(200)
                override fun close() = throw IOException("fixture-private-close")
            } }, createBackend = { _, _, _ -> object : RouteBackend {
                override val proxyPort = 12345
                override fun close() { backendCloses++ }
            } })
        assertThrows(IOException::class.java) { owned.execute("fixture-access", request()) }
        assertEquals(0, backendCloses)
        val fresh = OwnedTwitchHelixTransport(null, { true }, instanceId = instanceId,
            createTransport = { _, _ -> creates++; FakeTransport() })
        assertThrows(IllegalStateException::class.java) { fresh.execute("fixture-access", request()) }
        assertEquals(0, creates)
        val other = OwnedTwitchHelixTransport(null, { true }, instanceId = UUID.randomUUID().toString(),
            createTransport = { _, _ -> creates++; FakeTransport() })
        assertEquals(200, other.execute("fixture-access", request()).status)
        assertTrue(other.cleanupConfirmed)
        assertEquals(1, creates)
        fresh.close(); other.close(); owned.close()
    }

    @Test fun ConstructorRollbackRetainsCapturedBackendOnCloseFailureOrFatalPortRead() {
        for (fatal in listOf(false, true)) {
            val instanceId = UUID.randomUUID().toString(); var closes = 0; var transports = 0
            val owned = OwnedTwitchHelixTransport(profile(), { true }, instanceId = instanceId,
                createTransport = { _, _ -> transports++; FakeTransport() }, createBackend = { _, _, _ -> object : RouteBackend {
                    override val proxyPort: Int get() {
                        if (fatal) throw AssertionError("fixture-native-port")
                        throw IllegalStateException("fixture-private-port")
                    }
                    override fun close() { closes++; throw IOException("fixture-private-rollback") }
                } })
            val failure = assertThrows(Throwable::class.java) { owned.execute("fixture-access", request()) }
            assertTrue(if (fatal) failure is AssertionError else failure is IOException)
            assertEquals(0, transports)
            assertEquals(if (fatal) 0 else 1, closes)
            assertFalse(owned.cleanupConfirmed)
            assertTrue(TwitchContinueWatchingAdmission.poisoned(instanceId))
            owned.close()
            assertEquals(if (fatal) 0 else 1, closes)
        }
    }

    @Test fun asynchronousInterruptionFailureIsRecordedAndBlocksSubsequentRequests() {
        val entered = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val diagnosed = CountDownLatch(1)
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
            onCleanupFailure = { failed.countDown() }, diagnostics = FailureReporter(sink = {
                observations.add(it); diagnosed.countDown()
            }))
        val worker = thread { try { owned.execute("fixture-access", request()) } catch (error: Throwable) { failure = error } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            owned.cancelActiveRequest()
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            assertTrue(diagnosed.await(5, TimeUnit.SECONDS))
            assertEquals(FailureStage.CATALOG_CLOSE, observations.single().stage)
            assertFalse(observations.toString().contains("private fixture failure"))
            assertThrows(IllegalStateException::class.java) { owned.execute("fixture-access", request()) }
            release.countDown(); worker.join(5000)
            assertFalse(worker.isAlive)
            assertTrue(failure is IOException)
        } finally {
            release.countDown(); worker.join(5000); owned.close()
            assertFalse(worker.isAlive)
        }
    }
}
