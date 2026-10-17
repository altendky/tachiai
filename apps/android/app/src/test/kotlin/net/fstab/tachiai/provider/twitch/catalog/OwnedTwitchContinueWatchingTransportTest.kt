package net.fstab.tachiai.provider.twitch.catalog

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import net.fstab.tachiai.platform.diagnostics.FailureReporter
import net.fstab.tachiai.platform.network.*
import org.junit.Assert.*
import org.junit.Test

class OwnedTwitchContinueWatchingTransportTest {
    private class Hostile : Error() {
        override val message: String get() = throw AssertionError("Do not inspect private message")
        override fun toString(): String = throw AssertionError("Do not inspect private detail")
    }
    private class Backend(val portFailure: Boolean = false, val closeFailure: Boolean = false) : RouteBackend {
        val closes = AtomicInteger()
        override val proxyPort: Int get() { if (portFailure) throw Hostile(); return 12345 }
        override fun close() { closes.incrementAndGet(); if (closeFailure) throw Hostile() }
    }
    private val profile get() = parseConnectionProfile("http://proxy.example.test:3128".toByteArray())
    private fun success() = TwitchContinueWatchingResponse(200, TwitchContinueWatchingPage("9000", emptyList()))
    private fun failure(category: TwitchContinueWatchingFailure, action: () -> Unit) =
        assertEquals(category, assertThrows(TwitchContinueWatchingException::class.java, action).category)
    @Test fun actualRoutePurposeAndInnerCleanupPrecedeBackendReleaseAndPublishOnce() {
        val instance = UUID.randomUUID().toString(); val backend = Backend(); var innerCloses = 0
        var innerClosed = false; var routeCloses = 0
        val t = OwnedTwitchContinueWatchingTransport(instance, profile, { true },
            createBackend = { _, security, _ -> assertEquals("gql.twitch.tv", security.allowedHosts); backend },
            createTransport = { open, gate -> object : TwitchContinueWatchingTransport {
                override val cleanupConfirmed get() = innerClosed
                override fun fetch(accessToken: String, expectedUserId: String, nowEpochMs: Long): TwitchContinueWatchingResponse {
                    check(gate()); open(java.net.URL(TWITCH_CONTINUE_WATCHING_URL)).disconnect(); return success()
                }
                override fun cancelActiveRequest() = Unit
                override fun close() { innerClosed = true; innerCloses++ }
            } }, closeRoute = { route -> assertTrue(innerClosed); routeCloses++; route.close() })
        assertEquals(200, t.fetch("grant-token", "9000", 0).status)
        assertTrue(t.cleanupConfirmed); assertEquals(1, innerCloses); assertEquals(1, routeCloses); assertEquals(1, backend.closes.get())
        t.close(); t.close(); assertEquals(1, backend.closes.get()); assertFalse(TwitchContinueWatchingAdmission.poisoned(instance))
    }
    @Test fun cancelledConstructionRegistersRawBackendBeforeRollbackAndNeverConstructsHttp() {
        val instance = UUID.randomUUID().toString(); val backend = Backend(); var factories = 0
        val t = OwnedTwitchContinueWatchingTransport(instance, profile, { true },
            createBackend = { _, _, preparation -> preparation.cancel(); backend },
            createTransport = { _, _ -> factories++; error("Cancelled factory") })
        failure(TwitchContinueWatchingFailure.CANCELLED) { t.fetch("grant-token", "9000", 0) }
        assertEquals(1, backend.closes.get()); assertEquals(0, factories); assertTrue(t.cleanupConfirmed)
    }
    @Test fun fatalProxyPortAndFailedRollbackRetainActualBackendAndPoisonExactInstance() {
        for (port in listOf(true, false)) {
            val instance = UUID.randomUUID().toString(); val backend = Backend(portFailure = port, closeFailure = !port)
            val t = OwnedTwitchContinueWatchingTransport(instance, profile, { true }, createBackend = { _, _, preparation ->
                if (!port) preparation.cancel(); backend
            })
            failure(TwitchContinueWatchingFailure.CLEANUP_FAILED) { t.fetch("grant-token", "9000", 0) }
            assertEquals(if (port) 0 else 1, backend.closes.get()); assertFalse(t.cleanupConfirmed)
            assertTrue(TwitchContinueWatchingAdmission.poisoned(instance))
            val run = t.javaClass.getDeclaredField("active").apply { isAccessible = true }.get(t)!!
            assertSame(backend, run.javaClass.getDeclaredField("backend").apply { isAccessible = true }.get(run))
            t.close(); assertEquals(if (port) 0 else 1, backend.closes.get())
        }
    }
    @Test fun unconfirmedHttpRetainsRouteAndBackendWithoutTryingAnEarlyBackendClose() {
        val instance = UUID.randomUUID().toString(); val backend = Backend(); var routeCloses = 0
        val t = OwnedTwitchContinueWatchingTransport(instance, profile, { true }, createBackend = { _, _, _ -> backend },
            createTransport = { _, _ -> object : TwitchContinueWatchingTransport {
                override val cleanupConfirmed = false
                override fun fetch(accessToken: String, expectedUserId: String, nowEpochMs: Long) = success()
                override fun cancelActiveRequest() = Unit
                override fun close() = Unit
            } }, closeRoute = { routeCloses++; it.close() }, cleanupWaitMs = 20)
        failure(TwitchContinueWatchingFailure.CLEANUP_FAILED) { t.fetch("grant-token", "9000", 0) }
        assertEquals(0, routeCloses); assertEquals(0, backend.closes.get()); assertFalse(t.cleanupConfirmed)
        assertTrue(TwitchContinueWatchingAdmission.poisoned(instance))
    }
    @Test fun heldInnerCloseTimesOutWithoutReleasingRouteAndLateReceiptCannotUnpoisonAdmission() {
        val instance = UUID.randomUUID().toString(); val backend = Backend()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val t = OwnedTwitchContinueWatchingTransport(instance, profile, { true }, createBackend = { _, _, _ -> backend },
            createTransport = { _, _ -> object : TwitchContinueWatchingTransport {
                override val cleanupConfirmed = true
                override fun fetch(accessToken: String, expectedUserId: String, nowEpochMs: Long) = success()
                override fun cancelActiveRequest() = Unit
                override fun close() { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            } }, cleanupWaitMs = 20)
        try {
            failure(TwitchContinueWatchingFailure.CLEANUP_FAILED) { t.fetch("grant-token", "9000", 0) }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); assertEquals(0, backend.closes.get())
            release.countDown(); assertTrue(TwitchContinueWatchingAdmission.poisoned(instance)); assertFalse(t.cleanupConfirmed)
        } finally { release.countDown() }
    }
    @Test fun pauseCancelsHeldFetchPromptlyAndRejectsOwnerLossEvenAfterCleanClose() {
        val instance = UUID.randomUUID().toString(); val backend = Backend()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val t = OwnedTwitchContinueWatchingTransport(instance, profile, { true }, createBackend = { _, _, _ -> backend },
            createTransport = { _, _ -> object : TwitchContinueWatchingTransport {
                override val cleanupConfirmed = true
                override fun fetch(accessToken: String, expectedUserId: String, nowEpochMs: Long): TwitchContinueWatchingResponse {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return success()
                }
                override fun cancelActiveRequest() { release.countDown() }
                override fun close() = Unit
            } })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<TwitchContinueWatchingFailure> {
                try { t.fetch("grant-token", "9000", 0); error("Late result") } catch (e: TwitchContinueWatchingException) { e.category }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); t.close()
            assertEquals(TwitchContinueWatchingFailure.CANCELLED, result.get(5, TimeUnit.SECONDS))
            assertEquals(1, backend.closes.get()); assertTrue(t.cleanupConfirmed)
        } finally { release.countDown(); executor.shutdownNow() }
    }
    @Test fun heldOwnerReadBeforeOpenCannotDispatchAfterCloseEvenWhenItReturnsStaleTrue() {
        val instance = UUID.randomUUID().toString(); val backend = Backend()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val hold = AtomicBoolean(); val opened = AtomicInteger()
        val t = OwnedTwitchContinueWatchingTransport(instance, profile, {
            if (hold.get()) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            true
        }, createBackend = { _, _, _ -> backend }, createTransport = { open, _ -> object : TwitchContinueWatchingTransport {
            override val cleanupConfirmed = true
            override fun fetch(accessToken: String, expectedUserId: String, nowEpochMs: Long): TwitchContinueWatchingResponse {
                hold.set(true)
                val connection = open(java.net.URL(TWITCH_CONTINUE_WATCHING_URL))
                opened.incrementAndGet(); connection.disconnect()
                return success()
            }
            override fun cancelActiveRequest() = Unit
            override fun close() = Unit
        } })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<TwitchContinueWatchingFailure> {
                try { t.fetch("grant-token", "9000", 0); error("Late result") }
                catch (e: TwitchContinueWatchingException) { e.category }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); t.close(); release.countDown()
            assertEquals(TwitchContinueWatchingFailure.CANCELLED, result.get(5, TimeUnit.SECONDS))
            assertEquals(0, opened.get()); assertEquals(1, backend.closes.get()); assertTrue(t.cleanupConfirmed)
        } finally { release.countDown(); executor.shutdownNow() }
    }
    @Test fun heldOwnerOrReadCannotPostponeCloseUncertaintyAndLateWorkerRetainsBackend() {
        val instance = UUID.randomUUID().toString(); val backend = Backend()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val uncertain = CountDownLatch(1)
        val t = OwnedTwitchContinueWatchingTransport(instance, profile, { true }, diagnostics = FailureReporter({ uncertain.countDown() }),
            createBackend = { _, _, _ -> backend }, createTransport = { _, _ -> object : TwitchContinueWatchingTransport {
                override val cleanupConfirmed = true
                override fun fetch(accessToken: String, expectedUserId: String, nowEpochMs: Long): TwitchContinueWatchingResponse {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return success()
                }
                override fun cancelActiveRequest() = Unit
                override fun close() = Unit
            } }, cleanupWaitMs = 20)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<TwitchContinueWatchingFailure> {
                try { t.fetch("grant-token", "9000", 0); error("Late result") } catch (e: TwitchContinueWatchingException) { e.category }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); t.close()
            assertTrue(uncertain.await(5, TimeUnit.SECONDS)); assertTrue(TwitchContinueWatchingAdmission.poisoned(instance))
            assertFalse(t.cleanupConfirmed); assertEquals(0, backend.closes.get())
            release.countDown(); assertEquals(TwitchContinueWatchingFailure.CLEANUP_FAILED, result.get(5, TimeUnit.SECONDS))
            assertEquals(0, backend.closes.get()); assertTrue(TwitchContinueWatchingAdmission.poisoned(instance))
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
