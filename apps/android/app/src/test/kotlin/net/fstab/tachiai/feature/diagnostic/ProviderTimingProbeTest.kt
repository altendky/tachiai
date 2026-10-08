package net.fstab.tachiai.feature.diagnostic

import java.net.URI
import net.fstab.tachiai.feature.presentation.ABEMA_SUMO_REPLAY_URL
import net.fstab.tachiai.presentation.ResourceLocator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderTimingProbeTest {
    @Test
    fun `ABEMA probe is bound to one public playback resource and excludes account routes`() {
        val adapter = ProviderTimingAdapter("abema", "replay", ABEMA_SUMO_REPLAY_URL)
        val page = URI(ABEMA_SUMO_REPLAY_URL)
        assertTrue(adapter.isDiagnosticRouteAllowed(page))
        listOf("http://abema.tv${page.rawPath}", "https://abema.tv:444${page.rawPath}",
            "https://user@abema.tv${page.rawPath}", "https://abema.tv/account",
            "https://abema.tv${page.rawPath}?token=redacted", "https://abema.tv/video/episode/other",
            "https://abema.tv.evil.example${page.rawPath}").forEach {
            assertFalse(adapter.isDiagnosticRouteAllowed(URI(it)))
            assertNull(adapter.diagnosticScript("read", "tools", page, URI(it)))
        }
        assertNotNull(adapter.diagnosticScript("read", "tools", page, page))
        assertNull(adapter.diagnosticScript("arbitrary-code", "tools", page, page))
    }

    @Test
    fun `source kind and identifier syntax fail closed`() {
        listOf(Triple("abema", "live", ABEMA_SUMO_REPLAY_URL), Triple("abema", "replay", "https://abema.tv/account"),
            Triple("twitch", "live", "a');alert(1)"), Triple("twitch", "replay", "v123"),
            Triple("other", "live", "channel"), Triple("twitch", "unknown", "channel")).forEach { (provider, kind, resource) ->
            assertThrows(IllegalArgumentException::class.java) { ProviderTimingAdapter(provider, kind, resource) }
        }
        val live = ProviderTimingAdapter("abema", "live", "https://abema.tv/now-on-air/abema-news")
        assertTrue(live.isDiagnosticRouteAllowed(URI("https://abema.tv/now-on-air/abema-news")))
        assertFalse(live.isDiagnosticRouteAllowed(URI("https://abema.tv/now-on-air/other")))
    }

    @Test
    fun `Twitch uses one exact app-owned SDK page and original media permission origin`() {
        listOf("live" to "relaxbeats", "replay" to DEFAULT_ALIGNMENT_VIDEO).forEach { (kind, resource) ->
            val adapter = ProviderTimingAdapter("twitch", kind, resource)
            val request = adapter.browserRequest(ResourceLocator("unused"))
            assertTrue(request.isPackagedAsset)
            assertTrue(adapter.isDiagnosticRouteAllowed(URI(request.url)))
            assertFalse(adapter.isDiagnosticRouteAllowed(URI("https://appassets.androidplatform.net$TIMING_PATH")))
            assertTrue(adapter.isProtectedMediaOriginAllowed(URI("https://player.twitch.tv")))
            assertFalse(adapter.isProtectedMediaOriginAllowed(URI("https://player.twitch.tv.evil.example")))
        }
    }
}
