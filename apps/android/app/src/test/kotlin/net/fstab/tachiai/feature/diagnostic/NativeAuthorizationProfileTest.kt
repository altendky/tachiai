package net.fstab.tachiai.feature.diagnostic

import net.fstab.tachiai.provider.twitch.TwitchAuthorizationProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeAuthorizationProfileTest {
    private val webCases = setOf(NativeAccessCase.TWITCH_PROVIDER_AUTHORIZE_SAVE,
        NativeAccessCase.TWITCH_PROVIDER_LIVE, NativeAccessCase.TWITCH_PROVIDER_REPLAY)
    private val tvCases = setOf(NativeAccessCase.TWITCH_SMART_TV_AUTHORIZE_SAVE,
        NativeAccessCase.TWITCH_SMART_TV_LIVE, NativeAccessCase.TWITCH_SMART_TV_REPLAY,
        NativeAccessCase.TWITCH_SMART_TV_LIFETIME_INSPECTION)
    private val localCases = setOf(NativeAccessCase.TWITCH_SMART_TV_LOCAL_SAVE,
        NativeAccessCase.TWITCH_SMART_TV_LOCAL_LIVE, NativeAccessCase.TWITCH_SMART_TV_LOCAL_REPLAY,
        NativeAccessCase.TWITCH_NATIVE_LIVE, NativeAccessCase.TWITCH_NATIVE_REPLAY,
        NativeAccessCase.TWITCH_NATIVE_REPLAY_OBSERVED_CDN, NativeAccessCase.TWITCH_NATIVE_LIVE_TIMING,
        NativeAccessCase.TWITCH_NATIVE_REPLAY_TIMING, NativeAccessCase.TWITCH_NATIVE_REPLAY_PAIR,
        NativeAccessCase.TWITCH_SMART_TV_LOCAL_EXTEND)

    @Test fun `local retention cases select only their separate profile`() {
        localCases.forEach { assertEquals(TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL, nativeAuthorizationProfile(it)) }
    }

    @Test fun `Smart TV actions and lifetime inspection select the isolated Smart TV profile`() {
        tvCases.forEach { assertEquals(TwitchAuthorizationProfile.PROVIDER_SMART_TV, nativeAuthorizationProfile(it)) }
    }

    @Test fun `the original provider web actions keep their original profile`() {
        webCases.forEach { assertEquals(TwitchAuthorizationProfile.PROVIDER_PLAYBACK, nativeAuthorizationProfile(it)) }
    }

    @Test fun `non-Twitch cases use a harmless Smart TV profile default`() {
        NativeAccessCase.entries.filter { it !in webCases && it !in tvCases && it !in localCases }.forEach {
            assertEquals(TwitchAuthorizationProfile.PROVIDER_SMART_TV, nativeAuthorizationProfile(it))
        }
    }
}
