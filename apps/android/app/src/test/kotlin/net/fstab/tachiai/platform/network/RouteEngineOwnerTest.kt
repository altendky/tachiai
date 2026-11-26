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

    // #275 stage 5: renewal-job leases.
    private class JobFixture {
        val timers = mutableListOf<() -> Unit>()
        val events = mutableListOf<String>()
        val owner = RouteEngineOwner(idleMs = 30_000, log = { events.add(it) }) { _, action -> timers.add(action) }
        val shared = mutableListOf<FakeEngine>()
        val bound = mutableListOf<FakeEngine>()
        // Engines started and not yet closed, and the most ever alive at once.
        var live = 0; var maxLive = 0
        private fun started(engine: FakeEngine) = object : RouteEngine by engine {
            init { live++; maxLive = maxOf(maxLive, live) }
            override fun close() { engine.close(); live-- }
        }
        fun lease(configuration: String = "config-a", engine: () -> FakeEngine = { FakeEngine() }) =
            owner.lease(configuration, "key-a", security) { engine().also { shared.add(it) }.let(::started) }
        fun job(configuration: String = "config-a", engine: () -> FakeEngine = { FakeEngine() }) =
            owner.leaseForJob(configuration, "key-a", security) { engine().also { bound.add(it) }.let(::started) }
        fun fireTimers() { val due = timers.toList(); timers.clear(); due.forEach { it() } }
    }

    @Test fun aJobJoinsTheHeldSharedEngineInsteadOfBindingItsOwn() {
        val f = JobFixture()
        val feed = f.lease()
        val job = f.job()
        assertTrue(f.bound.isEmpty()); assertEquals(2, f.shared.single().leases); assertEquals(1, f.owner.engineCount())
        assertEquals(listOf("ENGINE_STARTED engines=1", "LEASE_OPENED leases=1", "JOB_LEASE_JOINED engine=SHARED",
            "LEASE_OPENED leases=2"), f.events)
        // The feed ends first: the shared engine stays for the job, then idles normally.
        feed.close(); assertTrue(f.timers.isEmpty())
        job.close(); assertEquals(0, f.shared.single().closes); assertEquals(1, f.timers.size)
        f.fireTimers()
        assertEquals(1, f.shared.single().closes); assertEquals(0, f.owner.engineCount())
    }

    @Test fun aJobJoinsAnIdleSharedEngineBeforeItRetires() {
        val f = JobFixture()
        f.lease().close()
        val job = f.job()
        f.fireTimers()
        assertTrue(f.bound.isEmpty()); assertEquals(0, f.shared.single().closes)
        job.close(); f.fireTimers()
        assertEquals(1, f.shared.single().closes)
    }

    @Test fun aJobWithNoHeldEngineStartsOneBoundEngineThatOtherJobLeasesJoin() {
        val f = JobFixture()
        val browser = f.job()
        val account = f.job()
        assertEquals(1, f.bound.size); assertEquals(2, f.bound.single().leases); assertTrue(f.shared.isEmpty())
        // Release ordering: the engine stays while any job lease holds it, then
        // retires at the last close without waiting for the idle timer.
        browser.close(); assertEquals(0, f.bound.single().closes)
        account.close(); account.close()
        assertEquals(1, f.bound.single().closes); assertTrue(f.timers.isEmpty()); assertEquals(0, f.owner.engineCount())
        assertEquals(listOf("JOB_ENGINE_STARTED engines=1", "LEASE_OPENED leases=1", "JOB_LEASE_JOINED engine=JOB",
            "LEASE_OPENED leases=2", "JOB_ENGINE_RETIRED reason=RELEASED engines=0"), f.events)
        f.job().close()
        assertEquals(2, f.bound.size); assertEquals(1, f.maxLive)
    }

    @Test fun anOrdinaryLeaseRetiresTheJobsBoundEngineInsteadOfStartingASecond() {
        val f = JobFixture()
        val job = f.job()
        val feed = f.lease()
        // The job's engine closed (revoking its lease) before the shared one started.
        assertEquals(1, f.bound.single().closes); assertEquals(1, f.shared.size); assertEquals(1, f.maxLive)
        assertEquals(listOf("JOB_ENGINE_STARTED engines=1", "LEASE_OPENED leases=1",
            "JOB_ENGINE_RETIRED reason=LEASE_REQUESTED engines=0", "ENGINE_STARTED engines=1", "LEASE_OPENED leases=1"), f.events)
        // The revoked job lease still closes once, confirmed, without touching the shared engine.
        job.close()
        assertEquals(1, f.bound.single().leaseCloses); assertEquals(1, f.bound.single().closes)
        assertEquals(0, f.shared.single().closes); assertEquals(1, f.owner.engineCount())
        // A later job lease joins the shared engine.
        val retry = f.job()
        assertEquals(1, f.bound.size); assertEquals(2, f.shared.single().leases)
        retry.close(); feed.close(); f.fireTimers()
        assertEquals(1, f.shared.single().closes); assertEquals(1, f.maxLive)
    }

    @Test fun anUnconfirmedRevocationBlocksTheIdentityForTheOrdinaryLeaseToo() {
        val f = JobFixture()
        f.job { FakeEngine(failClose = true) }
        assertThrows(IOException::class.java) { f.lease() }
        assertTrue(f.shared.isEmpty())
        assertThrows(IOException::class.java) { f.job() }
        assertEquals(1, f.bound.size)
        assertEquals("JOB_LEASE_REFUSED reason=CLEANUP_UNCONFIRMED", f.events.last())
    }

    @Test fun anUnconfirmedJobEngineCloseFailsTheLastCloseAndBlocksTheIdentity() {
        val f = JobFixture()
        val job = f.job { FakeEngine(failClose = true) }
        assertThrows(IOException::class.java) { job.close() }
        assertThrows(IOException::class.java) { job.close() }
        assertEquals(1, f.bound.single().closes); assertEquals("ENGINE_CLEANUP_UNCONFIRMED", f.events.last())
        assertThrows(IOException::class.java) { f.lease() }
        assertThrows(IOException::class.java) { f.job() }
        assertTrue(f.shared.isEmpty()); assertEquals(1, f.bound.size)
    }

    @Test fun anUnconfirmedJobEngineStartBlocksTheIdentity() {
        val f = JobFixture()
        assertThrows(RouteEngineOwner.CleanupUnconfirmed::class.java) {
            f.owner.leaseForJob("config-a", "key-a", security) { throw RouteEngineOwner.CleanupUnconfirmed() }
        }
        assertThrows(IOException::class.java) { f.lease() }
        assertThrows(IOException::class.java) { f.job() }
        assertTrue(f.shared.isEmpty() && f.bound.isEmpty())
        // A refused start that confirmed its cleanup leaves nothing behind.
        val other = RouteEngineOwner { _, _ -> }
        assertThrows(IOException::class.java) { other.leaseForJob("config-a", "key-a", security) { throw IOException("Refused") } }
        assertEquals(0, other.engineCount())
        other.lease("config-a", "key-a", security) { FakeEngine() }.close()
    }

    @Test fun aJobLeaseRefusesAnotherConfigurationInUseAndReplacesAnIdleOne() {
        val f = JobFixture()
        val feed = f.lease("config-a")
        assertThrows(IOException::class.java) { f.job("config-b") }
        assertTrue(f.bound.isEmpty()); assertEquals("JOB_LEASE_REFUSED reason=CONFLICT", f.events.last())
        feed.close()
        f.job("config-b").close()
        assertEquals(1, f.shared.single().closes); assertEquals(1, f.bound.single().closes); assertEquals(1, f.maxLive)
    }

    @Test fun aCancelledJobLeaseRetiresTheBoundEngineItStarted() {
        val f = JobFixture()
        assertThrows(IOException::class.java) { f.job { FakeEngine(failLease = true) } }
        assertEquals(1, f.bound.single().closes); assertEquals(0, f.owner.engineCount()); assertTrue(f.timers.isEmpty())
        assertEquals("JOB_ENGINE_RETIRED reason=RELEASED engines=0", f.events.last())
        // The identity is free again: the next start is a single engine.
        f.lease().close()
        assertEquals(1, f.shared.size); assertEquals(1, f.maxLive)
    }

    @Test fun jobLogNamesClosedEventsAndCountsOnly() {
        val f = JobFixture()
        f.job().close(); f.lease().also { f.job().close() }.close(); f.fireTimers()
        assertTrue(f.events.all { it.matches(Regex("[A-Z_]+( [a-z]+=[A-Z_0-9]+)*")) })
        assertTrue(f.events.none { it.contains("config") || it.contains("key") || it.contains("synthetic") })
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
