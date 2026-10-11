package net.fstab.tachiai.presentation

import java.util.Collections
import java.util.Locale
import net.fstab.tachiai.provider.abema.supportedConfiguredAbemaEpisode
import net.fstab.tachiai.provider.abema.supportedConfiguredAbemaChannel
import net.fstab.tachiai.provider.abema.supportedConfiguredAbemaSlot
import net.fstab.tachiai.provider.catalog.CatalogResource
import net.fstab.tachiai.provider.twitch.supportedConfiguredTwitchBroadcaster
import net.fstab.tachiai.provider.twitch.supportedConfiguredTwitchVideo

// Frozen public identity and local ownership. Signed sources and authorization
// belong to the provider session, never this viewer selection or its recovery.
internal data class ConfiguredPlaybackFeed(
    val choice: ConfiguredFeedChoice,
    val resource: CatalogResource,
    val service: PrototypeService,
    val kind: PrototypePlaybackKind,
    val historicalSource: PrototypeSource? = null,
) {
    init {
        require(resource.providerId.value == service.name.lowercase(Locale.ROOT))
        if (historicalSource != null) {
            require(historicalSource.service == service && historicalSource.kind == kind &&
                prototypeCatalogResource(historicalSource) == resource)
        } else {
            require(service == PrototypeService.ABEMA &&
                (kind == PrototypePlaybackKind.REPLAY &&
                    (supportedConfiguredAbemaEpisode(resource) || supportedConfiguredAbemaSlot(resource)) ||
                    kind == PrototypePlaybackKind.LIVE && supportedConfiguredAbemaChannel(resource)) ||
                service == PrototypeService.TWITCH &&
                    (kind == PrototypePlaybackKind.REPLAY && supportedConfiguredTwitchVideo(resource) ||
                        kind == PrototypePlaybackKind.LIVE && supportedConfiguredTwitchBroadcaster(resource)))
        }
    }

    val instanceId: String get() = choice.instanceId
    val diagnosticName: String get() = historicalSource?.name ?: when {
        service == PrototypeService.ABEMA && resource.kind == "slot" -> "ABEMA_CONFIGURED_SLOT"
        service == PrototypeService.ABEMA -> if (kind == PrototypePlaybackKind.LIVE) "ABEMA_CONFIGURED_CHANNEL" else "ABEMA_CONFIGURED_EPISODE"
        kind == PrototypePlaybackKind.LIVE -> "TWITCH_CONFIGURED_BROADCASTER"
        else -> "TWITCH_CONFIGURED_VIDEO"
    }

    fun resolve(instances: List<ProviderInstance>): ProviderInstance? =
        instances.singleOrNull { it.id == instanceId }?.takeIf { it.service == service &&
            resource.providerId.value == it.service.name.lowercase(Locale.ROOT) }
}

internal data class ConfiguredPlaybackSelection(val a: ConfiguredPlaybackFeed, val b: ConfiguredPlaybackFeed) {
    // Duplicate choices still own independent presentation slots.
    val feeds: List<ConfiguredPlaybackFeed> = Collections.unmodifiableList(listOf(a, b))
}
