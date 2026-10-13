package net.fstab.tachiai.provider.abema

import androidx.media3.common.util.UnstableApi
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.security.cert.Certificate
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.platform.media.BoundedMediaRequests
import net.fstab.tachiai.platform.media.DeclaredDashMediaPolicy
import net.fstab.tachiai.platform.media.NativePlaybackBudget
import org.junit.Assert.*
import org.junit.Test

@UnstableApi
class AbemaSlotNativePlaybackTest {
    private val channel = "channel-A"; private val slot = "slot-A"
    private val uri = URI("https://ds-vod-abematv.akamaized.net/observed/$slot/manifest.mpd?t=fixture-token&enc=clear&dt=pc_chrome&ut=1&ccf=0")
    private val kid = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val otherKid = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private fun protection(ids: String = kid.toString(), cipher: String = "cenc", scheme: String = "urn:mpeg:dash:mp4protection:2011") =
        "<ContentProtection schemeIdUri='$scheme' value='$cipher' cenc:default_KID='$ids'/>"
    private fun mpd(content: String = "<AdaptationSet>${protection()}</AdaptationSet>") =
        "<MPD xmlns='urn:mpeg:dash:schema:mpd:2011' xmlns:cenc='urn:mpeg:cenc:2013'><Period>$content</Period></MPD>"
    private class Connection(url: URL, private val bytes: ByteArray, private val status: Int = 200,
        val input: InputStream = ByteArrayInputStream(bytes)) : HttpsURLConnection(url) {
        var disconnected = false; var bodyRead = false; var disconnectFailure = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true; if (disconnectFailure) throw IOException("PRIVATE_CLEANUP_DETAIL") }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode() = status
        override fun getContentLengthLong() = -1L
        override fun getInputStream(): InputStream { bodyRead = true; return input }
    }
    private fun requests(connection: Connection, canRequest: () -> Boolean = { true }, dispatched: (() -> Unit)? = null) =
        BoundedMediaRequests(NativePlaybackBudget(120_000, { true }), { allowsAbemaSlotSource(it, channel, slot) }, { _, _ -> },
            canRequest, { dispatched?.invoke(); connection })

    @Test fun oneExactProtectedMpdReturnsOnlyStandardKidsAndCapturedSlotOwnerWithClosedResources() {
        val content = "<AdaptationSet>${protection()}<Representation>${protection()}</Representation></AdaptationSet>" +
            "<AdaptationSet>${protection(otherKid.toString())}</AdaptationSet>"
        val connection = Connection(uri.toURL(), mpd(content).toByteArray()); var calls = 0
        val requests = requests(connection, dispatched = { calls++ })
        try {
            val source = resolveAbemaSlotNativeSource(uri, requests, channel, slot)
            assertEquals(uri, source.uri); assertEquals(channel, source.channelId); assertEquals(slot, source.slotId)
            assertEquals(listOf(kid, otherKid), source.kids); assertEquals(1, calls); assertTrue(connection.disconnected)
            assertEquals("AbemaSlotNativeSource(redacted)", source.toString())
            assertFalse(connection.instanceFollowRedirects); assertFalse(connection.useCaches)
            for (name in listOf("Authorization", "Cookie", "Client-ID")) assertNull(connection.getRequestProperty(name))
            assertThrows(UnsupportedOperationException::class.java) { (source.kids as MutableList<UUID>).add(otherKid) }
        } finally { requests.close() }
    }
    @Test fun initializationObservationReportsTheSameAcceptedOrRefusedParseWithOneGetAndClosedResources() {
        for ((body, reason) in listOf(mpd() to AbemaInitializationReason.ACCEPTED,
            mpd("<AdaptationSet/>") to AbemaInitializationReason.NO_DEFAULT_ID,
            mpd("<AdaptationSet>${protection(cipher = "cbcs")}</AdaptationSet>") to AbemaInitializationReason.SCHEME_OR_CIPHER)) {
            var inputClosed = false; var calls = 0
            val input = object : ByteArrayInputStream(body.toByteArray()) {
                override fun close() { inputClosed = true; super.close() }
            }
            val connection = Connection(uri.toURL(), byteArrayOf(), input = input)
            val requests = requests(connection, dispatched = { calls++ })
            val observed = mutableListOf<AbemaSlotSourceDiagnostic>()
            val observer: (AbemaSlotSourceDiagnostic) -> Unit = {
                assertTrue(inputClosed); assertTrue(connection.disconnected); observed.add(it)
            }
            try {
                if (reason == AbemaInitializationReason.ACCEPTED) {
                    val source = resolveAbemaSlotNativeSource(uri, requests, channel, slot, observer)
                    assertEquals(listOf(kid), source.kids); assertEquals(uri, source.uri)
                } else {
                    val error = assertThrows(IOException::class.java) {
                        resolveAbemaSlotNativeSource(uri, requests, channel, slot, observer)
                    }
                    assertEquals("Slot source unavailable", error.message); assertNull(error.cause)
                }
                assertEquals(1, calls); assertTrue(inputClosed); assertTrue(connection.disconnected)
                val diagnostic = observed.single()
                assertEquals(if (reason == AbemaInitializationReason.ACCEPTED) AbemaSlotSourcePhase.READY else
                    AbemaSlotSourcePhase.INITIALIZATION, diagnostic.phase)
                assertEquals(body.toByteArray().size, diagnostic.bytes)
                val initialization = checkNotNull(diagnostic.initialization)
                assertEquals(reason, initialization.reason)
                assertEquals(1, initialization.periods); assertEquals(1, initialization.adaptationSets)
                assertEquals(if (reason == AbemaInitializationReason.NO_DEFAULT_ID) 0 else 1, initialization.defaultIdDeclarations)
                for (privateValue in listOf(kid.toString(), body, uri.toString(), "fixture-token"))
                    assertFalse(diagnostic.closedSummary().contains(privateValue))
                for (header in listOf("Authorization", "Cookie", "Client-ID")) assertNull(connection.getRequestProperty(header))
            } finally { requests.close() }
        }
    }
    @Test fun initializationObserverExceptionsAndErrorsCannotChangeAcceptanceRefusalOrRequestCount() {
        for (accepted in listOf(true, false)) for (fatal in listOf(true, false)) {
            val body = if (accepted) mpd() else mpd("<AdaptationSet/>")
            val connection = Connection(uri.toURL(), body.toByteArray()); var calls = 0; var observations = 0
            val requests = requests(connection, dispatched = { calls++ })
            val observer: (AbemaSlotSourceDiagnostic) -> Unit = {
                observations++
                if (fatal) throw AssertionError("PRIVATE_OBSERVER_DETAIL") else throw IllegalStateException("PRIVATE_OBSERVER_DETAIL")
            }
            try {
                if (accepted) assertEquals(listOf(kid), resolveAbemaSlotNativeSource(uri, requests, channel, slot, observer).kids)
                else {
                    val error = assertThrows(IOException::class.java) { resolveAbemaSlotNativeSource(uri, requests, channel, slot, observer) }
                    assertEquals("Slot source unavailable", error.message); assertNull(error.cause)
                }
                assertEquals(1, calls); assertEquals(1, observations); assertTrue(connection.disconnected)
            } finally { requests.close() }
        }
    }
    @Test fun acceptedDiagnosticDoesNotOverrideActualManifestCleanupFailure() {
        val connection = Connection(uri.toURL(), mpd().toByteArray()).apply { disconnectFailure = true }
        var calls = 0
        val requests = requests(connection, dispatched = { calls++ })
        val observed = mutableListOf<AbemaSlotSourceDiagnostic>()
        try {
            val error = assertThrows(IOException::class.java) { resolveAbemaSlotNativeSource(uri, requests, channel, slot, observed::add) }
            assertEquals(AbemaSlotSourcePhase.CLEANUP, observed.single().phase)
            assertEquals(AbemaInitializationReason.ACCEPTED, observed.single().initialization?.reason)
            assertEquals("Slot manifest cleanup unconfirmed", error.message); assertNull(error.cause)
            assertEquals(1, calls); assertTrue(connection.disconnected)
        } finally { connection.disconnectFailure = false; requests.close() }
    }
    @Test fun sourceObservationDistinguishesOpenReadByteLimitAndUtf8WithoutParsingOrAnotherGet() {
        for (phase in listOf(AbemaSlotSourcePhase.OPEN, AbemaSlotSourcePhase.READ, AbemaSlotSourcePhase.BYTE_LIMIT,
            AbemaSlotSourcePhase.UTF8)) {
            val bytes = when (phase) {
                AbemaSlotSourcePhase.BYTE_LIMIT -> ByteArray(256 * 1024 + 1) { 32 }
                AbemaSlotSourcePhase.UTF8 -> byteArrayOf(0xff.toByte())
                else -> mpd().toByteArray()
            }
            var inputClosed = false
            var reads = 0
            val input = object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (phase == AbemaSlotSourcePhase.READ && reads++ > 0) throw IOException("PRIVATE_READ_DETAIL")
                    return super.read(buffer, offset, if (phase == AbemaSlotSourcePhase.READ) minOf(length, 8) else length)
                }
                override fun close() { inputClosed = true; super.close() }
            }
            val connection = Connection(uri.toURL(), bytes, if (phase == AbemaSlotSourcePhase.OPEN) 403 else 200, input)
            var calls = 0
            val requests = requests(connection, dispatched = { calls++ })
            val observations = mutableListOf<AbemaSlotSourceDiagnostic>()
            try {
                val error = assertThrows(IOException::class.java) {
                    resolveAbemaSlotNativeSource(uri, requests, channel, slot) {
                        assertTrue(connection.disconnected); observations.add(it)
                    }
                }
                assertEquals("Slot source unavailable", error.message); assertNull(error.cause)
                val observed = observations.single(); assertEquals(phase, observed.phase); assertNull(observed.initialization)
                assertEquals(when (phase) { AbemaSlotSourcePhase.OPEN -> 0; AbemaSlotSourcePhase.READ -> 8
                    AbemaSlotSourcePhase.BYTE_LIMIT -> 256 * 1024 + 1; else -> 1 }, observed.bytes)
                assertEquals(1, calls); assertTrue(connection.disconnected)
                if (phase == AbemaSlotSourcePhase.OPEN) assertFalse(connection.bodyRead) else assertTrue(inputClosed)
            } finally { requests.close() }
        }
    }
    @Test fun clearWidevineWrongCipherNamespaceAndInvalidKidManifestsNeverBecomeSources() {
        val excessive = (1..17).map { UUID(0, it.toLong()).toString() }.joinToString(" ")
        for (body in listOf(mpd("<AdaptationSet/>"), mpd("<AdaptationSet>${protection(cipher = "cbcs")}</AdaptationSet>"),
            mpd("<AdaptationSet>${protection(scheme = "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")}</AdaptationSet>"),
            mpd("<AdaptationSet>${protection("00000000-0000-0000-0000-000000000000")}</AdaptationSet>"),
            mpd("<AdaptationSet>${protection("11111111222233334444555555555555")}</AdaptationSet>"),
            mpd("<AdaptationSet>${protection(excessive)}</AdaptationSet>"),
            mpd("<AdaptationSet>${protection()}${protection(otherKid.toString())}</AdaptationSet>"),
            mpd().replace("urn:mpeg:cenc:2013", "urn:unknown:cenc"), mpd().dropLast(1),
            "<!DOCTYPE MPD [<!ENTITY external SYSTEM 'https://synthetic.invalid/private'>]>" + mpd())) {
            val connection = Connection(uri.toURL(), body.toByteArray()); val requests = requests(connection)
            try {
                val error = assertThrows(IOException::class.java) { resolveAbemaSlotNativeSource(uri, requests, channel, slot) }
                assertEquals("Slot source unavailable", error.message); assertNull(error.cause); assertTrue(connection.disconnected)
            } finally { requests.close() }
        }
    }
    @Test fun byteAndUtf8LimitsRefuseBeforePublishingEvenWhenTheInitializationPrefixLooksValid() {
        for (bytes in listOf(mpd().toByteArray() + byteArrayOf(0xff.toByte()),
            mpd().toByteArray() + ByteArray(256 * 1024) { 32 })) {
            val connection = Connection(uri.toURL(), bytes); val requests = requests(connection)
            try {
                assertThrows(IOException::class.java) { resolveAbemaSlotNativeSource(uri, requests, channel, slot) }
                assertTrue(connection.disconnected)
            } finally { requests.close() }
        }
    }
    @Test fun redirectAndProviderRefusalCannotOpenABodyRetryOrSwitchSources() {
        for (status in listOf(301, 302, 307, 308, 401, 403)) {
            val connection = Connection(uri.toURL(), mpd().toByteArray(), status); var calls = 0
            val requests = requests(connection, dispatched = { calls++ })
            try {
                assertThrows(IOException::class.java) { resolveAbemaSlotNativeSource(uri, requests, channel, slot) }
                assertEquals(1, calls); assertFalse(connection.bodyRead); assertFalse(connection.instanceFollowRedirects)
                assertTrue(connection.disconnected)
            } finally { requests.close() }
        }
    }
    @Test fun mismatchedSlotOwnerAndSourceRefuseBeforeAnyConnectionAndLateReadRevocationCannotPublish() {
        val connection = Connection(uri.toURL(), mpd().toByteArray()); var calls = 0
        val requests = requests(connection, dispatched = { calls++ })
        try {
            assertThrows(IOException::class.java) { resolveAbemaSlotNativeSource(uri, requests, channel, "other-slot") }
            assertThrows(IOException::class.java) { resolveAbemaSlotNativeSource(uri, requests, "bad/channel", slot) }
            assertEquals(0, calls); assertFalse(connection.bodyRead)
        } finally { requests.close() }
        var admitted = true
        val late = object : ByteArrayInputStream(mpd().toByteArray()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                admitted = false; return super.read(buffer, offset, length)
            }
        }
        val lateConnection = Connection(uri.toURL(), byteArrayOf(), input = late)
        val lateRequests = requests(lateConnection, { admitted })
        try {
            assertThrows(IOException::class.java) { resolveAbemaSlotNativeSource(uri, lateRequests, channel, slot) }
            assertTrue(lateConnection.disconnected)
        } finally { lateRequests.close() }
    }
    @Test fun uncertainManifestCleanupCannotPublishAndDoesNotExposePrivateFailureText() {
        val connection = Connection(uri.toURL(), mpd().toByteArray()).apply { disconnectFailure = true }
        val requests = requests(connection)
        try {
            val error = assertThrows(IOException::class.java) { resolveAbemaSlotNativeSource(uri, requests, channel, slot) }
            assertEquals("Slot manifest cleanup unconfirmed", error.message); assertNull(error.cause)
            assertTrue(connection.disconnected)
        } finally { connection.disconnectFailure = false; requests.close() }
    }
    @Test fun standardKidSnapshotCannotBeMutatedOrConstructedWithoutProtectedInitialization() {
        val mutable = mutableListOf(kid); val source = AbemaSlotNativeSource(uri, mutable, channel, slot)
        mutable.clear(); assertEquals(listOf(kid), source.kids)
        for (kids in listOf(emptyList(), listOf(UUID(0, 0)), listOf(kid, kid), (1..17).map { UUID(0, it.toLong()) }))
            assertThrows(IllegalArgumentException::class.java) { AbemaSlotNativeSource(uri, kids, channel, slot) }
    }
    @Test fun declaredMediaRemainsExactAndAuthoritativeOutsideAnyGuessedSlotOrDirectoryRoot() {
        val initialization = URI("https://linear-abematv.akamaized.net/authoritative/video/init.mp4")
        val media = URI("https://ds-vod-abematv.akamaized.net/another/provider/path/42.m4s?opaque=fixture")
        val policy = DeclaredDashMediaPolicy(uri, ::allowsAbemaSlotDeclaredMedia)
        try {
            assertTrue(policy.allows(uri)); assertFalse(policy.allows(initialization))
            policy.publish(listOf(initialization, media)); assertTrue(policy.allows(initialization)); assertTrue(policy.allows(media))
            assertFalse(policy.allows(URI(media.toString().replace("42.m4s", "43.m4s"))))
            for (bad in listOf("https://unapproved.invalid/init.mp4", "https://nested.ds-vod-abematv.akamaized.net/init.mp4",
                "http://ds-vod-abematv.akamaized.net/init.mp4", "https://@ds-vod-abematv.akamaized.net/init.mp4",
                "https://ds-vod-abematv.akamaized.net:443/init.mp4", "https://ds-vod-abematv.akamaized.net/init.mp4#",
                "https://ds-vod-abematv.akamaized.net/%2e/init.mp4", "https://ds-vod-abematv.akamaized.net/../init.mp4")) {
                assertThrows(IOException::class.java) { policy.publish(listOf(initialization, URI(bad))) }
                assertTrue(policy.allows(media))
            }
        } finally { policy.close() }
        assertFalse(policy.allows(uri)); assertFalse(policy.allows(media))
    }
}
