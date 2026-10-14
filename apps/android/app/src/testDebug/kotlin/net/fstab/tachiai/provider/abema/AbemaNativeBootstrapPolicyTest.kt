package net.fstab.tachiai.provider.abema

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.*
import org.junit.Test

class AbemaNativeBootstrapPolicyTest {
    private val modernApi = "https://api.p-c3-e.abema-tv.com/v1"
    private fun encoded(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun modernAllows(value: String, method: String = "GET", mainFrame: Boolean = false) =
        AbemaNativeBootstrapPolicy.allows(URI(value), mainFrame, method, channelId = "abema-news", modernChannelContext = true)

    @Test fun `modern context is explicit configured channel scope with no legacy fallback or navigation`() {
        val channel = "$modernApi/channels"
        assertTrue(modernAllows(channel)); assertTrue(modernAllows(channel, "OPTIONS"))
        assertTrue(modernAllows(AbemaNativeBootstrapPolicy.SLOT_REGION_PAGE))
        for (endpoint in AbemaNativeBootstrapPolicy.SLOT_REGION_URIS) {
            assertTrue(modernAllows(endpoint))
            assertFalse(modernAllows(endpoint, "OPTIONS"))
            assertFalse(modernAllows("$endpoint?division=1"))
        }
        for (method in listOf("POST", "HEAD", "DELETE")) assertFalse(modernAllows(channel, method))
        assertFalse(modernAllows(AbemaNativeBootstrapPolicy.SLOT_REGION_PAGE, mainFrame = true))
        assertTrue(modernAllows(AbemaNativeBootstrapPolicy.PAGE, mainFrame = true))
        for (uri in listOf("https://api.abema.io/v1/channels", "https://api.abema.io/v1/video/programs/394-72_s10_p8529",
            "$modernApi/video/programs/fixture", "$modernApi/media/slots/fixture?include=payperview",
            "https://streaming-api-cf.p-c2-x.abema-tv.com/v1/playbackResources/fixture")) assertFalse(modernAllows(uri))
        for ((episode, configuredChannel, slot) in listOf(Triple(null, null, null), Triple("fixture", null, null),
            Triple(null, null, "sumo/fixture"), Triple("fixture", "abema-news", null),
            Triple(null, "abema-news", "sumo/fixture"), Triple(null, "bad/channel", null))) {
            assertFalse(AbemaNativeBootstrapPolicy.allows(URI(channel), false, "GET", episode, configuredChannel, slot, true))
            assertTrue(runCatching { AbemaNativeBootstrapPolicy.contentSecurityPolicy(episode, configuredChannel, slot, true) }.isFailure)
        }
        val csp = AbemaNativeBootstrapPolicy.contentSecurityPolicy(channelId = "abema-news", modernChannelContext = true)
        for (url in listOf(channel, "$modernApi/media/deviceTypeId", AbemaNativeBootstrapPolicy.SLOT_REGION_PAGE) +
            AbemaNativeBootstrapPolicy.SLOT_REGION_URIS) assertTrue(csp.contains(url))
        assertFalse(csp.contains("api.abema.io")); assertFalse(csp.contains("playbackResources"))
        assertFalse(csp.contains("/api/auth/login/guest")); assertFalse(csp.contains("license.p-c3-e.abema-tv.com"))
        val guest = "https://abema.tv/api/auth/login/guest"
        val license = "https://license.p-c3-e.abema-tv.com/abematv-dash?t=fixture&ct=channel&cid=abema-news"
        assertFalse(modernAllows(guest, "POST")); assertFalse(modernAllows(license, "POST"))
        assertFalse(modernAllows(license, "OPTIONS"))
        assertTrue(AbemaNativeBootstrapPolicy.allows(URI(guest), false, "POST", channelId = "abema-news"))
        assertTrue(AbemaNativeBootstrapPolicy.allows(URI(license), false, "POST", channelId = "abema-news"))
        assertTrue(AbemaNativeBootstrapPolicy.contentSecurityPolicy(channelId = "abema-news").contains("/api/auth/login/guest"))
        assertTrue(AbemaNativeBootstrapPolicy.contentSecurityPolicy(channelId = "abema-news").contains("license.p-c3-e.abema-tv.com"))
        assertTrue(csp.contains("media-src 'none'")); assertTrue(csp.contains("frame-src 'none'"))
        for ((episode, configuredChannel, slot) in listOf(Triple(null, null, null), Triple("fixture", null, null),
            Triple(null, "abema-news", null), Triple(null, null, "sumo/fixture"))) {
            assertEquals(AbemaNativeBootstrapPolicy.contentSecurityPolicy(episode, configuredChannel, slot),
                AbemaNativeBootstrapPolicy.contentSecurityPolicy(episode, configuredChannel, slot, false))
            for (url in listOf(channel, "https://api.abema.io/v1/channels", AbemaNativeBootstrapPolicy.PAGE,
                AbemaNativeBootstrapPolicy.SLOT_REGION_PAGE, AbemaNativeBootstrapPolicy.SLOT_REGION_URI))
                assertEquals(AbemaNativeBootstrapPolicy.allows(URI(url), false, "GET", episode, configuredChannel, slot),
                    AbemaNativeBootstrapPolicy.allows(URI(url), false, "GET", episode, configuredChannel, slot, false))
        }
    }

    @Test fun `modern device request preserves closed provider enums and canonical bounded version`() {
        val base = "$modernApi/media/deviceTypeId"
        for (os in listOf("MacOS", "Windows", "unknown")) for (drm in listOf("Widevine", "FairPlay", "PlayReady", "unknown"))
            for (version in listOf("", "16", "x".repeat(64), "fixture version/+~=")) {
                val request = "$base?os=$os&osVersion=${encoded(version)}&drm=$drm"
                assertTrue(request, modernAllows(request)); assertTrue(modernAllows(request, "OPTIONS"))
                assertFalse(AbemaNativeBootstrapPolicy.allows(URI(request), false, "GET", channelId = "abema-news"))
            }
        val valid = "$base?os=unknown&osVersion=16&drm=unknown"
        for (request in listOf(base, "$base?", valid.replace("os=unknown", "os=Android"),
            valid.replace("drm=unknown", "drm=ClearKey"), valid.replace("os=unknown", "os="),
            valid.replace("osVersion=16", "osVersion=${"x".repeat(65)}"),
            valid.replace("osVersion=16", "osVersion=%00"), valid.replace("osVersion=16", "osVersion=%C3%A9"),
            valid.replace("osVersion=16", "osVersion=%31%36"), valid.replace("osVersion=16", "osVersion=a%20b"),
            valid.replace("osVersion=16", "osVersion=a%2fb"), valid.replace("os=unknown", "%6Fs=unknown"),
            valid.substringBefore("&drm="), "$valid&os=unknown", "$valid&extra=1", "$valid&", "$valid#fragment",
            valid.replace(".com/", ".com:443/"), valid.replace("https://", "https://user@"),
            valid.replace("https://", "http://"), valid.replace("/media/", "/media/../media/"),
            valid.replace("deviceTypeId?", "deviceTypeId/?"), valid.replace("deviceTypeId", "%64eviceTypeId")))
            assertFalse(request, modernAllows(request))
        assertFalse(modernAllows(valid, "POST")); assertFalse(modernAllows(valid, mainFrame = true))
    }

    @Test fun `modern channels admit only actual division and canonical bounded opaque device ID`() {
        val base = "$modernApi/channels"
        for (query in listOf("", "?division=1", "?dtid=0", "?dtid=${encoded("opaque:/+~=")}",
            "?division=1&dtid=${"x".repeat(256)}", "?dtid=fixture&division=1")) assertTrue(modernAllows(base + query))
        for (query in listOf("?", "?division=0", "?division=2", "?division=01", "?division=%31", "?dtid=",
            "?dtid=${"x".repeat(257)}", "?dtid=a+b", "?dtid=%00", "?dtid=%7F", "?dtid=%C3%A9",
            "?dtid=%66ixture", "?dtid=a%2fb", "?dtid=a=b", "?dtid=fixture&dtid=other",
            "?division=1&division=1", "?%64tid=fixture", "?os=unknown", "?division=1&", "?division=1&&dtid=fixture"))
            assertFalse(query, modernAllows(base + query))
        for (uri in listOf(base.replace(".com/", ".com.evil/"), base.replace(".com/", ".com:443/"),
            "$base/", "$base#fragment", base.replace("https://", "https://user@"))) assertFalse(modernAllows(uri))
    }

    @Test fun `modern source device ID is opt in and retains every existing source constraint`() {
        val base = "https://linear-abematv.akamaized.net/channel/abema-news/manifest.mpd?t=fixture&enc=clear&dt=pc_chrome&ut=1&sw=0"
        fun allows(uri: String) = AbemaNativeBootstrapPolicy.allowsNewsSource(URI(uri), modernChannelContext = true)
        assertTrue(allows(base))
        for (id in listOf("0", "fixture", "opaque:/+~=", "x".repeat(256))) {
            val uri = "$base&dtid=${encoded(id)}"
            assertTrue(allows(uri)); assertFalse(AbemaNativeBootstrapPolicy.allowsNewsSource(URI(uri)))
            assertFalse(AbemaNativeBootstrapPolicy.allowsNewsCdn(URI(uri)))
        }
        for (uri in listOf("$base&dtid=", "$base&dtid=${"x".repeat(257)}", "$base&dtid=a+b", "$base&dtid=%00",
            "$base&dtid=%7F", "$base&dtid=%C3%A9", "$base&dtid=%66ixture", "$base&dtid=a%2fb",
            "$base&dtid=fixture&dtid=other", "$base&%64tid=fixture", "$base&dtid=a=b", "$base&dtid=fixture&extra=1",
            base.replace("abema-news", "other") + "&dtid=fixture", base.replace("enc=clear", "enc=wv") + "&dtid=fixture",
            base.replace("dt=pc_chrome", "dt=other") + "&dtid=fixture", base.replace("ut=1", "ut=0") + "&dtid=fixture",
            base.replace("sw=0", "sw=1") + "&dtid=fixture", base.replace("manifest.mpd", "segment.m4s") + "&dtid=fixture"))
            assertFalse(uri, allows(uri))
    }

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

    @Test fun `slot region uses either reviewed exact endpoint only for its bound preparation`() {
        for (endpoint in AbemaNativeBootstrapPolicy.SLOT_REGION_URIS) {
            fun allows(text: String = endpoint, method: String = "GET", main: Boolean = false) =
                AbemaNativeBootstrapPolicy.allows(URI(text), main, method, slotIdentity = "sumo/fixture-slot")
            assertTrue(allows())
            assertTrue(AbemaNativeBootstrapPolicy.contentSecurityPolicy(slotIdentity = "sumo/fixture-slot").contains(endpoint))
            for (method in listOf("POST", "OPTIONS", "HEAD")) assertFalse(allows(method = method))
            assertFalse(allows(main = true))
            for (text in listOf("$endpoint?token=fixture", "$endpoint#fragment", endpoint.replace("/region", "/media"),
                endpoint.replace("https://", "http://"), endpoint.replace("https://", "https://user@"),
                endpoint.replace(".net/", ".net.evil/"), endpoint.replace("/region", "/%72egion")))
                assertFalse(text, allows(text))
            assertFalse(AbemaNativeBootstrapPolicy.allows(URI(endpoint), false, "GET"))
            assertFalse(AbemaNativeBootstrapPolicy.allows(URI(endpoint), false, "GET", episodeId = "fixture"))
            assertFalse(AbemaNativeBootstrapPolicy.allows(URI(endpoint), false, "GET", channelId = "abema-news"))
        }
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
