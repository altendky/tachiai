package net.fstab.tachiai.provider.abema

import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class AbemaNewsNativePlaybackTest {
    private val source = "https://linear-abematv.akamaized.net/channel/abema-news/manifest.mpd"
    private fun metadata(uri: String = source) =
        "{\"channels\":[{\"id\":\"abema-news\",\"playback\":{\"dash\":\"$uri\"}}]}"

    @Test fun selectsOnlyTheAdvertisedNewsDashUri() {
        assertEquals(URI(source), parseAbemaNewsNativeUri(metadata()))
        assertNull(parseAbemaNewsNativeUri("{\"channels\":[]}"))
        assertNull(parseAbemaNewsNativeUri("{}"))
        assertNull(parseAbemaNewsNativeUri(metadata().replace("abema-news\",", "other\",")))
        assertNull(parseAbemaNewsNativeUri(metadata().replace("]}", "," + metadata().substringAfter("[") )))
    }

    @Test fun refusesOtherChannelsForeignHostsCredentialsRedirectsAndEncodedPaths() {
        for (uri in listOf(source.replace("https:", "http:"), source.replace("abema-news", "other"),
            source.replace("linear-abematv.akamaized.net", "evil.invalid"), "$source?token=fixture",
            "$source#fixture", source.replace("https://", "https://user@"),
            source.replace("/manifest", "/../manifest"), source.replace("/manifest", "/%2e%2e/manifest"),
            source.replace(".mpd", ".m3u8"))) {
            assertNull(uri, parseAbemaNewsNativeUri(metadata(uri)))
        }
    }

    @Test fun permitsOnlyUnchangedSameNewsRootMediaAndHttpsPort() {
        assertTrue(allowedAbemaNewsMediaUri(URI(source.replace("manifest.mpd", "video/123/init.mp4"))))
        assertTrue(allowedAbemaNewsMediaUri(URI(source.replace(".net/", ".net:443/"))))
        assertFalse(allowedAbemaNewsMediaUri(URI(source.replace(".net/", ".net:444/"))))
        assertFalse(allowedAbemaNewsMediaUri(URI(source.replace("abema-news/", "abema-news-extra/"))))
    }

    @Test fun denialDiagnosticsAreClosedCategoriesAndDoNotBroadenPolicy() {
        val cases = mapOf(
            source to AbemaNewsMediaUriPolicy.ALLOWED,
            source.replace("https:", "http:") to AbemaNewsMediaUriPolicy.AUTHORITY,
            source.replace("https://", "https://user@") to AbemaNewsMediaUriPolicy.USER_INFO,
            "$source#fixture" to AbemaNewsMediaUriPolicy.FRAGMENT,
            "$source?fixture=1" to AbemaNewsMediaUriPolicy.QUERY,
            source.replace("abema-news", "other") to AbemaNewsMediaUriPolicy.CHANNEL_ROOT,
            source.replace("/manifest", "/../manifest") to AbemaNewsMediaUriPolicy.PATH,
            "https://linear-abematv.akamaized.net" to AbemaNewsMediaUriPolicy.CHANNEL_ROOT,
        )
        for ((uri, reason) in cases) {
            assertEquals(reason, abemaNewsMediaUriPolicy(URI(uri)))
            assertEquals(reason == AbemaNewsMediaUriPolicy.ALLOWED, allowedAbemaNewsMediaUri(URI(uri)))
        }
    }
}
