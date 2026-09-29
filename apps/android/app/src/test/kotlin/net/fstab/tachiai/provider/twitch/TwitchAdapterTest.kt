package net.fstab.tachiai.provider.twitch

import java.net.URI
import net.fstab.tachiai.presentation.ResourceLocator
import net.fstab.tachiai.provider.BrowserCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchAdapterTest {
    @Test
    fun `packaged embed carries a validated channel on the HTTPS asset origin`() {
        val request = TwitchAdapter.browserRequest(ResourceLocator(TwitchAdapter.CHANNEL))

        assertEquals("midnightsumo", TwitchAdapter.CHANNEL)
        assertEquals(
            "https://appassets.androidplatform.net/assets/twitch/player.html?channel=midnightsumo",
            request.url,
        )
        assertTrue(request.isPackagedAsset)
        assertTrue(request.acceptsThirdPartyCookies)
        assertEquals(
            "https://appassets.androidplatform.net/assets/twitch/player.html?channel=queenbee",
            TwitchAdapter.browserRequest(ResourceLocator("queenbee")).url,
        )
        assertThrows(IllegalArgumentException::class.java) {
            TwitchAdapter.browserRequest(ResourceLocator("invalid/channel"))
        }
    }

    @Test
    fun `commands require the exact packaged top-level page`() {
        val request = TwitchAdapter.browserRequest(ResourceLocator("queenbee"))
        assertNotNull(TwitchAdapter.scriptFor(BrowserCommand.PLAY, URI(request.url), URI(request.url)))
        assertFalse(
            TwitchAdapter.isTopLevelNavigationAllowed(
                URI("https://appassets.androidplatform.net.evil.example/assets/twitch/player.html"),
            ),
        )
        assertFalse(
            TwitchAdapter.isTopLevelNavigationAllowed(
                URI("${TwitchAdapter.ASSET_URL}?channel=queenbee&unexpected=true"),
            ),
        )
    }

    @Test
    fun `full-site diagnostic accepts only an exact channel URL`() {
        val resource = TwitchAdapter.fullSiteResource("queenbee")
        val request = TwitchAdapter.browserRequest(resource)

        assertEquals("https://m.twitch.tv/queenbee", request.url)
        assertFalse(request.isPackagedAsset)
        assertTrue(request.acceptsThirdPartyCookies)
        assertTrue(TwitchAdapter.isTopLevelNavigationAllowed(URI(request.url)))
        val requestedUri = URI(request.url)
        BrowserCommand.entries.forEach { command ->
            assertNotNull(TwitchAdapter.scriptFor(command, requestedUri, requestedUri))
            assertNotNull(
                TwitchAdapter.scriptFor(command, requestedUri, URI("https://www.twitch.tv/queenbee")),
            )
            assertNotNull(
                TwitchAdapter.scriptFor(
                    command,
                    requestedUri,
                    URI("https://m.twitch.tv/queenbee/home"),
                ),
            )
        }
        listOf(
            "https://m.twitch.tv/login",
            "https://m.twitch.tv/another_channel",
            "https://m.twitch.tv/queenbee/extra",
            "https://m.twitch.tv/queenbee/videos",
            "https://m.twitch.tv/queenbee?unexpected=true",
            "http://m.twitch.tv/queenbee",
            "https://m.twitch.tv.evil.example/queenbee",
        ).forEach { url ->
            assertEquals(null, TwitchAdapter.scriptFor(BrowserCommand.PLAY, requestedUri, URI(url)))
        }
        assertNotNull(TwitchAdapter.focusScriptFor(requestedUri, requestedUri))
        val focusScript = requireNotNull(
            TwitchAdapter.focusScriptFor(requestedUri, requestedUri),
        )
        assertTrue(focusScript.contains("candidate.textContent?.trim() === 'Keep using web'"))
        assertTrue(focusScript.contains("candidate.textContent?.trim() === 'Open in App'"))
        assertTrue(focusScript.contains("appUri.pathname.toLowerCase()"))
        assertNotNull(
            TwitchAdapter.focusScriptFor(requestedUri, URI("https://www.twitch.tv/queenbee")),
        )
        assertNotNull(
            TwitchAdapter.focusScriptFor(
                requestedUri,
                URI("https://m.twitch.tv/queenbee/home"),
            ),
        )
        assertEquals(
            null,
            TwitchAdapter.focusScriptFor(
                requestedUri,
                URI("https://m.twitch.tv/another_channel"),
            ),
        )
        assertEquals(
            null,
            TwitchAdapter.focusScriptFor(
                requestedUri,
                URI("https://m.twitch.tv/queenbee?unexpected=true"),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            TwitchAdapter.browserRequest(ResourceLocator("https://www.twitch.tv/queenbee/extra"))
        }
    }

    @Test
    fun `full-site audio commands notify Twitch of media volume changes`() {
        val resource = TwitchAdapter.fullSiteResource("queenbee")
        val uri = URI(resource.value)
        val unmuteScript = requireNotNull(
            TwitchAdapter.scriptFor(BrowserCommand.UNMUTE, uri, uri),
        )
        val muteScript = requireNotNull(
            TwitchAdapter.scriptFor(BrowserCommand.MUTE, uri, uri),
        )
        val volumeScript = requireNotNull(
            TwitchAdapter.scriptFor(BrowserCommand.VOLUME_UP, uri, uri),
        )

        assertTrue(unmuteScript.contains("if (media.volume === 0) media.volume = 0.5"))
        assertTrue(unmuteScript.contains("new Event('volumechange', { bubbles: true })"))
        assertTrue(muteScript.contains("new Event('volumechange', { bubbles: true })"))
        assertTrue(volumeScript.contains("new Event('volumechange', { bubbles: true })"))
    }
}
