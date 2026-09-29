package net.fstab.tachiai.platform.web

import net.fstab.tachiai.provider.BrowserIdentity
import net.fstab.tachiai.provider.BrowserRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAgentProfileTest {
    private val webViewUserAgent =
        "Mozilla/5.0 (Linux; Android 17; Pixel 6 Build/example; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 " +
            "Chrome/153.0.8010.36 Mobile Safari/537.36"

    @Test
    fun `default profile preserves WebView identity`() {
        assertEquals(webViewUserAgent, userAgentFor(BrowserIdentity.DEFAULT, webViewUserAgent))
    }

    @Test
    fun `mobile Chrome diagnostic removes WebView markers`() {
        val userAgent = userAgentFor(BrowserIdentity.MOBILE_CHROME, webViewUserAgent)

        assertFalse(userAgent.contains("; wv"))
        assertFalse(userAgent.contains("Version/4.0"))
        assertTrue(userAgent.contains("Android 17"))
        assertTrue(userAgent.contains("Chrome/153.0.8010.36"))
    }

    @Test
    fun `desktop Chrome diagnostic retains the installed Chromium version`() {
        assertEquals(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/153.0.8010.36 Safari/537.36",
            userAgentFor(BrowserIdentity.DESKTOP_CHROME, webViewUserAgent),
        )
    }

    @Test
    fun `desktop Chrome diagnostic fails closed for an unexpected default identity`() {
        val unexpected = "unexpected browser identity"

        assertEquals(unexpected, userAgentFor(BrowserIdentity.DESKTOP_CHROME, unexpected))
    }

    @Test
    fun `mobile Chrome diagnostic fails closed for an unexpected default identity`() {
        val unexpected = "unexpected browser identity"

        assertEquals(unexpected, userAgentFor(BrowserIdentity.MOBILE_CHROME, unexpected))
    }

    @Test
    fun `request identity applies when no diagnostic override exists`() {
        val request = BrowserRequest(
            url = "https://example.test/video",
            browserIdentity = BrowserIdentity.DESKTOP_CHROME,
        )

        assertEquals(BrowserIdentity.DESKTOP_CHROME, browserIdentityFor(request, null))
    }

    @Test
    fun `explicit diagnostic identity overrides the request identity`() {
        val request = BrowserRequest(
            url = "https://example.test/video",
            browserIdentity = BrowserIdentity.DESKTOP_CHROME,
        )

        assertEquals(
            BrowserIdentity.DEFAULT,
            browserIdentityFor(request, BrowserIdentity.DEFAULT),
        )
    }
}
