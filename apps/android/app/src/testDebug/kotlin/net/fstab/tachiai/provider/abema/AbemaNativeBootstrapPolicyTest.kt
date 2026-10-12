package net.fstab.tachiai.provider.abema

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class AbemaNativeBootstrapPolicyTest {
    @Test fun `slot policy binds one exact metadata license and public region data without navigation or unrelated APIs`() {
        val identity = "sumo/fixture-slot"
        fun allows(value: String, method: String = "GET", main: Boolean = false) =
            AbemaNativeBootstrapPolicy.allows(URI(value), main, method, slotIdentity = identity)
        val slot = "https://api.p-c3-e.abema-tv.com/v1/media/slots/fixture-slot?include=payperview"
        assertTrue(allows(slot)); assertTrue(allows(slot, "OPTIONS")); assertFalse(allows(slot, "POST"))
        assertTrue(allows(AbemaNativeBootstrapPolicy.SLOT_REGION_PAGE))
        assertTrue(allows(AbemaNativeBootstrapPolicy.SLOT_REGION_URI))
        assertFalse(allows(AbemaNativeBootstrapPolicy.SLOT_REGION_PAGE, main = true))
        assertFalse(allows(AbemaNativeBootstrapPolicy.SLOT_REGION_URI, "OPTIONS"))
        for (uri in listOf(slot.replace("fixture-slot", "other-slot"), slot + "&division=0", slot.substringBefore('?'),
            slot.replace("include=payperview", "include=unknown"), "https://api.abema.io/v1/channels",
            "https://api.p-c3-e.abema-tv.com/v1/video/programs/fixture-episode",
            "https://streaming-api-cf.p-c2-x.abema-tv.com/v1/playbackResources/fixture",
            AbemaNativeBootstrapPolicy.SLOT_REGION_PAGE + "?fakeCountry=JP",
            AbemaNativeBootstrapPolicy.SLOT_REGION_URI + "?country=JP", "https://abema.tv/auth/login")) assertFalse(uri, allows(uri))
        val license = "https://license.p-c3-e.abema-tv.com/abematv-dash?t=fixture&ct=slot&cid=fixture-slot"
        assertTrue(allows(license, "POST")); assertTrue(allows(license, "OPTIONS")); assertFalse(allows(license))
        for (uri in listOf(license.replace("ct=slot", "ct=channel"), license.replace("ct=slot", "ct=program"),
            license.replace("fixture-slot", "other-slot"), "$license&tt=unverified", "$license&t=duplicate"))
            assertFalse(uri, allows(uri, "POST"))
        assertFalse(AbemaNativeBootstrapPolicy.allows(URI(slot), false, "GET", episodeId = "fixture", slotIdentity = identity))
        assertFalse(AbemaNativeBootstrapPolicy.allows(URI(slot), false, "GET", slotIdentity = "sumo/fixture-slot/extra"))
        val csp = AbemaNativeBootstrapPolicy.contentSecurityPolicy(slotIdentity = identity)
        assertTrue(csp.contains(slot.substringBefore('?')))
        assertTrue(csp.contains(AbemaNativeBootstrapPolicy.SLOT_REGION_PAGE))
        assertTrue(csp.contains(AbemaNativeBootstrapPolicy.SLOT_REGION_URI))
        assertFalse(csp.contains("api.abema.io")); assertFalse(csp.contains("playbackResources")); assertFalse(csp.contains("video/programs"))
        assertTrue(csp.contains("media-src 'none'")); assertTrue(csp.contains("frame-src 'none'"))
    }

    @Test fun `refusal diagnostics disclose only fixed request shape categories`() {
        val base = "https://streaming-api-cf.p-c2-x.abema-tv.com/v1/playbackResources/"
        for ((suffix, category) in listOf("fixture/part" to "GATEWAY_SLASH",
            "fixture+part" to "GATEWAY_OTHER_SHAPE", "fixture%3Apart" to "ENCODED_PATH",
            "fixture?t=PRIVATE_FIXTURE" to "GATEWAY_QUERY", "x".repeat(257) to "GATEWAY_TOO_LONG")) {
            val uri = URI(base + suffix)
            assertFalse(AbemaNativeBootstrapPolicy.allows(uri, false, "GET"))
            assertEquals(category, AbemaNativeBootstrapPolicy.refusalCategory(uri, false, "GET"))
        }
        assertEquals("GATEWAY_OPTIONS", AbemaNativeBootstrapPolicy.refusalCategory(URI(base + "fixture"), false, "OPTIONS"))
        assertEquals("OTHER_POLICY", AbemaNativeBootstrapPolicy.refusalCategory(URI("https://unrelated.example/private"), false, "GET"))
        assertEquals("INVALID_URI", AbemaNativeBootstrapPolicy.refusalCategory(null, false, "GET"))
    }

    @Test fun `advertised gateway identifiers admit unreserved dot and tilde without opening extra routes`() {
        val base = "https://streaming-api-cf.p-c2-x.abema-tv.com/v1/playbackResources/"
        for (suffix in listOf("fixture.part", "arin:fixture~part", "fixture.part~suffix")) {
            assertTrue(AbemaNativeBootstrapPolicy.allows(URI(base + suffix), false, "GET"))
        }
        for (suffix in listOf(".", "..", "fixture/part", "fixture%2Epart", "fixture?other=value", "fixture#other")) {
            assertFalse(AbemaNativeBootstrapPolicy.allows(URI(base + suffix), false, "GET"))
        }
    }

    @Test fun `only unchanged legacy selector query expands selected News source policy`() {
        val source = "https://linear-abematv.akamaized.net/channel/abema-news/manifest.mpd"
        val selected = "$source?t=fixture&enc=clear&dt=pc_chrome&ut=1&sw=0"
        assertTrue(AbemaNativeBootstrapPolicy.allowsNewsSource(URI(selected)))
        assertTrue(AbemaNativeBootstrapPolicy.allowsNewsCdn(URI(selected)))
        assertTrue(AbemaNativeBootstrapPolicy.allowsNewsCdn(URI(source.replace("manifest.mpd", "segment.m4s"))))
        assertEquals(AbemaNewsMediaUriPolicy.QUERY, abemaNewsMediaUriPolicy(URI(selected)))
        listOf(source, "$source?utc_timing=true", "$selected&password=fixture", "$selected&t=duplicate",
            selected.replace("enc=clear", "enc=wv"), selected.replace("sw=0", "sw=1"),
            selected.replace("dt=pc_chrome", "dt=unreviewed"), selected.replace("ut=1", "ut=0"),
            selected.replace("t=fixture", "%74=fixture"), "$selected#fragment",
            selected.replace("abema-news", "other"), selected.replace("manifest.mpd", "segment.m4s"),
            selected.replace("https", "http")).forEach {
            assertFalse(AbemaNativeBootstrapPolicy.allowsNewsSource(URI(it)))
        }
    }

    @Test fun `only the owned runtime document can navigate`() {
        assertTrue(AbemaNativeBootstrapPolicy.allows(URI(AbemaNativeBootstrapPolicy.PAGE), true, "GET"))
        listOf("https://abema.tv/", "https://abema.tv/video/episode/394-72_s10_p8529",
            "https://abema.tv/now-on-air/abema-news", "https://abema.tv/auth/login",
            AbemaNativeBootstrapPolicy.PAGE + "?t=fixture", AbemaNativeBootstrapPolicy.PAGE + "#fragment").forEach {
            assertFalse(AbemaNativeBootstrapPolicy.allows(URI(it), true, "GET"))
        }
        assertFalse(AbemaNativeBootstrapPolicy.allows(URI(AbemaNativeBootstrapPolicy.PAGE), true, "POST"))
    }

    @Test fun `owned scripts are exact and other documents assets and methods refuse`() {
        for (asset in listOf("control.js", "support.js", "bundle.js", "legacy.js", "utilities.js", "initialize.js")) {
            val uri = URI("https://abema.tv/_tachiai/native/$asset")
            assertTrue(AbemaNativeBootstrapPolicy.allows(uri, false, "GET"))
            assertFalse(AbemaNativeBootstrapPolicy.allows(uri, false, "POST"))
            assertFalse(AbemaNativeBootstrapPolicy.allows(URI(uri.toString() + "?x=fixture"), false, "GET"))
        }
        listOf("http://abema.tv/_tachiai/native/control.js", "https://user@abema.tv/_tachiai/native/control.js",
            "https://abema.tv:443/_tachiai/native/control.js", "https://other.abema.tv/_tachiai/native/control.js",
            "https://abema.tv/_tachiai/native/%63ontrol.js", "https://abema.tv/_tachiai/native/../native/control.js",
            "https://abema.tv/_tachiai/native/index.html", "https://abema.tv/assets/unreviewed.js").forEach {
            assertFalse(AbemaNativeBootstrapPolicy.allows(URI(it), false, "GET"))
        }
    }

    @Test fun `only reviewed anonymous bootstrap and initial license methods pass`() {
        val guest = URI("https://abema.tv/api/auth/login/guest")
        assertTrue(AbemaNativeBootstrapPolicy.allows(guest, false, "POST"))
        assertFalse(AbemaNativeBootstrapPolicy.allows(guest, false, "GET"))
        assertFalse(AbemaNativeBootstrapPolicy.allows(URI("https://abema.tv/api/auth/accessToken"), false, "POST"))
        assertTrue(AbemaNativeBootstrapPolicy.allows(URI("https://api.abema.io/v1/channels"), false, "GET"))
        assertFalse(AbemaNativeBootstrapPolicy.allows(URI("https://api.abema.io/v1/channels?dtid=fixture"), false, "GET"))
        val token = "https://api.p-c3-e.abema-tv.com/v1/media/token?osName=pc&osVersion=fixture&osLang=en&osTimezone=UTC&appVersion=fixture"
        assertTrue(AbemaNativeBootstrapPolicy.allows(URI(token), false, "GET"))
        assertTrue(AbemaNativeBootstrapPolicy.allows(URI(token), false, "OPTIONS"))
        assertFalse(AbemaNativeBootstrapPolicy.allows(URI(token), false, "POST"))
        assertFalse(AbemaNativeBootstrapPolicy.allows(URI(token + "&password=fixture"), false, "GET"))
        assertFalse(AbemaNativeBootstrapPolicy.allows(URI(token + "&osName=duplicate"), false, "GET"))
        assertTrue(AbemaNativeBootstrapPolicy.allows(URI("https://streaming-api-cf.p-c2-x.abema-tv.com/v1/playbackResources/abema-news"), false, "GET"))
        val license = "https://license.p-c3-e.abema-tv.com/abematv-dash?t=fixture&ct=program&cid=fixture"
        assertTrue(AbemaNativeBootstrapPolicy.allows(URI(license), false, "POST"))
        assertTrue(AbemaNativeBootstrapPolicy.allows(URI("$license&dt=web_android"), false, "POST"))
        assertFalse(AbemaNativeBootstrapPolicy.allows(URI("$license&dt=unreviewed"), false, "POST"))
        assertFalse(AbemaNativeBootstrapPolicy.allows(URI(license), false, "GET"))
        assertFalse(AbemaNativeBootstrapPolicy.allows(URI(license + "&redirect=fixture"), false, "POST"))
        assertFalse(AbemaNativeBootstrapPolicy.allows(URI(license.replace("?t=fixture", "?%74=fixture")), false, "POST"))
        assertFalse(AbemaNativeBootstrapPolicy.allows(URI("https://unrelated.example/v1/media/token"), false, "GET"))
    }
}
