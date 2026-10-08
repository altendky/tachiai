package net.fstab.tachiai.feature.diagnostic

import java.net.URI
import net.fstab.tachiai.feature.presentation.ABEMA_SUMO_REPLAY_URL
import net.fstab.tachiai.presentation.ResourceLocator
import net.fstab.tachiai.presentation.PaneId
import net.fstab.tachiai.provider.BrowserCommand
import net.fstab.tachiai.provider.twitch.TwitchCompositeEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(TwitchCompositeEndpoint.assetPaths, request.packagedChildPaths)
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

        assertTrue(script.contains("https://appassets.androidplatform.net/assets/twitch/composite.html"))
        assertTrue(script.contains("searchParams.set('channel', 'arcajazz')"))
        assertTrue(script.contains("event.source !== frame.contentWindow"))
        assertTrue(script.contains("event.origin !== '${TwitchCompositeEndpoint.ORIGIN}'"))
        assertTrue(script.contains("data.requestId !== endpoint.requestId"))
        assertTrue(script.contains("data.frameSession !== endpoint.frameSession"))
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

    @Test
    fun `targeted controls preserve provider and route boundaries`() {
        val adapter = AbemaTwitchSingleWebContentsAdapter("arcajazz")
        val replay = URI(ABEMA_SUMO_REPLAY_URL)
        val abema = adapter.scriptForPane(COMPOSITE_ABEMA_PANE, BrowserCommand.PAUSE, 1, replay, replay)!!
        val twitch = adapter.scriptForPane(COMPOSITE_TWITCH_PANE, BrowserCommand.PAUSE, 2, replay, replay)!!

        assertNull(abema.poll)
        assertFalse(abema.send.contains("postMessage"))
        assertTrue(twitch.send.contains("postMessage"))
        assertTrue(twitch.send.contains("requestId: 2"))
        assertTrue(twitch.send.contains("command: 'pause'"))
        assertTrue(twitch.poll!!.contains("endpoint.requestId === 2"))
        assertNull(adapter.scriptForPane(PaneId("unknown"), BrowserCommand.PLAY, 3, replay, replay))
        listOf("https://abema.tv/account", "https://abema.tv:444/video/foo", "https://evil.example/video/foo")
            .forEach { url ->
                assertNull(adapter.scriptForPane(COMPOSITE_TWITCH_PANE, BrowserCommand.PLAY, 3, replay, URI(url)))
            }
    }

    @Test
    fun `endpoint rejects request ids outside JavaScript safe integer range`() {
        listOf(0L, -1L, 9_007_199_254_740_992L).forEach { id ->
            assertThrows(IllegalArgumentException::class.java) {
                TwitchCompositeEndpoint.commandScript(BrowserCommand.PLAY, id)
            }
        }
    }
}
