package net.fstab.tachiai.provider.abema

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class AbemaGuestBootstrapPolicyTest {
    @Test fun onlyOwnedDocumentAssetsAndOneGuestMethodAreAdmitted() {
        assertTrue(AbemaGuestBootstrapPolicy.allows(URI(AbemaGuestBootstrapPolicy.PAGE), true, "GET"))
        assertFalse(AbemaGuestBootstrapPolicy.allows(URI(AbemaGuestBootstrapPolicy.PAGE), false, "GET"))
        for (file in listOf("control", "support", "utilities", "bundle", "legacy", "initialize")) {
            val uri = URI("https://abema.tv/_tachiai/guest/$file.js")
            assertTrue(AbemaGuestBootstrapPolicy.allows(uri, false, "GET"))
            assertFalse(AbemaGuestBootstrapPolicy.allows(uri, true, "GET"))
            assertFalse(AbemaGuestBootstrapPolicy.allows(uri, false, "POST"))
        }
        assertTrue(AbemaGuestBootstrapPolicy.allows(URI(AbemaGuestBootstrapPolicy.GUEST), false, "POST"))
        listOf("GET", "OPTIONS", "PUT").forEach {
            assertFalse(AbemaGuestBootstrapPolicy.allows(URI(AbemaGuestBootstrapPolicy.GUEST), false, it))
        }
    }

    @Test fun accountProfileChallengeRefreshPlaybackAndURLVariationsRefuse() {
        for (url in listOf("https://abema.tv/", "https://abema.tv/auth/login",
            "https://abema.tv/api/auth/oauth/device/authorization", "https://abema.tv/api/auth/oauth/device/token",
            "https://abema.tv/api/auth/accessToken", "https://abema.tv/api/auth/sessions", "https://abema.tv/api/user",
            "https://api.p-c3-e.abema-tv.com/v1/users/fixture", "https://api.p-c3-e.abema-tv.com/v1/media/token",
            "https://api.abema.io/v1/channels", "https://license.p-c3-e.abema-tv.com/abematv-dash",
            "https://abema.tv/_tachiai/native/control.js", "https://abema.tv/assets/unreviewed.js",
            "http://abema.tv/api/auth/login/guest", "https://abema.tv:443/api/auth/login/guest",
            "https://fixture@abema.tv/api/auth/login/guest", "https://abema.tv/api/auth/login/%67uest",
            "https://abema.tv/api/auth/login/../login/guest", "https://abema.tv/api/auth/login/guest?token=fixture",
            "https://abema.tv/api/auth/login/guest#fragment", "https://other.abema.tv/api/auth/login/guest")) {
            for (main in listOf(false, true)) for (method in listOf("GET", "POST"))
                assertFalse(AbemaGuestBootstrapPolicy.allows(URI(url), main, method))
        }
        assertFalse(AbemaGuestBootstrapPolicy.CONTENT_SECURITY_POLICY.contains("media-src https"))
        assertFalse(AbemaGuestBootstrapPolicy.CONTENT_SECURITY_POLICY.contains("api.p-c3"))
    }
}
