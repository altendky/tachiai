package net.fstab.tachiai.feature.connections

import java.io.RandomAccessFile
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.channels.OverlappingFileLockException
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
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

    @Test fun originalAndLastProvidersCanBeRemovedWithoutLegacyResurrectionOrIdentityReuse() {
        val memory = Memory(); val store = ProviderInstanceStore(memory)
        val initial = store.read(::defaults)
        val remaining = store.remove(initial.first().id, ::defaults)
        assertEquals(initial.drop(1), remaining)
        assertTrue(store.remove(remaining.single().id, ::defaults).isEmpty())
        assertTrue(ProviderInstanceStore(memory).read { error("Empty registry must not remigrate") }.isEmpty())
        val empty = memory.bytes!!.copyOf()
        assertThrows(Exception::class.java) { store.save(initial.first().id, null, japan, ::defaults) }
        assertArrayEquals(empty, memory.bytes)
        val replacement = store.create(PrototypeService.ABEMA) { error("Do not remigrate") }.single()
        assertEquals("ABEMA", replacement.name)
        assertTrue(initial.none { it.id == replacement.id })
        assertEquals(SourceRouteChoice.system, replacement.setup.route)
    }

    @Test fun reorderPreservesLatestInstanceDataAndRejectsStaleOrInvalidMembership() {
        val memory = Memory(); val first = ProviderInstanceStore(memory); val second = ProviderInstanceStore(memory)
        val initial = first.create(PrototypeService.TWITCH, ::defaults)
        val ids = initial.map { it.id }.reversed()
        second.save(initial.last().id, "My Twitch", japan, ::defaults)
        val reordered = first.reorder(ids, ::defaults)
        assertEquals(ids, reordered.map { it.id })
        assertEquals("My Twitch", reordered.first().name)
        assertEquals(japan, reordered.first().setup.route)
        assertEquals(reordered, ProviderInstanceStore(memory).read(::defaults))
        val committed = memory.bytes!!.copyOf()
        for (invalid in listOf(ids.drop(1), ids + ids.first(), listOf(ids.first(), ids.first(), ids.last()),
            ids.drop(1) + "00000000-0000-0000-0000-000000009999")) {
            assertThrows(Exception::class.java) { first.reorder(invalid, ::defaults) }
            assertArrayEquals(committed, memory.bytes)
        }
        second.remove(ids.first(), ::defaults)
        val afterRemoval = memory.bytes!!.copyOf()
        assertThrows(Exception::class.java) { first.reorder(ids, ::defaults) }
        assertThrows(Exception::class.java) { first.remove(ids.first(), ::defaults) }
        assertArrayEquals(afterRemoval, memory.bytes)
    }

    @Test fun failedRemovalAndReorderKeepCommittedRegistryAndLegacyFormatsStillRequireDefaults() {
        val memory = Memory(); val store = ProviderInstanceStore(memory)
        val entries = store.create(PrototypeService.TWITCH, ::defaults)
        val committed = memory.bytes!!.copyOf()
        memory.failWrite = true
        assertThrows(Exception::class.java) { store.remove(entries.first().id, ::defaults) }
        assertThrows(Exception::class.java) { store.reorder(entries.map { it.id }.reversed(), ::defaults) }
        assertArrayEquals(committed, memory.bytes)
        for (version in listOf(1, 2, 3)) {
            memory.failWrite = false
            memory.bytes = ByteArrayOutputStream().also { output -> DataOutputStream(output).use {
                it.writeInt(version); it.writeInt(0)
            } }.toByteArray()
            if (version == 3) assertTrue(store.read(::defaults).isEmpty())
            else assertThrows(Exception::class.java) { store.read(::defaults) }
        }
    }

    @Test fun versionOneRegistryIsReadWithoutWritingAndMigratesOnlyOnExplicitMutation() {
        // Hand-written old format: a named route plus an unresolved legacy review.
        val original = registryFixture(1)
        val memory = Memory().apply { bytes = original.copyOf() }
        val store = ProviderInstanceStore(memory)
        val entries = store.read { error("An explicit old registry cannot fall back to legacy settings") }
        assertEquals("Japan ABEMA", entries.first().name)
        assertEquals(listOf(japan), entries.first().setup.routes)
        assertNull(entries.last().setup.route)
        assertTrue(entries.last().setup.routes.isEmpty())
        assertEquals(listOf(japan, SourceRouteChoice.system), entries.last().setup.previousRoutes)
        assertEquals(0, memory.writes)
        assertArrayEquals(original, memory.bytes)

        val saved = store.saveRoutes(entries.first().id, entries.first().customName,
            listOf(japan, SourceRouteChoice.system)) { error("Do not consult legacy settings") }
        assertEquals(1, memory.writes)
        assertEquals(3, DataInputStream(memory.bytes!!.inputStream()).use { it.readInt() })
        assertEquals(entries.last(), saved.last())
        assertEquals(saved, store.read { error("Do not consult legacy settings") })
    }

    @Test fun routePoolsRoundTripReplacePromoteAndClearWithoutResolvingProfileReferences() {
        val memory = Memory(); val store = ProviderInstanceStore(memory)
        val entries = store.read(::defaults)
        val first = entries.first()
        // A profile may have been removed; persistence retains its ID for explicit repair.
        val missing = japan.copy(connectionId = "00000000-0000-0000-0000-000000009999", connectionName = "Missing exit")
        val pool = listOf(japan, missing, SourceRouteChoice.system)
        val saved = store.saveRoutes(first.id, "Ordered routes", pool, ::defaults)
        assertEquals(pool, saved.first().setup.routes)
        assertEquals(saved, store.read(::defaults))
        assertEquals(entries.drop(1), saved.drop(1))
        assertEquals(pool.drop(1), store.saveRoutes(first.id, null, pool.drop(1), ::defaults).first().setup.routes)
        assertEquals(missing, store.read(::defaults).first().setup.route)
        assertTrue(store.saveRoutes(first.id, null, emptyList(), ::defaults).first().setup.routes.isEmpty())
        assertNull(store.read(::defaults).first().setup.route)
        store.saveRoutes(first.id, null, pool, ::defaults)
        assertEquals(listOf(japan), store.save(first.id, null, japan, ::defaults).first().setup.routes)
    }

    @Test fun invalidPoolsAndFailedWholePoolWritesPreserveCommittedBytes() {
        val memory = Memory(); val store = ProviderInstanceStore(memory)
        val id = store.create(PrototypeService.TWITCH, ::defaults).last().id
        store.saveRoutes(id, "Routes", listOf(japan, SourceRouteChoice.system), ::defaults)
        val original = memory.bytes!!.copyOf()
        for (routes in listOf(listOf(japan, japan.copy(connectionName = "Same ID")),
            listOf(SourceRouteChoice.inherit), List(MAX_PROVIDER_ROUTES + 1) { SourceRouteChoice.system })) {
            assertThrows(Exception::class.java) { store.saveRoutes(id, null, routes, ::defaults) }
            assertArrayEquals(original, memory.bytes)
        }
        assertThrows(Exception::class.java) {
            store.saveRoutes("00000000-0000-0000-0000-000000009999", null, emptyList(), ::defaults)
        }
        assertArrayEquals(original, memory.bytes)
        memory.failWrite = true
        assertThrows(Exception::class.java) { store.saveRoutes(id, null, emptyList(), ::defaults) }
        assertArrayEquals(original, memory.bytes)
    }

    @Test fun unknownVersionsTruncationDuplicatesAndInvalidCountsFailClosedWithoutMutation() {
        val v2 = registryFixture(2)
        val fixtures = listOf(registryFixture(0), registryFixture(4), registryFixture(2, -1),
            registryFixture(2, MAX_PROVIDER_ROUTES + 1), registryFixture(2, 2),
            registryFixture(1, firstMode = "UNRECOGNIZED"), registryFixture(2, firstMode = "SOURCE_DEFAULT"),
            v2.copyOf(v2.size - 1), v2 + byteArrayOf(0), byteArrayOf(0, 0, 0), ByteArray(PRIVATE_SECRET_LIMIT + 1))
        for (fixture in fixtures) {
            val memory = Memory().apply { bytes = fixture.copyOf() }
            val store = ProviderInstanceStore(memory)
            assertThrows(Exception::class.java) { store.read(::defaults) }
            assertThrows(Exception::class.java) { store.create(PrototypeService.ABEMA, ::defaults) }
            assertEquals(0, memory.writes)
            assertArrayEquals(fixture, memory.bytes)
        }
    }

    @Test fun boundedEncryptedRecordLimitRejectsOversizedPoolMutationWithoutChangingRegistry() {
        val memory = Memory(); val store = ProviderInstanceStore(memory)
        while (store.read(::defaults).size < MAX_PROVIDER_INSTANCES) store.create(PrototypeService.TWITCH, ::defaults)
        val routes = (1..MAX_PROVIDER_ROUTES).map {
            SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION,
                "00000000-0000-0000-0000-%012d".format(it), "x".repeat(64))
        }
        var rejected = false
        for (entry in store.read(::defaults)) {
            val original = memory.bytes!!.copyOf()
            try {
                store.saveRoutes(entry.id, null, routes, ::defaults)
            } catch (_: IllegalStateException) {
                assertArrayEquals(original, memory.bytes)
                rejected = true
                break
            }
        }
        assertTrue("A full registry of maximum-size pools must respect the shared record bound", rejected)
        assertEquals(MAX_PROVIDER_INSTANCES, store.read(::defaults).size)
    }

    private fun registryFixture(version: Int, firstRouteCount: Int = 1, firstMode: String = "SAVED_CONNECTION"): ByteArray =
        ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { data ->
            data.writeInt(version); data.writeInt(2)
            data.writeUTF("00000000-0000-0000-0000-000000000001"); data.writeUTF("ABEMA")
            data.writeUTF("ABEMA"); data.writeBoolean(true); data.writeUTF("Japan ABEMA")
            if (version == 1) data.writeBoolean(true) else data.writeInt(firstRouteCount)
            repeat(if (version == 1) 1 else firstRouteCount.coerceIn(0, MAX_PROVIDER_ROUTES + 1)) {
                data.writeUTF(firstMode)
                if (firstMode == "SAVED_CONNECTION") {
                    data.writeUTF("12345678-1234-1234-1234-123456789abc"); data.writeUTF("Japan")
                }
            }
            data.writeInt(0)
            data.writeUTF("00000000-0000-0000-0000-000000000002"); data.writeUTF("TWITCH")
            data.writeUTF("Twitch"); data.writeBoolean(false)
            if (version == 1) data.writeBoolean(false) else data.writeInt(0)
            data.writeInt(2)
            data.writeUTF("SAVED_CONNECTION"); data.writeUTF("12345678-1234-1234-1234-123456789abc"); data.writeUTF("Japan")
            data.writeUTF("SYSTEM")
        }
    }.toByteArray()
}
