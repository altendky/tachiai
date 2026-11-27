package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.feature.connections.ProviderInstanceStore
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.platform.storage.EnvelopeSecretStore
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Test

// The settings-screen catalog, route startup and approval-browser owner checks
// through revision verdicts (#752).
class AbemaCatalogOwnerReadsTest {
    private class Stores(decryptMs: Long = 0, maxAgeMs: Long = ProviderRouteOwnerReads.VERDICT_MAX_AGE_MS) {
        val instanceSecrets = EnvelopeSecretStore(decryptMs)
        val profileSecrets = EnvelopeSecretStore(decryptMs)
        val instances = ProviderInstanceStore(instanceSecrets)
        val profiles = ConnectionProfileStore(profileSecrets)
        val abema = defaultProviderInstanceId(PrototypeService.ABEMA)
        val routeId: String = profiles.add("Japan", profile("first")).single().id
        init { instances.saveRoutes(abema, null, listOf(choice(routeId)), ::defaults) }
        val captured = captureAbemaCatalogRouteOwner(abema, { instances.read(::defaults) }, profiles::snapshot)
        val admission = ProviderRouteAdmission(captured.proof())
        val reads = ProviderRouteOwnerReads(instances, profiles, ::defaults, maxAgeMs = maxAgeMs)
        // The shape of the Android catalog's current().
        fun current() = admission.check { expected ->
            runCatching { abemaCatalogOwnerProof(reads, captured, abema, expected) }.getOrNull()
        }
        fun pool() = captureProviderRoutePool(abema, PrototypeService.ABEMA, { instances.read(::defaults) }, profiles::snapshot)
        fun decrypts() = instanceSecrets.decrypts.get() + profileSecrets.decrypts.get()
    }
    private companion object {
        fun defaults() = legacyProviderSetups(defaultSourceSetups())
        fun profile(secret: String) = parseConnectionProfile("http://user:$secret@proxy.example.test:3128".toByteArray())
        fun choice(id: String) = SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, id, "Japan")
        // One channel-list fetch with the settings screen open: about 29 fixed
        // gates plus 4 owner checks per 8 KB chunk of a ~350 KB response.
        const val FETCH_CHECKS = 200
    }

    @Test fun catalogChecksDecryptNoOwnerStoreWhileItIsUnchanged() {
        val s = Stores()
        assertTrue(s.current())
        val decrypts = s.decrypts()
        repeat(FETCH_CHECKS) { assertTrue(s.current()) }
        assertEquals(decrypts, s.decrypts())
        assertEquals(FETCH_CHECKS, s.instanceSecrets.revisions.get())
    }

    @Test fun aRouteChangeRefusesTheNextCatalogCheck() {
        val s = Stores()
        assertTrue(s.current())
        s.instances.saveRoutes(s.abema, null, listOf(SourceRouteChoice.system), ::defaults)
        assertFalse(s.current())
        // The binding stays refused even once the route is restored.
        s.instances.saveRoutes(s.abema, null, listOf(choice(s.routeId)), ::defaults)
        assertFalse(s.current())
        assertEquals(ProviderRouteRefusal.OWNER_CHANGED, s.admission.refusal)
    }

    @Test fun aProfileRemovalOrReplacementRefusesTheNextCatalogCheck() {
        val removed = Stores()
        assertTrue(removed.current())
        ConnectionProfileStore(removed.profileSecrets).remove(removed.routeId)
        assertFalse(removed.current())

        // A changed configuration is a new profile; the saved route no longer resolves.
        val replaced = Stores()
        assertTrue(replaced.current())
        val other = ConnectionProfileStore(replaced.profileSecrets)
        other.remove(replaced.routeId); other.add("Japan", profile("second"))
        assertFalse(replaced.current())
    }

    @Test fun anUnrelatedWriteReadsFullyAndKeepsTheOwner() {
        val s = Stores()
        assertTrue(s.current())
        ConnectionProfileStore(s.profileSecrets).add("Other", profile("other"))
        val decrypts = s.decrypts()
        assertTrue(s.current())
        assertEquals(4, s.decrypts() - decrypts) // The pool's double read of both stores, once.
        assertTrue(s.current())
        assertEquals(4, s.decrypts() - decrypts)
    }

    @Test fun anInstanceRemovalRefusesTheNextCatalogCheck() {
        val s = Stores()
        assertTrue(s.current())
        s.instances.remove(s.abema, ::defaults)
        assertFalse(s.current())
    }

    @Test fun routeStartupPoolChecksFollowEveryStoreChange() {
        val s = Stores()
        val pool = s.pool()
        assertTrue(providerRoutePoolCurrent(s.reads, pool))
        val decrypts = s.decrypts()
        repeat(10) { assertTrue(providerRoutePoolCurrent(s.reads, pool)) }
        assertEquals(decrypts, s.decrypts())
        // Another route in the pool changes its proof.
        val second = s.profiles.add("Second", profile("second")).last().id
        s.instances.saveRoutes(s.abema, null, listOf(choice(s.routeId), choice(second)), ::defaults)
        assertFalse(providerRoutePoolCurrent(s.reads, pool))
        assertFalse(providerRoutePoolCurrent(s.reads, pool)) // A mismatch is never kept.
        s.instances.saveRoutes(s.abema, null, listOf(choice(s.routeId)), ::defaults)
        assertTrue(providerRoutePoolCurrent(s.reads, pool))
        // A removed profile changes the pool's configuration key.
        ConnectionProfileStore(s.profileSecrets).remove(s.routeId)
        assertFalse(providerRoutePoolCurrent(s.reads, pool))
    }

    @Test fun anApprovalRequestFollowsTheOwnerItNames() {
        val s = Stores()
        val proof = s.captured.proof()
        assertTrue(abemaRequestedOwnerCurrent(s.reads, s.abema, proof))
        val decrypts = s.decrypts()
        repeat(10) { assertTrue(abemaRequestedOwnerCurrent(s.reads, s.abema, proof)) }
        assertEquals(decrypts, s.decrypts())
        s.instances.saveRoutes(s.abema, null, listOf(SourceRouteChoice.system), ::defaults)
        assertThrows(ProviderRoutePoolException::class.java) { abemaRequestedOwnerCurrent(s.reads, s.abema, proof) }
        s.instances.saveRoutes(s.abema, null, listOf(choice(s.routeId)), ::defaults)
        assertTrue(abemaRequestedOwnerCurrent(s.reads, s.abema, proof))
        ConnectionProfileStore(s.profileSecrets).remove(s.routeId)
        assertThrows(ProviderRoutePoolException::class.java) { abemaRequestedOwnerCurrent(s.reads, s.abema, proof) }
    }

    @Test fun settingsCatalogFetchCostBeforeAndAfterRevisionVerdicts() {
        fun measure(maxAgeMs: Long): Triple<Int, Int, Double> {
            val s = Stores(decryptMs = 10, maxAgeMs = maxAgeMs)
            val instanceDecrypts = s.instanceSecrets.decrypts.get()
            val profileDecrypts = s.profileSecrets.decrypts.get()
            val began = System.nanoTime()
            repeat(FETCH_CHECKS) { assertTrue(s.current()) }
            val elapsedMs = (System.nanoTime() - began) / 1_000_000.0
            return Triple(s.instanceSecrets.decrypts.get() - instanceDecrypts,
                s.profileSecrets.decrypts.get() - profileDecrypts, elapsedMs)
        }
        // A zero verdict age makes every check read fully: the previous behavior.
        val before = measure(0)
        val after = measure(ProviderRouteOwnerReads.VERDICT_MAX_AGE_MS)
        println("settingsCatalogFetch checks=$FETCH_CHECKS before instanceDecrypts=${before.first} " +
            "profileDecrypts=${before.second} ms=${before.third} after instanceDecrypts=${after.first} " +
            "profileDecrypts=${after.second} ms=${after.third}")
        assertEquals(2 * FETCH_CHECKS, before.first); assertEquals(2 * FETCH_CHECKS, before.second)
        assertEquals(2, after.first); assertEquals(2, after.second)
    }
}
