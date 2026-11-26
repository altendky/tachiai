package net.fstab.tachiai.platform.network

import net.fstab.tachiai.feature.connections.ProviderInstanceStore
import net.fstab.tachiai.platform.storage.EnvelopeSecretStore
import net.fstab.tachiai.platform.storage.PrivateSecretRevision
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Test

class ProviderRouteOwnerReadsTest {
    private class Stores(savedRoute: Boolean = true) {
        val instanceSecrets = EnvelopeSecretStore()
        val profileSecrets = EnvelopeSecretStore()
        val instances = ProviderInstanceStore(instanceSecrets)
        val profiles = ConnectionProfileStore(profileSecrets)
        var now = 1_000L
        val reads = ProviderRouteOwnerReads(instances, profiles, ::defaults, { now })
        val abema = defaultProviderInstanceId(PrototypeService.ABEMA)
        val routeId: String = profiles.add("Japan", profile("first")).single().id
        var fullReads = 0
        init { if (savedRoute) instances.saveRoutes(abema, null, listOf(choice(routeId)), ::defaults) }
        fun check(key: Any = "owner"): String? = reads.current(key) { readInstances, readProfiles ->
            fullReads++
            captureProviderRoutePool(abema, PrototypeService.ABEMA, readInstances, readProfiles).resolve().proof
        }
        fun decrypts() = instanceSecrets.decrypts.get() + profileSecrets.decrypts.get()
    }
    companion object {
        internal fun defaults() = legacyProviderSetups(defaultSourceSetups())
        internal fun profile(secret: String) = parseConnectionProfile("http://user:$secret@proxy.example.test:3128".toByteArray())
        internal fun choice(id: String) = SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, id, "Japan")
    }

    @Test fun unchangedStoresKeepTheVerdictWithoutDecryptingAgain() {
        val s = Stores()
        val before = s.decrypts()
        val proof = s.check()
        assertNotNull(proof)
        assertEquals(1, s.fullReads)
        val decrypts = s.decrypts()
        assertEquals(4, decrypts - before) // The pool's double read of both stores.
        repeat(20) { assertEquals(proof, s.check()) }
        assertEquals(1, s.fullReads)
        assertEquals(decrypts, s.decrypts())
        // Each later check reads one revision per store it consulted.
        assertEquals(20, s.instanceSecrets.revisions.get())
        assertEquals(20, s.profileSecrets.revisions.get())
    }

    @Test fun anyCommittedWriteToEitherStoreForcesTheNextCheckToReadFully() {
        val s = Stores()
        val proof = s.check()
        // Rewriting identical content still gives a new random-IV envelope.
        s.instances.saveRoutes(s.abema, null, listOf(choice(s.routeId)), ::defaults)
        assertEquals(proof, s.check()); assertEquals(2, s.fullReads)
        assertEquals(proof, s.check()); assertEquals(2, s.fullReads)
        // A writer through a separate store object, as another process would be.
        ProviderInstanceStore(s.instanceSecrets).saveRoutes(s.abema, null, listOf(SourceRouteChoice.system), ::defaults)
        val system = s.check()
        assertEquals(3, s.fullReads); assertNotEquals(proof, system)
        ProviderInstanceStore(s.instanceSecrets).saveRoutes(s.abema, null, listOf(choice(s.routeId)), ::defaults)
        assertEquals(proof, s.check()); assertEquals(4, s.fullReads)
        // An unrelated profile added to the connection store.
        ConnectionProfileStore(s.profileSecrets).add("Other", profile("other"))
        assertEquals(proof, s.check()); assertEquals(5, s.fullReads)
        // The referenced profile removed: the next check sees it missing.
        ConnectionProfileStore(s.profileSecrets).remove(s.routeId)
        assertThrows(ProviderRoutePoolException::class.java) { s.check() }
        assertEquals(6, s.fullReads)
        assertThrows(ProviderRoutePoolException::class.java) { s.check() }
        assertEquals(7, s.fullReads)
    }

    @Test fun aSystemRouteVerdictIgnoresTheUnconsultedProfileStore() {
        val s = Stores()
        s.instances.saveRoutes(s.abema, null, listOf(SourceRouteChoice.system), ::defaults)
        val proof = s.check()
        val profileRevisions = s.profileSecrets.revisions.get()
        ConnectionProfileStore(s.profileSecrets).add("Other", profile("other"))
        assertEquals(proof, s.check()); assertEquals(1, s.fullReads)
        assertEquals(profileRevisions, s.profileSecrets.revisions.get())
        // Choosing the saved route again is a registry change.
        s.instances.saveRoutes(s.abema, null, listOf(choice(s.routeId)), ::defaults)
        assertNotEquals(proof, s.check()); assertEquals(2, s.fullReads)
    }

    @Test fun legacyViewsNullVerdictsOtherKeysAndAgeAlwaysReadFully() {
        val legacy = Stores(savedRoute = false)
        assertNull(legacy.instanceSecrets.envelope)
        repeat(3) { runCatching { legacy.check() } }
        assertEquals(3, legacy.fullReads)

        val s = Stores()
        val proof = s.check()
        s.check("other"); assertEquals(2, s.fullReads)
        s.check("other"); assertEquals(2, s.fullReads)
        s.check(); assertEquals(3, s.fullReads)
        s.now += ProviderRouteOwnerReads.VERDICT_MAX_AGE_MS
        assertEquals(proof, s.check()); assertEquals(4, s.fullReads)
        s.now -= 1 // A clock that went backwards never keeps a verdict.
        s.check(); assertEquals(5, s.fullReads)

        var refuse = true
        val declined = ProviderRouteOwnerReads(s.instances, s.profiles, ::defaults, { s.now })
        var declinedReads = 0
        repeat(2) { declined.current<String>("owner") { _, _ -> declinedReads++; if (refuse) null else "accepted" } }
        assertEquals(2, declinedReads)
        refuse = false
        repeat(2) { assertEquals("accepted", declined.current<String>("owner") { _, _ -> declinedReads++; "accepted" }) }
        // A read that consulted no store has nothing to stand on.
        assertEquals(4, declinedReads)
    }

    @Test fun aRevisionReadFailureFailsTheCheckAndDropsTheVerdict() {
        val s = Stores()
        s.check()
        s.instanceSecrets.readHook = { error("PRIVATE STORAGE ERROR") }
        assertThrows(IllegalStateException::class.java) { s.check() }
        s.instanceSecrets.readHook = {}
        s.check(); assertEquals(2, s.fullReads)
    }

    @Test fun aChangeBetweenAFullReadsOwnStoreReadsIsNeverKept() {
        val s = Stores()
        var reads = 0
        s.instanceSecrets.readHook = {
            if (++reads == 2) ProviderInstanceStore(s.instanceSecrets).saveRoutes(s.abema, null, listOf(choice(s.routeId)), ::defaults)
        }
        // The pool's own double read is unchanged in content, so it resolves;
        // the differing revisions still keep no verdict.
        s.check()
        s.instanceSecrets.readHook = {}
        s.check(); assertEquals(2, s.fullReads)
        s.check(); assertEquals(2, s.fullReads)
    }

    @Test fun storesWithoutRevisionsKeepTheirPreviousEveryCheckReads() {
        class Plain : PrivateSecretStore {
            var bytes: ByteArray? = null; var reads = 0
            override fun read() = bytes?.copyOf().also { reads++ }
            override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf() }
        }
        val instanceSecrets = Plain(); val profileSecrets = Plain()
        val instances = ProviderInstanceStore(instanceSecrets); val profiles = ConnectionProfileStore(profileSecrets)
        val id = profiles.add("Japan", profile("first")).single().id
        val abema = defaultProviderInstanceId(PrototypeService.ABEMA)
        instances.saveRoutes(abema, null, listOf(choice(id)), ::defaults)
        assertNull(instances.revision()); assertNull(profiles.revision())
        val reads = ProviderRouteOwnerReads(instances, profiles, ::defaults)
        var full = 0
        repeat(3) { reads.current("owner") { i, p -> full++; captureProviderRoutePool(abema, PrototypeService.ABEMA, i, p).proof } }
        assertEquals(3, full)
    }

    @Test fun revisionsNameEachCommittedEnvelopeAndNoPlaintext() {
        val s = Stores()
        val first = s.instances.revision()
        assertNotNull(first)
        assertEquals(first, s.instances.revision())
        assertEquals(first, s.instances.readRevisioned(::defaults).second)
        s.instances.saveRoutes(s.abema, null, listOf(choice(s.routeId)), ::defaults)
        assertNotEquals(first, s.instances.revision())
        assertEquals("PrivateSecretRevision(redacted)", first.toString())
        val profileRevision = s.profiles.revision()
        assertEquals(profileRevision, s.profiles.snapshotRevisioned(setOf(s.routeId)).second)
        s.instanceSecrets.envelope = null
        assertNull(s.instances.revision())
        assertNull(s.instances.readRevisioned(::defaults).second)
        assertEquals(PrivateSecretRevision.ABSENT, PrivateSecretRevision.of(null))
        assertNotEquals(PrivateSecretRevision.ABSENT, PrivateSecretRevision.of(ByteArray(0)))
    }
}
