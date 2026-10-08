package net.fstab.tachiai.platform.web

import java.net.URI
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPopupPolicyTest {
    private val hosts = setOf("www.twitch.tv")

    @Test
    fun `popups require explicit opt in and a gesture and only one window`() {
        assertTrue(BrowserPopupPolicy.canOpen(hosts, true, false))
        assertFalse(BrowserPopupPolicy.canOpen(emptySet(), true, false))
        assertFalse(BrowserPopupPolicy.canOpen(hosts, false, false))
        assertFalse(BrowserPopupPolicy.canOpen(hosts, true, true))
    }

    @Test
    fun `only exact HTTPS origins without user info or alternate ports are allowed`() {
        listOf("https://www.twitch.tv/login?opaque=value", "https://www.twitch.tv", "HTTPS://WWW.TWITCH.TV:443/login").forEach {
            assertTrue(BrowserPopupPolicy.allows(URI(it), hosts))
        }
        listOf("http://www.twitch.tv/login", "https://user@www.twitch.tv/login", "https://www.twitch.tv:444/login",
            "https://www.twitch.tv.evil.example/login", "https://id.twitch.tv", "https://passport.twitch.tv",
            "https://m.twitch.tv", "https://player.twitch.tv", "https://appassets.androidplatform.net", "about:blank", "data:text/html,login",
            "javascript:alert(1)", "intent://www.twitch.tv", "file:///login", "https://evil.example/?www.twitch.tv").forEach {
            assertFalse(BrowserPopupPolicy.allows(URI(it), hosts))
        }
    }

    @Test
    fun `initial blank is distinct from a permitted visible login document`() {
        assertTrue(BrowserPopupPolicy.isInitialBlank(URI("about:blank")))
        listOf("about:blank#login", "about:srcdoc", "https://www.twitch.tv").forEach {
            assertFalse(BrowserPopupPolicy.isInitialBlank(URI(it)))
        }
    }

    @Test
    fun `blank or stale documents cannot display under a pending HTTPS origin`() {
        val navigation = BrowserPopupNavigation(hosts)
        val blank = URI("about:blank")
        val login = URI("https://www.twitch.tv/login?popup=true")
        assertTrue(navigation.start(blank))
        assertFalse(navigation.canDisplayCommitted(blank))
        assertFalse(navigation.canDisplayCommitted(login))
        assertTrue(navigation.start(login))
        assertFalse(navigation.canDisplayCommitted(blank))
        assertFalse(navigation.canDisplayCommitted(URI("https://www.twitch.tv/other")))
        assertTrue(navigation.canDisplayCommitted(login))
        assertFalse(navigation.start(blank))
        assertFalse(navigation.canDisplayCommitted(login))
    }

    @Test
    fun `failure before commit and unknown navigation permanently hide the popup`() {
        val login = URI("https://www.twitch.tv/login")
        val failed = BrowserPopupNavigation(hosts)
        assertTrue(failed.start(login))
        failed.fail()
        assertFalse(failed.canDisplayCommitted(login))
        assertFalse(failed.start(login))
        val blocked = BrowserPopupNavigation(hosts)
        assertTrue(blocked.start(login))
        assertFalse(blocked.start(URI("https://evil.example/login")))
        assertFalse(blocked.canDisplayCommitted(login))
        assertFalse(BrowserPopupNavigation(hosts).start(null))
    }
}
