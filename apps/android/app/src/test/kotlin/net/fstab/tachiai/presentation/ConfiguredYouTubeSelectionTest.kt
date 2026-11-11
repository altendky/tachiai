package net.fstab.tachiai.presentation

import java.util.UUID
import net.fstab.tachiai.provider.catalog.*
import net.fstab.tachiai.provider.youtube.YouTubeCatalog
import org.junit.Assert.*
import org.junit.Test

class ConfiguredYouTubeSelectionTest {
    private val instances = defaultProviderInstances()
    private val youtube = instances.single { it.service == PrototypeService.YOUTUBE }
    private fun imported(id: String = "_bcdefghi-1", owner: ProviderInstance = youtube): ConfiguredSource {
        val entry = (YouTubeCatalog(owner.id).lookup("https://youtu.be/$id") as CatalogResult.Value).value
        return ConfiguredSource(UUID.randomUUID().toString(), owner.id, entry)
    }
    private fun resolve(first: ConfiguredSource, second: ConfiguredSource = first,
        providers: List<ProviderInstance> = instances) = resolveConfiguredPrototypeSelection(
        ConfiguredFeedAssignments(first.choice, second.choice), listOf(first, second).distinct(), providers)

    @Test fun importedVideoReachesDuplicateViewerSlotsWithoutChangingExactIdentity() {
        val source = imported()
        val ready = resolve(source) as ConfiguredPrototypeSelectionResult.Ready
        assertEquals(listOf(source.choice, source.choice), ready.selection.feeds.map { it.choice })
        ready.selection.feeds.forEach { feed ->
            assertEquals(source.entry.resource, feed.resource)
            assertEquals(PrototypeService.YOUTUBE, feed.service)
            assertEquals(youtube, feed.resolve(instances))
            assertNull(feed.historicalSource)
            assertEquals("YOUTUBE_CONFIGURED_VIDEO", feed.diagnosticName)
        }
        assertEquals(CatalogAvailability.UNKNOWN, source.entry.availability)
    }

    @Test fun youtubeAndTwitchCanBeSelectedWithoutCrossingProviderBindings() {
        val source = imported("-bcdefghi_1")
        val twitch = instances.single { it.service == PrototypeService.TWITCH }
        val other = legacyConfiguredSources(twitch, defaultSourceSetups(), emptyMap()).first()
        val ready = resolve(source, other) as ConfiguredPrototypeSelectionResult.Ready
        assertEquals(listOf(PrototypeService.YOUTUBE, PrototypeService.TWITCH), ready.selection.feeds.map { it.service })
        assertEquals(listOf(youtube.id, twitch.id), ready.selection.feeds.map { it.instanceId })
    }

    @Test fun accountInstanceMismatchAndRemovedSelectionFailBeforePlayback() {
        val source = imported()
        val twitch = instances.single { it.service == PrototypeService.TWITCH }
        val wrongOwner = source.copy(instanceId = twitch.id)
        assertEquals(ConfiguredPrototypeSelectionResult.Failure(ConfiguredPrototypeSelectionFailure.PROVIDER_MISMATCH),
            resolve(wrongOwner))
        assertEquals(ConfiguredPrototypeSelectionResult.Failure(ConfiguredPrototypeSelectionFailure.STALE_INSTANCE),
            resolve(source, providers = instances.filterNot { it.id == youtube.id }))
        val missingRoute = youtube.copy(setup = ProviderSetup(null))
        assertEquals(ConfiguredPrototypeSelectionResult.Failure(ConfiguredPrototypeSelectionFailure.ROUTE_REQUIRED),
            resolve(source, providers = instances.map { if (it.id == youtube.id) missingRoute else it }))
    }
}
