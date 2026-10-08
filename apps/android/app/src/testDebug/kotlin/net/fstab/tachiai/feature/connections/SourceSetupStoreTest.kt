package net.fstab.tachiai.feature.connections

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class SourceSetupStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private class MemorySecrets : PrivateSecretStore {
        var bytes: ByteArray? = null
        var writes = 0
        override fun read() = bytes
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf(); writes++ }
    }

    // Fixed historical identities: this fixture must not grow with the catalogue.
    private val originalIdentities = listOf("ABEMA_LIVE", "ABEMA_REPLAY", "TWITCH_LIVE", "TWITCH_REPLAY")
    private fun record(
        identities: List<String> = originalIdentities,
        version: Int = 1,
        declaredCount: Int = identities.size,
        displayName: String = "Old stream",
        defaultMode: String = "SYSTEM",
        routeId: String = "12345678-1234-1234-1234-123456789abc",
    ): ByteArray = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { data ->
            data.writeInt(version); data.writeInt(declaredCount)
            identities.forEach { identity ->
                data.writeUTF(identity); data.writeUTF(displayName); data.writeUTF(defaultMode)
                if (defaultMode == "SAVED_CONNECTION") { data.writeUTF(routeId); data.writeUTF("Old route") }
                data.writeUTF("SOURCE_DEFAULT"); data.writeUTF("SOURCE_DEFAULT")
            }
        }
    }.toByteArray()

    @Test fun saveReloadAndUnrelatedSourcesKeepStablePreferences() {
        val memory = MemorySecrets()
        val store = SourceSetupStore(memory)
        val system = store.read()
        val route = SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, "12345678-1234-1234-1234-123456789abc", "Proton Japan")
        val setup = SourceSetup("Sumo replay", route, feedB = SourceRouteChoice.system)
        assertEquals(setup, store.save(PrototypeSource.ABEMA_REPLAY, setup)[PrototypeSource.ABEMA_REPLAY])
        val restored = SourceSetupStore(memory).read()
        assertEquals(setup, restored[PrototypeSource.ABEMA_REPLAY])
        assertEquals(system[PrototypeSource.TWITCH_LIVE], restored[PrototypeSource.TWITCH_LIVE])
        assertEquals(SourceRouteChoice.system, restored[PrototypeSource.ABEMA_REPLAY]!!.route(PrototypeSlot.B))
        assertFalse(String(memory.bytes!!).contains("PrivateKey"))
    }

    @Test fun corruptionDoesNotBecomeSystemRoutingOrGetOverwritten() {
        val memory = MemorySecrets().apply { bytes = byteArrayOf(1, 2, 3) }
        val store = SourceSetupStore(memory)
        assertThrows(Exception::class.java) { store.read() }
        assertThrows(Exception::class.java) { store.save(PrototypeSource.ABEMA_LIVE, SourceSetup("News")) }
        assertArrayEquals(byteArrayOf(1, 2, 3), memory.bytes)
    }

    @Test fun validOriginalFourRecordsRequireResetAndNeverWriteOnReadOrSave() {
        listOf(record(), record(defaultMode = "SAVED_CONNECTION")).forEach { original ->
            val memory = MemorySecrets().apply { bytes = original.copyOf() }
            val store = SourceSetupStore(memory)
            assertThrows(ObsoleteSourceSetupException::class.java) { store.read() }
            assertThrows(ObsoleteSourceSetupException::class.java) { store.save(PrototypeSource.ABEMA_LIVE, SourceSetup("News")) }
            assertArrayEquals(original, memory.bytes)
            assertEquals(0, memory.writes)
        }
    }

    @Test fun explicitResetWritesCurrentDefaultsOnceWithoutMigratingOldValues() {
        val memory = MemorySecrets().apply { bytes = record(defaultMode = "SAVED_CONNECTION") }
        val store = SourceSetupStore(memory)
        store.resetObsolete()
        assertEquals(1, memory.writes)
        assertEquals(defaultSourceSetups(), store.read())
        val committed = memory.bytes!!.copyOf()
        // A stale or concurrent second reset may not overwrite the now-current record.
        assertThrows(IllegalStateException::class.java) { SourceSetupStore(memory).resetObsolete() }
        assertArrayEquals(committed, memory.bytes)
        assertEquals(1, memory.writes)
    }

    @Test fun malformedHistoricalAndCurrentRecordsAreNotResetEligible() {
        val current = PrototypeSource.entries.map { it.name }
        val invalid = listOf(
            record(version = 2), record(declaredCount = 3), record(declaredCount = Int.MAX_VALUE),
            record(identities = originalIdentities.dropLast(1) + "UNKNOWN"),
            record(identities = originalIdentities.dropLast(1) + "TWITCH_LIVE"),
            record(identities = originalIdentities.dropLast(1) + "TWITCH_CHILLHOP_LIVE"),
            record(displayName = ""), record(displayName = " Bad name"),
            record(defaultMode = "SOURCE_DEFAULT"), record(defaultMode = "UNKNOWN"),
            record(defaultMode = "SAVED_CONNECTION", routeId = "not-a-route-id"),
            record() + byteArrayOf(0), record().dropLast(1).toByteArray(),
            record(identities = current, displayName = ""),
            record(identities = current.dropLast(1) + current.first()),
        )
        invalid.forEach { original ->
            val memory = MemorySecrets().apply { bytes = original.copyOf() }
            val store = SourceSetupStore(memory)
            val failure = assertThrows(Exception::class.java) { store.read() }
            assertFalse(failure is ObsoleteSourceSetupException)
            assertThrows(Exception::class.java) { store.resetObsolete() }
            assertThrows(Exception::class.java) { store.save(PrototypeSource.ABEMA_LIVE, SourceSetup("News")) }
            assertArrayEquals(original, memory.bytes)
            assertEquals(0, memory.writes)
        }
    }

    @Test fun missingAndCurrentRecordsCannotBeReset() {
        val memory = MemorySecrets()
        val store = SourceSetupStore(memory)
        assertThrows(IllegalStateException::class.java) { store.resetObsolete() }
        assertNull(memory.bytes)
        val custom = SourceSetup("Custom stream")
        store.save(PrototypeSource.ABEMA_LIVE, custom)
        val original = memory.bytes!!.copyOf()
        assertThrows(IllegalStateException::class.java) { store.resetObsolete() }
        assertArrayEquals(original, memory.bytes)
        assertEquals(custom, store.read()[PrototypeSource.ABEMA_LIVE])
    }

    @Test fun explicitResetHoldsFileLockAcrossDetectionAndWrite() {
        val file = temporary.newFile("obsolete-source.lock")
        val original = record()
        var committed: ByteArray? = null
        fun assertLocked() = RandomAccessFile(file, "rw").use { handle ->
            assertThrows(OverlappingFileLockException::class.java) { handle.channel.tryLock() }
        }
        val memory = object : PrivateSecretStore {
            override fun read(): ByteArray { assertLocked(); return original }
            override fun write(plaintext: ByteArray) { assertLocked(); committed = plaintext.copyOf() }
        }
        SourceSetupStore(memory, file).resetObsolete()
        assertNotNull(committed)
        RandomAccessFile(file, "rw").use { handle -> handle.channel.tryLock().use { assertNotNull(it) } }
    }

    @Test fun committedSaveReturnsPreferencesWithoutRereading() {
        var written = false
        val memory = object : PrivateSecretStore {
            override fun read(): ByteArray? { check(!written); return null }
            override fun write(plaintext: ByteArray) { written = true }
        }
        assertEquals("News", SourceSetupStore(memory).save(PrototypeSource.ABEMA_LIVE, SourceSetup("News"))[PrototypeSource.ABEMA_LIVE]!!.name)
    }

    @Test fun fileLockCoversReadsAndWritesAndIsReleasedOnFailure() {
        val file = temporary.newFile("source-setup.lock")
        var fail = false
        val memory = object : PrivateSecretStore {
            override fun read(): ByteArray? {
                RandomAccessFile(file, "rw").use { handle ->
                    assertThrows(OverlappingFileLockException::class.java) { handle.channel.tryLock() }
                }
                check(!fail)
                return null
            }
            override fun write(plaintext: ByteArray) {
                RandomAccessFile(file, "rw").use { handle ->
                    assertThrows(OverlappingFileLockException::class.java) { handle.channel.tryLock() }
                }
            }
        }
        val store = SourceSetupStore(memory, file)
        store.read()
        store.save(PrototypeSource.ABEMA_LIVE, SourceSetup("News"))
        fail = true
        assertThrows(IllegalStateException::class.java) { store.read() }
        RandomAccessFile(file, "rw").use { handle -> handle.channel.tryLock().use { assertNotNull(it) } }
    }
}
