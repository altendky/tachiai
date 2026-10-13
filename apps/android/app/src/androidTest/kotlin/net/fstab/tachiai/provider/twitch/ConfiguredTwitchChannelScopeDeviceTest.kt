package net.fstab.tachiai.provider.twitch

import java.io.IOException
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URL
import java.security.cert.Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import android.os.SystemClock
import net.fstab.tachiai.provider.catalog.CatalogResource
import net.fstab.tachiai.provider.twitch.catalog.*
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.CatalogIntent
import org.junit.Assert.*
import org.junit.Test

// Test-only admission of one saved broadcaster's normal production sequence.
// Private aliases and signed source values remain RAM-only and never enter errors.
internal class ConfiguredTwitchChannelScope(private val resource: CatalogResource) {
    enum class Stage { SELECTED, IDENTITY, LIVE, ACCESS, SOURCE, CONFIRMED, REFUSED }
    enum class Refusal { NONE, REQUEST, HTTP, SCHEMA, STREAMS_PARSE, STREAMS_CURSOR, STREAMS_ROW_COUNT,
        STREAMS_IDENTITY, MISSING, OFFLINE, IDENTITY, SOURCE }
    var stage = Stage.SELECTED
        private set
    var refusal = Refusal.NONE
        private set
    var usersIdHttp = -1
        private set
    var streamsHttp = -1
        private set
    var streamsParsed = false
        private set
    var streamsRawCount = -1
        private set
    var streamsTypedCount = -1
        private set
    var streamsHasCursor = false
        private set
    var streamsIdentityMatched: Boolean? = null
        private set
    var usersLoginHttp = -1
        private set
    var helixRequests = 0
        private set
    private var inFlight: URI? = null
    private var login: String? = null
    private var nativeStarted = false
    private var validationPending = false
    private var validations = 0
    private var positiveValidation = false
    private var accessHttp = -1
    private var source: URI? = null
    init { require(supportedConfiguredTwitchBroadcaster(resource)) }
    private fun need(value: Boolean, category: Refusal) {
        if (!value) {
            if (refusal == Refusal.NONE) refusal = category
            stage = Stage.REFUSED
            throw IOException("Configured channel observation refused its closed scope")
        }
    }
    @Synchronized fun beginValidation() {
        need(stage in setOf(Stage.SELECTED, Stage.LIVE) && !validationPending && validations < 2, Refusal.REQUEST)
        validationPending = true; validations++
    }
    @Synchronized fun validated(status: Int) {
        need(validationPending && status == 200, Refusal.HTTP)
        validationPending = false; positiveValidation = true
    }
    @Synchronized fun beginHelix(request: TwitchHelixRequest) {
        need(positiveValidation && !validationPending && inFlight == null, Refusal.REQUEST)
        val expected = when (stage) {
            Stage.SELECTED -> TwitchHelixRequest.usersById(listOf(resource.identity))
            Stage.IDENTITY -> TwitchHelixRequest.streams(listOf(resource.identity))
            Stage.SOURCE -> TwitchHelixRequest.userByLogin(checkNotNull(login))
            else -> { need(false, Refusal.REQUEST); return }
        }
        need(request.operation == expected.operation && request.url.toURI() == expected.url.toURI(), Refusal.REQUEST)
        inFlight = request.url.toURI(); helixRequests++
    }
    @Synchronized fun completeHelix(request: TwitchHelixRequest, response: TwitchHelixResponse) {
        need(inFlight == request.url.toURI(), Refusal.REQUEST)
        val status = response.status.takeIf { it in 100..599 } ?: -1
        when (stage) {
            Stage.SELECTED -> usersIdHttp = status
            Stage.IDENTITY -> streamsHttp = status
            Stage.SOURCE -> usersLoginHttp = status
            else -> need(false, Refusal.REQUEST)
        }
        need(status == 200, Refusal.HTTP) // Refuse before production refresh/retry branches.
        try {
            if (stage == Stage.IDENTITY) {
                // Counts are bounded diagnostics, never a retained raw page.
                // 101 means the parser's 100-row limit was exceeded.
                streamsRawCount = (response.fields["data"] as? List<*>)?.size?.coerceAtMost(101) ?: -1
                val page = try { parseTwitchStreams(response) }
                catch (_: Throwable) { need(false, Refusal.STREAMS_PARSE); return }
                streamsParsed = true
                streamsTypedCount = page.items.size
                streamsHasCursor = page.nextCursor != null
                streamsIdentityMatched = page.items.takeIf { it.isNotEmpty() }?.all {
                    it.broadcaster.id == resource.identity && it.broadcaster.login == login
                }
                need(page.items.size <= 1, Refusal.STREAMS_ROW_COUNT)
                // One exact live match answers this identity query even when
                // Twitch supplies a cursor. No continuation is admitted.
                if (page.items.isEmpty()) need(page.nextCursor == null, Refusal.STREAMS_CURSOR)
                need(page.items.isNotEmpty(), Refusal.OFFLINE)
                need(streamsIdentityMatched == true, Refusal.STREAMS_IDENTITY)
                stage = Stage.LIVE
            } else {
                val page = parseTwitchUsers(response)
                need(page.nextCursor == null && page.items.size <= 1, Refusal.SCHEMA)
                need(page.items.isNotEmpty(), Refusal.MISSING)
                val row = page.items.single()
                need(row.id == resource.identity && validTwitchPlaybackResource(TwitchAccessCase.LIVE, row.login), Refusal.IDENTITY)
                if (stage == Stage.SELECTED) { login = row.login; stage = Stage.IDENTITY }
                else { need(row.login == login, Refusal.IDENTITY); stage = Stage.CONFIRMED }
            }
            inFlight = null
        } catch (_: Throwable) { need(false, if (refusal == Refusal.NONE) Refusal.SCHEMA else refusal) }
    }
    @Synchronized fun beginNative(replay: Boolean, value: String) {
        need(stage == Stage.LIVE && !nativeStarted && !replay && value == login, Refusal.IDENTITY)
        nativeStarted = true
    }
    @Synchronized fun beginAccess(): ByteArray {
        need(stage == Stage.LIVE && nativeStarted && positiveValidation && !validationPending, Refusal.REQUEST)
        stage = Stage.ACCESS
        return twitchAccessProbeBody(TwitchAccessCase.LIVE, checkNotNull(login))
    }
    @Synchronized fun accessStatus(status: Int) {
        need(stage == Stage.ACCESS && status == 200, Refusal.HTTP)
        accessHttp = status
    }
    @Synchronized fun bindSource(uri: URI) {
        need(stage == Stage.ACCESS && accessHttp == 200 && uri.scheme == "https" && uri.host == "usher.ttvnw.net" &&
            uri.rawUserInfo == null && uri.rawFragment == null && uri.port == -1 &&
            uri.rawPath == "/api/v2/channel/hls/${checkNotNull(login)}.m3u8", Refusal.SOURCE)
        source = uri; stage = Stage.SOURCE
    }
    @Synchronized fun admitMedia(uri: URI) {
        need(stage == Stage.CONFIRMED && allowedTwitchMediaUri(uri, observedReplayCdn = false) &&
            (uri.host != "usher.ttvnw.net" || uri == source), Refusal.SOURCE)
    }
    @Synchronized fun accepted(): Boolean = stage == Stage.CONFIRMED && refusal == Refusal.NONE &&
        validations in 1..2 && !validationPending && positiveValidation && helixRequests == 3 &&
        usersIdHttp == 200 && streamsHttp == 200 && usersLoginHttp == 200 && accessHttp == 200 && source != null
    @Synchronized fun clear() { login = null; source = null; inFlight = null }
    override fun toString() = "ConfiguredTwitchChannelScope(redacted)"
}

class ConfiguredTwitchChannelScopeDeviceTest {
    private val resource = CatalogResource(ProviderId("twitch"), "broadcaster", "123", CatalogIntent.CHANNEL)
    private fun page(rows: List<Map<String, Any?>>) = TwitchHelixResponse(200,
        mapOf("data" to rows, "pagination" to emptyMap<String, Any?>()))
    private fun user(id: String = "123", login: String = "fixture_channel") =
        mapOf<String, Any?>("id" to id, "login" to login, "display_name" to "Fixture")
    private fun live(login: String = "fixture_channel") = mapOf<String, Any?>("id" to "456", "user_id" to "123",
        "user_login" to login, "user_name" to "Fixture", "type" to "live")
    private fun identity(): ConfiguredTwitchChannelScope = ConfiguredTwitchChannelScope(resource).also { scope ->
        scope.beginValidation(); scope.validated(200)
        val request = TwitchHelixRequest.usersById(listOf("123"))
        scope.beginHelix(request); scope.completeHelix(request, page(listOf(user())))
    }
    private fun source(cursor: Boolean = false): ConfiguredTwitchChannelScope = identity().also { scope ->
        val request = TwitchHelixRequest.streams(listOf("123"))
        val response = if (cursor) TwitchHelixResponse(200,
            mapOf("data" to listOf(live()), "pagination" to mapOf("cursor" to "fixture-cursor"))) else page(listOf(live()))
        scope.beginHelix(request); scope.completeHelix(request, response)
        scope.beginNative(false, "fixture_channel")
        scope.beginValidation(); scope.validated(200)
        scope.beginAccess(); scope.accessStatus(200)
        scope.bindSource(twitchPlaybackSource(TwitchAccessCase.LIVE, "fixture_channel", "fixture_sig", "fixture-value").uri)
    }
    private class HelixConnection(private val failInputClose: Boolean = false, private val failDisconnect: Boolean = false) :
        HttpsURLConnection(URL("https://api.twitch.tv/helix/users?id=123")) {
        private val body = """{"data":[{"id":"123","login":"fixture_channel","display_name":"Fixture"}],"pagination":{}}"""
        var statusCalls = 0
        var disconnectCalls = 0
        var inputCloseCalls = 0
        override fun getResponseCode(): Int { statusCalls++; return 200 }
        override fun getInputStream() = object : ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)) {
            override fun close() {
                inputCloseCalls++
                if (failInputClose) throw IOException("Synthetic input close failure")
                super.close()
            }
        }
        override fun getContentLengthLong() = body.toByteArray(Charsets.UTF_8).size.toLong()
        override fun disconnect() {
            disconnectCalls++
            if (failDisconnect) throw IOException("Synthetic disconnect failure")
        }
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
    private fun observed(remote: HelixConnection, failed: AtomicBoolean) =
        ConfiguredTwitchPlaybackAcceptanceDeviceTest.ObservedConnection(remote, failed, { true },
            expectedBody = null, expectedAuthorization = null, onStatus = {},
            expectedGetAuthorization = "Bearer fixture-access", expectedGetClientId = SMART_TV_TWITCH_CLIENT_ID)
    @Test fun exactCurrentBroadcasterSequenceRequiresPositiveConfirmationBeforeMedia() {
        val scope = source()
        assertFalse(scope.accepted())
        val request = TwitchHelixRequest.userByLogin("fixture_channel")
        scope.beginHelix(request); scope.completeHelix(request, page(listOf(user())))
        scope.admitMedia(URI("https://video.ttvnw.net/fixture.ts"))
        assertTrue(scope.accepted())
    }
    @Test fun oneExactLiveRowWithCursorStillRequiresFinalConfirmationAndNeverRequestsContinuation() {
        val scope = source(cursor = true)
        assertTrue(scope.streamsParsed)
        assertEquals(1, scope.streamsTypedCount)
        assertTrue(scope.streamsHasCursor)
        assertEquals(true, scope.streamsIdentityMatched)
        assertFalse(scope.accepted())
        val request = TwitchHelixRequest.userByLogin("fixture_channel")
        scope.beginHelix(request); scope.completeHelix(request, page(listOf(user())))
        scope.admitMedia(URI("https://video.ttvnw.net/fixture.ts"))
        assertTrue(scope.accepted())
        assertEquals(3, scope.helixRequests)
        assertThrows(IOException::class.java) {
            scope.beginHelix(TwitchHelixRequest.streams(listOf("123"), "fixture-cursor"))
        }
        assertEquals(3, scope.helixRequests)
    }
    @Test fun scopeRefusesSweepPaginationRetryAndAliasMismatchBeforeAcceptance() {
        for (request in listOf(TwitchHelixRequest.usersById(listOf("999")), TwitchHelixRequest.streams(),
            TwitchHelixRequest.following("123"), TwitchHelixRequest.schedule("123", 0))) {
            val scope = ConfiguredTwitchChannelScope(resource)
            scope.beginValidation(); scope.validated(200)
            assertThrows(IOException::class.java) { scope.beginHelix(request) }
            assertFalse(scope.accepted())
        }
        val paged = identity()
        assertThrows(IOException::class.java) { paged.beginHelix(TwitchHelixRequest.streams(listOf("123"), "fixture-cursor")) }
        val renamed = identity(); val streams = TwitchHelixRequest.streams(listOf("123"))
        renamed.beginHelix(streams)
        assertThrows(IOException::class.java) { renamed.completeHelix(streams, page(listOf(live("other_channel")))) }
        val reused = source(); val login = TwitchHelixRequest.userByLogin("fixture_channel")
        reused.beginHelix(login)
        assertThrows(IOException::class.java) { reused.completeHelix(login, page(listOf(user(id = "999")))) }
    }
    @Test fun offlineAndNon200ResponsesCannotBecomeAcceptedEmptyOrTriggerAnotherRequest() {
        val offline = identity(); val request = TwitchHelixRequest.streams(listOf("123"))
        offline.beginHelix(request)
        assertThrows(IOException::class.java) { offline.completeHelix(request, page(emptyList())) }
        assertEquals(ConfiguredTwitchChannelScope.Refusal.OFFLINE, offline.refusal)
        for (status in listOf(401, 403, 429, 503)) {
            val validation = ConfiguredTwitchChannelScope(resource); validation.beginValidation()
            assertThrows(IOException::class.java) { validation.validated(status) }
            assertThrows(IOException::class.java) { validation.beginValidation() }
            val scope = identity(); scope.beginHelix(request)
            assertThrows(IOException::class.java) { scope.completeHelix(request, TwitchHelixResponse(status)) }
            assertThrows(IOException::class.java) { scope.beginHelix(request) }
            assertFalse(scope.accepted())
        }
        val bounded = ConfiguredTwitchChannelScope(resource)
        repeat(2) { bounded.beginValidation(); bounded.validated(200) }
        assertThrows(IOException::class.java) { bounded.beginValidation() }
    }
    @Test fun wrongNativeIdentityAndReplayOnlyDestinationStayRefused() {
        val wrong = identity(); val request = TwitchHelixRequest.streams(listOf("123"))
        wrong.beginHelix(request); wrong.completeHelix(request, page(listOf(live())))
        assertThrows(IOException::class.java) { wrong.beginNative(false, "other_channel") }
        val scope = source(); val confirm = TwitchHelixRequest.userByLogin("fixture_channel")
        scope.beginHelix(confirm); scope.completeHelix(confirm, page(listOf(user())))
        assertThrows(IOException::class.java) { scope.admitMedia(URI("https://$OBSERVED_TWITCH_REPLAY_CDN/fixture.ts")) }
        assertFalse(scope.accepted())
    }
    @Test fun observedHelixGetRequiresExactHeadersAndPreservesProductionResponseBytes() {
        val remote = HelixConnection(); val failed = AtomicBoolean()
        val connection = observed(remote, failed)
        assertThrows(IOException::class.java) { connection.responseCode }
        assertEquals(0, remote.statusCalls)
        val transport = TwitchHelixHttpTransport(open = { connection }, canRequest = { true })
        val response = transport.execute("fixture-access", TwitchHelixRequest.usersById(listOf("123")))
        assertEquals(200, response.status)
        assertEquals(1, parseTwitchUsers(response).items.size)
        assertEquals(1, remote.statusCalls)
        assertEquals(1, remote.inputCloseCalls)
        assertEquals(1, remote.disconnectCalls)
        assertTrue(connection.closeConfirmed(SystemClock.elapsedRealtime() + 1000))
        assertFalse(failed.get())
        transport.close()
    }
    @Test fun streamsDiagnosticsDistinguishParserCursorRowCountAndIdentityWithoutAdmittingAny() {
        val responses = listOf(
            TwitchHelixResponse(200, mapOf("data" to listOf(live().minus("type")), "pagination" to emptyMap<String, Any?>()))
                to ConfiguredTwitchChannelScope.Refusal.STREAMS_PARSE,
            TwitchHelixResponse(200, mapOf("data" to emptyList<Any>(), "pagination" to mapOf("cursor" to "fixture-cursor")))
                to ConfiguredTwitchChannelScope.Refusal.STREAMS_CURSOR,
            page(listOf(live(), live())) to ConfiguredTwitchChannelScope.Refusal.STREAMS_ROW_COUNT,
            page(listOf(live("other_channel"))) to ConfiguredTwitchChannelScope.Refusal.STREAMS_IDENTITY,
            page(List(101) { live() }) to ConfiguredTwitchChannelScope.Refusal.STREAMS_PARSE,
        )
        for ((response, category) in responses) {
            val scope = identity(); val request = TwitchHelixRequest.streams(listOf("123"))
            scope.beginHelix(request)
            assertThrows(IOException::class.java) { scope.completeHelix(request, response) }
            assertEquals(category, scope.refusal)
            assertEquals(category != ConfiguredTwitchChannelScope.Refusal.STREAMS_PARSE, scope.streamsParsed)
            assertEquals(category == ConfiguredTwitchChannelScope.Refusal.STREAMS_CURSOR, scope.streamsHasCursor)
            if (!scope.streamsParsed || scope.streamsTypedCount == 0) assertNull(scope.streamsIdentityMatched)
            else assertEquals(category != ConfiguredTwitchChannelScope.Refusal.STREAMS_IDENTITY, scope.streamsIdentityMatched)
            assertEquals((response.fields["data"] as List<*>).size.coerceAtMost(101), scope.streamsRawCount)
            assertEquals(if (scope.streamsParsed) scope.streamsRawCount else -1, scope.streamsTypedCount)
            assertFalse(scope.accepted())
            assertThrows(IOException::class.java) { scope.beginHelix(request) }
        }
    }
    @Test fun cursorDoesNotAdmitDuplicateRowsWrongOwnerOrWrongAlias() {
        val rows = listOf(
            listOf(live(), live()) to ConfiguredTwitchChannelScope.Refusal.STREAMS_ROW_COUNT,
            listOf(live() + ("user_id" to "999")) to ConfiguredTwitchChannelScope.Refusal.STREAMS_IDENTITY,
            listOf(live("other_channel")) to ConfiguredTwitchChannelScope.Refusal.STREAMS_IDENTITY,
        )
        for ((values, category) in rows) {
            val scope = identity(); val request = TwitchHelixRequest.streams(listOf("123"))
            scope.beginHelix(request)
            val response = TwitchHelixResponse(200,
                mapOf("data" to values, "pagination" to mapOf("cursor" to "fixture-cursor")))
            assertThrows(IOException::class.java) { scope.completeHelix(request, response) }
            assertTrue(scope.streamsHasCursor)
            assertEquals(category, scope.refusal)
            assertFalse(scope.accepted())
            assertThrows(IOException::class.java) { scope.beginHelix(request) }
        }
    }
    @Test fun uncertainHelixInputOrDisconnectStaysOwnedAfterProductionTransportClearsActive() {
        for (failInput in listOf(false, true)) {
            val remote = HelixConnection(failInputClose = failInput, failDisconnect = !failInput)
            val failed = AtomicBoolean(); val connection = observed(remote, failed)
            val transport = TwitchHelixHttpTransport(open = { connection }, canRequest = { true })
            assertThrows(TwitchHelixNetworkException::class.java) {
                transport.execute("fixture-access", TwitchHelixRequest.usersById(listOf("123")))
            }
            // Production's active reference is now empty. Later close must
            // preserve the actual failed receipt rather than report success.
            assertThrows(IOException::class.java) { transport.close() }
            connection.startClose()
            assertTrue(failed.get())
            assertFalse(connection.closeConfirmed(SystemClock.elapsedRealtime() + 1000))
            assertEquals(1, remote.inputCloseCalls)
            assertEquals(1, remote.disconnectCalls)
        }
    }
}
