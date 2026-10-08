package net.fstab.tachiai.feature.diagnostic

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AbemaInspectionPolicyTest {
    @Test fun `fixed source selection has no arbitrary URL fallback`() {
        assertEquals(AbemaInspectionSource.NEWS, AbemaInspectionPolicy.source(null))
        assertEquals(AbemaInspectionSource.NEWS, AbemaInspectionPolicy.source("NEWS"))
        assertEquals(AbemaInspectionSource.REPLAY, AbemaInspectionPolicy.source("REPLAY"))
        listOf("", "news", "https://abema.tv/", "LOGIN").forEach { assertNull(AbemaInspectionPolicy.source(it)) }
    }

    @Test fun `older Android does not fall back to the ordinary browser profile`() {
        assertFalse(AbemaInspectionPolicy.supportsInspection(26))
        assertFalse(AbemaInspectionPolicy.supportsInspection(27))
        assertTrue(AbemaInspectionPolicy.supportsInspection(28))
        assertTrue(AbemaInspectionPolicy.supportsInspection(37))
    }

    @Test fun `only exact playback targets may navigate`() {
        AbemaInspectionSource.entries.forEach { assertTrue(AbemaInspectionPolicy.allowsNavigation(URI(it.url))) }
        listOf("https://abema.tv/", "https://abema.tv/login", "https://abema.tv/account",
            "https://abema.tv/now-on-air/other", "https://abema.tv/video/episode/other", "about:blank",
            "https://www.twitch.tv/login", "intent://abema", "file:///local").forEach {
            assertFalse(it, AbemaInspectionPolicy.allowsNavigation(URI(it)))
        }
    }

    @Test fun `origin and encoding variants cannot extend the target allowlist`() {
        val url = AbemaInspectionSource.NEWS.url
        listOf(url.replace("https:", "http:"), url.replace(".tv/", ".tv:443/"),
            url.replace("abema.tv", "abema.tv.evil.example"), url.replace("https://", "https://user@"),
            url.replace("abema.tv", "www.abema.tv"), url.replace("abema-news", "%61bema-news"),
            "$url?token=not-real", "$url#fragment", "$url/").forEach {
            assertFalse(it, AbemaInspectionPolicy.allowsNavigation(URI(it)))
        }
    }
}
