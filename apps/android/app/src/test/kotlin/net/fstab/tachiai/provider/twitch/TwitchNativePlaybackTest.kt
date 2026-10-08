package net.fstab.tachiai.provider.twitch

import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchNativePlaybackTest {
    @Test fun `live URI uses fixed v2 path and separately encoded values`() {
        val value = "{\"sample\":\"a+b /&=?\"}"
        val source = twitchPlaybackSource(TwitchAccessCase.LIVE, "bobross", "abcdef", value)
        assertEquals("usher.ttvnw.net", source.uri.host)
        assertEquals("/api/v2/channel/hls/bobross.m3u8", source.uri.path)
        val pairs = source.uri.rawQuery.split('&').associate { part ->
            val pair = part.split('=', limit = 2)
            pair[0] to URLDecoder.decode(pair[1], "UTF-8")
        }
        assertEquals(value, pairs["token"])
        assertEquals("abcdef", pairs["sig"])
        assertEquals(setOf("sig", "token", "platform", "allow_source", "allow_audio_only"), pairs.keys)
        assertEquals("web", pairs["platform"])
        assertFalse(source.toString().contains("abcdef"))
        assertFalse(source.toString().contains("sample"))
    }

    @Test fun `replay uses its distinct fixed v2 path`() {
        assertEquals("/vod/v2/2080217716.m3u8",
            twitchPlaybackSource(TwitchAccessCase.REPLAY, "2080217716", "abc", "{}").uri.path)
    }

    @Test fun `resources cannot change resolver path or query`() {
        for (resource in listOf("../other", "a/b", "bobross?token=x", "bobross#x", "a", "x".repeat(26))) {
            rejected { twitchPlaybackSource(TwitchAccessCase.LIVE, resource, "abc", "{}") }
        }
        for (resource in listOf("abc", "1/2", "1?x", "1".repeat(21))) {
            rejected { twitchPlaybackSource(TwitchAccessCase.REPLAY, resource, "abc", "{}") }
        }
    }

    @Test fun `signed fields must be bounded and cannot contain controls`() {
        for (signature in listOf(null, 1, "", "a&token=x", "a".repeat(257))) {
            rejected { twitchPlaybackSource(TwitchAccessCase.LIVE, "bobross", signature, "{}") }
        }
        for (value in listOf(null, 1, "", "value\n", "x".repeat(12_001))) {
            rejected { twitchPlaybackSource(TwitchAccessCase.LIVE, "bobross", "abc", value) }
        }
    }

    @Test fun `media URI policy accepts only experimental provider HTTPS families`() {
        for (uri in listOf("https://usher.ttvnw.net/a", "https://video-weaver.example.hls.ttvnw.net/v.m3u8",
            "https://vod.twitchcdn.net:443/a.ts")) assertTrue(allowedTwitchMediaUri(URI(uri)))
        for (uri in listOf("http://usher.ttvnw.net/a", "https://user@usher.ttvnw.net/a",
            "https://usher.ttvnw.net/a#secret", "https://usher.ttvnw.net:8443/a",
            "https://notttvnw.net/a", "https://ttvnw.net.other.example/a", "https://example.cloudfront.net/a",
            "https://127.0.0.1/a", "https://localhost/a", "file:///a", "https://gql.twitch.tv/gql")) {
            assertFalse(uri, allowedTwitchMediaUri(URI(uri)))
        }
    }

    @Test fun `CDN diagnostic reveals only a recognized public distribution hostname`() {
        val host = "d123456789abc.cloudfront.net"
        assertEquals(host, twitchPublicCdnDiagnosticHost(URI("https://$host/private-path?token=fixture")))
        for (uri in listOf("https://private.example/a?token=fixture", "https://example.cloudfront.net/a",
            "https://$host.other.example/a", "https://user@$host/a", "http://$host/a",
            "https://$host:8443/a", "https://$host/a#fixture")) {
            assertEquals(null, twitchPublicCdnDiagnosticHost(URI(uri)))
        }
    }

    @Test fun `transcode missing classification is exact status and fixed code only`() {
        assertEquals(TwitchManifestRejection.TRANSCODE_MISSING_REPORTED,
            classifyTwitchManifestRejection(404, "transcode_does_not_exist"))
        for (code in listOf(null, 1, "", "not_found", "TRANSCODE_DOES_NOT_EXIST", " transcode_does_not_exist ")) {
            assertEquals(TwitchManifestRejection.UNCLASSIFIED, classifyTwitchManifestRejection(404, code))
        }
        assertEquals(TwitchManifestRejection.UNCLASSIFIED, classifyTwitchManifestRejection(403, "transcode_does_not_exist"))
    }

    @Test fun `public resource inputs distinguish channel and replay syntax`() {
        assertTrue(validTwitchPlaybackResource(TwitchAccessCase.LIVE, "relaxbeats"))
        assertFalse(validTwitchPlaybackResource(TwitchAccessCase.LIVE, "https://twitch.tv/bobross"))
        assertTrue(validTwitchPlaybackResource(TwitchAccessCase.REPLAY, "2080217716"))
        assertFalse(validTwitchPlaybackResource(TwitchAccessCase.REPLAY, "bobross"))
        assertFalse(validTwitchPlaybackResource(TwitchAccessCase.REPLAY, "1".repeat(21)))
    }

    @Test fun `observed replay comparison permits exactly one additional distribution`() {
        val uri = URI("https://$OBSERVED_TWITCH_REPLAY_CDN/fixture.m3u8?token=synthetic")
        assertFalse(allowedTwitchMediaUri(uri))
        assertTrue(allowedTwitchMediaUri(uri, observedReplayCdn = true))
        for (other in listOf("https://other.cloudfront.net/a", "https://$OBSERVED_TWITCH_REPLAY_CDN.other.example/a",
            "https://user@$OBSERVED_TWITCH_REPLAY_CDN/a", "https://$OBSERVED_TWITCH_REPLAY_CDN:8443/a",
            "https://$OBSERVED_TWITCH_REPLAY_CDN/a#fixture", "http://$OBSERVED_TWITCH_REPLAY_CDN/a")) {
            assertFalse(allowedTwitchMediaUri(URI(other), observedReplayCdn = true))
        }
    }

    private fun rejected(action: () -> Unit) {
        try { action(); throw AssertionError("Expected bounded validation rejection") }
        catch (_: IllegalArgumentException) { /* expected */ }
    }
}
