package net.fstab.tachiai.platform.network

import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Test

class ProviderRoutePoolTest {
    private val firstId = "12345678-1234-1234-1234-123456789abc"
    private val secondId = "12345678-1234-1234-1234-123456789abd"
    private fun choice(id: String, name: String = "Saved route") =
        SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, id, name)
    private fun profile(secret: String = "fixture-secret") =
        parseConnectionProfile("http://user:$secret@proxy.example.test:3128".toByteArray())
    private fun instance(routes: List<SourceRouteChoice>) = defaultProviderInstances().first().copy(
        setup = ProviderSetup(routes.firstOrNull(), fallbackRoutes = routes.drop(1)))
    private fun capture(instance: ProviderInstance, profiles: Map<String, ConnectionProfile> = emptyMap()) =
        captureProviderRoutePool(instance.id, instance.service, { listOf(instance) }) { ids -> profiles.filterKeys { it in ids } }
    private fun failure(reason: ProviderRoutePoolFailure, action: () -> Unit) {
        assertEquals(reason, assertThrows(ProviderRoutePoolException::class.java, action).reason)
    }

    @Test fun aSingleSystemChoiceNeedsNoProfileReadAndAReviewRequiredPoolNeverUsesSystem() {
        val owner = instance(listOf(SourceRouteChoice.system))
        var registryReads = 0
        val pool = captureProviderRoutePool(owner.id, owner.service, { registryReads++; listOf(owner) }) {
            error("System must not read saved profiles")
        }
        val effective = pool.resolve()
        assertEquals(2, registryReads)
        assertEquals(SourceRouteChoice.system, effective.choice)
        assertNull(effective.profile)
        assertEquals(owner, effective.effectiveInstance)
        assertEquals(effective.proof, pool.resolve(effective.proof).proof)
        assertTrue(effective.proof.matches(Regex("[a-f0-9]{64}")))
        val pending = capture(instance(emptyList()))
        failure(ProviderRoutePoolFailure.CHOICE_REQUIRED) { pending.resolve() }
        failure(ProviderRoutePoolFailure.OWNER_MISMATCH) { pending.resolve(effective.proof) }
    }

    @Test fun alternativesRequireTheirExactProofAndMissingFirstRoutesNeverSelectTheNextChoice() {
        val owner = instance(listOf(choice(firstId), SourceRouteChoice.system))
        val pool = capture(owner)
        assertFalse(pool.candidates.first().available)
        assertTrue(pool.candidates.last().available)
        failure(ProviderRoutePoolFailure.PROFILE_MISSING) { pool.resolve() }
        failure(ProviderRoutePoolFailure.PROFILE_MISSING) { pool.resolve(pool.candidates.first().proof) }
        assertEquals(SourceRouteChoice.system, pool.resolve(pool.candidates.last().proof).choice)
        for (proof in listOf("", "not-a-proof", "0".repeat(64)))
            failure(ProviderRoutePoolFailure.OWNER_MISMATCH) { pool.resolve(proof) }
        val saved = capture(owner, mapOf(firstId to profile())).resolve()
        assertEquals(firstId, saved.choice.connectionId)
        assertNotNull(saved.profile)
        assertEquals(listOf(saved.choice), saved.effectiveInstance.setup.routes)
        assertEquals(owner, saved.configuredInstance)
    }

    @Test fun poolAndEveryCandidateProofChangeForReorderDeletionOrAnyReferencedProfileEdit() {
        val profiles = mapOf(firstId to profile(), secondId to profile("other-secret"))
        val original = capture(instance(listOf(choice(firstId), choice(secondId))), profiles)
        val previousProofs = original.candidates.map { it.proof }
        val changed = listOf(
            capture(instance(listOf(choice(secondId), choice(firstId))), profiles),
            capture(instance(listOf(choice(firstId))), profiles),
            capture(instance(listOf(choice(firstId), choice(secondId))), profiles + (secondId to profile("rotated-secret"))),
            capture(instance(listOf(choice(firstId), choice(secondId))), profiles - secondId),
        )
        changed.forEach { pool ->
            assertNotEquals(original.proof, pool.proof)
            previousProofs.forEach { proof -> failure(ProviderRoutePoolFailure.OWNER_MISMATCH) { pool.resolve(proof) } }
        }
        val otherInstance = original.configuredInstance.copy(id = "12345678-1234-1234-1234-123456789abe")
        val other = capture(otherInstance, profiles)
        previousProofs.forEach { proof -> failure(ProviderRoutePoolFailure.OWNER_MISMATCH) { other.resolve(proof) } }
    }

    @Test fun cleanupIdentitySurvivesPoolEditsAndAliasesButNotDifferentTransportConfigurationOrInstance() {
        val transport = profile()
        val initial = capture(instance(listOf(choice(firstId), SourceRouteChoice.system)), mapOf(firstId to transport))
        val reordered = capture(instance(listOf(SourceRouteChoice.system, choice(firstId))), mapOf(firstId to transport))
        val alias = capture(instance(listOf(choice(secondId))), mapOf(secondId to transport))
        val rotated = capture(instance(listOf(choice(firstId))), mapOf(firstId to profile("changed-secret")))
        val original = initial.resolve()
        val selected = reordered.resolve(reordered.candidates.last().proof)
        assertNotEquals(original.proof, selected.proof)
        assertEquals(original.routeIdentity, selected.routeIdentity)
        assertEquals(original.routeIdentity, alias.resolve().routeIdentity)
        assertNotEquals(original.routeIdentity, rotated.resolve().routeIdentity)
        val other = capture(original.configuredInstance.copy(id = "12345678-1234-1234-1234-123456789abe"),
            mapOf(firstId to transport))
        assertNotEquals(original.routeIdentity, other.resolve().routeIdentity)
    }

    @Test fun displayNamesDoNotRevokeOwnershipAndSnapshotCollectionsCannotBeMutated() {
        val alternatives = mutableListOf(choice(secondId))
        val previous = mutableListOf(SourceRouteChoice.system)
        val owner = instance(listOf(choice(firstId))).copy(setup = ProviderSetup(choice(firstId), previous, alternatives))
        val profiles = mutableMapOf(firstId to profile(), secondId to profile("second-secret"))
        val pool = capture(owner, profiles)
        val firstProof = pool.resolve().proof
        val renamed = owner.copy(customName = "New provider name", setup = owner.setup.copy(
            route = choice(firstId, "Renamed first"), fallbackRoutes = listOf(choice(secondId, "Renamed second"))))
        assertEquals(pool.proof, capture(renamed, profiles).proof)
        assertEquals(firstProof, capture(renamed, profiles).resolve().proof)
        alternatives.clear(); previous.clear(); profiles.clear()
        assertEquals(2, pool.candidates.size)
        assertEquals(2, pool.configuredInstance.setup.routes.size)
        assertEquals(1, pool.configuredInstance.setup.previousRoutes.size)
        assertEquals(firstProof, pool.resolve().proof)
        assertThrows(UnsupportedOperationException::class.java) { (pool.candidates as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (pool.configuredInstance.setup.fallbackRoutes as MutableList<*>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (pool.configuredInstance.setup.previousRoutes as MutableList<*>).clear() }
        for (value in listOf(pool, pool.resolve(), pool.candidates.first())) {
            assertFalse(value.toString().contains("fixture-secret"))
            assertFalse(value.toString().contains("proxy.example.test"))
        }
    }

    @Test fun boundedDoubleReadRejectsObservedRegistryAndProfileChangesWithoutRetrying() {
        val first = instance(listOf(choice(firstId), choice(secondId)))
        var reads = 0
        val changed = first.copy(setup = ProviderSetup(choice(secondId), fallbackRoutes = listOf(choice(firstId))))
        failure(ProviderRoutePoolFailure.SNAPSHOT_CHANGED) {
            captureProviderRoutePool(first.id, first.service, { reads++; listOf(if (reads == 1) first else changed) }) {
                mapOf(firstId to profile(), secondId to profile("second-secret"))
            }
        }
        assertEquals(2, reads)
        var profileReads = 0
        failure(ProviderRoutePoolFailure.SNAPSHOT_CHANGED) {
            captureProviderRoutePool(first.id, first.service, { listOf(first) }) {
                profileReads++
                mapOf(firstId to profile(if (profileReads == 1) "first" else "changed"), secondId to profile())
            }
        }
        assertEquals(2, profileReads)
        profileReads = 0
        failure(ProviderRoutePoolFailure.SNAPSHOT_CHANGED) {
            captureProviderRoutePool(first.id, first.service, { listOf(first) }) {
                profileReads++
                if (profileReads == 1) emptyMap() else mapOf(firstId to profile())
            }
        }
        assertEquals(2, profileReads)
    }

    @Test fun unreadableProfileStoresAndAmbiguousOrMismatchedInstancesFailInsteadOfProducingCandidates() {
        val owner = instance(listOf(choice(firstId), SourceRouteChoice.system))
        val unreadable = IllegalStateException("Synthetic unreadable store")
        assertSame(unreadable, assertThrows(IllegalStateException::class.java) {
            captureProviderRoutePool(owner.id, owner.service, { listOf(owner) }) { throw unreadable }
        })
        for (instances in listOf(emptyList(), listOf(owner, owner))) {
            failure(ProviderRoutePoolFailure.OWNER_MISMATCH) {
                captureProviderRoutePool(owner.id, owner.service, { instances }) { error("No profile read for a stale instance") }
            }
        }
        val other = owner.copy(id = "12345678-1234-1234-1234-123456789abe", service = PrototypeService.TWITCH)
        failure(ProviderRoutePoolFailure.OWNER_MISMATCH) {
            captureProviderRoutePool(other.id, PrototypeService.ABEMA, { listOf(other) }) { error("Wrong provider") }
        }
    }
}
