package net.fstab.tachiai.provider.youtube

import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit public-network check of the production downloader and its strict boundaries. */
class YouTubePlaybackHostSmokeTest {
    @Test fun strictProductionPreparationResolvesAndMediaReturnsBytes() {
        assumeTrue("Real YouTube host smoke requires explicit opt-in",
            System.getenv("TACHIAI_YOUTUBE_HOST_SMOKE") == "true")
        try {
            val (uri, progressive) = resolveYouTubePlayback("M7lc1UVf-VE", { true }) { event ->
                // No source path, query, response content or exception message.
                println("YOUTUBE_PHASE=${event.phase} HTTP=${event.httpStatus ?: 0}" +
                    (event.rejectedHost?.let { " REJECTED_HOST=$it" } ?: ""))
            }
            assertTrue("Production preparation must return an allowed media origin", allowedYouTubeMediaUri(uri))
            println("YOUTUBE_RESOLVED=true PROGRESSIVE=$progressive")
            val agents = linkedMapOf(
                "DEFAULT" to null,
                "DALVIK" to "Dalvik/2.1.0 (Linux; U; Android 16; sdk_gphone64_x86_64 Build/BP2A.250605.031.A2)",
                "FIREFOX" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0",
            )
            var readable = false
            for ((label, agent) in agents) {
                val connection = uri.toURL().openConnection() as HttpsURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.useCaches = false
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 10_000
                    connection.setRequestProperty("Range", "bytes=0-1023")
                    if (agent != null) connection.setRequestProperty("User-Agent", agent)
                    val status = connection.responseCode
                    println("YOUTUBE_MEDIA_AGENT=$label HTTP=$status")
                    if (status in 200..299) {
                        val count = connection.inputStream.use { input -> input.read(ByteArray(1024)) }
                        println("YOUTUBE_MEDIA_AGENT=$label BYTES=${count.coerceAtLeast(0)}")
                        if (count > 0) readable = true
                    }
                } finally { connection.disconnect() }
            }
            assertTrue("Resolved media must return initial bytes", readable)
        } catch (error: Throwable) {
            // An extractor exception's message/cause may contain signed URLs.
            // Keep Gradle/JUnit reports limited to a closed exception class.
            fail("Production YouTube host smoke failed: ${error.javaClass.simpleName}")
        }
    }
}
