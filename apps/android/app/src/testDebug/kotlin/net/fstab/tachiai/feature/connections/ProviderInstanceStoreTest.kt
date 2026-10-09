package net.fstab.tachiai.feature.connections

import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProviderInstanceStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        var writes = 0
        var failWrite = false
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { check(!failWrite); bytes = plaintext.copyOf(); writes++ }
    }
    private val japan = SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION,
        "12345678-1234-1234-1234-123456789abc", "Japan")
    private fun defaults() = legacyProviderSetups(defaultSourceSetups())

    @Test fun migrationIsReadOnlyAndFirstExplicitWritePreservesPendingChoicesAndLegacyBytes() {
        val sourcesMemory = Memory(); val sources = SourceSetupStore(sourcesMemory)
        sources.save(PrototypeSource.ABEMA_REPLAY, SourceSetup("My sumo", japan))
        val providersMemory = Memory(); val providers = ProviderSetupStore(providersMemory)
        providers.save(PrototypeService.TWITCH, japan, sources.read())
        val originalSources = sourcesMemory.bytes!!.copyOf()
        val originalProviders = providersMemory.bytes!!.copyOf()
        val memory = Memory(); val store = ProviderInstanceStore(memory)
        fun legacy() = providers.read(sources.read())
        val initial = store.read(::legacy)
        assertEquals(0, memory.writes)
        assertNull(initial.first { it.service == PrototypeService.ABEMA }.setup.route)
        assertEquals(japan, initial.first { it.service == PrototypeService.TWITCH }.setup.route)
        val created = store.create(PrototypeService.TWITCH, ::legacy)
        assertEquals(initial, created.take(2))
        assertEquals("Twitch 2", created.last().name)
        assertEquals(SourceRouteChoice.system, created.last().setup.route)
        val restored = ProviderInstanceStore(memory).read { error("Explicit registry must not read legacy settings") }
        assertEquals(created, restored)
        assertArrayEquals(originalSources, sourcesMemory.bytes)
        assertArrayEquals(originalProviders, providersMemory.bytes)
        val default = created.first { it.service == PrototypeService.ABEMA }
        val saved = store.save(default.id, "Japan ABEMA", japan, ::legacy)
        assertEquals(default.setup.previousRoutes, saved.first().setup.previousRoutes)
        assertEquals(created.drop(1), saved.drop(1))
    }

    @Test fun failedWritesCorruptionAndStaleIdsNeverReplaceTheCommittedRegistry() {
        val memory = Memory(); val store = ProviderInstanceStore(memory)
        val created = store.create(PrototypeService.TWITCH, ::defaults)
        val committed = memory.bytes!!.copyOf()
        memory.failWrite = true
        assertThrows(Exception::class.java) { store.save(created.last().id, "Other", japan, ::defaults) }
        assertArrayEquals(committed, memory.bytes)
        memory.failWrite = false
        assertThrows(Exception::class.java) { store.save("12345678-1234-1234-1234-123456789abc", null, japan, ::defaults) }
        assertArrayEquals(committed, memory.bytes)
        memory.bytes = byteArrayOf(1, 2, 3)
        assertThrows(Exception::class.java) { store.read(::defaults) }
        assertThrows(Exception::class.java) { store.create(PrototypeService.ABEMA, ::defaults) }
        assertArrayEquals(byteArrayOf(1, 2, 3), memory.bytes)
    }

    @Test fun namesAreStableUniqueBoundedAndResetOnlyToTheInstancesOwnDefault() {
        val memory = Memory(); val store = ProviderInstanceStore(memory)
        val second = store.create(PrototypeService.TWITCH, ::defaults).last()
        val named = store.save(second.id, "My account", japan, ::defaults).last()
        assertEquals(second.id, named.id); assertEquals("Twitch 2", named.defaultName)
        assertThrows(Exception::class.java) { store.save(second.id, "twitch", japan, ::defaults) }
        assertThrows(Exception::class.java) { store.save(second.id, "x".repeat(65), japan, ::defaults) }
        assertThrows(Exception::class.java) { store.save(second.id, "Bad\u200bname", japan, ::defaults) }
        store.save(defaultProviderInstanceId(PrototypeService.TWITCH), "Account İ", SourceRouteChoice.system, ::defaults)
        assertThrows(Exception::class.java) { store.save(second.id, "Account i\u0307", japan, ::defaults) }
        assertTrue(sameProviderInstanceName("Account İ", "Account i\u0307"))
        assertFalse(sameProviderInstanceName("Account İ", "Account I"))
        val reset = store.save(second.id, null, japan, ::defaults).last()
        assertEquals("Twitch 2", reset.name); assertEquals(japan, reset.setup.route)
        while (store.read(::defaults).size < MAX_PROVIDER_INSTANCES) store.create(PrototypeService.ABEMA, ::defaults)
        val bytes = memory.bytes!!.copyOf()
        assertThrows(Exception::class.java) { store.create(PrototypeService.TWITCH, ::defaults) }
        assertArrayEquals(bytes, memory.bytes)
    }

    @Test fun storesReadLatestRegistryAndHoldFileLockAcrossCompleteMutation() {
        val file = temporary.newFile("instances.lock")
        val memory = Memory()
        val guarded = object : PrivateSecretStore {
            fun locked() = RandomAccessFile(file, "rw").use { handle ->
                assertThrows(OverlappingFileLockException::class.java) { handle.channel.tryLock() }
            }
            override fun read(): ByteArray? { locked(); return memory.read() }
            override fun write(plaintext: ByteArray) { locked(); memory.write(plaintext) }
        }
        val first = ProviderInstanceStore(guarded, file)
        val second = ProviderInstanceStore(guarded, file)
        val twitch = first.create(PrototypeService.TWITCH, ::defaults).last()
        val abema = second.create(PrototypeService.ABEMA, ::defaults).last()
        first.save(twitch.id, "Account one", japan, ::defaults)
        val latest = second.save(abema.id, "Japan ABEMA", japan, ::defaults)
        assertEquals("Account one", latest.single { it.id == twitch.id }.name)
        assertEquals("Japan ABEMA", latest.single { it.id == abema.id }.name)
        RandomAccessFile(file, "rw").use { it.channel.tryLock().use { lock -> assertNotNull(lock) } }
    }
}
