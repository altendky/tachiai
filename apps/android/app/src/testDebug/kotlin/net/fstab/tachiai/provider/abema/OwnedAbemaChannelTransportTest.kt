package net.fstab.tachiai.provider.abema

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import net.fstab.tachiai.platform.network.RouteBackend
import net.fstab.tachiai.platform.network.RouteSession
import net.fstab.tachiai.platform.network.parseConnectionProfile
import org.junit.Assert.*
import org.junit.Test

// Real route ownership with an in-memory backend and HTTP transport; no native
// tunnel, provider request or external connection is created.
class OwnedAbemaChannelTransportTest {
    private class Backend : RouteBackend {
        val closes = AtomicInteger()
        override val proxyPort = 12345
        override fun close() { closes.incrementAndGet() }
    }
    private class Transport(val failCancel: Boolean = false) : AbemaChannelTransport {
        val fetching = CountDownLatch(1); val returnFetch = CountDownLatch(1)
        val cancelling = CountDownLatch(1); val returnCancel = CountDownLatch(1)
        val cancelled = CountDownLatch(1); val closes = AtomicInteger()
        override fun fetch(): AbemaChannelResponse {
            fetching.countDown(); check(returnFetch.await(5, TimeUnit.SECONDS))
            return AbemaChannelResponse(200, emptyList())
        }
        override fun cancelActiveRequest() {
            cancelling.countDown()
            try { check(returnCancel.await(5, TimeUnit.SECONDS)); if (failCancel) throw IOException("Fixture cancellation failure") }
            finally { cancelled.countDown() }
        }
        override fun close() { closes.incrementAndGet() }
    }
    private fun owned(backend: Backend, transport: Transport, waitMs: Long = 1000,
        poison: () -> Unit = {}) = OwnedAbemaChannelTransport(
        parseConnectionProfile("http://fixture:private@proxy.example.test:3128".toByteArray()), { true },
        createRoute = { profile, preparing -> RouteSession.createAbemaCatalog(profile, preparing) { _, _ -> backend } },
        createTransport = { _, _ -> transport }, onCleanupFailure = poison, cleanupWaitMs = waitMs)
    private fun retained(resource: Any): Boolean {
        val owners = OwnedAbemaChannelTransport::class.java.getDeclaredField("retained")
            .apply { isAccessible = true }.get(null) as Iterable<*>
        return owners.any { entry -> (entry as Collection<*>).any { it === resource } }
    }

    @Test fun cancellationWorkerFinishesBeforeDependentRouteIsReleased() {
        val backend = Backend(); val transport = Transport(); val owned = owned(backend, transport)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> { try { owned.fetch(); false } catch (_: IOException) { true } }
            assertTrue(transport.fetching.await(2, TimeUnit.SECONDS))
            owned.close(); assertTrue(transport.cancelling.await(2, TimeUnit.SECONDS))
            transport.returnFetch.countDown()
            assertThrows(TimeoutException::class.java) { result.get(100, TimeUnit.MILLISECONDS) }
            assertEquals(0, backend.closes.get())
            transport.returnCancel.countDown()
            assertTrue(result.get(2, TimeUnit.SECONDS))
            assertEquals(1, backend.closes.get()); assertEquals(1, transport.closes.get())
            assertTrue(owned.cleanupConfirmed); assertFalse(retained(transport))
        } finally { transport.returnFetch.countDown(); transport.returnCancel.countDown(); worker.shutdownNow() }
    }

    @Test fun timedOutCancellationRetainsOwnersAndLateCompletionCannotClearPoison() {
        val backend = Backend(); val transport = Transport(); val poisons = AtomicInteger()
        val owned = owned(backend, transport, 30) { poisons.incrementAndGet() }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> { try { owned.fetch(); false } catch (_: IOException) { true } }
            assertTrue(transport.fetching.await(2, TimeUnit.SECONDS))
            owned.close(); assertTrue(transport.cancelling.await(2, TimeUnit.SECONDS))
            transport.returnFetch.countDown(); assertTrue(result.get(2, TimeUnit.SECONDS))
            assertEquals(0, backend.closes.get()); assertTrue(retained(transport)); assertFalse(owned.cleanupConfirmed)
            assertEquals(1, poisons.get())
            transport.returnCancel.countDown(); assertTrue(transport.cancelled.await(2, TimeUnit.SECONDS))
            assertFalse(owned.cleanupConfirmed); assertEquals(0, backend.closes.get())
            owned.close(); assertThrows(IllegalStateException::class.java) { owned.fetch() }
            assertEquals(1, poisons.get())
        } finally { transport.returnFetch.countDown(); transport.returnCancel.countDown(); worker.shutdownNow() }
    }

    @Test fun failedCancellationKeepsActualHttpAndRouteOwners() {
        val backend = Backend(); val transport = Transport(failCancel = true); val poisons = AtomicInteger()
        val owned = owned(backend, transport, poison = { poisons.incrementAndGet() })
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Boolean> { try { owned.fetch(); false } catch (_: IOException) { true } }
            assertTrue(transport.fetching.await(2, TimeUnit.SECONDS))
            owned.close(); assertTrue(transport.cancelling.await(2, TimeUnit.SECONDS))
            transport.returnCancel.countDown(); transport.returnFetch.countDown()
            assertTrue(result.get(2, TimeUnit.SECONDS))
            assertEquals(0, backend.closes.get()); assertTrue(retained(transport)); assertTrue(retained(owned))
            assertFalse(owned.cleanupConfirmed); assertEquals(1, poisons.get())
        } finally { transport.returnFetch.countDown(); transport.returnCancel.countDown(); worker.shutdownNow() }
    }
}
