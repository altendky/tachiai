package net.fstab.tachiai.provider.abema

import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.feature.diagnostic.NativeAccessCase
import net.fstab.tachiai.feature.diagnostic.initialAccessEndpoint
import net.fstab.tachiai.platform.net.AccessProbeEndpoint
import net.fstab.tachiai.platform.net.AccessProbeHttp
import net.fstab.tachiai.platform.net.AccessProbeOutcome
import org.junit.Assert.*
import org.junit.Test

class AbemaNativeManifestInspectionTest {
    private val master = URI("https://linear-abematv.akamaized.net/channel/abema-news/playlist.m3u8")
    private val masterBody = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100000\n360/playlist.m3u8\n"
    private val mediaBody = "#EXTM3U\n#EXTINF:6.0,\nsegment.ts\n"
    private class Connection(url: URL, private val body: String, private val status: Int = 200) : HttpsURLConnection(url) {
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
    }

    @Test fun `DASH inspection distinguishes Widevine hints while retaining the original result`() {
        val body = "<MPD><ContentProtection schemeIdUri='urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed'/></MPD>"
        assertEquals(AccessProbeOutcome.DASH_PROTECTION_MARKERS_PRESENT, abemaDashClassification(200, body))
        assertEquals(AccessProbeOutcome.DASH_WIDEVINE_MARKERS_PRESENT, abemaDashFormatClassification(200, body))
        assertEquals(AccessProbeOutcome.HTTP_REJECTED, abemaDashFormatClassification(403, body))
        assertEquals(AccessProbeOutcome.INVALID_RESPONSE, abemaDashFormatClassification(200, "<html/>"))
        assertEquals(AccessProbeOutcome.DASH_MARKERS_PRESENT, abemaDashFormatClassification(200, "<MPD></MPD>"))
        assertEquals(AccessProbeOutcome.DASH_PROTECTION_MARKERS_PRESENT,
            abemaDashFormatClassification(200, "<MPD><ContentProtection schemeIdUri='other'/></MPD>"))
    }

    @Test fun `HLS requires a header and distinguishes master media and invalid content`() {
        assertEquals(AccessProbeOutcome.HLS_MASTER_MARKERS_PRESENT, abemaHlsClassification(200, masterBody))
        assertEquals(AccessProbeOutcome.HLS_MEDIA_MARKERS_PRESENT, abemaHlsClassification(200, mediaBody))
        assertEquals(AccessProbeOutcome.HLS_MASTER_MARKERS_PRESENT, abemaHlsClassification(200, "\uFEFF\r\n" + masterBody))
        listOf("<html/>", "{}", "#EXTINF:6\nfile.ts", "#EXTM3U\n", "#EXTM3Uevil\n#EXTINF:6\nx.ts").forEach {
            assertEquals(AccessProbeOutcome.INVALID_RESPONSE, abemaHlsClassification(200, it))
        }
        listOf(302, 401, 403).forEach { assertEquals(AccessProbeOutcome.HTTP_REJECTED, abemaHlsClassification(it, mediaBody)) }
    }

    @Test fun `DASH format hints distinguish common encryption known systems and multiple schemes`() {
        fun descriptor(scheme: String) = "<dash:ContentProtection cenc:default_KID='fixture' schemeIdUri=\"$scheme\"/>"
        mapOf(
            "urn:mpeg:dash:mp4protection:2011" to AccessProbeOutcome.DASH_COMMON_ENCRYPTION_MARKERS_PRESENT,
            "urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95" to AccessProbeOutcome.DASH_PLAYREADY_MARKERS_PRESENT,
            "urn:uuid:e2719d58-a985-b3c9-781a-b030af78d30e" to AccessProbeOutcome.DASH_CLEARKEY_MARKERS_PRESENT,
            "urn:uuid:5e629af5-38da-4063-8977-97ffbd9902d4" to AccessProbeOutcome.DASH_MARLIN_MARKERS_PRESENT,
            "urn:uuid:unknown" to AccessProbeOutcome.DASH_PROTECTION_MARKERS_PRESENT,
        ).forEach { (scheme, expected) ->
            assertEquals(expected, abemaDashFormatClassification(200, "<dash:MPD>${descriptor(scheme)}</dash:MPD>"))
        }
        val widevine = descriptor("urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
        val playready = descriptor("urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95")
        assertEquals(AccessProbeOutcome.DASH_MULTIPLE_DRM_MARKERS_PRESENT, abemaDashFormatClassification(200, "<MPD>$widevine$playready</MPD>"))
        assertEquals(AccessProbeOutcome.DASH_WIDEVINE_MARKERS_PRESENT, abemaDashFormatClassification(200, "<MPD>$widevine$widevine</MPD>"))
    }

    @Test fun `all protection markers remain hints and no key reference is followed`() {
        listOf("#EXT-X-KEY:METHOD=AES-128,URI=\"https://example.test/key\"",
            "#EXT-X-KEY:METHOD=SAMPLE-AES", "#EXT-X-KEY:",
            "#EXT-X-SESSION-KEY:METHOD=NONE", "#EXT-X-KEY:METHOD=NONE,URI=\"key\"").forEach {
            assertEquals(AccessProbeOutcome.HLS_PROTECTION_MARKERS_PRESENT, abemaHlsClassification(200, mediaBody + "  $it\n"))
            assertNull(abemaFirstHlsVariant(masterBody + it, master))
        }
        assertEquals(AccessProbeOutcome.HLS_MEDIA_MARKERS_PRESENT,
            abemaHlsClassification(200, mediaBody + "#EXT-X-KEY:METHOD=NONE\n"))
        assertEquals(AccessProbeOutcome.HLS_ABEMA_KEY_MARKERS_PRESENT,
            abemaHlsClassification(200, mediaBody + "#EXT-X-KEY:METHOD=AES-128,URI=\"abematv-license://fixture\"\n"))
    }

    @Test fun `variant selection uses exactly the first advertised allowlisted playlist`() {
        assertEquals(master.resolve("360/playlist.m3u8"), abemaFirstHlsVariant(masterBody, master))
        assertNull(abemaFirstHlsVariant(mediaBody, master))
        listOf("http://linear-abematv.akamaized.net/x.m3u8", "https://other.example/x.m3u8",
            "https://user@linear-abematv.akamaized.net/x.m3u8", "x.m3u8?token=fixture", "x.m3u8#fragment",
            "segment.ts", "# comment", "not a URI").forEach { candidate ->
            val body = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n$candidate\n#EXT-X-STREAM-INF:BANDWIDTH=2\n360/playlist.m3u8\n"
            assertNull(abemaFirstHlsVariant(body, master))
        }
    }

    @Test fun `master-only comparison never fetches the variant`() {
        var calls = 0
        val connection = Connection(master.toURL(), masterBody)
        val result = AccessProbeHttp(open = { assertEquals(master.toURL(), it); calls++; connection }).use {
            probeAbemaAdvertisedHlsManifest(it, master, false)
        }
        assertEquals(1, calls)
        assertEquals(AccessProbeOutcome.HLS_MASTER_MARKERS_PRESENT, result.outcome)
        assertTrue(connection.disconnected)
    }

    @Test fun `variant comparison fetches just one child and no advertised media or key`() {
        val visited = mutableListOf<URL>()
        val connections = mutableListOf<Connection>()
        val protectedMedia = mediaBody + "#EXT-X-KEY:METHOD=AES-128,URI=\"abematv-license://fixture\"\n"
        val result = AccessProbeHttp(open = { url ->
            visited.add(url)
            Connection(url, if (visited.size == 1) masterBody else protectedMedia).also(connections::add)
        }).use { probeAbemaAdvertisedHlsManifest(it, master, true) }
        assertEquals(listOf(master.toURL(), master.resolve("360/playlist.m3u8").toURL()), visited)
        assertEquals(AccessProbeOutcome.HLS_ABEMA_KEY_MARKERS_PRESENT, result.outcome)
        connections.forEach {
            assertEquals("application/vnd.apple.mpegurl", it.getRequestProperty("Accept"))
            assertNull(it.getRequestProperty("Authorization"))
            assertNull(it.getRequestProperty("Client-ID"))
            assertNull(it.getRequestProperty("Cookie"))
            assertFalse(it.instanceFollowRedirects)
            assertTrue(it.disconnected)
        }
    }

    @Test fun `denied protected malformed and unallowlisted masters stop before a second request`() {
        listOf(403 to masterBody, 302 to masterBody, 200 to "<html/>",
            200 to (masterBody + "#EXT-X-SESSION-KEY:METHOD=AES-128\n"),
            200 to masterBody.replace("360/playlist.m3u8", "https://other.example/x.m3u8")).forEach { (status, body) ->
            var calls = 0
            AccessProbeHttp(open = { calls++; Connection(it, body, status) }).use {
                probeAbemaAdvertisedHlsManifest(it, master, true)
            }
            assertEquals(1, calls)
        }
    }

    @Test fun `closing after master classification prevents variant dispatch`() {
        var requestsAllowed = true
        var calls = 0
        val http = AccessProbeHttp(canRequest = { requestsAllowed }, open = {
            calls++
            object : HttpsURLConnection(it) {
                override fun connect() = Unit
                override fun disconnect() { requestsAllowed = false }
                override fun usingProxy() = false
                override fun getCipherSuite() = "fixture"
                override fun getLocalCertificates(): Array<Certificate>? = null
                override fun getServerCertificates(): Array<Certificate> = emptyArray()
                override fun getResponseCode() = 200
                override fun getInputStream() = ByteArrayInputStream(masterBody.toByteArray())
            }
        })
        assertThrows(java.io.InterruptedIOException::class.java) { http.use { probeAbemaAdvertisedHlsManifest(it, master, true) } }
        assertEquals(1, calls)
    }

    @Test fun `new ABEMA failure cases never report a Twitch endpoint`() {
        listOf(NativeAccessCase.ABEMA_ANONYMOUS, NativeAccessCase.ABEMA_DASH_FORMAT,
            NativeAccessCase.ABEMA_ANONYMOUS_HLS, NativeAccessCase.ABEMA_HLS_VARIANT).forEach {
            assertEquals(AccessProbeEndpoint.ABEMA_CHANNELS, initialAccessEndpoint(it))
        }
        assertEquals(AccessProbeEndpoint.TWITCH_ACCESS, initialAccessEndpoint(NativeAccessCase.TWITCH_LIVE_ANONYMOUS))
    }
}
