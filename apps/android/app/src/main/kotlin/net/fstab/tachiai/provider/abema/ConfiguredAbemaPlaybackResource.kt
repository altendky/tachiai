package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.provider.catalog.CatalogIntent
import net.fstab.tachiai.provider.catalog.CatalogResource

// Syntax/identity only. Actual fresh provider eligibility and source checks are
// mandatory inside the playback adapter, even for a locally AVAILABLE item.
internal fun supportedConfiguredAbemaEpisode(resource: CatalogResource): Boolean =
    resource.providerId.value == "abema" && resource.kind == "episode" && resource.intent == CatalogIntent.VIDEO &&
        validAbemaEpisodeIdentity(resource.identity)

internal fun validAbemaEpisodeIdentity(id: String): Boolean = id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))

internal fun supportedConfiguredAbemaChannel(resource: CatalogResource): Boolean =
    resource.providerId.value == "abema" && resource.kind == "channel" && resource.intent == CatalogIntent.CHANNEL &&
        validAbemaChannelIdentity(resource.identity)

internal fun validAbemaChannelIdentity(id: String): Boolean = id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))

internal fun supportedConfiguredAbemaSlot(resource: CatalogResource): Boolean =
    resource.providerId.value == "abema" && resource.kind == "slot" && resource.intent == CatalogIntent.BROADCAST &&
        validAbemaSlotIdentity(resource.identity)

// Keep the catalog/import grammar: an exact channel and exact slot, not an
// episode ID, a recurring collection, or a URL carrying transient parameters.
internal fun validAbemaSlotIdentity(identity: String): Boolean = identity.length in 3..129 && identity.split('/').let { parts ->
    parts.size == 2 && validAbemaChannelIdentity(parts[0]) && validAbemaChannelIdentity(parts[1])
}
