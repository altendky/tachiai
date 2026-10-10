package net.fstab.tachiai.provider.twitch

import net.fstab.tachiai.provider.catalog.CatalogIntent
import net.fstab.tachiai.provider.catalog.CatalogResource
import net.fstab.tachiai.provider.twitch.catalog.validTwitchCatalogId

// Syntactic support for the existing debug native replay path, not entitlement.
// Keep the public catalog's canonical IDs and the approved native input bound.
internal fun supportedConfiguredTwitchVideo(resource: CatalogResource): Boolean =
    resource.providerId.value == "twitch" && resource.kind == "video" &&
        resource.intent == CatalogIntent.VIDEO && validTwitchCatalogId(resource.identity) &&
        validTwitchPlaybackResource(TwitchAccessCase.REPLAY, resource.identity)
