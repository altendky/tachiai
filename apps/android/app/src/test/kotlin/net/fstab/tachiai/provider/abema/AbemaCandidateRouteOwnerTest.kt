package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Test

class AbemaCandidateRouteOwnerTest {
    private val instanceId = "12345678-1234-1234-1234-123456789abc"
    private val firstId = "12345678-1234-1234-1234-123456789abd"
    private val secondId = "12345678-1234-1234-1234-123456789abe"
    private fun saved(id: String, title: String = "Saved") = SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, id, title)
    private fun profile(secret: String = "fixture-secret") = parseConnectionProfile("http://user:$secret@proxy.example.test:3128".toByteArray())
    private fun instance(routes: List<SourceRouteChoice>) = ProviderInstance(instanceId, PrototypeService.ABEMA, "ABEMA",
        setup = ProviderSetup(routes.first(), fallbackRoutes = routes.drop(1)))
    private fun pool(owner: ProviderInstance, profiles: Map<String, ConnectionProfile>) =
        captureProviderRoutePool(instanceId, PrototypeService.ABEMA, { listOf(owner) }) { ids -> profiles.filterKeys(ids::contains) }
    private fun capture(owner: ProviderInstance, profiles: Map<String, ConnectionProfile>, proof: String? = null) =
        captureAbemaCatalogRouteOwner(instanceId, { listOf(owner) }, { ids -> profiles.filterKeys(ids::contains) }, proof)
    private fun failure(reason: ProviderRoutePoolFailure, action: () -> Unit) =
        assertEquals(reason, assertThrows(ProviderRoutePoolException::class.java, action).reason)

    @Test fun initialCaptureChoosesOnlyFirstAndExactProofSelectsConfiguredAlternative() {
        val configured = instance(listOf(saved(firstId), SourceRouteChoice.system))
        val profiles = mapOf(firstId to profile())
        val snapshot = pool(configured, profiles)
        val first = capture(configured, profiles)
        assertEquals(saved(firstId), first.instance.setup.route)
        assertEquals(snapshot.resolve().proof, first.proof())
        val second = capture(configured, profiles, snapshot.candidates.last().proof)
        assertEquals(SourceRouteChoice.system, second.instance.setup.route)
        assertEquals(listOf(SourceRouteChoice.system), second.instance.setup.routes)
        assertEquals(configured, second.configuredInstance); assertNull(second.profile)
        assertTrue(second.sameOwnership(capture(configured, profiles, second.proof())))
        assertFalse(first.sameOwnership(second))
        assertFalse(second.sameOwnership(AbemaCatalogRouteOwner(first.instance, first.profile, second.proof())))
        assertFalse(first.sameOwnership(AbemaCatalogRouteOwner(first.instance, profile("wrong-frozen-profile"), first.proof())))
        failure(ProviderRoutePoolFailure.PROFILE_MISSING) { capture(configured, emptyMap()) }
        val missing = pool(configured, emptyMap())
        assertNull(capture(configured, emptyMap(), missing.candidates.last().proof).profile)
    }

    @Test fun fullPoolAndCoherentProfilesAreCapturedAndEveryCandidateMutationRevokesProof() {
        val configured = instance(listOf(saved(firstId), saved(secondId)))
        val profiles = mapOf(firstId to profile(), secondId to profile("second"))
        var reads = 0
        val secondProof = pool(configured, profiles).candidates.last().proof
        val captured = captureAbemaCatalogRouteOwner(instanceId, { listOf(configured) }, { ids ->
            reads++; assertEquals(setOf(firstId, secondId), ids); profiles
        }, secondProof)
        assertEquals(2, reads); assertEquals(secondId, captured.instance.setup.route!!.connectionId)
        for ((changed, changedProfiles) in listOf(
            configured.copy(setup = ProviderSetup(saved(secondId), fallbackRoutes = listOf(saved(firstId)))) to profiles,
            configured.copy(setup = ProviderSetup(saved(firstId))) to profiles,
            configured to (profiles + (firstId to profile("rotated-first"))),
            configured to (profiles - firstId),
        )) failure(ProviderRoutePoolFailure.OWNER_MISMATCH) { capture(changed, changedProfiles, secondProof) }
        assertEquals(secondProof, capture(configured.copy(customName = "Renamed", setup = configured.setup.copy(
            route = saved(firstId, "First renamed"), fallbackRoutes = listOf(saved(secondId, "Second renamed")))), profiles, secondProof).proof())
        failure(ProviderRoutePoolFailure.OWNER_MISMATCH) { capture(configured, profiles, "f".repeat(64)) }
        assertFalse(captured.toString().contains("fixture-secret")); assertFalse(captured.toString().contains("proxy.example.test"))
    }

    @Test fun changedFullSnapshotIsRefusedAndPlaybackRevocationIsStickyForNonFirstCandidate() {
        val configured = instance(listOf(SourceRouteChoice.system, saved(secondId)))
        var profiles = mapOf(secondId to profile())
        val proof = pool(configured, profiles).candidates.last().proof
        var reads = 0
        failure(ProviderRoutePoolFailure.SNAPSHOT_CHANGED) {
            captureAbemaCatalogRouteOwner(instanceId, { listOf(configured) }, {
                reads++; if (reads == 1) profiles else mapOf(secondId to profile("changed"))
            }, proof)
        }
        val owner = capture(configured, profiles, proof)
        val guard = AbemaEpisodePlaybackOwnership(owner, { capture(configured, profiles, proof) }, { true })
        assertTrue(guard.checkWorker())
        profiles = emptyMap(); assertFalse(guard.checkWorker()); assertFalse(guard.canContinue())
        profiles = mapOf(secondId to profile()); assertFalse(guard.checkWorker())
    }
}
