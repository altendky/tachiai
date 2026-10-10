package net.fstab.tachiai.provider.twitch

import net.fstab.tachiai.platform.storage.PrivateAuthorizationSlot

// Public provider web client identifier observed in independent clients.
// Not Tachiai's registration, a secret, or a supported integration contract.
internal const val PROVIDER_TWITCH_CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko" // gitleaks:allow public client identifier, not a token
internal const val SMART_TV_TWITCH_CLIENT_ID = "ue6666qo983tsx6so1t0vnawi233wa" // gitleaks:allow public client identifier, not a token

internal enum class TwitchAuthorizationProfile(val clientId: String, val storageSlot: PrivateAuthorizationSlot) {
    PROVIDER_PLAYBACK(PROVIDER_TWITCH_CLIENT_ID, PrivateAuthorizationSlot.TWITCH_PROVIDER_PLAYBACK),
    PROVIDER_SMART_TV(SMART_TV_TWITCH_CLIENT_ID, PrivateAuthorizationSlot.TWITCH_PROVIDER_SMART_TV),
    PROVIDER_SMART_TV_LOCAL(SMART_TV_TWITCH_CLIENT_ID, PrivateAuthorizationSlot.TWITCH_PROVIDER_SMART_TV_LOCAL),
}
