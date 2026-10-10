package net.fstab.tachiai.provider.twitch.catalog

import net.fstab.tachiai.provider.catalog.CatalogResource
import net.fstab.tachiai.provider.catalog.CatalogResult

// Worker-only mapping/confirmation; only canPublish is a cheap admission gate.
// The transient login never replaces the immutable configured broadcaster ID.
internal interface TwitchLiveIdentityResolver : AutoCloseable {
    fun begin(resource: CatalogResource): CatalogResult<TwitchLiveIdentity>
    fun confirm(identity: TwitchLiveIdentity): CatalogResult<Unit>
    fun canPublish(identity: TwitchLiveIdentity): Boolean
}

internal class TwitchLiveIdentity internal constructor(val resource: CatalogResource, val login: String) {
    override fun toString() = "TwitchLiveIdentity(redacted)"
}
