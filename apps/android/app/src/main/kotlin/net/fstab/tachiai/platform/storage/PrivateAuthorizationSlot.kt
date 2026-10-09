package net.fstab.tachiai.platform.storage

// Closed slots, not caller-supplied paths or aliases. Preserve the original
// slot's file/key/AAD identity so updating the debug APK retains its record.
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
}
