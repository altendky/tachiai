package net.fstab.tachiai.presentation

import java.util.UUID
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class ConfiguredCollectionLatestSelectionTest {
    private val instances = defaultProviderInstances()
    private val abema = instances.single { it.service == PrototypeService.ABEMA }
    private val twitch = instances.single { it.service == PrototypeService.TWITCH }
    private fun saved(resource: CatalogResource, owner: ProviderInstance = abema,
        availability: CatalogAvailability = CatalogAvailability.UNKNOWN) =
        ConfiguredSource(UUID.randomUUID().toString(), owner.id, CatalogEntry(resource, "Saved collection", availability))
    private val series = saved(CatalogResource(ProviderId("abema"), "series", "394-72", CatalogIntent.COLLECTION))
    private val season = saved(CatalogResource(ProviderId("abema"), "season", "394-72/394-72_s1", CatalogIntent.COLLECTION))
    private val episode = CatalogResource(ProviderId("abema"), "episode", "394-72_s1_p15", CatalogIntent.VIDEO)
    private val older = CatalogResource(ProviderId("abema"), "episode", "394-72_s1_p14", CatalogIntent.VIDEO)
    private fun resolve(a: ConfiguredSource?, b: ConfiguredSource?, latest: List<ConfiguredLatestEpisode?> = listOf(null, null),
        allowEpisodes: Boolean = true) = resolveConfiguredPrototypeSelection(
        ConfiguredFeedAssignments(a?.choice, b?.choice), listOfNotNull(a, b).distinct(), instances,
        allowConfiguredAbemaEpisodes = allowEpisodes, latest = latest)
    private fun bound(source: ConfiguredSource, resource: CatalogResource = episode) =
        ConfiguredLatestEpisode(source.choice, source.entry.resource, resource)

    @Test fun supportedCollectionsEnableWatchAndAskForResolutionPerSlot() {
        val result = resolve(series, season) as ConfiguredPrototypeSelectionResult.NeedsLatest
        assertTrue(result.canWatch)
        assertEquals(listOf(0, 1), result.slots)
        assertEquals(listOf(series, season), result.collections)
        val one = resolve(null, series) as ConfiguredPrototypeSelectionResult.NeedsLatest
        assertEquals(listOf(1), one.slots)
        assertEquals(listOf(null, series), one.collections)
    }

    @Test fun resolvedEpisodeBindsAsExactReplayWhileTheSavedCollectionStaysIntact() {
        val exact = saved(older.copy(identity = "fixture-exact"))
        val ready = resolve(series, exact, listOf(bound(series), null)) as ConfiguredPrototypeSelectionResult.Ready
        val feed = checkNotNull(ready.selection.a)
        assertEquals(episode, feed.resource)
        assertEquals(series.entry.resource, feed.collection)
        assertEquals(series.choice, feed.choice)
        assertEquals(PrototypePlaybackKind.REPLAY, feed.kind)
        assertEquals("ABEMA_CONFIGURED_EPISODE", feed.diagnosticName)
        assertEquals(listOf(series, exact), ready.sourceSlots)
        assertNull(checkNotNull(ready.selection.b).collection)
        assertEquals(CatalogIntent.COLLECTION, series.entry.resource.intent)
    }

    @Test fun duplicateCollectionAssignmentsKeepIndependentSlots() {
        val ready = resolve(series, series, listOf(bound(series), bound(series))) as ConfiguredPrototypeSelectionResult.Ready
        assertEquals(listOf(episode, episode), ready.selection.slots.map { it?.resource })
        assertEquals(listOf(series.choice, series.choice), ready.selection.slots.map { it?.choice })
        assertTrue(ready.selection.isPair)
    }

    @Test fun aResolutionForAnotherCollectionOrItemIsNotApplied() {
        val other = saved(series.entry.resource.copy(identity = "394-73"))
        // Same slot, now a different collection: resolve again rather than reuse.
        assertTrue(resolve(other, null, listOf(bound(series), null)) is ConfiguredPrototypeSelectionResult.NeedsLatest)
        // Same resource re-added under a new local item.
        val readded = series.copy(id = UUID.randomUUID().toString())
        assertTrue(resolve(readded, null, listOf(bound(series), null)) is ConfiguredPrototypeSelectionResult.NeedsLatest)
        // A resolution for slot B never applies to slot A.
        assertEquals(listOf(0), (resolve(series, null, listOf(null, bound(series))) as ConfiguredPrototypeSelectionResult.NeedsLatest).slots)
    }

    @Test fun ordinaryChecksStillRunBeforeResolution() {
        listOf(CatalogAvailability.UPCOMING, CatalogAvailability.EXPIRED, CatalogAvailability.UNAVAILABLE,
            CatalogAvailability.OFFLINE).forEach { availability ->
            assertEquals(ConfiguredPrototypeSelectionResult.Failure(ConfiguredPrototypeSelectionFailure.UNAVAILABLE),
                resolve(saved(series.entry.resource, availability = availability), null))
        }
        assertEquals(ConfiguredPrototypeSelectionResult.Failure(ConfiguredPrototypeSelectionFailure.UNSUPPORTED),
            resolve(series, null, allowEpisodes = false))
        val missingRoute = abema.copy(setup = ProviderSetup(null))
        assertEquals(ConfiguredPrototypeSelectionResult.Failure(ConfiguredPrototypeSelectionFailure.ROUTE_REQUIRED),
            resolveConfiguredPrototypeSelection(ConfiguredFeedAssignments(series.choice, null), listOf(series),
                instances.map { if (it.id == abema.id) missingRoute else it }))
        assertEquals(ConfiguredPrototypeSelectionResult.Failure(ConfiguredPrototypeSelectionFailure.PROVIDER_MISMATCH),
            resolve(saved(series.entry.resource, owner = twitch), null))
    }

    @Test fun unsupportedCollectionsKeepTheExistingRefusal() {
        listOf(
            CatalogResource(ProviderId("abema"), "series", "series.dotted", CatalogIntent.COLLECTION),
            CatalogResource(ProviderId("abema"), "season", "394-72", CatalogIntent.COLLECTION),
            CatalogResource(ProviderId("abema"), "genre", "sumo", CatalogIntent.COLLECTION),
        ).forEach { resource ->
            assertEquals(ConfiguredPrototypeSelectionResult.Failure(ConfiguredPrototypeSelectionFailure.COLLECTION),
                resolve(saved(resource), null))
        }
        val twitchCollection = saved(CatalogResource(ProviderId("twitch"), "collection", "123", CatalogIntent.COLLECTION), twitch)
        assertEquals(ConfiguredPrototypeSelectionResult.Failure(ConfiguredPrototypeSelectionFailure.COLLECTION),
            resolve(twitchCollection, null))
    }

    @Test fun latestBindingsAndFeedsRequireExactCollectionAndEpisodeIdentities() {
        assertThrows(IllegalArgumentException::class.java) { ConfiguredLatestEpisode(series.choice, episode, episode) }
        assertThrows(IllegalArgumentException::class.java) { ConfiguredLatestEpisode(series.choice, series.entry.resource, series.entry.resource) }
        assertThrows(IllegalArgumentException::class.java) {
            ConfiguredPlaybackFeed(series.choice, episode, PrototypeService.ABEMA, PrototypePlaybackKind.LIVE, series.entry.resource)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConfiguredPlaybackFeed(series.choice, series.entry.resource, PrototypeService.ABEMA, PrototypePlaybackKind.REPLAY)
        }
        assertThrows(IllegalArgumentException::class.java) { resolve(series, null, listOf(null)) }
    }
}
