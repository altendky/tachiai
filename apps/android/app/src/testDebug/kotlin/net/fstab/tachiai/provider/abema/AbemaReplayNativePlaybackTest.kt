package net.fstab.tachiai.provider.abema

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Test

class AbemaReplayNativePlaybackTest {
    private val source = "https://ds-vod-abematv.akamaized.net/program/394-72_s10_p8529/manifest.mpd?fixture=opaque"

    @Test fun exactSignedSelectedSourceIsNotReconstructed() {
        assertEquals(AbemaReplayUriPolicy.ALLOWED, abemaReplaySelectedSourcePolicy(URI(source)))
        assertEquals(AbemaReplayUriPolicy.ALLOWED, abemaReplaySelectedSourcePolicy(URI(source.substringBefore('?'))))
        assertEquals(AbemaReplayUriPolicy.ALLOWED, abemaReplayCdnUriPolicy(URI(source.substringBefore('?').replace("manifest.mpd", "video/init.mp4"))))
        assertEquals(AbemaNewsMediaUriPolicy.QUERY, abemaNewsCdnUriPolicy(URI("https://linear-abematv.akamaized.net/manifest.mpd?fixture=1")))
    }

    @Test fun unknownOriginsCredentialsFragmentsEncodedPathsAndOtherSourcesRefuse() {
        for ((candidate, reason) in mapOf(
            source.replace("ds-vod-abematv.akamaized.net", "other.akamaized.net") to AbemaReplayUriPolicy.AUTHORITY,
            source.replace("https:", "http:") to AbemaReplayUriPolicy.AUTHORITY,
            source.replace(".net/", ".net:444/") to AbemaReplayUriPolicy.AUTHORITY,
            source.replace("https://", "https://fixture@") to AbemaReplayUriPolicy.USER_INFO,
            "$source#fixture" to AbemaReplayUriPolicy.FRAGMENT,
            source.replace("/manifest", "/../manifest") to AbemaReplayUriPolicy.PATH,
            source.replace("/manifest", "/%2e%2e/manifest") to AbemaReplayUriPolicy.PATH,
            source.replace("394-72_s10_p8529", "394-72_s10_p8529-extra") to AbemaReplayUriPolicy.SOURCE_ROUTE,
            source.replace(".mpd", ".m3u8") to AbemaReplayUriPolicy.SOURCE_ROUTE,
            source.substringBefore('?') + "?" + "x".repeat(1025) to AbemaReplayUriPolicy.QUERY_SIZE,
        )) assertEquals(reason, abemaReplaySelectedSourcePolicy(URI(candidate)))
    }
}
