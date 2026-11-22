package net.fstab.tachiai.presentation

import java.util.Collections
import java.util.Locale
import net.fstab.tachiai.provider.abema.supportedConfiguredAbemaCollection
import net.fstab.tachiai.provider.abema.supportedConfiguredAbemaEpisode
import net.fstab.tachiai.provider.abema.supportedConfiguredAbemaChannel
import net.fstab.tachiai.provider.abema.supportedConfiguredAbemaSlot
import net.fstab.tachiai.provider.catalog.CatalogAvailability
import net.fstab.tachiai.provider.catalog.CatalogIntent
import net.fstab.tachiai.provider.catalog.CatalogResource
import net.fstab.tachiai.provider.twitch.supportedConfiguredTwitchBroadcaster
import net.fstab.tachiai.provider.twitch.supportedConfiguredTwitchVideo

internal enum class ConfiguredPrototypeSelectionFailure {
    MISSING_CHOICE, STALE_ITEM, STALE_INSTANCE, PROVIDER_MISMATCH, ROUTE_REQUIRED,
    UNAVAILABLE, COLLECTION, EPISODE_REQUIRED, UNSUPPORTED,
}

// Episode choice needs the guest series operation, which requires API 28.
internal val collectionEpisodesAvailable: Boolean get() = android.os.Build.VERSION.SDK_INT >= 28

// Whether a saved collection offers episode choice on the main picker (#550).
internal fun collectionEpisodesSupported(resource: CatalogResource, available: Boolean = collectionEpisodesAvailable): Boolean =
    available && supportedConfiguredAbemaCollection(resource)

internal sealed interface ConfiguredPrototypeSelectionResult {
    data class Ready(val selection: ConfiguredPlaybackSelection, val sources: List<ConfiguredSource>) : ConfiguredPrototypeSelectionResult {
        val sourceSlots: List<ConfiguredSource?> = Collections.unmodifiableList(selection.slots.map { feed ->
            feed?.let { selected -> sources.firstOrNull { it.choice == selected.choice } }
        })
    }
    data class Failure(val reason: ConfiguredPrototypeSelectionFailure) : ConfiguredPrototypeSelectionResult
}

// Resolve local ownership before the exact public-resource playback bridge.
// This validation never opens a route/session or replaces a missing selection.
internal fun resolveConfiguredPrototypeSelection(
    assignments: ConfiguredFeedAssignments,
    sources: List<ConfiguredSource>,
    instances: List<ProviderInstance>,
    allowConfiguredAbemaEpisodes: Boolean = true,
    allowConfiguredAbemaChannels: Boolean = true,
    allowConfiguredAbemaSlots: Boolean = true,
    allowCollectionEpisodes: Boolean = true,
): ConfiguredPrototypeSelectionResult {
    fun failure(reason: ConfiguredPrototypeSelectionFailure) = ConfiguredPrototypeSelectionResult.Failure(reason)
    val choices = listOf(assignments.a, assignments.b)
    if (choices.all { it == null }) return failure(ConfiguredPrototypeSelectionFailure.MISSING_CHOICE)
    val selected = mutableListOf<ConfiguredSource>()
    val feeds = arrayOfNulls<ConfiguredPlaybackFeed>(2)
    for ((index, choice) in choices.withIndex()) {
        if (choice == null) continue
        val source = choice.resolve(sources) ?: return failure(ConfiguredPrototypeSelectionFailure.STALE_ITEM)
        val instance = instances.singleOrNull { it.id == choice.instanceId }
            ?: return failure(ConfiguredPrototypeSelectionFailure.STALE_INSTANCE)
        if (source.entry.resource.providerId.value != instance.service.name.lowercase(Locale.ROOT))
            return failure(ConfiguredPrototypeSelectionFailure.PROVIDER_MISMATCH)
        val collection = source.entry.resource.intent == CatalogIntent.COLLECTION
        if (collection && !collectionEpisodesSupported(source.entry.resource, allowCollectionEpisodes))
            return failure(ConfiguredPrototypeSelectionFailure.COLLECTION)
        val broadcaster = supportedConfiguredTwitchBroadcaster(source.entry.resource)
        if (source.entry.availability in setOf(CatalogAvailability.UPCOMING,
                CatalogAvailability.EXPIRED, CatalogAvailability.UNAVAILABLE) ||
            source.entry.availability == CatalogAvailability.OFFLINE && !broadcaster)
            return failure(ConfiguredPrototypeSelectionFailure.UNAVAILABLE)
        if (instance.setup.route == null) return failure(ConfiguredPrototypeSelectionFailure.ROUTE_REQUIRED)
        // Saved built-in resources play through the same configured paths as any item.
        val supported = if (collection) {
            if (!allowConfiguredAbemaEpisodes) return failure(ConfiguredPrototypeSelectionFailure.UNSUPPORTED)
            if (instance.service != PrototypeService.ABEMA) return failure(ConfiguredPrototypeSelectionFailure.PROVIDER_MISMATCH)
            val episode = (if (index == 0) assignments.aEpisode else assignments.bEpisode)
                ?.takeIf { it.collection == source.entry.resource &&
                    (supportedConfiguredAbemaEpisode(it.episode) || supportedConfiguredAbemaSlot(it.episode)) }
                ?: return failure(ConfiguredPrototypeSelectionFailure.EPISODE_REQUIRED)
            // A chosen broadcast (#622) plays through the configured slot path.
            if (supportedConfiguredAbemaSlot(episode.episode) && !allowConfiguredAbemaSlots)
                return failure(ConfiguredPrototypeSelectionFailure.UNSUPPORTED)
            ConfiguredPlaybackFeed(source.choice, episode.episode, PrototypeService.ABEMA, PrototypePlaybackKind.REPLAY,
                source.entry.resource, episode.title)
        } else if (supportedConfiguredAbemaEpisode(source.entry.resource)) {
            if (!allowConfiguredAbemaEpisodes) return failure(ConfiguredPrototypeSelectionFailure.UNSUPPORTED)
            ConfiguredPlaybackFeed(source.choice, source.entry.resource, PrototypeService.ABEMA, PrototypePlaybackKind.REPLAY)
        } else if (supportedConfiguredAbemaChannel(source.entry.resource)) {
            if (!allowConfiguredAbemaChannels) return failure(ConfiguredPrototypeSelectionFailure.UNSUPPORTED)
            ConfiguredPlaybackFeed(source.choice, source.entry.resource, PrototypeService.ABEMA, PrototypePlaybackKind.LIVE)
        } else if (supportedConfiguredAbemaSlot(source.entry.resource)) {
            if (!allowConfiguredAbemaSlots) return failure(ConfiguredPrototypeSelectionFailure.UNSUPPORTED)
            ConfiguredPlaybackFeed(source.choice, source.entry.resource, PrototypeService.ABEMA, PrototypePlaybackKind.REPLAY)
        } else if (net.fstab.tachiai.provider.youtube.supportedYouTubeResource(source.entry.resource)) {
            ConfiguredPlaybackFeed(source.choice, source.entry.resource, PrototypeService.YOUTUBE, PrototypePlaybackKind.REPLAY)
        } else if (supportedConfiguredTwitchVideo(source.entry.resource)) {
            ConfiguredPlaybackFeed(source.choice, source.entry.resource, PrototypeService.TWITCH, PrototypePlaybackKind.REPLAY)
        } else if (broadcaster) {
            ConfiguredPlaybackFeed(source.choice, source.entry.resource, PrototypeService.TWITCH, PrototypePlaybackKind.LIVE)
        } else return failure(ConfiguredPrototypeSelectionFailure.UNSUPPORTED)
        if (supported.service != instance.service) return failure(ConfiguredPrototypeSelectionFailure.PROVIDER_MISMATCH)
        selected += source
        feeds[index] = supported
    }
    return ConfiguredPrototypeSelectionResult.Ready(
        ConfiguredPlaybackSelection(feeds[0], feeds[1]),
        Collections.unmodifiableList(selected.toList()),
    )
}
