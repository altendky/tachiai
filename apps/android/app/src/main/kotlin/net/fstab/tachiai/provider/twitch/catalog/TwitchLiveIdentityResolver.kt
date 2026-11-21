package net.fstab.tachiai.provider.twitch.catalog

import net.fstab.tachiai.provider.catalog.CatalogEntry
import net.fstab.tachiai.provider.catalog.CatalogFailure
import net.fstab.tachiai.provider.catalog.CatalogResource
import net.fstab.tachiai.provider.catalog.CatalogResult

// Worker-only mapping/confirmation; only canPublish is a cheap admission gate.
// The transient login never replaces the immutable configured broadcaster ID.
internal interface TwitchLiveIdentityResolver : AutoCloseable {
    fun begin(resource: CatalogResource): CatalogResult<TwitchLiveIdentity>
    fun confirm(identity: TwitchLiveIdentity): CatalogResult<Unit>
    fun canPublish(identity: TwitchLiveIdentity): Boolean
    // The broadcaster's current availability and any published scheduled
    // start (#587), through the same owned catalog session as begin().
    fun status(resource: CatalogResource): CatalogResult<CatalogEntry> = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
}

internal class TwitchLiveIdentity internal constructor(val resource: CatalogResource, val login: String) {
    override fun toString() = "TwitchLiveIdentity(redacted)"
}
