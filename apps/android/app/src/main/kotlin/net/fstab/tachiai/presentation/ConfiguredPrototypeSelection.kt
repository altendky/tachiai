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
    UNAVAILABLE, COLLECTION, UNSUPPORTED,
}

internal sealed interface ConfiguredPrototypeSelectionResult {
    data class Ready(val selection: ConfiguredPlaybackSelection, val sources: List<ConfiguredSource>) : ConfiguredPrototypeSelectionResult {
        val sourceSlots: List<ConfiguredSource?> = Collections.unmodifiableList(selection.slots.map { feed ->
            feed?.let { selected -> sources.firstOrNull { it.choice == selected.choice } }
        })
    }
    data class Failure(val reason: ConfiguredPrototypeSelectionFailure) : ConfiguredPrototypeSelectionResult
    // Every other check passed; these A/B slot indexes hold supported
    // collections that Watch must resolve to an exact episode first (#549).
    data class NeedsLatest(val slots: List<Int>, val collections: List<ConfiguredSource?>) : ConfiguredPrototypeSelectionResult
}

// One Watch's resolution of a saved collection to an exact episode. It applies
// only while the same slot still selects the same collection item.
internal data class ConfiguredLatestEpisode(val choice: ConfiguredFeedChoice, val collection: CatalogResource,
    val episode: CatalogResource) {
    init { require(supportedConfiguredAbemaCollection(collection) && supportedConfiguredAbemaEpisode(episode)) }
}

// The guest series operation that resolves a collection needs API 28.
internal val collectionLatestAvailable: Boolean get() = android.os.Build.VERSION.SDK_INT >= 28

// Whether Watch resolves this saved collection to its newest published episode.
internal fun collectionPlaysLatest(resource: CatalogResource, available: Boolean = collectionLatestAvailable): Boolean =
    available && supportedConfiguredAbemaCollection(resource)

internal val ConfiguredPrototypeSelectionResult.canWatch: Boolean
    get() = this !is ConfiguredPrototypeSelectionResult.Failure

// Resolve local ownership before the exact public-resource playback bridge.
// This validation never opens a route/session or replaces a missing selection.
internal fun resolveConfiguredPrototypeSelection(
    assignments: ConfiguredFeedAssignments,
    sources: List<ConfiguredSource>,
    instances: List<ProviderInstance>,
    allowConfiguredAbemaEpisodes: Boolean = true,
    allowConfiguredAbemaChannels: Boolean = true,
    allowConfiguredAbemaSlots: Boolean = true,
    // Per A/B slot: the exact episode this Watch resolved for a collection.
    latest: List<ConfiguredLatestEpisode?> = listOf(null, null),
    allowCollectionLatest: Boolean = true,
): ConfiguredPrototypeSelectionResult {
    require(latest.size == 2)
    fun failure(reason: ConfiguredPrototypeSelectionFailure) = ConfiguredPrototypeSelectionResult.Failure(reason)
    val choices = listOf(assignments.a, assignments.b)
    if (choices.all { it == null }) return failure(ConfiguredPrototypeSelectionFailure.MISSING_CHOICE)
    val selected = mutableListOf<ConfiguredSource>()
    val feeds = arrayOfNulls<ConfiguredPlaybackFeed>(2)
    val unresolved = mutableListOf<Int>()
    val collections = arrayOfNulls<ConfiguredSource>(2)
    for ((index, choice) in choices.withIndex()) {
        if (choice == null) continue
        val source = choice.resolve(sources) ?: return failure(ConfiguredPrototypeSelectionFailure.STALE_ITEM)
        val instance = instances.singleOrNull { it.id == choice.instanceId }
            ?: return failure(ConfiguredPrototypeSelectionFailure.STALE_INSTANCE)
        if (source.entry.resource.providerId.value != instance.service.name.lowercase(Locale.ROOT))
            return failure(ConfiguredPrototypeSelectionFailure.PROVIDER_MISMATCH)
        val collection = source.entry.resource.intent == CatalogIntent.COLLECTION
        if (collection && !collectionPlaysLatest(source.entry.resource, allowCollectionLatest))
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
            val episode = latest[index]?.takeIf { it.choice == source.choice && it.collection == source.entry.resource }?.episode
            if (episode == null) {
                if (instance.service != PrototypeService.ABEMA) return failure(ConfiguredPrototypeSelectionFailure.PROVIDER_MISMATCH)
                unresolved += index; collections[index] = source; continue
            }
            ConfiguredPlaybackFeed(source.choice, episode, PrototypeService.ABEMA, PrototypePlaybackKind.REPLAY, source.entry.resource)
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
    if (unresolved.isNotEmpty()) return ConfiguredPrototypeSelectionResult.NeedsLatest(
        Collections.unmodifiableList(unresolved.toList()), Collections.unmodifiableList(collections.toList()))
    return ConfiguredPrototypeSelectionResult.Ready(
        ConfiguredPlaybackSelection(feeds[0], feeds[1]),
        Collections.unmodifiableList(selected.toList()),
    )
}
