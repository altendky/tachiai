package net.fstab.tachiai.feature.diagnostic

import java.net.URI
import net.fstab.tachiai.presentation.ResourceLocator
import net.fstab.tachiai.provider.BrowserCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchReplayAlignmentProbeTest {
    @Test
    fun `video identifiers cannot supply script or URL syntax`() {
        listOf(null, "", "0", "v123", "-1", "1.2", "123&channel=foo", "123456789012345678901")
            .forEach { assertNull(validAlignmentVideo(it)) }
        assertEquals("2080217716", validAlignmentVideo(DEFAULT_ALIGNMENT_VIDEO))
        assertThrows(IllegalArgumentException::class.java) { TwitchReplayAlignmentAdapter("bad") }
    }

    @Test
    fun `probe loads only its exact app-owned page`() {
        val adapter = TwitchReplayAlignmentAdapter(DEFAULT_ALIGNMENT_VIDEO)
        val request = adapter.browserRequest(ResourceLocator("unused"))
        assertEquals(
            "https://appassets.androidplatform.net$ALIGNMENT_PATH?video=$DEFAULT_ALIGNMENT_VIDEO",
            request.url,
        )
        assertTrue(request.isPackagedAsset)
        assertTrue(adapter.isTopLevelNavigationAllowed(URI(request.url)))
        listOf(
            "http://appassets.androidplatform.net$ALIGNMENT_PATH",
            "https://appassets.androidplatform.net:444$ALIGNMENT_PATH",
            "https://appassets.androidplatform.net.evil.example$ALIGNMENT_PATH",
            "https://appassets.androidplatform.net/assets/twitch/composite.html",
            "https://appassets.androidplatform.net/assets/twitch/%72eplay-alignment.html",
        ).forEach { assertFalse(adapter.isTopLevelNavigationAllowed(URI(it))) }
    }

    @Test
    fun `permissions stay with original Twitch frame without native injected commands`() {
        val adapter = TwitchReplayAlignmentAdapter(DEFAULT_ALIGNMENT_VIDEO)
        assertTrue(adapter.isProtectedMediaOriginAllowed(URI("https://player.twitch.tv")))
        listOf("http://player.twitch.tv", "https://player.twitch.tv:444", "https://player.twitch.tv.evil.example")
            .forEach { assertFalse(adapter.isProtectedMediaOriginAllowed(URI(it))) }
        val page = URI(adapter.browserRequest(ResourceLocator("unused")).url)
        assertNull(adapter.scriptFor(BrowserCommand.PLAY, page, page))
        assertNull(adapter.focusScriptFor(page, page))
    }
}
