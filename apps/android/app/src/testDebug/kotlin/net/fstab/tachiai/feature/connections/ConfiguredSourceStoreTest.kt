package net.fstab.tachiai.feature.connections

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import net.fstab.tachiai.platform.media.*
import net.fstab.tachiai.platform.storage.*
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConfiguredSourceStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        var writes = 0
        var failWrite = false
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { check(!failWrite); bytes = plaintext.copyOf(); writes++ }
    }
    private val instance = defaultProviderInstances().single { it.service == PrototypeService.TWITCH }
    private val secondInstance = ProviderInstance("12345678-1234-1234-1234-123456789abc", PrototypeService.TWITCH, "Twitch 2")
    private val provider = ProviderId("twitch")
    private val video = NativeQualityRequest(NativeQualityTrack(NativeQualityKind.VIDEO, NativeQualityCodec.AVC, 2_000_000, 1280, 720))
    private val audio = NativeQualityRequest(NativeQualityTrack(NativeQualityKind.AUDIO, NativeQualityCodec.AAC, 192_000,
        channelCount = 2, sampleRateHz = 48_000))
    private fun entry(identity: String = "newchannel", intent: CatalogIntent = CatalogIntent.CHANNEL) =
        CatalogEntry(CatalogResource(provider, "channel", identity, intent), "New channel", CatalogAvailability.OFFLINE)
    private fun store(memory: Memory, owner: ProviderInstance = instance) = ConfiguredSourceStore(memory, owner.id, provider)
    private fun legacy(owner: ProviderInstance = instance) = legacyConfiguredSources(owner, defaultSourceSetups(), emptyMap())

    @Test fun legacyProjectionAndFirstWritePreserveOriginalNamesQualityAndRecordBytes() {
        val sourceMemory = Memory(); val sourceStore = SourceSetupStore(sourceMemory)
        sourceStore.save(PrototypeSource.TWITCH_LIVE, SourceSetup("My channel"))
        val qualityMemory = Memory(); val qualityStore = StreamQualityStore(qualityMemory)
        qualityStore.save(PrototypeSource.TWITCH_LIVE, NativeQualityKind.AUDIO, audio)
        val originalSources = sourceMemory.bytes!!.copyOf(); val originalQuality = qualityMemory.bytes!!.copyOf()
        fun projection() = legacyConfiguredSources(instance, sourceStore.read(), qualityStore.read())
        val memory = Memory(); val configured = store(memory)
        val initial = configured.read(::projection)
        assertEquals(0, memory.writes)
        val old = initial.single { it.id == configuredLegacyId(instance.id, PrototypeSource.TWITCH_LIVE) }
        assertEquals("My channel", old.entry.title); assertEquals(audio, old.quality.audio)
        val added = configured.add(entry(), ::projection)
        assertEquals(initial, added.dropLast(1)); assertEquals(1, memory.writes)
        assertEquals(added, store(memory).read { error("Committed list must not read legacy slots") })
        assertArrayEquals(originalSources, sourceMemory.bytes); assertArrayEquals(originalQuality, qualityMemory.bytes)
    }

    @Test fun deduplicationOrderingRefreshAndEmptyListsSurviveRestart() {
        val memory = Memory(); val configured = store(memory)
        val first = configured.add(entry(), { emptyList() }).single()
        val duplicate = configured.add(entry().copy(title = "Renamed upstream"), { error("No legacy read") })
        assertEquals(listOf(first), duplicate)
        val second = configured.add(entry("nextchannel"), { emptyList() }).last()
        assertNotEquals(first.id, second.id)
        assertEquals(listOf(second, first), configured.reorder(listOf(second.id, first.id), { emptyList() }))
        val upcoming = first.entry.copy(availability = CatalogAvailability.UPCOMING, scheduledStartEpochMs = 1234)
        val refreshed = configured.refresh(first.id, upcoming, { emptyList() })
        assertEquals(upcoming, refreshed.last().entry)
        configured.remove(first.id, { emptyList() }); configured.remove(second.id, { emptyList() })
        assertTrue(store(memory).read { error("Explicit empty list must not resurrect defaults") }.isEmpty())
        configured.add(entry(), { error("Explicit empty list is committed") })
        assertEquals(1, store(memory).read { emptyList() }.size)
    }

    @Test fun qualityChangesArePerInstanceAndPreserveOtherItemsAndKinds() {
        val firstMemory = Memory(); val secondMemory = Memory()
        val first = store(firstMemory); val second = store(secondMemory, secondInstance)
        val firstItems = first.read { legacy() }; val secondItems = second.read { legacy(secondInstance) }
        val id = firstItems.first().id
        first.saveQuality(id, NativeQualityKind.VIDEO, video, { firstItems })
        val saved = first.saveQuality(id, NativeQualityKind.AUDIO, audio, { firstItems })
        assertEquals(NativeQualityPreferences(video, audio), saved.first().quality)
        assertEquals(firstItems.drop(1), saved.drop(1))
        val reset = first.saveQuality(id, NativeQualityKind.VIDEO, NativeQualityRequest.auto, { firstItems })
        assertEquals(NativeQualityPreferences(audio = audio), reset.first().quality)
        assertEquals(reset, store(firstMemory).read { error("No legacy read") })
        assertEquals(secondItems, second.read { legacy(secondInstance) }); assertEquals(0, secondMemory.writes)
        assertThrows(Exception::class.java) { second.saveQuality(id, NativeQualityKind.AUDIO, audio, { secondItems }) }
        assertNull(secondMemory.bytes)
    }

    @Test fun incrementalMoveUsesLatestLockedOrderAndPreservesAnotherWritersAddedItem() {
        val memory = Memory(); val first = store(memory); val second = store(memory)
        val initial = first.add(entry("first"), { emptyList() }).single()
        val next = first.add(entry("second"), { emptyList() }).last()
        val staleView = first.read { emptyList() }
        val added = second.add(entry("third"), { emptyList() }).last()
        second.reorder(listOf(added.id, initial.id, next.id), { emptyList() })
        val moved = first.move(next.id, -1, { emptyList() })
        assertEquals(listOf(initial.id, next.id), staleView.map { it.id })
        assertEquals(listOf(added.id, next.id, initial.id), moved.map { it.id })
        assertEquals(moved, second.read { emptyList() })
        val bytes = memory.bytes!!.copyOf(); val writes = memory.writes
        listOf<() -> Unit>(
            { first.move(added.id, -1, { emptyList() }) },
            { first.move(initial.id, 1, { emptyList() }) },
            { first.move(next.id, 2, { emptyList() }) },
            { first.move(UUID.randomUUID().toString(), -1, { emptyList() }) },
        ).forEach { action -> assertThrows(Exception::class.java) { action() } }
        assertArrayEquals(bytes, memory.bytes); assertEquals(writes, memory.writes)
    }

    @Test fun staleIdsChangedResourcesAndInvalidPermutationsNeverWrite() {
        val memory = Memory(); val configured = store(memory)
        val items = configured.add(entry(), { emptyList() }); val old = items.single()
        val committed = memory.bytes!!.copyOf(); val writes = memory.writes
        val stale = UUID.randomUUID().toString()
        listOf<() -> Unit>(
            { configured.remove(stale, { emptyList() }) },
            { configured.refresh(stale, entry(), { emptyList() }) },
            { configured.saveQuality(stale, NativeQualityKind.AUDIO, audio, { emptyList() }) },
            { configured.refresh(old.id, entry("different"), { emptyList() }) },
            { configured.refresh(old.id, entry(intent = CatalogIntent.COLLECTION), { emptyList() }) },
            { configured.reorder(listOf(old.id, old.id), { emptyList() }) },
            { configured.reorder(listOf(stale), { emptyList() }) },
            { configured.add(CatalogEntry(CatalogResource(ProviderId("abema"), "channel", "sumo", CatalogIntent.CHANNEL), "Sumo"), { emptyList() }) },
        ).forEach { action -> assertThrows(Exception::class.java) { action() } }
        assertArrayEquals(committed, memory.bytes); assertEquals(writes, memory.writes)
    }

    private fun record(version: Int = 1, owner: String = instance.id, providerName: String = "twitch", count: Int = 0) =
        ByteArrayOutputStream().also { output -> DataOutputStream(output).use { data ->
            data.writeInt(version); data.writeUTF(owner); data.writeUTF(providerName); data.writeInt(count)
        } }.toByteArray()

    @Test fun malformedAndOversizedRecordsNeverFallBackOrGetOverwritten() {
        val memory = Memory(); store(memory).add(entry(), { emptyList() })
        val valid = memory.bytes!!.copyOf()
        val badQualityMarker = valid.copyOf().apply { this[lastIndex] = 2 }
        val item = store(memory).read { emptyList() }.single()
        val textFields = listOf(item.id, item.entry.resource.kind, item.entry.resource.identity,
            item.entry.resource.intent.name, item.entry.title, item.entry.availability.name)
        val startMarkerIndex = record(count = 1).size + textFields.sumOf { 2 + it.length }
        val badStartMarker = valid.copyOf().apply { this[startMarkerIndex] = 2 }
        val invalid = listOf(byteArrayOf(1), record(version = 2), record(owner = secondInstance.id),
            record(providerName = "abema"), record(count = -1), record(count = 33), record(count = 1),
            valid + byteArrayOf(0), valid.dropLast(1).toByteArray(), badQualityMarker, badStartMarker,
            ByteArray(PRIVATE_SECRET_LIMIT + 1))
        invalid.forEach { bytes ->
            val broken = Memory().apply { this.bytes = bytes.copyOf() }; val configured = store(broken)
            assertThrows(Exception::class.java) { configured.read { error("Must not fall back") } }
            assertThrows(Exception::class.java) { configured.add(entry("another"), { error("Must not fall back") }) }
            assertArrayEquals(bytes, broken.bytes); assertEquals(0, broken.writes)
        }
    }

    @Test fun invalidLegacyOwnershipDuplicatesAndSizeLimitsNeverCommit() {
        val memory = Memory(); val configured = store(memory)
        val first = legacy().first()
        val wrongProvider = first.copy(entry = CatalogEntry(CatalogResource(ProviderId("abema"), "channel", "sumo", CatalogIntent.CHANNEL), "Sumo"))
        listOf(listOf(first.copy(instanceId = secondInstance.id)), listOf(wrongProvider), listOf(first, first),
            listOf(first, first.copy(id = UUID.randomUUID().toString()))).forEach { entries ->
            assertThrows(Exception::class.java) { configured.read { entries } }
        }
        fun large(index: Int) = ConfiguredSource(UUID.randomUUID().toString(), instance.id,
            entry("channel$index" + "x".repeat(230)).copy(title = "x".repeat(160)))
        val tooMany = (0..MAX_CONFIGURED_SOURCES).map(::large)
        assertThrows(Exception::class.java) { configured.read { tooMany } }
        val tooLarge = tooMany.take(MAX_CONFIGURED_SOURCES)
        assertThrows(Exception::class.java) { configured.add(entry(), { tooLarge }) }
        assertNull(memory.bytes); assertEquals(0, memory.writes)
    }

    @Test fun countLimitRejectsAdditionalItemAndPreservesTheFullCommittedList() {
        val memory = Memory(); val configured = store(memory)
        repeat(MAX_CONFIGURED_SOURCES) { configured.add(entry("channel$it"), { emptyList() }) }
        val committed = memory.bytes!!.copyOf()
        val before = configured.read { emptyList() }
        assertEquals(MAX_CONFIGURED_SOURCES, before.size)
        assertThrows(Exception::class.java) { configured.add(entry("overflow"), { emptyList() }) }
        assertArrayEquals(committed, memory.bytes)
        assertEquals(before, store(memory).read { emptyList() })
    }

    @Test fun failedWritesKeepCommittedListAndDoNotInventMigration() {
        val memory = Memory(); val configured = store(memory)
        configured.add(entry(), { emptyList() }); val original = memory.bytes!!.copyOf()
        memory.failWrite = true
        assertThrows(Exception::class.java) { configured.add(entry("second"), { emptyList() }) }
        assertArrayEquals(original, memory.bytes)
        val absent = Memory().apply { failWrite = true }
        assertThrows(Exception::class.java) { store(absent).add(entry(), { legacy() }) }
        assertNull(absent.bytes); assertEquals(legacy(), store(absent).read { legacy() })
    }

    @Test fun completeReadModifyWriteHoldsFileLockAndReadsLatestOtherWriter() {
        val file = temporary.newFile("configured.lock"); val memory = Memory()
        val guarded = object : PrivateSecretStore {
            fun locked() = RandomAccessFile(file, "rw").use { handle ->
                assertThrows(OverlappingFileLockException::class.java) { handle.channel.tryLock() }
            }
            override fun read(): ByteArray? { locked(); return memory.read() }
            override fun write(plaintext: ByteArray) { locked(); memory.write(plaintext) }
        }
        val first = ConfiguredSourceStore(guarded, instance.id, provider, file)
        val second = ConfiguredSourceStore(guarded, instance.id, provider, file)
        val one = first.add(entry(), { emptyList() }).single()
        second.add(entry("next"), { emptyList() })
        val latest = first.saveQuality(one.id, NativeQualityKind.VIDEO, video, { emptyList() })
        assertEquals(2, latest.size); assertEquals(latest, second.read { error("No legacy read") })
        assertThrows(Exception::class.java) { first.remove(UUID.randomUUID().toString(), { emptyList() }) }
        RandomAccessFile(file, "rw").use { handle -> handle.channel.tryLock().use { lock -> assertNotNull(lock) } }
    }

    @Test fun catalogBindingsAcceptReservedInstancesAndRejectNoncanonicalIdsWithoutChangingLegacyBindings() {
        assertEquals("configured-provider-instance-${instance.id}", configuredProviderInstanceBindingName(instance.id))
        listOf("../slot", secondInstance.id.uppercase(), "${instance.id} ").forEach { id ->
            assertThrows(Exception::class.java) { configuredProviderInstanceBindingName(id) }
        }
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        fun binding(id: String) = "net.fstab.tachiai:${configuredProviderInstanceBindingName(id)}:v1".toByteArray()
        val ciphertext = encryptPrivateSecret(key, binding(instance.id), byteArrayOf(1))
        assertThrows(AEADBadTagException::class.java) { decryptPrivateSecret(key, binding(secondInstance.id), ciphertext) }
        assertEquals("source-setup", PrivateAuthorizationSlot.SOURCE_SETUP.bindingName)
        assertEquals("stream-quality", PrivateAuthorizationSlot.STREAM_QUALITY.bindingName)
    }
}
