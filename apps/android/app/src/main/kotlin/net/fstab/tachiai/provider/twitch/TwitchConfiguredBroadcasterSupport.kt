package net.fstab.tachiai.provider.twitch

import net.fstab.tachiai.provider.catalog.CatalogIntent
import net.fstab.tachiai.provider.catalog.CatalogResource
import net.fstab.tachiai.provider.twitch.catalog.validTwitchCatalogId

// A stable public broadcaster identity, never a stored login or stream ID.
// Admission requires a fresh worker assessment before native live preparation.
internal fun supportedConfiguredTwitchBroadcaster(resource: CatalogResource): Boolean =
    resource.providerId.value == "twitch" && resource.kind == "broadcaster" &&
        resource.intent == CatalogIntent.CHANNEL && validTwitchCatalogId(resource.identity)
