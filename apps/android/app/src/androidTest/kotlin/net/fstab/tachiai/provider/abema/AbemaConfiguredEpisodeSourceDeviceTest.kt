package net.fstab.tachiai.provider.abema

import androidx.media3.common.util.UnstableApi
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.net.URL
import java.security.cert.Certificate
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.platform.media.BoundedMediaRequests
import net.fstab.tachiai.platform.media.NativePlaybackBudget
import org.junit.Assert.*
import org.junit.Test

// Actual native source consumer with entirely in-memory sockets/MPDs. No
// WebView, provider request, CDM/license action, saved state or test reset.
@UnstableApi
class AbemaConfiguredEpisodeSourceDeviceTest {
    private val kid = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val mpd = "<MPD xmlns='urn:mpeg:dash:schema:mpd:2011' xmlns:cenc='urn:mpeg:cenc:2013'>" +
        "<Period><AdaptationSet><ContentProtection schemeIdUri='urn:mpeg:dash:mp4protection:2011' " +
        "value='cenc' cenc:default_KID='$kid'/></AdaptationSet></Period></MPD>"
    private class Connection(url: URL, private val body: ByteArray, private val afterRead: () -> Unit = {}) : HttpsURLConnection(url) {
        var closed = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode() = 200
        override fun getContentLengthLong() = body.size.toLong()
        override fun getInputStream() = object : ByteArrayInputStream(body) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, length).also { afterRead() }
        }
    }
    private fun uri(id: String) = URI("https://ds-vod-abematv.akamaized.net/program/$id/manifest.mpd?t=synthetic")

    private fun channelUri(id: String) = URI("https://linear-abematv.akamaized.net/channel/$id/manifest.mpd?t=synthetic&enc=clear&dt=pc_chrome&ut=1")

    private fun slotUri(id: String) = URI("https://ds-vod-abematv.akamaized.net/occurrence/$id/manifest.mpd?t=synthetic&enc=clear&dt=pc_chrome&ut=1&ccf=0")

    @Test fun exactSlotNativeConsumerRetainsBothIdsAndProtectedInitializationWithConfirmedCleanup() {
        for (id in listOf("fixture-one", "fixture-two")) {
            val selected = slotUri(id)
            val connection = Connection(selected.toURL(), mpd.toByteArray())
            val requests = BoundedMediaRequests(NativePlaybackBudget(60_000, { true }), { it == selected }, { _, _ -> }, { true },
                openConnection = { assertEquals(selected.toURL(), it); connection })
            try {
                val resolved = resolveAbemaSlotNativeSource(selected, requests, "fixture-channel", id)
                assertEquals("fixture-channel", resolved.channelId); assertEquals(id, resolved.slotId)
                assertEquals(selected, resolved.uri); assertEquals(listOf(kid), resolved.kids)
                assertTrue(connection.closed); assertFalse(resolved.toString().contains("synthetic"))
            } finally { requests.close() }
        }
    }

    @Test fun exactSlotNativeConsumerRejectsWrongSlotBeforeIoAndCancelledOrClearInitialization() {
        val id = "fixture-one"; val selected = slotUri(id)
        var opened = 0; var active = true
        val requests = BoundedMediaRequests(NativePlaybackBudget(60_000, { true }), { true }, { _, _ -> }, { active },
            openConnection = { opened++; Connection(it, mpd.toByteArray()) { active = false } })
        try {
            assertThrows(IOException::class.java) { resolveAbemaSlotNativeSource(slotUri("other"), requests, "fixture-channel", id) }
            assertEquals(0, opened)
            assertThrows(IOException::class.java) { resolveAbemaSlotNativeSource(selected, requests, "fixture-channel", id) }
            assertEquals(1, opened)
        } finally { requests.close() }
        val body = "<MPD xmlns='urn:mpeg:dash:schema:mpd:2011'><Period/></MPD>".toByteArray()
        val connection = Connection(selected.toURL(), body)
        val invalid = BoundedMediaRequests(NativePlaybackBudget(60_000, { true }), { true }, { _, _ -> }, { true }, openConnection = { connection })
        try {
            assertThrows(IOException::class.java) { resolveAbemaSlotNativeSource(selected, invalid, "fixture-channel", id) }
            assertTrue(connection.closed)
        } finally { invalid.close() }
    }

    @Test fun exactChannelNativeConsumerRetainsBothIdsAndProtectedInitializationWithConfirmedTransportCleanup() {
        for (id in listOf("fixture-one", "fixture-two")) {
            val selected = channelUri(id)
            val connection = Connection(selected.toURL(), mpd.toByteArray())
            val requests = BoundedMediaRequests(NativePlaybackBudget(60_000, { true }), { it == selected }, { _, _ -> }, { true },
                openConnection = { assertEquals(selected.toURL(), it); connection })
            try {
                val resolved = resolveAbemaChannelNativeSource(selected, requests, id)
                assertEquals(id, resolved.channelId); assertEquals(selected, resolved.uri); assertEquals(listOf(kid), resolved.kids)
                assertTrue(connection.closed)
                val transitions = AbemaManifestTransitionPolicy(resolved.kids)
                assertTrue(transitions.accepts(mpd))
                assertTrue(transitions.accepts("<MPD xmlns='urn:mpeg:dash:schema:mpd:2011'><Period/></MPD>"))
                assertTrue(transitions.accepts(mpd))
                assertFalse(transitions.accepts(mpd.replace(kid.toString(), "00000000-0000-0000-0000-000000000002")))
            } finally { requests.close() }
        }
    }

    @Test fun exactChannelNativeConsumerRejectsOtherRootBeforeIoAndCancelledInvalidOrClearInitialization() {
        val id = "fixture-one"; val selected = channelUri(id)
        var opened = 0; var active = true
        val requests = BoundedMediaRequests(NativePlaybackBudget(60_000, { true }), { true }, { _, _ -> }, { active },
            openConnection = { opened++; Connection(it, mpd.toByteArray()) { active = false } })
        try {
            assertThrows(IOException::class.java) { resolveAbemaChannelNativeSource(channelUri("other"), requests, id) }
            assertEquals(0, opened)
            assertThrows(IOException::class.java) { resolveAbemaChannelNativeSource(selected, requests, id) }
            assertEquals(1, opened)
        } finally { requests.close() }
        for (body in listOf(byteArrayOf(0xc3.toByte(), 0x28), "<MPD xmlns='urn:mpeg:dash:schema:mpd:2011'><Period/></MPD>".toByteArray())) {
            val connection = Connection(selected.toURL(), body)
            val owned = BoundedMediaRequests(NativePlaybackBudget(60_000, { true }), { true }, { _, _ -> }, { true }, openConnection = { connection })
            try {
                assertThrows(IOException::class.java) { resolveAbemaChannelNativeSource(selected, owned, id) }
                assertTrue(connection.closed)
            } finally { owned.close() }
        }
    }

    @Test fun actualManifestResolverRetainsEachExpectedEpisodeAndExactUriAndClosesItsTransport() {
        for (id in listOf("394-72_s10_p8510", "fixture-series_p2")) {
            val source = uri(id); val connection = Connection(source.toURL(), mpd.toByteArray())
            val requests = BoundedMediaRequests(NativePlaybackBudget(60_000, { true }),
                { it == source }, { _, _ -> }, { true }, openConnection = { requested ->
                    assertEquals(source.toURL(), requested); connection
                })
            try {
                val resolved = resolveAbemaReplayNativeSource(source, requests, AbemaReplayCdnScope.APPROVED_PROTOTYPE, id)
                assertEquals(id, resolved.episodeId); assertEquals(source, resolved.uri); assertEquals(listOf(kid), resolved.kids)
                assertTrue(connection.closed)
                assertFalse(resolved.toString().contains("synthetic"))
            } finally { requests.close() }
        }
    }

    @Test fun mismatchCancellationAndInvalidUtf8NeverBecomeAnAcceptedNativeSource() {
        val id = "394-72_s10_p8510"
        val source = uri(id)
        var opened = 0; var active = true
        val requests = BoundedMediaRequests(NativePlaybackBudget(60_000, { true }), { true }, { _, _ -> }, { active },
            openConnection = { opened++; Connection(it, mpd.toByteArray()) { active = false } })
        try {
            assertThrows(IOException::class.java) { resolveAbemaReplayNativeSource(uri("other"), requests,
                AbemaReplayCdnScope.APPROVED_PROTOTYPE, id) }
            assertEquals(0, opened)
            assertThrows(IOException::class.java) { resolveAbemaReplayNativeSource(source, requests,
                AbemaReplayCdnScope.APPROVED_PROTOTYPE, id) }
            assertEquals(1, opened)
        } finally { requests.close() }
        val bad = Connection(source.toURL(), byteArrayOf(0xc3.toByte(), 0x28))
        val invalid = BoundedMediaRequests(NativePlaybackBudget(60_000, { true }), { true }, { _, _ -> }, { true }, openConnection = { bad })
        try {
            assertThrows(IOException::class.java) { resolveAbemaReplayNativeSource(source, invalid,
                AbemaReplayCdnScope.APPROVED_PROTOTYPE, id) }
            assertTrue(bad.closed)
        } finally { invalid.close() }
    }
}
