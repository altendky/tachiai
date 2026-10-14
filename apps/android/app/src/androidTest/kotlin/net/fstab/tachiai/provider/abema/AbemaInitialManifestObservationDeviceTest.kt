package net.fstab.tachiai.provider.abema

import androidx.media3.common.util.UnstableApi
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.platform.media.BoundedMediaRequests
import net.fstab.tachiai.platform.media.NativePlaybackBudget
import org.junit.Assert.*
import org.junit.Test

// Only synthetic in-memory HTTPS and XML. No source store, player, CDM or provider request.
@UnstableApi
class AbemaInitialManifestObservationDeviceTest {
    private val expected = AbemaSlotRequest("fixture-channel", "fixture-slot")
    private val selected = URI("https://linear-abematv.akamaized.net/channel/fixture-channel/manifest.mpd?t=synthetic&enc=clear&dt=pc_chrome&ut=1")
    private val kid = "00000000-0000-0000-0000-000000000001"
    private fun mpd(periodAttributes: String = "id='fixture-slot' start='PT0S' duration='PT60S'", extra: String = "") =
        "<MPD xmlns='urn:mpeg:dash:schema:mpd:2011' xmlns:cenc='urn:mpeg:cenc:2013'>" +
            "<Period $periodAttributes><EventStream slotId='fixture-slot'/><AdaptationSet>" +
            "<InbandEventStream/><ContentProtection schemeIdUri='urn:mpeg:dash:mp4protection:2011' " +
            "value='cenc' cenc:default_KID='$kid'/></AdaptationSet>$extra</Period></MPD>"

    private class Connection(url: URL, body: ByteArray, private val afterRead: () -> Unit = {},
        private val failClose: Boolean = false) : HttpsURLConnection(url) {
        var disconnected = false
        var reads = 0
        private val input = object : ByteArrayInputStream(body) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, length).also {
                reads++; afterRead()
            }
        }
        override fun connect() = Unit
        override fun disconnect() { disconnected = true; if (failClose) throw IllegalStateException("synthetic close") }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode() = 200
        override fun getContentLengthLong() = input.available().toLong()
        override fun getInputStream() = input
    }

    @Test fun sameInitialChannelBodyObservesClosedShapeWithoutChangingProtectedSourceOrIssuingExtraIo() {
        val observer = AbemaInitialManifestObservation(expected)
        val connection = Connection(selected.toURL(), mpd().toByteArray())
        var opens = 0
        val requests = BoundedMediaRequests(NativePlaybackBudget(60_000, { true }), { it == selected }, { _, _ -> },
            { true }, openConnection = { assertEquals(selected.toURL(), it); opens++; connection })
        try {
            val source = resolveAbemaChannelNativeSource(selected, requests, expected.channelId, observer)
            assertEquals(selected, source.uri); assertEquals(expected.channelId, source.channelId)
            assertEquals(1, source.kids.size); assertEquals(1, opens); assertTrue(connection.disconnected)
            val snapshot = observer.snapshot
            assertEquals(AbemaInitialManifestReason.OBSERVED, snapshot.reason); assertTrue(snapshot.channelMatches)
            assertEquals(1, snapshot.periods); assertEquals(1, snapshot.finiteExplicitIntervals)
            assertEquals(1, snapshot.eventStreams); assertEquals(1, snapshot.inbandEventStreams)
            assertEquals(1, snapshot.matchingPeriodIds); assertEquals(1, snapshot.matchingSlotIdAttributes)
            for (value in listOf(snapshot.closedSummary(), snapshot.toString(), observer.toString())) {
                for (secret in listOf(expected.channelId, expected.slotId, kid, "synthetic", "https://", "<MPD", "PT60S"))
                    assertFalse(value.contains(secret))
            }
        } finally { requests.close() }
    }

    @Test fun absentUnknownAndMalformedTimingNeverBecomeFiniteIntervalsOrIdentityProof() {
        for (attributes in listOf("", "id='other' start='PT0S' duration='P1Y'",
            "id='other' start='PT-1S' duration='PT60S'", "start='PT0S' duration='PT0S'",
            "start='PT0S' duration='Infinity'", "start='PT0S' duration='PT999999999999999999S'")) {
            val observer = AbemaInitialManifestObservation(expected)
            observer.capture(expected.channelId, mpd(attributes).replace(" slotId='fixture-slot'", " slotId='other'"))
            assertEquals(AbemaInitialManifestReason.OBSERVED, observer.snapshot.reason)
            assertEquals(0, observer.snapshot.finiteExplicitIntervals)
            assertEquals(0, observer.snapshot.matchingPeriodIds); assertEquals(0, observer.snapshot.matchingSlotIdAttributes)
        }
        val overflow = AbemaInitialManifestObservation(expected)
        overflow.capture(expected.channelId, mpd("start='PT9223372036S' duration='PT9223372036S'"))
        assertEquals(1, overflow.snapshot.validFiniteStarts); assertEquals(1, overflow.snapshot.validFiniteDurations)
        assertEquals(0, overflow.snapshot.finiteExplicitIntervals)
    }

    @Test fun literalMatchesDoNotTrimCoerceOrMatchForeignNamespaceAttributes() {
        val observer = AbemaInitialManifestObservation(expected)
        observer.capture(expected.channelId, mpd("id=' fixture-slot ' start='PT0S' duration='PT1S'")
            .replace("slotId='fixture-slot'", "xmlns:x='urn:fixture' x:slotId='fixture-slot'"))
        assertEquals(1, observer.snapshot.finiteExplicitIntervals)
        assertEquals(0, observer.snapshot.matchingPeriodIds); assertEquals(0, observer.snapshot.slotIdAttributes)
    }

    @Test fun channelMismatchAndRepeatCaptureRefuseWithoutRetainingEarlierPositiveShape() {
        val mismatch = AbemaInitialManifestObservation(expected)
        mismatch.capture("other-channel", mpd())
        assertEquals(AbemaInitialManifestReason.CHANNEL_MISMATCH, mismatch.snapshot.reason)
        assertFalse(mismatch.snapshot.channelMatches); assertEquals(0, mismatch.snapshot.periods)
        val repeated = AbemaInitialManifestObservation(expected)
        repeated.capture(expected.channelId, mpd()); val original = repeated.snapshot
        repeated.capture(expected.channelId, mpd())
        assertEquals(AbemaInitialManifestReason.REPEATED_CAPTURE, repeated.snapshot.reason)
        assertEquals(0, repeated.snapshot.matchingPeriodIds)
        assertEquals(1, original.matchingPeriodIds) // Published snapshots are immutable.
    }

    @Test fun malformedXmlDtdEntitiesAndDuplicateAttributesExportNoPartialPositiveCounts() {
        for (body in listOf(mpd().dropLast(6), "<!DOCTYPE MPD SYSTEM 'https://example.invalid/private'>" + mpd(),
            "<!DOCTYPE MPD [<!ENTITY private 'fixture-slot'>]>" + mpd(), mpd("id='fixture-slot' id='other'"),
            "<wrong xmlns='urn:mpeg:dash:schema:mpd:2011'/>",
            mpd(extra = "<Period id='fixture-slot'/>") )) {
            val observer = AbemaInitialManifestObservation(expected)
            observer.capture(expected.channelId, body)
            assertNotEquals(AbemaInitialManifestReason.OBSERVED, observer.snapshot.reason)
            assertEquals(0, observer.snapshot.periods); assertEquals(0, observer.snapshot.matchingPeriodIds)
        }
    }

    @Test fun byteElementDepthAndAttributeLimitsRemainClosedRefusals() {
        val bodies = listOf(
            " ".repeat(256 * 1024 + 1) to AbemaInitialManifestReason.BYTE_LIMIT,
            mpd(extra = "<Event/>".repeat(8192)) to AbemaInitialManifestReason.ELEMENT_LIMIT,
            mpd(extra = "<Extension>".repeat(33) + "</Extension>".repeat(33)) to AbemaInitialManifestReason.DEPTH_LIMIT,
            mpd((0..64).joinToString(" ") { "a$it='x'" }) to AbemaInitialManifestReason.ATTRIBUTE_LIMIT,
            mpd("id='" + "x".repeat(4097) + "'") to AbemaInitialManifestReason.ATTRIBUTE_LIMIT)
        for ((body, reason) in bodies) {
            val observer = AbemaInitialManifestObservation(expected); observer.capture(expected.channelId, body)
            assertEquals(reason, observer.snapshot.reason); assertEquals(0, observer.snapshot.periods)
        }
    }

    @Test fun wrongSourceCancellationAndMalformedUtf8NeverCaptureAnInitialBody() {
        for (mode in listOf("source", "cancel", "utf8")) {
            val observer = AbemaInitialManifestObservation(expected)
            var active = true; var opens = 0
            val connection = Connection(selected.toURL(), if (mode == "utf8") byteArrayOf(0xc3.toByte(), 0x28)
                else mpd().toByteArray(), { if (mode == "cancel") active = false })
            val requests = BoundedMediaRequests(NativePlaybackBudget(60_000, { true }), { it == selected }, { _, _ -> },
                { active }, openConnection = { opens++; connection })
            try {
                assertThrows(IOException::class.java) {
                    resolveAbemaChannelNativeSource(selected, requests, if (mode == "source") "other" else expected.channelId, observer)
                }
                assertEquals(if (mode == "source") 0 else 1, opens)
                assertEquals(AbemaInitialManifestReason.UNOBSERVED, observer.snapshot.reason)
                if (opens > 0) assertTrue(connection.disconnected)
            } finally { requests.close() }
        }
    }

    @Test fun observationNeverReplacesProtectedInitializationOrLaundersCleanupFailure() {
        for (failClose in listOf(false, true)) {
            val observer = AbemaInitialManifestObservation(expected)
            val body = if (failClose) mpd() else "<MPD xmlns='urn:mpeg:dash:schema:mpd:2011'><Period/></MPD>"
            val connection = Connection(selected.toURL(), body.toByteArray(), failClose = failClose)
            val requests = BoundedMediaRequests(NativePlaybackBudget(60_000, { true }), { it == selected }, { _, _ -> },
                { true }, openConnection = { connection })
            try {
                assertThrows(Exception::class.java) { resolveAbemaChannelNativeSource(selected, requests, expected.channelId, observer) }
                assertTrue(connection.disconnected); assertEquals(AbemaInitialManifestReason.OBSERVED, observer.snapshot.reason)
            } finally { try { requests.close() } catch (_: Throwable) { assertTrue(failClose) } }
        }
    }
}
