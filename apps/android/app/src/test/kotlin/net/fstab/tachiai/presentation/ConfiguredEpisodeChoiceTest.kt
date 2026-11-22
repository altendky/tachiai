package net.fstab.tachiai.presentation

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.UUID
import net.fstab.tachiai.feature.connections.ViewerSelectionStore
import net.fstab.tachiai.feature.presentation.ConfiguredRecoveryCheckpoint
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConfiguredEpisodeChoiceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val instances = defaultProviderInstances()
    private val abema = instances.single { it.service == PrototypeService.ABEMA }
    private val twitch = instances.single { it.service == PrototypeService.TWITCH }
    private fun saved(resource: CatalogResource, owner: ProviderInstance = abema,
        availability: CatalogAvailability = CatalogAvailability.UNKNOWN) =
        ConfiguredSource(UUID.randomUUID().toString(), owner.id, CatalogEntry(resource, "Saved collection", availability))
    private val series = saved(CatalogResource(ProviderId("abema"), "series", "394-72", CatalogIntent.COLLECTION))
    private val season = saved(CatalogResource(ProviderId("abema"), "season", "394-72/394-72_s10", CatalogIntent.COLLECTION))
    private fun episode(id: String, collection: ConfiguredSource = series, title: String = "大相撲LIVE 十一月場所 12日目｜11.19") =
        ConfiguredEpisodeChoice(collection.entry.resource, CatalogResource(ProviderId("abema"), "episode", id, CatalogIntent.VIDEO), title)
    private val day12 = episode("394-72_s10_p8541")
    private val day11 = episode("394-72_s10_p8540", title = "大相撲LIVE 十一月場所 11日目｜11.18")
    private fun resolve(assignments: ConfiguredFeedAssignments, sources: List<ConfiguredSource> = listOf(series, season),
        providers: List<ProviderInstance> = instances, allowEpisodes: Boolean = true) =
        resolveConfiguredPrototypeSelection(assignments, sources, providers, allowCollectionEpisodes = allowEpisodes)
    private fun failed(reason: ConfiguredPrototypeSelectionFailure, result: ConfiguredPrototypeSelectionResult) =
        assertEquals(ConfiguredPrototypeSelectionResult.Failure(reason), result)

    @Test fun encodingIsAsciiStrictAndRoundTripsJapaneseTitles() {
        val encoded = day12.encode()
        assertTrue(encoded.all { it.code in 33..126 })
        assertEquals(day12, ConfiguredEpisodeChoice.decode(encoded))
        for (bad in listOf(null, "", "e2" + encoded.drop(2), encoded + "|x", encoded.replace("|episode|", "|episode |"),
            encoded.dropLast(1) + "=", "x".repeat(ConfiguredEpisodeChoice.MAX_ENCODED + 1)))
            assertNull(bad, ConfiguredEpisodeChoice.decode(bad))
        assertThrows(IllegalArgumentException::class.java) { episode("e", title = " padded ") }
        assertThrows(IllegalArgumentException::class.java) { episode("e", title = "") }
        assertThrows(IllegalArgumentException::class.java) { episode("e", title = "x".repeat(161)) }
        assertThrows(IllegalArgumentException::class.java) {
            ConfiguredEpisodeChoice(day12.episode, day12.episode, "Not a collection")
        }
    }

    // #622: a broadcast listed under the series, chosen like an episode.
    private val day15 = ConfiguredEpisodeChoice(series.entry.resource,
        CatalogResource(ProviderId("abema"), "slot", "sumo/CQ2AnExAX8s7LT", CatalogIntent.BROADCAST),
        "大相撲LIVE 十一月場所（幕内）15日目[千秋楽]｜11.22")

    @Test fun videoEncodingIsUnchangedAndBroadcastsUseAVersionedForm() {
        // Stored before #622: the video form stays byte for byte.
        val stored = "e1|abema|series|394-72|episode|394-72_s10_p8541|" + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(day12.title.toByteArray(Charsets.UTF_8))
        assertEquals(stored, day12.encode())
        assertEquals(day12, ConfiguredEpisodeChoice.decode(stored))
        val encoded = day15.encode()
        assertTrue(encoded.startsWith("e2|abema|series|394-72|slot|sumo/CQ2AnExAX8s7LT|BROADCAST|"))
        assertTrue(encoded.all { it.code in 33..126 })
        assertEquals(day15, ConfiguredEpisodeChoice.decode(encoded))
        // Canonical only: a video in the e2 form, an unknown intent or a missing field.
        for (bad in listOf(encoded.replace("|BROADCAST|", "|VIDEO|"), encoded.replace("|BROADCAST|", "|LIVE|"),
            encoded.replace("|BROADCAST|", "|CHANNEL|"), encoded.replace("|BROADCAST|", "|"), "e1" + encoded.drop(2)))
            assertNull(bad, ConfiguredEpisodeChoice.decode(bad))
        assertThrows(IllegalArgumentException::class.java) {
            ConfiguredEpisodeChoice(series.entry.resource, CatalogResource(ProviderId("abema"), "channel", "sumo", CatalogIntent.CHANNEL), "Channel")
        }
        assertNull(ConfiguredEpisodeChoice.of(series.entry.resource,
            CatalogEntry(CatalogResource(ProviderId("abema"), "channel", "sumo", CatalogIntent.CHANNEL), "Channel")))
        assertEquals(day15, ConfiguredEpisodeChoice.of(series.entry.resource, CatalogEntry(day15.episode, day15.title)))
    }

    @Test fun watchPlaysAChosenBroadcastThroughTheConfiguredSlotPath() {
        val ready = resolve(ConfiguredFeedAssignments(series.choice, series.choice, day15, day12)) as ConfiguredPrototypeSelectionResult.Ready
        val broadcast = ready.selection.a!!
        assertEquals(day15.episode, broadcast.resource)
        assertEquals(series.entry.resource, broadcast.collection); assertEquals(day15.title, broadcast.episodeTitle)
        assertEquals(PrototypePlaybackKind.REPLAY, broadcast.kind); assertEquals("ABEMA_CONFIGURED_SLOT", broadcast.diagnosticName)
        assertEquals("ABEMA_CONFIGURED_EPISODE", ready.selection.b!!.diagnosticName)
        // Builds without the configured slot path refuse it like a saved slot.
        failed(ConfiguredPrototypeSelectionFailure.UNSUPPORTED, resolveConfiguredPrototypeSelection(
            ConfiguredFeedAssignments(series.choice, null, day15, null), listOf(series), instances, allowConfiguredAbemaSlots = false))
        // A broadcast kind without the configured slot path never plays.
        failed(ConfiguredPrototypeSelectionFailure.EPISODE_REQUIRED, resolve(ConfiguredFeedAssignments(series.choice, null,
            day15.copy(episode = day15.episode.copy(kind = "broadcast")), null)))
    }

    @Test fun storesAndCheckpointRoundTripAChosenBroadcast() {
        val both = ConfiguredFeedAssignments(series.choice, series.choice, day15, day12)
        val memory = Memory()
        ViewerSelectionStore(memory).save(both)
        assertEquals(both, ViewerSelectionStore(memory).read())
        assertEquals(both, decodeConfiguredFeedAssignments(encodeConfiguredFeedChoice(series.choice),
            encodeConfiguredFeedChoice(series.choice), day15.encode(), day12.encode()))
        val directory = temporary.newFolder()
        val checkpoint = ConfiguredRecoveryCheckpoint(directory, true)
        checkpoint.write(both)
        assertEquals(ConfiguredRecoveryCheckpoint.Result.Restored(both), checkpoint.consume())
    }

    @Test fun assigningEpisodesKeepsItemIdentityAndClearsOnItemChange() {
        val empty = ConfiguredFeedAssignments(null, null)
        val chosen = empty.assignEpisode(PrototypeSlot.A, series.choice, day12, true)
        assertEquals(ConfiguredFeedAssignments(series.choice, null, day12, null), chosen)
        // Same collection may feed B with another episode.
        val both = chosen.assignEpisode(PrototypeSlot.B, series.choice, day11, true)
        assertEquals(listOf(day12, day11), listOf(both.aEpisode, both.bEpisode))
        // Re-checking the same item keeps its episode; another item clears it.
        assertEquals(both, both.assign(PrototypeSlot.A, series.choice, true))
        assertEquals(ConfiguredFeedAssignments(season.choice, series.choice, null, day11),
            both.assign(PrototypeSlot.A, season.choice, true))
        // Unchecking the chosen episode, or the item, empties the slot.
        assertEquals(ConfiguredFeedAssignments(null, series.choice, null, day11),
            both.assignEpisode(PrototypeSlot.A, series.choice, day12, false))
        assertEquals(both, both.assignEpisode(PrototypeSlot.A, series.choice, day11, false))
        assertEquals(ConfiguredFeedAssignments(null, series.choice, null, day11), both.assign(PrototypeSlot.A, series.choice, false))
        assertThrows(IllegalArgumentException::class.java) { ConfiguredFeedAssignments(null, null, day12, null) }
        // Episode identity, not its title: a provider retitle still unchecks it.
        val retitled = day12.copy(title = "幕内全取組ハイライト 12日目")
        assertTrue(retitled.sameEpisode(day12))
        assertEquals(ConfiguredFeedAssignments(null, null), chosen.assignEpisode(PrototypeSlot.A, series.choice, retitled, false))
    }

    @Test fun restoringDropsEpisodesWhoseItemNoLongerHoldsTheirCollection() {
        val both = ConfiguredFeedAssignments(series.choice, series.choice, day12, day11)
        assertEquals(both, both.resolvedIn(listOf(series)))
        val replaced = series.copy(entry = series.entry.copy(resource = season.entry.resource))
        assertEquals(ConfiguredFeedAssignments(series.choice, series.choice), both.resolvedIn(listOf(replaced)))
        assertEquals(ConfiguredFeedAssignments(null, null), both.resolvedIn(emptyList()))
        assertEquals(both, decodeConfiguredFeedAssignments(encodeConfiguredFeedChoice(series.choice),
            encodeConfiguredFeedChoice(series.choice), day12.encode(), day11.encode()))
        assertEquals(ConfiguredFeedAssignments(null, series.choice, null, day11),
            decodeConfiguredFeedAssignments(null, encodeConfiguredFeedChoice(series.choice), day12.encode(), day11.encode()))
        assertEquals(ConfiguredFeedAssignments(series.choice, null),
            restoreConfiguredFeedAssignments(true, encodeConfiguredFeedChoice(series.choice), null, "corrupt", null))
    }

    @Test fun watchPlaysTheChosenExactEpisodeAndOtherwiseAsksForOne() {
        failed(ConfiguredPrototypeSelectionFailure.EPISODE_REQUIRED, resolve(ConfiguredFeedAssignments(series.choice, null)))
        val ready = resolve(ConfiguredFeedAssignments(series.choice, series.choice, day12, day11)) as ConfiguredPrototypeSelectionResult.Ready
        assertEquals(listOf(day12.episode, day11.episode), ready.selection.slots.map { it?.resource })
        assertEquals(listOf(series.entry.resource, series.entry.resource), ready.selection.slots.map { it?.collection })
        assertEquals(listOf(day12.title, day11.title), ready.selection.slots.map { it?.episodeTitle })
        assertEquals(listOf(series, series), ready.sourceSlots)
        assertTrue(ready.selection.feeds.all { it.kind == PrototypePlaybackKind.REPLAY && it.diagnosticName == "ABEMA_CONFIGURED_EPISODE" })
        // An episode of another collection never plays under this item.
        failed(ConfiguredPrototypeSelectionFailure.EPISODE_REQUIRED,
            resolve(ConfiguredFeedAssignments(series.choice, null, episode("394-73_p1", season), null)))
        val seasonReady = resolve(ConfiguredFeedAssignments(season.choice, null, episode("394-72_s10_p1", season), null))
        assertTrue(seasonReady is ConfiguredPrototypeSelectionResult.Ready)
    }

    @Test fun ordinaryChecksAndUnsupportedCollectionsKeepTheirRefusals() {
        val chosen = ConfiguredFeedAssignments(series.choice, null, day12, null)
        failed(ConfiguredPrototypeSelectionFailure.COLLECTION, resolve(chosen, allowEpisodes = false))
        failed(ConfiguredPrototypeSelectionFailure.UNAVAILABLE,
            resolve(chosen, listOf(series.copy(entry = series.entry.copy(availability = CatalogAvailability.EXPIRED)))))
        failed(ConfiguredPrototypeSelectionFailure.ROUTE_REQUIRED,
            resolve(chosen, providers = instances.map { if (it.id == abema.id) it.copy(setup = ProviderSetup(null)) else it }))
        val other = saved(CatalogResource(ProviderId("twitch"), "collection", "123", CatalogIntent.COLLECTION), twitch)
        failed(ConfiguredPrototypeSelectionFailure.COLLECTION, resolve(ConfiguredFeedAssignments(other.choice, null), listOf(other)))
        val genre = saved(CatalogResource(ProviderId("abema"), "genre", "sumo", CatalogIntent.COLLECTION))
        failed(ConfiguredPrototypeSelectionFailure.COLLECTION, resolve(ConfiguredFeedAssignments(genre.choice, null), listOf(genre)))
        assertFalse(collectionEpisodesSupported(series.entry.resource, available = false))
        assertTrue(collectionEpisodesSupported(season.entry.resource, available = true))
        assertThrows(IllegalArgumentException::class.java) {
            ConfiguredPlaybackFeed(series.choice, day12.episode, PrototypeService.ABEMA, PrototypePlaybackKind.REPLAY, series.entry.resource)
        }
    }

    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf() }
    }

    @Test fun selectionStoreKeepsVersionOneWithoutEpisodesAndRoundTripsThem() {
        val memory = Memory()
        val store = ViewerSelectionStore(memory)
        val plain = ConfiguredFeedAssignments(series.choice, null)
        store.save(plain)
        val legacy = ByteArrayOutputStream().also { DataOutputStream(it).use { data ->
            data.writeInt(0x5456534c); data.writeInt(1); data.writeByte(1)
            data.write(series.id.toByteArray()); data.write(series.instanceId.toByteArray()); data.writeByte(0)
        } }.toByteArray()
        assertArrayEquals(legacy, memory.bytes)
        assertEquals(plain, store.read())
        val both = ConfiguredFeedAssignments(series.choice, season.choice, day12, null)
        store.save(both)
        assertEquals(both, store.read())
        assertEquals(2, java.nio.ByteBuffer.wrap(memory.bytes!!, 4, 4).int)
    }

    @Test fun recoveryCheckpointWritesV2WithoutEpisodesAndV3WithThem() {
        val directory = temporary.newFolder()
        val checkpoint = ConfiguredRecoveryCheckpoint(directory, true)
        val file = java.io.File(directory, ConfiguredRecoveryCheckpoint.fileName(true))
        checkpoint.write(ConfiguredFeedAssignments(series.choice, null))
        assertTrue(file.readText().startsWith("tachiai-configured-recovery-v2\n"))
        assertEquals(ConfiguredRecoveryCheckpoint.Result.Restored(ConfiguredFeedAssignments(series.choice, null)), checkpoint.consume())
        val both = ConfiguredFeedAssignments(series.choice, series.choice, day12, day11)
        checkpoint.write(both)
        assertTrue(file.readText().startsWith("tachiai-configured-recovery-v3\n"))
        assertTrue(file.length() <= ConfiguredRecoveryCheckpoint.MAX_BYTES)
        assertEquals(ConfiguredRecoveryCheckpoint.Result.Restored(both), checkpoint.consume())
        // A v2 header never carries episode fields.
        checkpoint.write(both)
        file.writeText(file.readText().replace("recovery-v3", "recovery-v2"))
        assertEquals(ConfiguredRecoveryCheckpoint.Result.Invalid, checkpoint.consume())
    }
}
