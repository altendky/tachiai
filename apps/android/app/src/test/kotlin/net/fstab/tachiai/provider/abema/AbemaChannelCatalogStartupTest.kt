package net.fstab.tachiai.provider.abema

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
        override fun capabilities() = CatalogCapabilities(browse = CatalogAccess.AVAILABLE)
        override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> { browses++; return work() }
        override fun lookup(input: String) = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
        override fun refresh(resource: CatalogResource) = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
        override fun resolve(resource: CatalogResource) = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
        override fun close() { closes++; if (closeThrows) error("Synthetic close failure") }
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
            val result = prepareAbemaChannelCatalogStartup(f.startup) { owner, attempt -> f.candidate(owner, attempt) {
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
    @Test fun anotherOperationDiscardsPreparedPageInsteadOfResurrectingItsOldCursor() {
        val f = Fixture()
        val ready = prepareAbemaChannelCatalogStartup(f.startup) { owner, attempt -> f.candidate(owner, attempt) { success() } } as ProviderStartupResult.Ready
        ready.value.lookup("local-fixture")
        ready.value.browse(CatalogQuery())
        assertEquals(2, f.created.single().browses); f.startup.close()
    }
}
