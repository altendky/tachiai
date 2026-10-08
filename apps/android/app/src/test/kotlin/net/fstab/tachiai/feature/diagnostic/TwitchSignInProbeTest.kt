package net.fstab.tachiai.feature.diagnostic

import java.net.URI
import net.fstab.tachiai.presentation.PaneId
import net.fstab.tachiai.presentation.ResourceLocator
import net.fstab.tachiai.provider.BrowserCommand
import net.fstab.tachiai.provider.BrowserIdentity
import net.fstab.tachiai.provider.BrowserRequest
import net.fstab.tachiai.provider.BrowserProbeRole
import net.fstab.tachiai.provider.BrowserResourceCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchSignInProbeTest {
    @Test
    fun `web comparisons use identical header text to avoid viewport height confounds`() {
        assertEquals(twitchSessionStageLabel(SessionStage.WEB), twitchSessionStageLabel(SessionStage.DEFAULT_DIALOGS_WEB))
        assertEquals("WEB", twitchSessionStageLabel(SessionStage.WEB))
        assertEquals("SIGN_IN", twitchSessionStageLabel(SessionStage.SIGN_IN))
    }

    @Test
    fun `default dialogs change only the web request dialog option`() {
        val resource = ResourceLocator("unused")
        val baseline = TwitchSessionAdapter(false, "bobross").browserRequest(resource)
        val comparisonAdapter = TwitchSessionAdapter(false, "bobross", defaultDialogs = true)
        val comparison = comparisonAdapter.browserRequest(resource)
        assertEquals(baseline.copy(debugUseDefaultScriptDialogs = true), comparison)
        assertEquals(BrowserProbeRole.WEB, comparison.nativeProbeRole)
        assertTrue(comparison.suppressScriptDialogsAndConsole)
        assertTrue(comparison.popupHttpsHosts.isEmpty())
        assertFalse(TwitchSessionAdapter(true, "bobross", defaultDialogs = true).browserRequest(resource).debugUseDefaultScriptDialogs)
        assertFalse(TwitchSessionAdapter(false, "bobross", desktop = true, defaultDialogs = true).browserRequest(resource).debugUseDefaultScriptDialogs)
        assertFalse(TwitchEverythingSessionAdapter("bobross").browserRequest(resource).debugUseDefaultScriptDialogs)
        assertNull(comparisonAdapter.focusScriptFor(URI(comparison.url), URI(comparison.url)))
        BrowserCommand.entries.forEach { assertNull(comparisonAdapter.scriptFor(it, URI(comparison.url), URI(comparison.url))) }
    }

    @Test
    fun `native launch mode accepts only the closed web comparisons`() {
        assertEquals(SessionStage.WEB, initialTwitchSessionStage("web"))
        assertEquals(SessionStage.DEFAULT_DIALOGS_WEB, initialTwitchSessionStage("default-dialogs"))
        listOf(null, "", "login", "https://example.com", "WEB").forEach {
            assertEquals(SessionStage.EVERYTHING, initialTwitchSessionStage(it))
        }
    }

    @Test
    fun `overview comparison changes only native direct login overview setting after wide viewport`() {
        val resource = ResourceLocator("unused")
        val wide = TwitchSessionAdapter(true, "bobross", wideViewport = true).browserRequest(resource)
        val overview = TwitchSessionAdapter(true, "bobross", wideViewport = true, overviewMode = true)
            .browserRequest(resource)
        assertFalse(BrowserRequest("https://example.com").loadWithOverviewMode)
        assertEquals(wide.copy(loadWithOverviewMode = true), overview)
        assertFalse(TwitchSessionAdapter(true, "bobross", overviewMode = true)
            .browserRequest(resource).loadWithOverviewMode)
        assertFalse(TwitchSessionAdapter(false, "bobross", wideViewport = true, overviewMode = true)
            .browserRequest(resource).loadWithOverviewMode)
        assertFalse(TwitchEverythingSessionAdapter("bobross").browserRequest(resource).loadWithOverviewMode)
    }

    @Test
    fun `wide viewport comparison changes only native direct login viewport configuration`() {
        val resource = ResourceLocator("unused")
        val baseline = TwitchSessionAdapter(true, "bobross").browserRequest(resource)
        val wide = TwitchSessionAdapter(true, "bobross", wideViewport = true).browserRequest(resource)
        assertFalse(BrowserRequest("https://example.com").useWideViewport)
        assertFalse(baseline.useWideViewport)
        assertEquals(baseline.copy(useWideViewport = true), wide)
        assertFalse(TwitchSessionAdapter(false, "bobross", wideViewport = true).browserRequest(resource).useWideViewport)
        assertFalse(TwitchEverythingSessionAdapter("bobross").browserRequest(resource).useWideViewport)
    }

    @Test
    fun `full embed opts into narrow popups but has no provider document injection`() {
        val adapter = TwitchEverythingSessionAdapter("bobross")
        val request = adapter.browserRequest(ResourceLocator("unused"))
        val initial = URI(request.url)
        val config = URI("https://assets.twitch.tv/config/settings.a346c0e26e35ab87386587f3d6bd61f2.js")
        assertEquals(BrowserResourceCategory.CONFIG_SCRIPT, adapter.diagnosticResourceCategory(config))
        assertNull(TwitchSessionAdapter(true, "bobross").diagnosticResourceCategory(config))
        assertTrue(request.isPackagedAsset)
        assertTrue(request.acceptsThirdPartyCookies)
        assertFalse(request.allowsExternalNavigation)
        assertEquals(BrowserIdentity.MOBILE_CHROME, request.browserIdentity)
        assertEquals(setOf("www.twitch.tv"), request.popupHttpsHosts)
        assertEquals(setOf("m.twitch.tv"), request.popupDiagnosticAlternateHttpsHosts)
        assertTrue(request.suppressScriptDialogsAndConsole)
        assertTrue(request.logNativePageEvents)
        assertTrue(adapter.isTopLevelNavigationAllowed(initial))
        listOf(URI(TWITCH_SIGN_IN_URL), URI("https://appassets.androidplatform.net/assets/twitch/session.html"),
            URI(request.url + "&unexpected=1")).forEach {
            assertFalse(adapter.isTopLevelNavigationAllowed(it))
            assertFalse(adapter.isDiagnosticRouteAllowed(it))
            assertNull(adapter.focusScriptFor(initial, it))
            BrowserCommand.entries.forEach { command -> assertNull(adapter.scriptFor(command, initial, it)) }
        }
        assertFalse(adapter.isDiagnosticRouteAllowed(initial))
        assertNull(adapter.focusScriptFor(initial, initial))
        assertThrows(IllegalArgumentException::class.java) { TwitchEverythingSessionAdapter("bad/channel") }
        assertTrue(TwitchSessionAdapter(true, "bobross").browserRequest(ResourceLocator("unused")).popupHttpsHosts.isEmpty())
        assertTrue(ProviderTimingAdapter("twitch", "live", "bobross").browserRequest(ResourceLocator("unused")).popupHttpsHosts.isEmpty())
    }

    @Test
    fun `sign in uses the normal first-party profile without identity override or external handoff`() {
        val adapter = TwitchSessionAdapter(true, "bobross", desktop = true)
        val request = adapter.browserRequest(ResourceLocator("unused"))
        assertEquals(TWITCH_SIGN_IN_URL, request.url)
        assertEquals("https://www.twitch.tv/login", request.url)
        assertEquals(BrowserIdentity.DEFAULT, request.browserIdentity)
        assertTrue(request.acceptsThirdPartyCookies)
        assertFalse(request.isPackagedAsset)
        assertFalse(request.allowsExternalNavigation)
        assertTrue(request.suppressScriptDialogsAndConsole)
        assertTrue(request.logNativePageEvents)
        listOf("https://m.twitch.tv/login", "https://www.twitch.tv/login?callback=opaque",
            "https://www.twitch.tv/bobross").forEach { assertTrue(adapter.isTopLevelNavigationAllowed(URI(it))) }
        listOf("http://m.twitch.tv/login", "https://user@m.twitch.tv/login", "https://m.twitch.tv:444/login",
            "https://m.twitch.tv.evil.example/login", "https://id.twitch.tv/login",
            "https://player.twitch.tv", "https://appassets.androidplatform.net/login").forEach {
            assertFalse(adapter.isTopLevelNavigationAllowed(URI(it)))
        }
    }

    @Test
    fun `sign in and original web modes have no adapter or diagnostic injection`() {
        listOf(true, false).forEach { login ->
            val adapter = TwitchSessionAdapter(login, "bobross")
            val requested = URI(adapter.browserRequest(ResourceLocator("unused")).url)
            assertTrue(adapter.browserRequest(ResourceLocator("unused")).logNativePageEvents)
            listOf(requested, URI(TWITCH_SIGN_IN_URL)).forEach { current ->
                assertNull(adapter.focusScriptFor(requested, current))
                assertFalse(adapter.isDiagnosticRouteAllowed(current))
                BrowserCommand.entries.forEach { command ->
                    assertNull(adapter.scriptFor(command, requested, current))
                    assertNull(adapter.scriptForPane(PaneId("pane"), command, 1, requested, current))
                }
            }
            assertEquals(!login, adapter.isProtectedMediaOriginAllowed(URI("https://player.twitch.tv")))
            assertFalse(adapter.isProtectedMediaOriginAllowed(URI("https://evil.example")))
        }
    }

    @Test
    fun `manual web mode validates fixture and labels desktop identity as separate choice`() {
        assertThrows(IllegalArgumentException::class.java) { TwitchSessionAdapter(false, "bad/channel") }
        val request = TwitchSessionAdapter(false, "bobross", desktop = true).browserRequest(ResourceLocator("unused"))
        assertEquals("https://www.twitch.tv/bobross", request.url)
        assertEquals(BrowserIdentity.DESKTOP_CHROME, request.browserIdentity)
        assertFalse(request.allowsExternalNavigation)
        val embed = ProviderTimingAdapter("twitch", "live", "bobross", allowsExternalNavigation = false)
            .browserRequest(ResourceLocator("unused"))
        assertFalse(embed.allowsExternalNavigation)
        assertFalse(embed.suppressScriptDialogsAndConsole)
        val sessionEmbed = ProviderTimingAdapter("twitch", "live", "bobross", allowsExternalNavigation = false,
            suppressScriptDialogsAndConsole = true).browserRequest(ResourceLocator("unused"))
        assertTrue(sessionEmbed.suppressScriptDialogsAndConsole)
        assertTrue(sessionEmbed.popupHttpsHosts.isEmpty())
    }
}
