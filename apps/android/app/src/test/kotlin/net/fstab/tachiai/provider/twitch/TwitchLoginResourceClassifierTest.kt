package net.fstab.tachiai.provider.twitch

import java.net.URI
import net.fstab.tachiai.provider.BrowserResourceCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TwitchLoginResourceClassifierTest {
    private val config = "/config/settings.a346c0e26e35ab87386587f3d6bd61f2.js"

    @Test
    fun `classifies only the public deployment snapshot without query or fragment readback`() {
        assertEquals(BrowserResourceCategory.CONFIG_SCRIPT,
            TwitchLoginResourceClassifier.classify(URI("https://assets.twitch.tv$config?opaque=not-retained#not-retained")))
        assertEquals(BrowserResourceCategory.BOOTSTRAP_SCRIPT,
            TwitchLoginResourceClassifier.classify(URI("https://assets.twitch.tv/assets/21956-1764bec9bf11526bf6b6.js")))
        listOf("32683-40511fffc5990b8ef8a4.js",
            "features.auth.components.auth-form.components.login-cf0495af2eeb95a51623.js",
            "features.auth.components.standalone-auth-pages-26d6ed7d622beb2880cc.js",
            "features.auth.components.auth-modal-991846811f3d2a3661a3.js").forEach {
            assertEquals(BrowserResourceCategory.AUTH_UI_SCRIPT,
                TwitchLoginResourceClassifier.classify(URI("https://assets.twitch.tv/assets/$it")))
        }
        assertEquals(BrowserResourceCategory.PROTECTION_SCRIPT, TwitchLoginResourceClassifier.classify(
            URI("https://k.twitchcdn.net/149e9513-01fa-4fb0-aad4-566afd725d1b/2d206a39-8ed7-437e-a3be-862e0f06eea3/p.js")))
    }

    @Test
    fun `rejects origin lookalikes encoded paths auth endpoints and changed deployments`() {
        listOf("http://assets.twitch.tv$config", "https://user@assets.twitch.tv$config",
            "https://assets.twitch.tv:444$config", "https://assets.twitch.tv.evil.example$config",
            "https://evil.example$config", "https://k.twitchcdn.net$config",
            "https://assets.twitch.tv/config/settings.changed.js", "https://assets.twitch.tv/extra$config",
            "https://assets.twitch.tv/config/%73ettings.a346c0e26e35ab87386587f3d6bd61f2.js",
            "https://passport.twitch.tv/protected_login", "https://www.twitch.tv/login",
            "https://k.twitchcdn.net/other/p.js").forEach {
            assertNull(it, TwitchLoginResourceClassifier.classify(URI(it)))
        }
    }
}
