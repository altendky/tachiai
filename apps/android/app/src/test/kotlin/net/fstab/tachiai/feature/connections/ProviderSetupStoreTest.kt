package net.fstab.tachiai.feature.connections

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProviderSetupStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        override fun read() = bytes
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf() }
    }
    private val japan = SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, "12345678-1234-1234-1234-123456789abc", "Proton Japan")
    @Test fun explicitProviderSaveResolvesOnlyThatProviderAndLeavesLegacyBytesUntouched() {
        val legacyMemory = Memory()
        val legacyStore = SourceSetupStore(legacyMemory)
        legacyStore.save(PrototypeSource.ABEMA_REPLAY, SourceSetup("My sumo", japan))
        legacyStore.save(PrototypeSource.TWITCH_LIVE, SourceSetup("My Twitch", feedA = SourceRouteChoice.system))
        val original = legacyMemory.bytes!!.copyOf()
        val legacy = legacyStore.read()
        val memory = Memory(); val store = ProviderSetupStore(memory)
        assertNull(store.read(legacy)[PrototypeService.ABEMA]!!.route)
        val committed = store.save(PrototypeService.ABEMA, japan, legacy)
        assertEquals(japan, committed[PrototypeService.ABEMA]!!.route)
        assertNull(committed[PrototypeService.TWITCH]!!.route)
        assertEquals(japan, ProviderSetupStore(memory).read(legacy)[PrototypeService.ABEMA]!!.route)
        assertArrayEquals(original, legacyMemory.bytes)
        assertFalse(String(memory.bytes!!).contains("PrivateKey"))
    }
    @Test fun corruptProviderSettingsCannotBeOverwrittenOrBecomeSystem() {
        val memory = Memory().apply { bytes = byteArrayOf(1, 2, 3) }
        val store = ProviderSetupStore(memory)
        assertThrows(Exception::class.java) { store.read(defaultSourceSetups()) }
        assertThrows(Exception::class.java) { store.save(PrototypeService.ABEMA, japan, defaultSourceSetups()) }
        assertArrayEquals(byteArrayOf(1, 2, 3), memory.bytes)
    }
    @Test fun obsoleteStreamResetPreservesProviderPreferencesAndImportedRouteSlot() {
        val importedRoutes = Memory().apply { bytes = byteArrayOf(7, 8, 9) }
        val providerMemory = Memory()
        val providers = ProviderSetupStore(providerMemory)
        providers.save(PrototypeService.ABEMA, japan, defaultSourceSetups())
        providers.save(PrototypeService.TWITCH, SourceRouteChoice.system, defaultSourceSetups())
        val providerBytes = providerMemory.bytes!!.copyOf()
        val importedBytes = importedRoutes.bytes!!.copyOf()
        val obsolete = ByteArrayOutputStream().also { output -> DataOutputStream(output).use { data ->
            data.writeInt(1); data.writeInt(4)
            listOf("ABEMA_LIVE", "ABEMA_REPLAY", "TWITCH_LIVE", "TWITCH_REPLAY").forEach { identity ->
                data.writeUTF(identity); data.writeUTF("Obsolete stream")
                data.writeUTF("SYSTEM"); data.writeUTF("SOURCE_DEFAULT"); data.writeUTF("SOURCE_DEFAULT")
            }
        } }.toByteArray()
        val sourceMemory = Memory().apply { bytes = obsolete }
        val sources = SourceSetupStore(sourceMemory)
        assertThrows(ObsoleteSourceSetupException::class.java) { sources.read() }
        sources.resetObsolete()
        val restoredProviders = providers.read(sources.read())
        assertEquals(japan, restoredProviders[PrototypeService.ABEMA]!!.route)
        assertEquals(SourceRouteChoice.system, restoredProviders[PrototypeService.TWITCH]!!.route)
        assertArrayEquals(providerBytes, providerMemory.bytes)
        assertArrayEquals(importedBytes, importedRoutes.bytes)
    }
    @Test fun fileLockProtectsCompleteMutationAndCommittedSaveDoesNotReadAgain() {
        val file = temporary.newFile("provider.lock")
        var committed = false
        fun assertLocked() = RandomAccessFile(file, "rw").use { handle ->
            assertThrows(OverlappingFileLockException::class.java) { handle.channel.tryLock() }
        }
        val memory = object : PrivateSecretStore {
            override fun read(): ByteArray? { assertLocked(); check(!committed); return null }
            override fun write(plaintext: ByteArray) { assertLocked(); committed = true }
        }
        assertEquals(japan, ProviderSetupStore(memory, file).save(PrototypeService.ABEMA, japan, defaultSourceSetups())[PrototypeService.ABEMA]!!.route)
        RandomAccessFile(file, "rw").use { handle -> handle.channel.tryLock().use { assertNotNull(it) } }
    }
}
