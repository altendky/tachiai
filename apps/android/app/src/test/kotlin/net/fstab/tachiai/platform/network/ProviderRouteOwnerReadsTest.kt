package net.fstab.tachiai.platform.network

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    // A gate that holds every decryption on one chosen thread until released.
    private class DecryptGate(private val held: Thread.() -> Boolean) {
        val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)
        fun hook() {
            if (!Thread.currentThread().held()) return
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }
        fun open() = release.countDown()
    }

    @Test fun aRevisionHitRunsWhileSettingsFullReadsDecryptBothStores() {
        val dir = Files.createTempDirectory("owner-reads").toFile()
        val pool = Executors.newFixedThreadPool(3)
        try {
            val instanceLock = File(dir, "provider-instances.lock")
            val profileLock = File(dir, "connection-profiles.lock")
            val s = Stores()
            // Every store object shares the store's process lock and file lock.
            val reads = ProviderRouteOwnerReads(ProviderInstanceStore(s.instanceSecrets, instanceLock),
                ConnectionProfileStore(s.profileSecrets, profileLock), ::defaults, { s.now })
            fun check() = reads.current("owner") { i, p ->
                s.fullReads++; captureProviderRoutePool(s.abema, PrototypeService.ABEMA, i, p).resolve().proof
            }
            val proof = check()
            assertEquals(1, s.fullReads)
            val decrypts = s.decrypts()
            // The Providers screen's load, each read held inside its decryption.
            val instanceGate = DecryptGate { name == "settings-instances" }
            val profileGate = DecryptGate { name == "settings-profiles" }
            s.instanceSecrets.decryptHook = instanceGate::hook
            s.profileSecrets.decryptHook = profileGate::hook
            val settingsInstances = pool.submit<List<ProviderInstance>> {
                Thread.currentThread().name = "settings-instances"
                ProviderInstanceStore(s.instanceSecrets, instanceLock).read(::defaults)
            }
            val settingsProfiles = pool.submit<List<ConnectionSummary>> {
                Thread.currentThread().name = "settings-profiles"
                ConnectionProfileStore(s.profileSecrets, profileLock).summaries(publicKeys = false)
            }
            assertTrue(instanceGate.entered.await(5, TimeUnit.SECONDS))
            assertTrue(profileGate.entered.await(5, TimeUnit.SECONDS))
            // Both full reads are still decrypting; the hit needs neither.
            assertEquals(proof, pool.submit<String?> { check() }.get(5, TimeUnit.SECONDS))
            assertEquals(1, s.fullReads)
            assertFalse(settingsInstances.isDone || settingsProfiles.isDone)
            instanceGate.open(); profileGate.open()
            assertEquals(s.abema, settingsInstances.get(5, TimeUnit.SECONDS).first().id)
            assertEquals(listOf(s.routeId), settingsProfiles.get(5, TimeUnit.SECONDS).map { it.id })
            assertEquals(decrypts + 2, s.decrypts())
        } finally { pool.shutdownNow(); dir.deleteRecursively() }
    }

    @Test fun aWriteCommittedDuringAnOlderFullReadsDecryptionIsSeenAtTheNextCheck() {
        val dir = Files.createTempDirectory("owner-reads").toFile()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val instanceLock = File(dir, "provider-instances.lock")
            val profileLock = File(dir, "connection-profiles.lock")
            val s = Stores()
            val reads = ProviderRouteOwnerReads(ProviderInstanceStore(s.instanceSecrets, instanceLock),
                ConnectionProfileStore(s.profileSecrets, profileLock), ::defaults, { s.now })
            fun check() = reads.current("owner") { i, p ->
                s.fullReads++; captureProviderRoutePool(s.abema, PrototypeService.ABEMA, i, p).resolve().proof
            }
            // Hold the full read's last profile decryption, after every
            // record it uses was read, so it can only see the old records.
            var profileDecrypts = 0
            val gate = DecryptGate { name == "owner-check" && ++profileDecrypts == 2 }
            s.profileSecrets.decryptHook = gate::hook
            val stale = pool.submit<String?> { Thread.currentThread().name = "owner-check"; check() }
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
            // The write takes the same locks; it is not held behind that decryption.
            pool.submit { ConnectionProfileStore(s.profileSecrets, profileLock).remove(s.routeId) }.get(5, TimeUnit.SECONDS)
            gate.open()
            // That read resolved the records it read, and its verdict names their old revisions.
            assertNotNull(stale.get(5, TimeUnit.SECONDS))
            assertEquals(1, s.fullReads)
            assertThrows(ProviderRoutePoolException::class.java) { check() }
            assertEquals(2, s.fullReads)
            assertThrows(ProviderRoutePoolException::class.java) { check() }
            assertEquals(3, s.fullReads)
        } finally { pool.shutdownNow(); dir.deleteRecursively() }
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
