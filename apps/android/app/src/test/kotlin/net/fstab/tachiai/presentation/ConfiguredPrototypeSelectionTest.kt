package net.fstab.tachiai.presentation

import java.util.UUID
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class ConfiguredPrototypeSelectionTest {
    private val instances = defaultProviderInstances()
    private val twitch = instances.single { it.service == PrototypeService.TWITCH }
    private val second = ProviderInstance("12345678-1234-1234-1234-123456789abc", PrototypeService.TWITCH, "Twitch 2")
    private val source = legacyConfiguredSources(twitch, defaultSourceSetups(), emptyMap()).first()
    private fun resolve(first: ConfiguredSource = source, secondSource: ConfiguredSource = first,
        providers: List<ProviderInstance> = instances) = resolveConfiguredPrototypeSelection(
        ConfiguredFeedAssignments(first.choice, secondSource.choice), listOf(first, secondSource).distinct(), providers)
    private fun failed(reason: ConfiguredPrototypeSelectionFailure, result: ConfiguredPrototypeSelectionResult) =
        assertEquals(ConfiguredPrototypeSelectionResult.Failure(reason), result)

    @Test fun duplicateChoicesAndNewLocalIdForSameResourceKeepExactPlaybackIdentity() {
        val readded = source.copy(id = UUID.randomUUID().toString(), entry = source.entry.copy(title = "My saved channel"))
        val result = resolve(readded) as ConfiguredPrototypeSelectionResult.Ready
        assertEquals(PrototypeSelection(PrototypeSource.TWITCH_LIVE, PrototypeSource.TWITCH_LIVE), result.selection)
        assertEquals(listOf(readded, readded), result.sources)
        assertEquals(readded.choice, result.sources[0].choice)
    }

    @Test fun sameResourceInDifferentInstancesRetainsBothAccountBindings() {
        val other = source.copy(id = UUID.randomUUID().toString(), instanceId = second.id)
        val result = resolve(source, other, instances + second) as ConfiguredPrototypeSelectionResult.Ready
        assertEquals(PrototypeSelection(PrototypeSource.TWITCH_LIVE, PrototypeSource.TWITCH_LIVE, twitch.id, second.id), result.selection)
        assertEquals(listOf(source, other), result.sources)
    }

    @Test fun nullUncheckedEmptyAndRemovedItemsNeverAcquireDefaults() {
        failed(ConfiguredPrototypeSelectionFailure.MISSING_CHOICE,
            resolveConfiguredPrototypeSelection(ConfiguredFeedAssignments(null, source.choice), listOf(source), instances))
        failed(ConfiguredPrototypeSelectionFailure.MISSING_CHOICE,
            resolveConfiguredPrototypeSelection(ConfiguredFeedAssignments(null, null), emptyList(), instances))
        failed(ConfiguredPrototypeSelectionFailure.STALE_ITEM,
            resolveConfiguredPrototypeSelection(ConfiguredFeedAssignments(source.choice, source.choice), emptyList(), instances))
        val wrongInstance = ConfiguredFeedChoice(source.id, second.id)
        failed(ConfiguredPrototypeSelectionFailure.STALE_ITEM,
            resolveConfiguredPrototypeSelection(ConfiguredFeedAssignments(wrongInstance, source.choice), listOf(source), instances + second))
    }

    @Test fun absentAmbiguousAndWrongProviderInstancesRejectBeforePlaybackBridge() {
        failed(ConfiguredPrototypeSelectionFailure.STALE_INSTANCE, resolve(providers = emptyList()))
        failed(ConfiguredPrototypeSelectionFailure.STALE_INSTANCE, resolve(providers = instances + twitch))
        val abemaResource = source.copy(entry = source.entry.copy(resource = prototypeCatalogResource(PrototypeSource.ABEMA_LIVE)))
        failed(ConfiguredPrototypeSelectionFailure.PROVIDER_MISMATCH, resolve(abemaResource))
        val ambiguous = listOf(source, source.copy(entry = source.entry.copy(title = "Duplicate ID")))
        failed(ConfiguredPrototypeSelectionFailure.STALE_ITEM,
            resolveConfiguredPrototypeSelection(ConfiguredFeedAssignments(source.choice, source.choice), ambiguous, instances))
    }

    @Test fun routeReviewIsRequiredWithoutRewritingSavedChoices() {
        val pending = twitch.copy(setup = ProviderSetup(null))
        failed(ConfiguredPrototypeSelectionFailure.ROUTE_REQUIRED, resolve(providers = instances.map { if (it.id == twitch.id) pending else it }))
        assertNotNull(twitch.setup.route)
        assertEquals(source, resolve().let { (it as ConfiguredPrototypeSelectionResult.Ready).sources.first() })
    }

    @Test fun knownUnavailableStatesRejectWhileUnknownLiveAndAvailablePreserveExactIdentity() {
        listOf(CatalogAvailability.UPCOMING, CatalogAvailability.OFFLINE, CatalogAvailability.EXPIRED,
            CatalogAvailability.UNAVAILABLE).forEach { availability ->
            failed(ConfiguredPrototypeSelectionFailure.UNAVAILABLE, resolve(source.copy(entry = source.entry.copy(availability = availability))))
        }
        listOf(CatalogAvailability.UNKNOWN, CatalogAvailability.LIVE, CatalogAvailability.AVAILABLE).forEach { availability ->
            val result = resolve(source.copy(entry = source.entry.copy(availability = availability)))
            assertTrue(result is ConfiguredPrototypeSelectionResult.Ready)
        }
    }

    @Test fun collectionsAndUnknownExactResourcesNeverBecomeSamplePlayback() {
        val collection = source.copy(entry = source.entry.copy(resource = source.entry.resource.copy(intent = CatalogIntent.COLLECTION)))
        failed(ConfiguredPrototypeSelectionFailure.COLLECTION, resolve(collection))
        listOf(source.entry.resource.copy(identity = "anotherchannel"), source.entry.resource.copy(kind = "video"),
            source.entry.resource.copy(intent = CatalogIntent.BROADCAST)).forEach { resource ->
            failed(ConfiguredPrototypeSelectionFailure.UNSUPPORTED, resolve(source.copy(entry = source.entry.copy(resource = resource))))
        }
    }

    @Test fun readySnapshotIsOrderedDetachedAndImmutable() {
        val replay = legacyConfiguredSources(twitch, defaultSourceSetups(), emptyMap()).last()
        val mutableSources = mutableListOf(source, replay)
        val mutableInstances = instances.toMutableList()
        val result = resolveConfiguredPrototypeSelection(ConfiguredFeedAssignments(replay.choice, source.choice),
            mutableSources, mutableInstances) as ConfiguredPrototypeSelectionResult.Ready
        mutableSources.clear(); mutableInstances.clear()
        assertEquals(listOf(replay, source), result.sources)
        assertEquals(PrototypeSelection(PrototypeSource.TWITCH_REPLAY, PrototypeSource.TWITCH_LIVE), result.selection)
        assertThrows(UnsupportedOperationException::class.java) { (result.sources as MutableList<ConfiguredSource>).clear() }
    }
}
