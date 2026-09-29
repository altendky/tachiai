package net.fstab.tachiai.provider.abema

import java.net.URI
import net.fstab.tachiai.presentation.ResourceLocator
import net.fstab.tachiai.provider.BrowserCommand
import net.fstab.tachiai.provider.BrowserIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AbemaAdapterTest {
    @Test
    fun `top-level navigation accepts only exact secure ABEMA origins`() {
        assertTrue(AbemaAdapter.isTopLevelNavigationAllowed(URI("https://abema.tv/video/title/394-72")))
        assertTrue(AbemaAdapter.isTopLevelNavigationAllowed(URI("https://www.abema.tv/channels/sumo")))
        assertFalse(AbemaAdapter.isTopLevelNavigationAllowed(URI("https://abema.tv/account")))
        assertFalse(AbemaAdapter.isTopLevelNavigationAllowed(URI("http://abema.tv/video/title/394-72")))
        assertFalse(AbemaAdapter.isTopLevelNavigationAllowed(URI("https://abema.tv.evil.example/video/")))
        assertFalse(AbemaAdapter.isTopLevelNavigationAllowed(URI("https://user@abema.tv/video/")))
        assertFalse(AbemaAdapter.isTopLevelNavigationAllowed(URI("https://abema.tv:8443/video/")))
    }

    @Test
    fun `commands are available only on playback routes`() {
        assertNotNull(
            AbemaAdapter.scriptFor(
                BrowserCommand.MUTE,
                URI("https://abema.tv/video/title/394-72"),
                URI("https://abema.tv/video/title/394-72"),
            ),
        )
        assertNotNull(
            AbemaAdapter.scriptFor(
                BrowserCommand.PAUSE,
                URI("https://abema.tv/video/title/394-72"),
                URI("https://abema.tv/channels/sumo/slots/example"),
            ),
        )
        assertNull(
            AbemaAdapter.scriptFor(
                BrowserCommand.PLAY,
                URI("https://abema.tv/video/title/394-72"),
                URI("https://abema.tv/account"),
            ),
        )
    }

    @Test
    fun `diagnostics are available only on exact secure playback routes`() {
        assertTrue(AbemaAdapter.isDiagnosticRouteAllowed(URI("https://abema.tv/video/episode/example")))
        assertTrue(AbemaAdapter.isDiagnosticRouteAllowed(URI("https://www.abema.tv/channels/sumo")))
        assertFalse(AbemaAdapter.isDiagnosticRouteAllowed(URI("https://abema.tv/account")))
        assertFalse(AbemaAdapter.isDiagnosticRouteAllowed(URI("http://abema.tv/video/episode/example")))
        assertFalse(AbemaAdapter.isDiagnosticRouteAllowed(URI("https://abema.tv.evil.example/video/")))
    }

    @Test
    fun `browser request rejects an unallowlisted initial resource`() {
        assertThrows(IllegalArgumentException::class.java) {
            AbemaAdapter.browserRequest(ResourceLocator("https://abema.tv.evil.example/video/"))
        }
    }

    @Test
    fun `browser request selects the experimentally verified desktop identity`() {
        val request = AbemaAdapter.browserRequest(
            ResourceLocator("https://abema.tv/video/episode/394-72_s10_p8529"),
        )

        assertEquals(BrowserIdentity.DESKTOP_CHROME, request.browserIdentity)
    }
}
