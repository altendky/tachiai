package net.fstab.tachiai.provider.twitch.catalog

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import net.fstab.tachiai.feature.connections.ProviderInstanceStore
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.twitch.DeviceAuthResponse
import net.fstab.tachiai.provider.twitch.SMART_TV_TWITCH_CLIENT_ID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TwitchCatalogSystemRouteSnapshotTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        var reads = 0
        override fun read(): ByteArray? { reads++; return bytes?.copyOf() }
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf() }
    }
    private class Fixture(committed: Boolean = true, lockFile: File? = null) {
        val memory = Memory()
        val registry = ProviderInstanceStore(memory, lockFile)
        var legacy = legacyProviderSetups(defaultSourceSetups())
        var legacyReads = 0
        val id = if (committed) registry.create(PrototypeService.TWITCH) { legacy }.last().id
            else defaultProviderInstanceId(PrototypeService.TWITCH)
        val routeId = "12345678-1234-1234-1234-123456789abc"
        val imported = SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, routeId, "Synthetic route")
        val profile = parseConnectionProfile("http://fixture:synthetic-secret@proxy.example.test:3128".toByteArray())
        var profiles: Map<String, ConnectionProfile> = mapOf(routeId to profile)
        var profileReads = 0
        var profileHook: () -> Unit = {}
        val observed = mutableListOf<ProviderInstance>()
        init { reset() }
        fun reset() { memory.reads = 0; profileReads = 0; legacyReads = 0; observed.clear() }
        fun readProfiles(ids: Set<String>): Map<String, ConnectionProfile> {
            profileReads++; profileHook(); assertEquals(setOf(routeId), ids)
            return profiles.filterKeys { it in ids }
        }
        fun capture(expected: String? = null) = captureTwitchCatalogSystemRegistryOwner(id, registry,
            { legacyReads++; legacy }, ::readProfiles, expected, observed::add)
        fun generic(expected: String? = null) = captureTwitchCatalogRouteOwner(id,
            { registry.read { legacy } }, ::readProfiles, expected)
        fun routes(values: List<SourceRouteChoice>) = registry.saveRoutes(id, null, values) { legacy }
        fun failure(reason: ProviderRoutePoolFailure, action: () -> Unit) =
            assertEquals(reason, assertThrows(ProviderRoutePoolException::class.java, action).reason)
    }

    @Test fun committedSystemReadsOneRecordAndProducesExactlyTheGenericProofs() {
        val f = Fixture()
        val generic = f.generic()
        assertEquals(2, f.memory.reads); assertEquals(0, f.profileReads)
        f.reset()
        val captured = f.capture()
        assertEquals(1, f.memory.reads); assertEquals(0, f.profileReads); assertEquals(0, f.legacyReads)
        assertEquals(listOf(captured.effective.configuredInstance), f.observed)
        assertEquals(generic.effective.poolProof, captured.effective.poolProof)
        assertEquals(generic.proof, captured.proof)
        assertEquals(generic.routeIdentity, captured.routeIdentity)
        assertEquals(generic.instance, captured.instance); assertNull(captured.profile)
        assertTrue(captured.presentation.sameOwnership(generic.presentation))
        f.reset()
        assertEquals(captured.proof, f.capture(captured.proof).proof)
        assertEquals(1, f.memory.reads)
        f.reset()
        f.failure(ProviderRoutePoolFailure.OWNER_MISMATCH) { f.capture("0".repeat(64)) }
        assertEquals(1, f.memory.reads); assertEquals(0, f.profileReads)
        f.registry.save(f.id, "Renamed Twitch", SourceRouteChoice.system) { f.legacy }; f.reset()
        val renamed = f.capture(captured.proof)
        assertEquals(captured.proof, renamed.proof); assertEquals("Renamed Twitch", renamed.instance.name)
        assertEquals(1, f.memory.reads)
    }

    @Test fun registryLockEndsBeforeObservationAndProfileCallbacks() {
        val file = temporary.newFile("registry.lock")
        val f = Fixture(lockFile = file)
        var callbacks = 0
        fun unlocked() {
            RandomAccessFile(file, "rw").use { handle ->
                handle.channel.tryLock().use { lock -> assertNotNull(lock) }
            }
            callbacks++
        }
        captureTwitchCatalogSystemRegistryOwner(f.id, f.registry, { f.legacy }, f::readProfiles,
            onInstanceObserved = { unlocked() })
        assertEquals(1, callbacks)
        f.routes(listOf(f.imported, SourceRouteChoice.system))
        f.profileHook = ::unlocked
        captureTwitchCatalogSystemRegistryOwner(f.id, f.registry, { f.legacy }, f::readProfiles,
            onInstanceObserved = { unlocked() })
        assertEquals(5, callbacks) // Two observations and two profile reads.
    }

    @Test fun legacySystemAndEveryImportedOrMixedPoolRetainTwoRegistryAndProfileReconciliation() {
        val legacy = Fixture(committed = false)
        val captured = legacy.capture()
        assertEquals(SourceRouteChoice.system, captured.effective.choice)
        assertEquals(2, legacy.memory.reads); assertEquals(0, legacy.profileReads); assertEquals(2, legacy.legacyReads)
        assertNull(legacy.memory.bytes)
        for (ordering in 0..2) {
            val f = Fixture()
            f.routes(when (ordering) {
                0 -> listOf(SourceRouteChoice.system, f.imported)
                1 -> listOf(f.imported, SourceRouteChoice.system)
                else -> listOf(f.imported)
            })
            val reference = f.generic()
            f.reset()
            val owner = f.capture(reference.proof)
            assertEquals(reference.proof, owner.proof); assertEquals(reference.effective.poolProof, owner.effective.poolProof)
            assertEquals(reference.routeIdentity, owner.routeIdentity)
            assertEquals(2, f.memory.reads); assertEquals(2, f.profileReads); assertEquals(0, f.legacyReads)
            assertEquals(2, f.observed.size)
        }
        val importedLegacy = Fixture(committed = false)
        importedLegacy.legacy = importedLegacy.legacy +
            (PrototypeService.TWITCH to ProviderSetup(importedLegacy.imported))
        importedLegacy.capture()
        assertEquals(2, importedLegacy.memory.reads); assertEquals(2, importedLegacy.profileReads)
        assertEquals(2, importedLegacy.legacyReads); assertNull(importedLegacy.memory.bytes)
    }

    @Test fun missingOrUnreadableProfilesAndChangedFallbackCannotTurnMixedPoolIntoSystem() {
        val missing = Fixture(); missing.routes(listOf(missing.imported, SourceRouteChoice.system))
        missing.profiles = emptyMap(); missing.reset()
        missing.failure(ProviderRoutePoolFailure.PROFILE_MISSING) { missing.capture() }
        assertEquals(2, missing.memory.reads); assertEquals(2, missing.profileReads)
        val unreadable = Fixture(); unreadable.routes(listOf(SourceRouteChoice.system, unreadable.imported)); unreadable.reset()
        val error = IllegalStateException("Synthetic profile-read refusal")
        unreadable.profileHook = { throw error }
        assertSame(error, assertThrows(IllegalStateException::class.java) { unreadable.capture() })
        assertEquals(1, unreadable.memory.reads); assertEquals(1, unreadable.profileReads)
        val changed = Fixture(); changed.routes(listOf(SourceRouteChoice.system, changed.imported)); changed.reset()
        changed.profileHook = {
            if (changed.profileReads == 2) changed.profiles = mapOf(changed.routeId to
                parseConnectionProfile("http://changed.example.test:3128".toByteArray()))
        }
        changed.failure(ProviderRoutePoolFailure.SNAPSHOT_CHANGED) { changed.capture() }
        assertEquals(2, changed.memory.reads); assertEquals(2, changed.profileReads)
    }

    @Test fun fallbackStillRejectsRegistryChangesObservedBetweenItsTwoSamples() {
        val f = Fixture()
        val system = checkNotNull(f.memory.bytes).copyOf()
        f.routes(listOf(SourceRouteChoice.system, f.imported)); f.reset()
        f.profileHook = { if (f.profileReads == 1) f.memory.bytes = system.copyOf() }
        f.failure(ProviderRoutePoolFailure.SNAPSHOT_CHANGED) { f.capture() }
        assertEquals(2, f.memory.reads); assertEquals(1, f.profileReads)
    }

    @Test fun missingEmptyMalformedAndAmbiguousRegistryNeverUseShortcutOrLegacyFallback() {
        val absent = Fixture(); absent.registry.remove(absent.id) { absent.legacy }; absent.reset()
        absent.failure(ProviderRoutePoolFailure.OWNER_MISMATCH) { absent.capture() }
        assertEquals(1, absent.memory.reads); assertEquals(0, absent.profileReads)
        val empty = Fixture(); empty.routes(emptyList()); empty.reset()
        empty.failure(ProviderRoutePoolFailure.CHOICE_REQUIRED) { empty.capture() }
        assertEquals(2, empty.memory.reads); assertEquals(0, empty.profileReads)
        val malformed = Fixture()
        val duplicate = ByteArrayOutputStream().also { output -> DataOutputStream(output).use { data ->
            data.writeInt(3); data.writeInt(2)
            repeat(2) {
                data.writeUTF(malformed.id); data.writeUTF("TWITCH"); data.writeUTF("Synthetic"); data.writeBoolean(false)
                data.writeInt(1); data.writeUTF("SYSTEM"); data.writeInt(0)
            }
        } }.toByteArray()
        for (bytes in listOf(byteArrayOf(1, 2, 3), duplicate)) {
            malformed.memory.bytes = bytes; malformed.reset()
            assertThrows(Exception::class.java) { malformed.capture() }
            assertEquals(1, malformed.memory.reads); assertEquals(0, malformed.profileReads); assertEquals(0, malformed.legacyReads)
            assertArrayEquals(bytes, malformed.memory.bytes)
        }
    }

    @Test fun everyGateReadsFreshAndAnObservedPoolChangeRemainsRevokedAfterRestoration() {
        val f = Fixture()
        val proof = f.capture().proof
        val admission = ProviderRouteAdmission(proof)
        fun gate() = admission.check { expected -> f.capture(expected).proof }
        f.reset()
        repeat(2) { assertTrue(gate()) }
        assertEquals(2, f.memory.reads); assertEquals(0, f.profileReads)
        f.routes(listOf(SourceRouteChoice.system, f.imported)); f.reset()
        assertFalse(gate()); assertFalse(admission.canContinue())
        assertEquals(2, f.memory.reads); assertEquals(2, f.profileReads)
        f.routes(listOf(SourceRouteChoice.system)); f.reset()
        assertFalse(gate()); assertEquals(0, f.memory.reads)
        assertEquals(proof, f.capture().proof) // Only a new binding could use it.
        assertFalse(admission.canContinue())
    }

    @Test fun realIdleSessionKeepsOneFreshRegistryAndGrantReadAndExactExpiryAndForgetGates() {
        for (forget in listOf(false, true)) {
            val f = Fixture()
            val memory = Memory()
            var wall = 1_000L
            var mono = 10_000L
            val store = TwitchCatalogGrantStore(memory, f.id, wallMs = { wall })
            store.commitConnection(store.beginConnection(), TwitchCatalogReplacement(
                TwitchCatalogCredentials("synthetic-access", "synthetic-refresh", 7_200_000L),
                TwitchCatalogValidation("synthetic-user", setOf(TWITCH_CATALOG_SCOPE), 7_200_000L), 7_200_000L,
                providerExpiresAtMs = wall + 7_200_000L,
                localRetentionUntilMs = wall + TWITCH_CATALOG_LOCAL_RETENTION_MS, savedAtMs = wall))!!
            val admission = ProviderRouteAdmission(f.capture().proof)
            var validations = 0
            val session = TwitchCatalogSession(store, { object : TwitchCatalogTransport {
                override fun device(): DeviceAuthResponse = error("No device consent")
                override fun poll(deviceCode: String): DeviceAuthResponse = error("No polling")
                override fun refresh(refreshToken: String): DeviceAuthResponse = error("No refresh")
                override fun validate(accessToken: String): DeviceAuthResponse {
                    validations++
                    return DeviceAuthResponse(200, mapOf("client_id" to SMART_TV_TWITCH_CLIENT_ID,
                        "user_id" to "synthetic-user", "scopes" to listOf(TWITCH_CATALOG_SCOPE), "expires_in" to 7200))
                }
                override fun close() = Unit
            } }, { wall }, { mono }, { admission.check { expected -> f.capture(expected).proof } })
            session.use {
                session.attach()
                val lease = checkNotNull(session.validate().lease)
                val beforeValidation = validations
                f.reset(); memory.reads = 0
                repeat(2) { assertEquals(TwitchCatalogIdleViewerAdmission.ADMITTED, session.admitIdleViewer(lease) { true }) }
                assertEquals(2, f.memory.reads); assertEquals(2, memory.reads); assertEquals(0, f.profileReads)
                assertEquals(beforeValidation, validations)
                if (forget) assertTrue(store.forget()) else {
                    val tighter = checkNotNull(store.tightenProviderExpiry(lease.grant, wall + 1000))
                    assertEquals(lease.grant.generation, tighter.generation)
                    assertEquals(lease.accessToken, tighter.credentials!!.accessToken)
                    assertEquals(TwitchCatalogIdleViewerAdmission.ADMITTED, session.admitIdleViewer(lease) { true })
                    wall += 1000; mono += 1000
                }
                assertEquals(TwitchCatalogIdleViewerAdmission.REFUSED, session.admitIdleViewer(lease) { true })
                assertEquals(beforeValidation, validations)
            }
        }
    }
}
