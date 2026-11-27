package net.fstab.tachiai.provider.twitch.catalog

import net.fstab.tachiai.feature.connections.ProviderInstanceStore
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.platform.storage.EnvelopeSecretStore
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Test

// Twitch catalog owner checks through revision verdicts (#752).
class TwitchCatalogOwnerReadsTest {
    private class Stores(committed: Boolean = true) {
        val instanceSecrets = EnvelopeSecretStore()
        val profileSecrets = EnvelopeSecretStore()
        val instances = ProviderInstanceStore(instanceSecrets)
        val profiles = ConnectionProfileStore(profileSecrets)
        var legacyReads = 0
        private val setups = legacyProviderSetups(defaultSourceSetups())
        fun legacy(): Map<PrototypeService, ProviderSetup> { legacyReads++; return setups }
        val id = if (committed) instances.create(PrototypeService.TWITCH, ::legacy).last().id
            else defaultProviderInstanceId(PrototypeService.TWITCH)
        val routeId: String = profiles.add("Japan", profile("synthetic")).single().id
        val imported = SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, routeId, "Japan")
        val reads = ProviderRouteOwnerReads(instances, profiles, ::legacy)
        val observed = mutableListOf<ProviderInstance>()
        fun routes(values: List<SourceRouteChoice>) = instances.saveRoutes(id, null, values, ::legacy)
        fun read(expected: String? = null) = twitchCatalogRouteOwnerRead(reads, id, expected, observed::add)
        fun generic(expected: String? = null) = captureTwitchCatalogRouteOwner(id, { instances.read(::legacy) },
            profiles::snapshot, expected)
        fun decrypts() = instanceSecrets.decrypts.get() + profileSecrets.decrypts.get()
    }
    private companion object {
        fun profile(secret: String) = parseConnectionProfile("http://user:$secret@proxy.example.test:3128".toByteArray())
    }

    @Test fun aCommittedSystemPoolReadsOneRecordOnceAndIgnoresProfiles() {
        val s = Stores()
        s.routes(listOf(SourceRouteChoice.system))
        val before = s.instanceSecrets.decrypts.get()
        val owner = s.read()
        assertEquals(1, s.instanceSecrets.decrypts.get() - before) // The committed System single read.
        assertEquals(s.generic().proof, owner.proof)
        s.read(owner.proof) // The admission's pinned proof is its own key.
        val decrypts = s.decrypts()
        val profileRevisions = s.profileSecrets.revisions.get()
        repeat(10) { assertEquals(owner.proof, s.read(owner.proof).proof) }
        assertEquals(decrypts, s.decrypts())
        assertEquals(2, s.observed.size) // The two full reads observed it; hits do not.
        // An unrelated profile change does not touch a System pool's verdict.
        ConnectionProfileStore(s.profileSecrets).add("Other", profile("other"))
        val written = s.decrypts()
        assertEquals(owner.proof, s.read(owner.proof).proof)
        assertEquals(written, s.decrypts())
        assertEquals(profileRevisions, s.profileSecrets.revisions.get())
    }

    @Test fun aRouteOrProfileChangeTakesEffectAtTheNextCheck() {
        val s = Stores()
        s.routes(listOf(SourceRouteChoice.system))
        val system = s.read(); s.read(system.proof)
        s.routes(listOf(s.imported))
        assertEquals(ProviderRoutePoolFailure.OWNER_MISMATCH, assertThrows(ProviderRoutePoolException::class.java) {
            s.read(system.proof) }.reason)
        val imported = s.read()
        assertNotEquals(system.proof, imported.proof)
        assertEquals(s.generic().proof, imported.proof)
        s.read(imported.proof)
        val decrypts = s.decrypts()
        repeat(5) { assertEquals(imported.proof, s.read(imported.proof).proof) }
        assertEquals(decrypts, s.decrypts())
        // The imported pool consulted the profile store, so its removal counts.
        ConnectionProfileStore(s.profileSecrets).remove(s.routeId)
        assertThrows(ProviderRoutePoolException::class.java) { s.read(imported.proof) }
        // Back to System: a fresh owner, never the earlier kept verdict.
        s.routes(listOf(SourceRouteChoice.system))
        assertEquals(system.proof, s.read(system.proof).proof)
    }

    @Test fun aLegacyViewIsNeverKept() {
        val s = Stores(committed = false)
        assertNull(s.instanceSecrets.envelope)
        s.legacyReads = 0
        repeat(3) { runCatching { s.read() } }
        // Each check derives the legacy view afresh: two registry reads per check.
        assertEquals(6, s.legacyReads)
    }
}
