package net.fstab.tachiai.provider.abema

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class AbemaChannelCatalogStartupTest {
    private val instanceId = "12345678-1234-1234-1234-123456789abc"
    private val routeId = "12345678-1234-1234-1234-123456789abd"
    private val entry = CatalogEntry(CatalogResource(ProviderId("abema"), "channel", "fixture-channel", CatalogIntent.CHANNEL), "Fixture channel")
    private inner class Fixture {
        val instance = ProviderInstance(instanceId, PrototypeService.ABEMA, "ABEMA", setup = ProviderSetup(SourceRouteChoice.system,
            fallbackRoutes = listOf(SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, routeId, "Second route"))))
        val profile = parseConnectionProfile("http://user:fixture-secret@proxy.example.test:3128".toByteArray())
        val pool = captureProviderRoutePool(instanceId, PrototypeService.ABEMA, { listOf(instance) }) { mapOf(routeId to profile) }
        var now = 1000L; var active = true
        val progress = mutableListOf<ProviderStartupProgress>()
        val startup = ProviderRouteStartup(pool, 46000, { now }, { active }, progress::add,
            scheduleDeadline = { _, _ -> AutoCloseable {} })
        val created = mutableListOf<Catalog>()
        fun candidate(owner: ProviderEffectiveRoute, attempt: ProviderStartupAttemptContext, work: Catalog.() -> CatalogResult<CatalogPage>): AbemaChannelStartupCandidate {
            val observation = AbemaChannelStartupObservation()
            observation.bindCurrent { active && attempt.canContinue() }
            val catalog = Catalog(observation, work)
            observation.own { catalog.cleanup }
            created += catalog
            assertEquals(pool.candidates[created.lastIndex].proof, owner.proof)
            return AbemaChannelStartupCandidate(catalog, observation)
        }
    }
    private inner class Catalog(val observation: AbemaChannelStartupObservation,
        val work: Catalog.() -> CatalogResult<CatalogPage>) : ProviderCatalog {
        override val providerId = ProviderId("abema"); override val instanceId = this@AbemaChannelCatalogStartupTest.instanceId
        var browses = 0; var closes = 0; var cleanup = true; var closeThrows = false
        @Volatile var closeHook: () -> Unit = {}
        override fun capabilities() = CatalogCapabilities(browse = CatalogAccess.AVAILABLE)
        override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> { browses++; return work() }
        override fun lookup(input: String) = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
        override fun refresh(resource: CatalogResource) = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
        override fun resolve(resource: CatalogResource) = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
        override fun close() { closes++; closeHook(); if (closeThrows) error("Synthetic close failure") }
        fun success(): CatalogResult<CatalogPage> { observation.response(200); return CatalogResult.Value(CatalogPage(listOf(entry), "fixture-cursor")) }
        fun transport(evidence: ProviderTransportEvidence): CatalogResult<CatalogPage> {
            observation.failure(evidence); return CatalogResult.Failure(CatalogFailure.TEMPORARY)
        }
    }
    @Test fun firstRealChannelReadFailsThenCleanSecondCandidateRetainsItsExactFirstPageAndCursor() {
        val f = Fixture()
        val result = prepareAbemaChannelCatalogStartup(f.startup) { owner, attempt ->
            if (f.created.isNotEmpty()) assertEquals(1, f.created.first().closes)
            f.candidate(owner, attempt) {
                if (owner.choice.mode == SourceRouteMode.SYSTEM) transport(ProviderTransportEvidence(ProviderTransportFailure.CONNECT)) else success()
            }
        } as ProviderStartupResult.Ready
        try {
            assertEquals(routeId, result.owner.choice.connectionId); assertTrue(f.startup.admitted)
            assertEquals(2, f.created.size); assertEquals(listOf(1, 1), f.created.map { it.browses })
            f.now = 100000 // Startup deadline does not expire the retained winner.
            val page = (result.value.browse(CatalogQuery()) as CatalogResult.Value).value
            assertEquals(listOf(entry), page.entries); assertEquals("fixture-cursor", page.nextCursor)
            assertEquals(1, f.created.last().browses)
            result.value.browse(CatalogQuery(cursor = page.nextCursor)); assertEquals(2, f.created.last().browses)
            assertEquals(ProviderStartupPhase.SELECTED, f.progress.last().phase)
            assertEquals(CatalogAccess.AVAILABLE, result.value.capabilities().browse)
        } finally { f.startup.close() }
        assertEquals(listOf(1, 1), f.created.map { it.closes })
    }
    @Test fun tlsUnknownHttpAuthenticationAndUnclassifiedTemporaryNeverTryAnotherCandidate() {
        for (kind in 0..4) {
            val f = Fixture()
            val result = prepareAbemaChannelCatalogStartup(f.startup) { owner, attempt -> f.candidate(owner, attempt) {
                when (kind) {
                    0 -> transport(ProviderTransportEvidence(ProviderTransportFailure.TLS))
                    1 -> transport(ProviderTransportEvidence(ProviderTransportFailure.UNKNOWN))
                    2 -> { observation.response(403); CatalogResult.Failure(CatalogFailure.TEMPORARY) }
                    3 -> CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED)
                    else -> CatalogResult.Failure(CatalogFailure.TEMPORARY)
                }
            } }
            assertTrue(result is ProviderStartupResult.Failed); assertEquals(1, f.created.size); assertEquals(1, f.created.single().closes)
            f.startup.close()
        }
    }
    @Test fun unconfirmedTransportOrCatalogCleanupPreventsFallback() {
        for (closeThrows in listOf(false, true)) {
            val f = Fixture()
            val result = prepareAbemaChannelCatalogStartup(f.startup, cleanupWaitMs = 20) { owner, attempt -> f.candidate(owner, attempt) {
                if (closeThrows) this.closeThrows = true else cleanup = false
                transport(ProviderTransportEvidence(ProviderTransportFailure.DNS))
            } } as ProviderStartupResult.Failed
            assertEquals(ProviderStartupFailure.CLEANUP_UNCONFIRMED, result.failure.reason); assertEquals(1, f.created.size)
            f.startup.close()
        }
    }
    @Test fun constructedCatalogWithoutAnObservedSuccessfulGetDoesNotPinAndCachedPageCannotSurviveRevocation() {
        val noHttp = Fixture()
        val refused = prepareAbemaChannelCatalogStartup(noHttp.startup) { owner, attempt ->
            noHttp.candidate(owner, attempt) { CatalogResult.Value(CatalogPage(listOf(entry))) }
        }
        assertTrue(refused is ProviderStartupResult.Failed); assertFalse(noHttp.startup.admitted); noHttp.startup.close()
        val f = Fixture()
        val ready = prepareAbemaChannelCatalogStartup(f.startup) { owner, attempt -> f.candidate(owner, attempt) { success() } } as ProviderStartupResult.Ready
        f.active = false
        assertEquals(CatalogFailure.ACCESS_REQUIRED, (ready.value.browse(CatalogQuery()) as CatalogResult.Failure).reason)
        assertEquals(1, f.created.single().browses); f.startup.close()
    }
    private fun elapsedMs() = System.nanoTime() / 1_000_000

    // #764: leaving provider details during an account check closes the stream
    // controller, whose IO worker closes the admitted catalog, while the Activity
    // retires the startup scope. The failing account check's attachment release
    // keeps that close running. The scope's cancellation signal then finds the
    // catalog already closing, and its worker previously read an unfinished close
    // as unconfirmed and poisoned the instance for the process.
    @Test fun retiredProviderDetailsScopeConfirmsWhileTheControllerStillClosesItsCatalog() {
        val f = Fixture()
        val ready = prepareAbemaChannelCatalogStartup(f.startup) { owner, attempt ->
            f.candidate(owner, attempt) { success() }
        } as ProviderStartupResult.Ready
        val catalog = f.created.single()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        catalog.closeHook = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val worker = Executors.newSingleThreadExecutor(); val lines = CopyOnWriteArrayList<String>()
        val registry = ProviderStartupCleanupRegistry(worker, lines::add, ::elapsedMs)
        try {
            val controllerClose = thread { ready.value.close() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            registry.retire(instanceId, f.startup)
            Thread.sleep(100)
            assertFalse(registry.isPoisoned(instanceId)); assertFalse(registry.isReady(instanceId))
            release.countDown(); controllerClose.join(5000)
            assertEquals(ProviderStartupCleanupAdmission.READY, registry.awaitReady(instanceId, elapsedMs() + 5000) { true })
            assertTrue(f.startup.cleanupConfirmed); assertEquals(1, catalog.closes)
            assertEquals(emptyList<String>(), lines.toList())
        } finally { release.countDown(); worker.shutdownNow() }
    }

    // A channel request cancelled by the scope's signal releases its route on
    // its own worker afterwards. Its later confirmation is the receipt.
    @Test fun retiredScopeWaitsForACancelledChannelRequestToReleaseItsRoute() {
        val f = Fixture()
        prepareAbemaChannelCatalogStartup(f.startup) { owner, attempt -> f.candidate(owner, attempt) { success() } }
            as ProviderStartupResult.Ready
        val released = AtomicBoolean(false)
        f.created.single().observation.own { released.get() }
        val worker = Executors.newSingleThreadExecutor(); val lines = CopyOnWriteArrayList<String>()
        val registry = ProviderStartupCleanupRegistry(worker, lines::add, ::elapsedMs)
        try {
            registry.retire(instanceId, f.startup)
            Thread.sleep(100)
            assertFalse(registry.isPoisoned(instanceId)); assertFalse(registry.isReady(instanceId))
            released.set(true)
            assertEquals(ProviderStartupCleanupAdmission.READY, registry.awaitReady(instanceId, elapsedMs() + 5000) { true })
            assertEquals(emptyList<String>(), lines.toList())
        } finally { worker.shutdownNow() }
    }

    // Resources that do not close in time, fail to close, or report a sticky
    // transport failure still poison the instance. A late finish is not a receipt.
    @Test fun closeThatNeverConfirmsStillPoisonsTheRetiredScope() {
        for (kind in 0..2) {
            val f = Fixture()
            val ready = prepareAbemaChannelCatalogStartup(f.startup, cleanupWaitMs = 50) { owner, attempt ->
                f.candidate(owner, attempt) { success() }
            } as ProviderStartupResult.Ready
            val catalog = f.created.single()
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            when (kind) {
                0 -> catalog.closeHook = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                1 -> catalog.cleanup = false
                else -> catalog.closeThrows = true
            }
            val worker = Executors.newSingleThreadExecutor(); val lines = CopyOnWriteArrayList<String>()
            val registry = ProviderStartupCleanupRegistry(worker, lines::add, ::elapsedMs)
            try {
                val controllerClose = if (kind == 0) thread { ready.value.close() } else null
                if (kind == 0) assertTrue(entered.await(5, TimeUnit.SECONDS))
                registry.retire(instanceId, f.startup)
                assertEquals(ProviderStartupCleanupAdmission.CLEANUP_UNCONFIRMED,
                    registry.awaitReady(instanceId, elapsedMs() + 5000) { true })
                release.countDown(); controllerClose?.join(5000); catalog.cleanup = true
                assertEquals(ProviderStartupCleanupAdmission.CLEANUP_UNCONFIRMED,
                    registry.awaitReady(instanceId, elapsedMs() + 5000) { true })
                assertFalse(f.startup.cleanupConfirmed)
                assertEquals(listOf("startupCleanup=POISONED cause=SCOPE_UNCONFIRMED scope=LEASE_CLEANUP retired=1 poisoned=1"),
                    lines.toList())
            } finally { release.countDown(); worker.shutdownNow() }
        }
    }

    @Test fun anotherOperationDiscardsPreparedPageInsteadOfResurrectingItsOldCursor() {
        val f = Fixture()
        val ready = prepareAbemaChannelCatalogStartup(f.startup) { owner, attempt -> f.candidate(owner, attempt) { success() } } as ProviderStartupResult.Ready
        ready.value.lookup("local-fixture")
        ready.value.browse(CatalogQuery())
        assertEquals(2, f.created.single().browses); f.startup.close()
    }
}
