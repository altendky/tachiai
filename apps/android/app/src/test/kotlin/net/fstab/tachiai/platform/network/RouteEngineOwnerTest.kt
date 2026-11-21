package net.fstab.tachiai.platform.network

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class RouteEngineOwnerTest {
    private class FakeEngine(val failClose: Boolean = false, val failLease: Boolean = false) : RouteEngine {
        var leases = 0; var leaseCloses = 0; var closes = 0
        override fun lease(security: RouteProxySecurity): RouteBackend {
            if (failLease) throw IOException("Lease refused")
            leases++
            return object : RouteBackend {
                override val proxyPort = 40_000 + leases
                override fun close() { leaseCloses++ }
            }
        }
        override fun close() { closes++; if (failClose) throw IOException("Cleanup unconfirmed") }
    }
    private class Fixture {
        val timers = mutableListOf<() -> Unit>()
        val owner = RouteEngineOwner(idleMs = 30_000) { _, action -> timers.add(action) }
        val engines = mutableListOf<FakeEngine>()
        fun lease(configuration: String = "config-a", conflict: String = "key-a", engine: () -> FakeEngine = { FakeEngine() }) =
            owner.lease(configuration, conflict, security) { engine().also { engines.add(it) } }
        fun fireTimers() { val due = timers.toList(); timers.clear(); due.forEach { it() } }
    }
    private companion object {
        val security = RouteProxySecurity("synthetic_user_0001", "synthetic_password_01", "synthetic_realm_0001", "example.test")
    }

    @Test fun lifecycleLogNamesClosedEventsAndCountsOnly() {
        val timers = mutableListOf<() -> Unit>()
        val events = mutableListOf<String>()
        val owner = RouteEngineOwner(idleMs = 30_000, log = { events.add(it) }) { _, action -> timers.add(action) }
        val a = owner.lease("config-a", "key-a", security) { FakeEngine() }
        val b = owner.lease("config-a", "key-a", security) { FakeEngine() }
        a.close(); b.close(); timers.toList().forEach { it() }
        assertEquals(listOf("ENGINE_STARTED engines=1", "LEASE_OPENED leases=1", "LEASE_OPENED leases=2",
            "ENGINE_RETIRED engines=0"), events)
        assertTrue(events.none { it.contains("config") || it.contains("key") || it.contains("synthetic") })
    }

    @Test fun leasesOfOneConfigurationShareOneEngineUntilIdleRetirement() {
        val f = Fixture()
        val a = f.lease(); val b = f.lease()
        assertEquals(1, f.engines.size); assertEquals(2, f.engines.single().leases)
        assertNotEquals(a.proxyPort, b.proxyPort)
        a.close(); assertTrue(f.timers.isEmpty())
        b.close(); b.close()
        assertEquals(2, f.engines.single().leaseCloses); assertEquals(0, f.engines.single().closes)
        assertEquals(1, f.timers.size)
        f.fireTimers()
        assertEquals(1, f.engines.single().closes); assertEquals(0, f.owner.engineCount())
        f.lease().close()
        assertEquals(2, f.engines.size)
    }

    @Test fun aLeaseBeforeTheIdleDeadlineKeepsTheWarmEngine() {
        val f = Fixture()
        f.lease().close()
        val again = f.lease()
        f.fireTimers()
        assertEquals(1, f.engines.size); assertEquals(0, f.engines.single().closes)
        again.close(); f.fireTimers()
        assertEquals(1, f.engines.single().closes)
    }

    @Test fun anotherConfigurationOfTheSameIdentityWaitsForRetirement() {
        val f = Fixture()
        val held = f.lease("config-a")
        assertThrows(IOException::class.java) { f.lease("config-b") }
        assertEquals(1, f.engines.size)
        held.close()
        // The idle old engine retires at once instead of running beside the new one.
        val replaced = f.lease("config-b")
        assertEquals(1, f.engines[0].closes); assertEquals(2, f.engines.size)
        replaced.close()
        f.lease("config-c", "key-c").close()
        assertEquals(3, f.engines.size)
    }

    @Test fun unconfirmedEngineCleanupBlocksTheIdentityInsteadOfStartingAnother() {
        val f = Fixture()
        f.lease { FakeEngine(failClose = true) }.close()
        f.fireTimers()
        assertEquals(1, f.engines.single().closes)
        assertThrows(IOException::class.java) { f.lease() }
        assertEquals(1, f.engines.size)
        f.lease(conflict = "key-other").close()
        assertEquals(2, f.engines.size)
    }

    @Test fun aRefusedLeaseDoesNotLeakItsCount() {
        val f = Fixture()
        assertThrows(IOException::class.java) { f.lease { FakeEngine(failLease = true) } }
        assertEquals(1, f.timers.size)
        f.fireTimers()
        assertEquals(1, f.engines.single().closes); assertEquals(0, f.owner.engineCount())
    }

    @Test fun concurrentLeasesCreateOneEngine() {
        val created = AtomicInteger(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val owner = RouteEngineOwner(idleMs = 30_000) { _, _ -> }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<RouteBackend> {
                owner.lease("config-a", "key-a", security) {
                    created.incrementAndGet(); entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); FakeEngine()
                }
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val second = executor.submit<RouteBackend> { owner.lease("config-a", "key-a", security) { created.incrementAndGet(); FakeEngine() } }
            release.countDown()
            first.get(3, TimeUnit.SECONDS).close(); second.get(3, TimeUnit.SECONDS).close()
            assertEquals(1, created.get())
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
