package net.fstab.tachiai.provider.twitch

import android.content.ContextWrapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.security.cert.Certificate
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Job
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.twitch.catalog.*
import org.junit.Assert.*
import org.junit.Test

class CatalogTwitchPreparationTest {
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        var forbidden = false
        var reads = 0
        var writes = 0
        override fun read(): ByteArray? { check(!forbidden); reads++; return bytes?.copyOf() }
        override fun write(plaintext: ByteArray) { check(!forbidden); writes++; bytes = plaintext.copyOf() }
    }
    private class Connection(url: URL, private val beforeResponse: () -> Unit,
        private val response: String) : HttpsURLConnection(url) {
        val written = ByteArrayOutputStream()
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode(): Int { beforeResponse(); return 200 }
        override fun getContentLengthLong() = -1L
        override fun getInputStream() = ByteArrayInputStream(response.toByteArray())
        override fun getOutputStream() = written
    }
    private inner class Fixture {
        val id = UUID.randomUUID().toString()
        val routeId = UUID.randomUUID().toString()
        val profile = parseConnectionProfile("http://fixture.example.test:3128".toByteArray())
        val choice = SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, routeId, "Fixture route")
        val route = TwitchCatalogRouteOwner(ProviderInstance(id, PrototypeService.TWITCH, "Fixture",
            setup = ProviderSetup(choice)), profile)
        val memory = Memory()
        var wall = 1_000L
        var mono = 10_000L
        val store = TwitchCatalogGrantStore(memory, id, wallMs = { wall })
        var owner = route.presentation
        var admission = true
        var bindingOpened = 0
        var bindingClosed = 0
        var sessionOpened = 0
        var ownerReads = 0
        var validationCalls = 0
        var refreshCalls = 0
        var transportCloses = 0
        var mediaCalls = 0
        var scopes = listOf(TWITCH_CATALOG_SCOPE)
        var validateStatus = 200
        var beforeResponse: () -> Unit = {}
        var response = "{\"data\":{\"streamPlaybackAccessToken\":{\"signature\":\"abc\",\"value\":\"fixture-value\"}," +
            "\"videoPlaybackAccessToken\":{\"signature\":\"abc\",\"value\":\"fixture-value\"}}}"
        val connections = mutableListOf<Connection>()
        val phases = mutableListOf<String>()
        lateinit var retainedSession: TwitchCatalogSession
        fun connect(lifetime: Long = 7_200_000L, token: String = "fixture-access") =
            store.commitConnection(store.beginConnection(), TwitchCatalogReplacement(
                TwitchCatalogCredentials(token, "fixture-refresh", lifetime),
                TwitchCatalogValidation("fixture-user", setOf(TWITCH_CATALOG_SCOPE), lifetime), lifetime))!!
        fun transport() = object : TwitchCatalogTransport {
            override fun device(): DeviceAuthResponse = error("No device consent")
            override fun poll(deviceCode: String): DeviceAuthResponse = error("No polling")
            override fun validate(accessToken: String): DeviceAuthResponse {
                validationCalls++
                return DeviceAuthResponse(validateStatus, mapOf("client_id" to SMART_TV_TWITCH_CLIENT_ID,
                    "user_id" to "fixture-user", "scopes" to scopes, "expires_in" to 7200))
            }
            override fun refresh(refreshToken: String): DeviceAuthResponse {
                refreshCalls++
                return DeviceAuthResponse(200, mapOf("access_token" to "fresh-access", "refresh_token" to "fresh-refresh",
                    "token_type" to "bearer", "scope" to listOf(TWITCH_CATALOG_SCOPE), "expires_in" to 7200))
            }
            override fun close() { transportCloses++ }
        }
        fun preparation(expectedProfile: ConnectionProfile? = profile, expectedRoute: SourceRouteChoice = choice) =
            CatalogTwitchPreparation(ContextWrapper(null), id, expectedProfile, expectedRoute, { admission },
                openConnection = { url ->
                    assertEquals("https://gql.twitch.tv/gql", url.toExternalForm())
                    mediaCalls++
                    Connection(url, { beforeResponse() }, response).also(connections::add)
                }, bindingFactory = {
                    bindingOpened++
                    route to object : TwitchCatalogConnectionBinding {
                        var closed = false
                        override fun owner(): TwitchCatalogConnectionOwner { check(!memory.forbidden); ownerReads++; return owner }
                        override fun canPublishLocally() = admission && !closed
                        override fun session(): TwitchCatalogSession {
                            sessionOpened++
                            return TwitchCatalogSession(store, this@Fixture::transport, { wall }, { mono }, {
                                canPublishLocally() && owner().sameOwnership(route.presentation)
                            }).also { retainedSession = it }
                        }
                        override fun transport(canRequest: () -> Boolean) = this@Fixture.transport()
                        override fun close() { closed = true; bindingClosed++ }
                    }
                }, monotonicMs = { mono })
        fun resolve(preparation: CatalogTwitchPreparation, replay: Boolean = false): TwitchPlaybackSource =
            preparation.resolve(replay, if (replay) "123456" else "fixture_channel") { phase, _ -> phases.add(phase) }
        fun advance(ms: Long) { wall += ms; mono += ms }
    }

    @Test fun constructionIsCheapAndOneValidatedGrantFeedsLiveAndReplayWithoutCopyingSecrets() {
        for (replay in listOf(false, true)) {
            val fixture = Fixture(); fixture.connect(); val writes = fixture.memory.writes
            val preparation = fixture.preparation()
            assertEquals(0, fixture.bindingOpened); assertEquals(0, fixture.ownerReads)
            val source = fixture.resolve(preparation, replay)
            assertEquals(if (replay) "/vod/v2/123456.m3u8" else "/api/v2/channel/hls/fixture_channel.m3u8", source.uri.path)
            assertEquals(1, fixture.validationCalls); assertEquals(1, fixture.transportCloses)
            assertEquals("OAuth fixture-access", fixture.connections.single().getRequestProperty("Authorization"))
            assertEquals("", fixture.connections.single().getRequestProperty("Client-ID"))
            assertTrue(fixture.connections.single().disconnected)
            assertEquals(writes, fixture.memory.writes)
            assertEquals(40_000L, preparation.acceptanceDeadlineMs)
            assertTrue(preparation.canContinue()); preparation.close(); assertFalse(preparation.canContinue())
            assertEquals(1, fixture.bindingClosed)
        }
    }

    @Test fun expiredSavedGrantRefreshesOnceAndOnlyTheFreshScopedTokenReachesPlayback() {
        val fixture = Fixture(); fixture.connect(lifetime = 1_000); fixture.advance(1_000)
        val preparation = fixture.preparation(); fixture.resolve(preparation)
        assertEquals(1, fixture.refreshCalls); assertEquals(1, fixture.validationCalls)
        assertEquals("OAuth fresh-access", fixture.connections.single().getRequestProperty("Authorization"))
        assertTrue(preparation.checkStored(force = true)); preparation.close()
    }

    @Test fun sourceAcceptanceDeadlineDoesNotBecomeTheMediaGrantExpiry() {
        val fixture = Fixture(); fixture.connect(); val preparation = fixture.preparation(); fixture.resolve(preparation)
        fixture.advance(30_000)
        assertTrue(preparation.canContinue()); assertTrue(preparation.checkStored(force = true))
        fixture.advance(TWITCH_CATALOG_VALIDATION_INTERVAL_MS)
        assertFalse(preparation.canContinue()); assertFalse(preparation.checkStored(force = true)); preparation.close()
    }

    @Test fun routeProfileChoiceAndCapturedOwnerMismatchDenyBeforeReadingOrValidatingGrant() {
        val fixtures = listOf(Fixture(), Fixture(), Fixture(), Fixture())
        val preparations = listOf(fixtures[0].preparation(expectedProfile = null),
            fixtures[1].preparation(expectedRoute = SourceRouteChoice.system),
            fixtures[2].preparation(expectedRoute = fixtures[2].choice.copy(connectionId = UUID.randomUUID().toString())),
            fixtures[3].also { it.owner = TwitchCatalogConnectionOwner(it.id, "Changed", "Changed", "OTHER") }.preparation())
        fixtures.zip(preparations).forEach { (fixture, preparation) ->
            assertThrows(IllegalStateException::class.java) { fixture.resolve(preparation) }
            assertEquals(0, fixture.memory.reads); assertEquals(0, fixture.validationCalls); assertEquals(0, fixture.mediaCalls)
            assertEquals(1, fixture.bindingClosed); assertFalse(preparation.canContinue())
        }
    }

    @Test fun missingStorageScopeMismatchAndUnreadableStorageFailClosedWithFixedStatus() {
        val missing = Fixture(); val missingPreparation = missing.preparation()
        assertThrows(IOException::class.java) { missing.resolve(missingPreparation) }
        assertTrue("STORAGE_MISSING" in missing.phases); assertEquals(0, missing.mediaCalls)
        val mismatch = Fixture(); mismatch.connect(); mismatch.scopes = emptyList()
        assertThrows(IOException::class.java) { mismatch.resolve(mismatch.preparation()) }
        assertTrue("VALIDATION_REJECTED" in mismatch.phases); assertEquals(0, mismatch.mediaCalls)
        val unreadable = Fixture(); unreadable.connect()
        val preparation = unreadable.preparation()
        // Only grant access fails; route/owner fixture stays independently readable.
        unreadable.memory.bytes = byteArrayOf(1, 2, 3)
        assertThrows(IOException::class.java) { unreadable.resolve(preparation) }
        assertTrue("STORAGE_UNREADABLE" in unreadable.phases); assertEquals(0, unreadable.mediaCalls)
        listOf(missing, mismatch, unreadable).forEach { assertEquals(1, it.bindingClosed) }
    }

    @Test fun forgetReconnectAndRefreshInvalidateThePlaybackLeaseWithoutTokenMigration() {
        for (operation in listOf("forget", "reconnect", "refresh")) {
            val fixture = Fixture(); fixture.connect(); val preparation = fixture.preparation(); fixture.resolve(preparation)
            when (operation) {
                "forget" -> fixture.store.forget()
                "reconnect" -> fixture.connect(token = "replacement-access")
                "refresh" -> {
                    val other = TwitchCatalogSession(fixture.store, fixture::transport, { fixture.wall }, { fixture.mono })
                    val value = other.validate().lease!!
                    assertEquals(TwitchCatalogSessionState.CONNECTED, other.onUnauthorized(value).summary.state)
                    other.close()
                }
            }
            // Refresh rotates the durable generation without a local revision
            // signal; the worker gate observes it before further media access.
            assertFalse(preparation.checkStored(force = true)); assertFalse(preparation.canContinue()); preparation.close()
        }
    }

    @Test fun cheapAdmissionAndClosePerformNoProtectedOrOwnerIoAndWorkerChecksObserveOwnerChanges() {
        val fixture = Fixture(); fixture.connect(); val preparation = fixture.preparation(); fixture.resolve(preparation)
        val reads = fixture.memory.reads; val owners = fixture.ownerReads
        fixture.memory.forbidden = true
        repeat(4) { assertTrue(preparation.canContinue()) }
        preparation.close()
        assertEquals(reads, fixture.memory.reads); assertEquals(owners, fixture.ownerReads)
        assertFalse(preparation.canContinue())
        val changed = Fixture(); changed.connect(); val changedPreparation = changed.preparation(); changed.resolve(changedPreparation)
        changed.owner = TwitchCatalogConnectionOwner(changed.id, "Changed", "Changed", "OTHER")
        assertTrue(changedPreparation.canContinue())
        assertFalse(changedPreparation.checkStored(force = true)); assertFalse(changedPreparation.canContinue())
        changedPreparation.close()
    }

    @Test fun pendingForgetAndFailedClearRejectSourceAndQueuedPublication() {
        val fixture = Fixture(); fixture.connect(); val preparation = fixture.preparation(); fixture.resolve(preparation)
        val pending = Job()
        TwitchCatalogConnectionWrites.track(fixture.id, pending)
        assertFalse(preparation.canContinue()); assertFalse(preparation.checkStored(force = true))
        pending.complete()
        assertTrue(preparation.canContinue())
        TwitchCatalogConnectionWrites.markFailure(fixture.id, true)
        try { assertFalse(preparation.canContinue()); assertFalse(preparation.checkStored(force = true)) }
        finally { TwitchCatalogConnectionWrites.markFailure(fixture.id, false); preparation.close() }
        val blocked = Fixture(); blocked.connect(); TwitchCatalogConnectionWrites.markFailure(blocked.id, true)
        try {
            assertThrows(IllegalStateException::class.java) { blocked.resolve(blocked.preparation()) }
            assertEquals(0, blocked.validationCalls); assertEquals(0, blocked.mediaCalls)
        } finally { TwitchCatalogConnectionWrites.markFailure(blocked.id, false) }
    }

    @Test fun forgetDuringSourceRequestAndMalformedSourceCloseRequestSessionAndBinding() {
        val forgotten = Fixture(); forgotten.connect(); forgotten.beforeResponse = { forgotten.store.forget() }
        val preparation = forgotten.preparation()
        assertThrows(IOException::class.java) { forgotten.resolve(preparation) }
        assertTrue(forgotten.connections.single().disconnected); assertEquals(1, forgotten.bindingClosed)
        assertFalse(preparation.canContinue())
        val malformed = Fixture(); malformed.connect(); malformed.response = "{}"
        val other = malformed.preparation()
        assertThrows(IOException::class.java) { malformed.resolve(other) }
        assertTrue(malformed.connections.single().disconnected); assertEquals(1, malformed.bindingClosed)
        assertFalse(other.canContinue()); assertEquals(TwitchCatalogSessionState.SUPERSEDED, malformed.retainedSession.readSummary().state)
    }

    @Test fun sourceRequestCannotPublishAfterTheShortAcceptanceDeadline() {
        val fixture = Fixture(); fixture.connect(); fixture.beforeResponse = { fixture.advance(30_000) }
        val preparation = fixture.preparation()
        assertThrows(IOException::class.java) { fixture.resolve(preparation) }
        assertTrue(fixture.connections.single().disconnected); assertEquals(1, fixture.bindingClosed)
        assertFalse(preparation.canContinue())
    }
}
