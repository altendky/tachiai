package net.fstab.tachiai.feature.diagnostic

import java.net.URI
import net.fstab.tachiai.feature.presentation.ABEMA_SUMO_REPLAY_URL
import net.fstab.tachiai.presentation.ResourceLocator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AbemaTwitchSingleWebContentsProbeTest {
    @Test
    fun `probe validates channel before generating script`() {
        assertThrows(IllegalArgumentException::class.java) {
            AbemaTwitchSingleWebContentsAdapter("bad-channel!")
        }
    }

    @Test
    fun `probe retains ABEMA top-level policy and opts into third-party cookies`() {
        val adapter = AbemaTwitchSingleWebContentsAdapter("arcajazz")
        val request = adapter.browserRequest(ResourceLocator(ABEMA_SUMO_REPLAY_URL))

        assertTrue(request.acceptsThirdPartyCookies)
        assertTrue(adapter.isTopLevelNavigationAllowed(URI(ABEMA_SUMO_REPLAY_URL)))
        assertFalse(adapter.isTopLevelNavigationAllowed(URI("https://abema.tv/account")))
        assertFalse(adapter.isTopLevelNavigationAllowed(URI("https://abema.tv.evil.example/video/")))
    }

    @Test
    fun `probe grants protected media only to exact provider origins`() {
        val adapter = AbemaTwitchSingleWebContentsAdapter("arcajazz")

        assertTrue(adapter.isProtectedMediaOriginAllowed(URI("https://abema.tv")))
        assertTrue(adapter.isProtectedMediaOriginAllowed(URI("https://player.twitch.tv")))
        assertFalse(adapter.isProtectedMediaOriginAllowed(URI("https://player.twitch.tv.evil.example")))
    }

    @Test
    fun `injection is limited to ABEMA playback routes and fixed official Twitch host`() {
        val adapter = AbemaTwitchSingleWebContentsAdapter("arcajazz")
        val script = adapter.focusScriptFor(
            URI(ABEMA_SUMO_REPLAY_URL),
            URI(ABEMA_SUMO_REPLAY_URL),
        ).orEmpty()

        assertTrue(script.contains("https://player.twitch.tv/"))
        assertTrue(script.contains("searchParams.set('channel', 'arcajazz')"))
        assertTrue(script.contains("searchParams.set('autoplay', 'true')"))
        assertTrue(script.contains("searchParams.set('muted', 'true')"))
        assertTrue(script.contains("location.pathname.startsWith('/video/')"))
        assertTrue(script.contains("document.getElementById(frameId)"))
        assertTrue(script.contains("if (!isPlaybackRoute())"))
        assertTrue(script.contains("stopProbe()"))
        assertTrue(script.contains("window.__tachiaiSingleWebContentsCleanup = stopProbe"))
        assertFalse(script.contains(".click()"))
        assertFalse(
            adapter.focusScriptFor(
                URI(ABEMA_SUMO_REPLAY_URL),
                URI("https://abema.tv/account"),
            ) != null,
        )
    }
}
