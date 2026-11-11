package net.fstab.tachiai.provider.youtube

import java.net.URI
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMediaBoundaryTest {
    @Test fun onlyHttpsGoogleVideoDestinationsAreAccepted() {
        assertTrue(allowedYouTubeMediaUri(URI("https://rr1---sn-example.googlevideo.com/videoplayback?expire=123")))
        assertTrue(allowedYouTubeMediaUri(URI("https://manifest.googlevideo.com/api/manifest/hls_variant/123")))
        for (url in listOf(
            "http://rr1.googlevideo.com/videoplayback", "https://evilgooglevideo.com/videoplayback",
            "https://googlevideo.com.evil.example/videoplayback", "https://user@rr1.googlevideo.com/videoplayback",
            "https://rr1.googlevideo.com:444/videoplayback", "https://www.youtube.com/watch?v=M7lc1UVf-VE",
            "https://rr1.googlevideo.com/videoplayback#fragment",
        )) assertFalse(url, allowedYouTubeMediaUri(URI(url)))
    }
}
