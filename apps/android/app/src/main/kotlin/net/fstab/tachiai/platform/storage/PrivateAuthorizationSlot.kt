package net.fstab.tachiai.platform.storage

// Fixed slots preserve original file/key/AAD identities. Additional Twitch
// instance bindings accept only canonical, non-reserved UUIDs below.
internal enum class PrivateAuthorizationSlot(val bindingName: String) {
    TWITCH_OWN("twitch-own-authorization"),
    TWITCH_PROVIDER_PLAYBACK("twitch-provider-playback-authorization"),
    TWITCH_PROVIDER_SMART_TV("twitch-provider-smart-tv-authorization"),
    TWITCH_PROVIDER_SMART_TV_LOCAL("twitch-provider-smart-tv-local-authorization"),
    // Separate import-only network profile record, never a provider authorization grant.
    CONNECTION_PROFILES("connection-profiles"),
    SOURCE_SETUP("source-setup"),
    PROVIDER_SETUP("provider-setup"),
    STREAM_QUALITY("stream-quality"),
    PROVIDER_INSTANCES("provider-instances"),
}

internal fun twitchProviderInstanceBindingName(id: String): String {
    require(net.fstab.tachiai.presentation.validProviderInstanceId(id))
    require(net.fstab.tachiai.presentation.PrototypeService.entries.none {
        id == net.fstab.tachiai.presentation.defaultProviderInstanceId(it)
    })
    return "twitch-provider-instance-$id-local-authorization"
}
