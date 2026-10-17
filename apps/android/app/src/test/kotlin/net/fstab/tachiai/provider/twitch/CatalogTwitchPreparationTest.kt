package net.fstab.tachiai.provider.twitch

import android.content.ContextWrapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.security.cert.Certificate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
        var onRead: () -> Unit = {}
        var onWrite: () -> Unit = {}
        override fun read(): ByteArray? { check(!forbidden); reads++; onRead(); return bytes?.copyOf() }
        override fun write(plaintext: ByteArray) { check(!forbidden); writes++; bytes = plaintext.copyOf(); onWrite() }
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
    private inner class Fixture(private val shared: Boolean = false) {
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
        var onOwner: () -> Unit = {}
        var validationCalls = 0
        var refreshCalls = 0
        var transportCloses = 0
        var mediaCalls = 0
        var scopes = listOf(TWITCH_CATALOG_SCOPE)
        var validateStatus = 200
        var validationSeconds = 7200
        var onValidate: () -> Unit = {}
        var onRefresh: () -> Unit = {}
        var onClose: () -> Unit = {}
        var beforeResponse: () -> Unit = {}
        var response = "{\"data\":{\"streamPlaybackAccessToken\":{\"signature\":\"abc\",\"value\":\"fixture-value\"}," +
            "\"videoPlaybackAccessToken\":{\"signature\":\"abc\",\"value\":\"fixture-value\"}}}"
        val connections = mutableListOf<Connection>()
        val phases = mutableListOf<String>()
        lateinit var retainedSession: TwitchCatalogSession
        private val maintenance by lazy {
            if (shared) TwitchCatalogMaintenanceCoordinator(store, { transport() }, {
                admission && owner.sameOwnership(route.presentation)
            }, { wall }, { mono }) else null
        }
        fun peerSession() = TwitchCatalogSession(store, { transport() }, { wall }, { mono }, {
            admission && owner.sameOwnership(route.presentation)
        }, maintenance, { admission }).also { it.attach() }
        fun connect(lifetime: Long = 7_200_000L, token: String = "fixture-access") =
            store.commitConnection(store.beginConnection(), TwitchCatalogReplacement(
                TwitchCatalogCredentials(token, "fixture-refresh", lifetime),
                TwitchCatalogValidation("fixture-user", setOf(TWITCH_CATALOG_SCOPE), lifetime), lifetime))!!
        fun transport() = object : TwitchCatalogTransport {
            override fun device(): DeviceAuthResponse = error("No device consent")
            override fun poll(deviceCode: String): DeviceAuthResponse = error("No polling")
            override fun validate(accessToken: String): DeviceAuthResponse {
                validationCalls++
                onValidate()
                return DeviceAuthResponse(validateStatus, mapOf("client_id" to SMART_TV_TWITCH_CLIENT_ID,
                    "user_id" to "fixture-user", "scopes" to scopes, "expires_in" to validationSeconds))
            }
            override fun refresh(refreshToken: String): DeviceAuthResponse {
                refreshCalls++
                onRefresh()
                return DeviceAuthResponse(200, mapOf("access_token" to "fresh-access", "refresh_token" to "fresh-refresh",
                    "token_type" to "bearer", "scope" to listOf(TWITCH_CATALOG_SCOPE), "expires_in" to 7200))
            }
            override fun close() { transportCloses++; onClose() }
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
                        override fun owner(): TwitchCatalogConnectionOwner {
                            check(!memory.forbidden); ownerReads++; onOwner(); return owner
                        }
                        override fun canPublishLocally() = admission && !closed
                        override fun session(): TwitchCatalogSession {
                            sessionOpened++
                            return TwitchCatalogSession(store, { this@Fixture.transport() }, { wall }, { mono }, {
                                canPublishLocally() && owner().sameOwnership(route.presentation)
                            }, maintenance, { canPublishLocally() }).also { retainedSession = it; it.attach() }
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

    @Test fun secondViewerTightensSameTokenExpiryWithoutInterruptingFirstViewer() {
        val fixture = Fixture()
        fixture.validationSeconds = 10
        val saved = fixture.store.commitConnection(fixture.store.beginConnection(), TwitchCatalogReplacement(
            TwitchCatalogCredentials("fixture-access", "fixture-refresh", 10_000L),
            TwitchCatalogValidation("fixture-user", setOf(TWITCH_CATALOG_SCOPE), 10_000L), 10_000L,
            providerExpiresAtMs = fixture.wall + 10_000L,
            localRetentionUntilMs = fixture.wall + TWITCH_CATALOG_LOCAL_RETENTION_MS,
            savedAtMs = fixture.wall))!!
        val first = fixture.preparation()
        val second = fixture.preparation()
        try {
            fixture.resolve(first)
            fixture.validationSeconds = 9
            fixture.resolve(second, replay = true)
            assertEquals(saved.generation, fixture.store.read()!!.generation)
            // Media3 uses the default gate, including immediately after a
            // successful check before the other viewer's tighter deadline.
            assertTrue(first.checkStored()); assertTrue(second.checkStored())
            fixture.advance(8_999L)
            assertTrue(first.checkStored()); assertTrue(second.checkStored())
            fixture.advance(1L)
            assertFalse(first.checkStored()); assertFalse(second.checkStored())
            assertFalse(first.canContinue()); assertFalse(second.canContinue())
            assertEquals(2, fixture.validationCalls); assertEquals(2, fixture.mediaCalls)
        } finally { first.close(); second.close() }
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
                    val other = TwitchCatalogSession(fixture.store, { fixture.transport() }, { fixture.wall }, { fixture.mono })
                    val value = other.validate().lease!!
                    assertEquals(TwitchCatalogSessionState.CONNECTED, other.onUnauthorized(value).summary.state)
                    other.close()
                }
            }
            // Refresh rotates the durable generation without a local revision
            // signal; the worker gate observes it before further media access.
            assertFalse(preparation.checkStored()); assertFalse(preparation.canContinue()); preparation.close()
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
        assertFalse(changedPreparation.checkStored()); assertFalse(changedPreparation.canContinue())
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

    @Test fun completedPeerRotationPreservesLiveReplaySourceAndOriginalAcceptanceDeadlineWithoutHttp() {
        for (replay in listOf(false, true)) {
            val fixture = Fixture(shared = true); fixture.connect()
            val preparation = fixture.preparation()
            val source = fixture.resolve(preparation, replay)
            val sourceDeadline = preparation.acceptanceDeadlineMs
            fixture.peerSession().use { peer ->
                val old = peer.validate().lease!!
                assertNotNull(peer.onUnauthorized(old).lease)
                val validations = fixture.validationCalls
                val refreshes = fixture.refreshCalls
                val media = fixture.mediaCalls
                assertTrue(preparation.canContinue())
                fixture.advance(30_000)
                assertTrue(preparation.checkStored())
                assertTrue(preparation.canContinue())
                assertEquals(sourceDeadline, preparation.acceptanceDeadlineMs)
                assertEquals(if (replay) "/vod/v2/123456.m3u8" else
                    "/api/v2/channel/hls/fixture_channel.m3u8", source.uri.path)
                assertEquals(validations, fixture.validationCalls)
                assertEquals(refreshes, fixture.refreshCalls)
                assertEquals(media, fixture.mediaCalls)
                assertEquals(1, refreshes)
                val reads = fixture.memory.reads; val owners = fixture.ownerReads
                fixture.memory.forbidden = true
                try { repeat(4) { assertTrue(preparation.canContinue()) } }
                finally { fixture.memory.forbidden = false }
                assertEquals(reads, fixture.memory.reads); assertEquals(owners, fixture.ownerReads)
            }
            preparation.close()
        }
    }

    @Test fun temporaryPeerValidationFailureDoesNotDiscardAnAdmissibleUnchangedViewerGrant() {
        for (failure in listOf("server", "network")) {
            val fixture = Fixture(shared = true); fixture.connect()
            val preparation = fixture.preparation(); fixture.resolve(preparation)
            val bytes = fixture.memory.bytes!!.copyOf()
            val deadline = preparation.acceptanceDeadlineMs
            if (failure == "server") fixture.validateStatus = 500
            else fixture.onValidate = { throw IOException("Fixture network") }
            fixture.peerSession().use { peer ->
                assertEquals(TwitchCatalogSessionState.TEMPORARY_FAILURE, peer.validate(force = true).summary.state)
                val requests = fixture.validationCalls + fixture.refreshCalls
                assertTrue(preparation.checkStored())
                assertTrue(preparation.canContinue())
                assertArrayEquals(bytes, fixture.memory.bytes)
                assertEquals(deadline, preparation.acceptanceDeadlineMs)
                assertEquals(requests, fixture.validationCalls + fixture.refreshCalls)
                assertEquals(0, fixture.refreshCalls); assertEquals(1, fixture.mediaCalls)
            }
            preparation.close()
        }
    }

    @Test fun temporaryPeerValidationFailurePreservesThePreviouslyProvenUnadoptedSuccessor() {
        for (failure in listOf("server", "network")) {
            val fixture = Fixture(shared = true); fixture.connect()
            val preparation = fixture.preparation(); fixture.resolve(preparation)
            val deadline = preparation.acceptanceDeadlineMs
            fixture.peerSession().use { peer ->
                assertNotNull(peer.onUnauthorized(peer.validate().lease!!).lease)
                val bytes = fixture.memory.bytes!!.copyOf()
                if (failure == "server") fixture.validateStatus = 500
                else fixture.onValidate = { throw IOException("Fixture network") }
                assertEquals(TwitchCatalogSessionState.TEMPORARY_FAILURE, peer.validate(force = true).summary.state)
                val requests = fixture.validationCalls + fixture.refreshCalls
                assertTrue(preparation.checkStored())
                assertTrue(preparation.canContinue())
                assertArrayEquals(bytes, fixture.memory.bytes)
                assertEquals(deadline, preparation.acceptanceDeadlineMs)
                assertEquals(requests, fixture.validationCalls + fixture.refreshCalls)
                assertEquals(1, fixture.refreshCalls); assertEquals(1, fixture.mediaCalls)
            }
            preparation.close()
        }
    }

    @Test fun completedConcurrentTemporaryAttemptRetainsOriginalDurableViewerAdmission() {
        for (failure in listOf("server", "network")) {
            val fixture = Fixture(shared = true); fixture.connect()
            val preparation = fixture.preparation(); fixture.resolve(preparation)
            val bytes = fixture.memory.bytes!!.copyOf()
            val peer = fixture.peerSession()
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val admissionThread = AtomicReference<Thread>()
            val executor = Executors.newFixedThreadPool(2)
            try {
                fixture.validateStatus = 500
                fixture.onValidate = {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    if (failure == "network") throw IOException("Fixture network")
                }
                val validation = executor.submit<TwitchCatalogSessionResult> { peer.validate(force = true) }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val admission = executor.submit<Boolean> {
                    admissionThread.set(Thread.currentThread()); preparation.checkStored()
                }
                val waiting = setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING)
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (admissionThread.get()?.state !in waiting && System.nanoTime() < until) Thread.yield()
                assertTrue(admissionThread.get()?.state in waiting); assertFalse(admission.isDone)
                assertTrue(preparation.canContinue())
                release.countDown()
                assertEquals(TwitchCatalogSessionState.TEMPORARY_FAILURE, validation.get(5, TimeUnit.SECONDS).summary.state)
                assertTrue(admission.get(5, TimeUnit.SECONDS))
                assertTrue(preparation.canContinue()); assertArrayEquals(bytes, fixture.memory.bytes)
                assertEquals(2, fixture.validationCalls); assertEquals(0, fixture.refreshCalls)
                assertEquals(1, fixture.mediaCalls); assertEquals(40_000L, preparation.acceptanceDeadlineMs)
            } finally {
                release.countDown(); fixture.onValidate = {}
                preparation.close(); peer.close(); executor.shutdownNow()
            }
        }
    }

    @Test fun unchangedGrantFallbackCannotRestartBudgetAfterOwnerOrProtectedReadDelay() {
        for (stage in listOf("owner", "record")) {
            val fixture = Fixture(shared = true); fixture.connect()
            val preparation = fixture.preparation(); fixture.resolve(preparation)
            val requests = fixture.validationCalls + fixture.refreshCalls
            val reads = fixture.memory.reads
            if (stage == "owner") fixture.onOwner = { fixture.advance(30_000) }
            else fixture.memory.onRead = { fixture.advance(30_000) }
            assertFalse(preparation.checkStored()); assertFalse(preparation.canContinue())
            assertEquals(requests, fixture.validationCalls + fixture.refreshCalls)
            assertEquals(1, fixture.mediaCalls); assertEquals(40_000L, preparation.acceptanceDeadlineMs)
            if (stage == "owner") assertEquals(reads, fixture.memory.reads)
            fixture.onOwner = {}; fixture.memory.onRead = {}
            preparation.close()
        }
    }

    @Test fun successiveCompletedPeerRotationsKeepOnlyTheLatestProvenViewerLease() {
        val fixture = Fixture(shared = true); fixture.connect()
        val preparation = fixture.preparation(); fixture.resolve(preparation)
        fixture.peerSession().use { peer ->
            val first = peer.validate().lease!!
            val second = peer.onUnauthorized(first).lease!!
            assertNotNull(peer.onUnauthorized(second).lease)
            val validations = fixture.validationCalls
            assertTrue(preparation.checkStored())
            assertEquals(validations, fixture.validationCalls)
            assertEquals(2, fixture.refreshCalls)
            assertTrue(preparation.canContinue())
        }
        preparation.close()
    }

    @Test fun viewerWaitsForCommittedPeerRotationProofWithoutStartingAnotherRequest() {
        val fixture = Fixture(shared = true); fixture.connect()
        val preparation = fixture.preparation(); fixture.resolve(preparation)
        val peer = fixture.peerSession()
        val first = peer.validate().lease!!
        val committed = CountDownLatch(1); val release = CountDownLatch(1)
        val checking = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            fixture.memory.onWrite = {
                if (fixture.store.read()?.state == TwitchCatalogGrantState.READY) {
                    committed.countDown(); check(release.await(5, TimeUnit.SECONDS))
                }
            }
            val rotation = executor.submit<TwitchCatalogSessionResult> { peer.onUnauthorized(first) }
            assertTrue(committed.await(2, TimeUnit.SECONDS))
            fixture.onOwner = { checking.countDown() }
            val admission = executor.submit<Boolean> { preparation.checkStored() }
            assertTrue(checking.await(2, TimeUnit.SECONDS))
            assertFalse(admission.isDone)
            assertTrue(preparation.canContinue())
            release.countDown()
            assertNotNull(rotation.get(5, TimeUnit.SECONDS).lease)
            assertTrue(admission.get(5, TimeUnit.SECONDS))
            assertTrue(preparation.canContinue())
            assertEquals(1, fixture.refreshCalls); assertEquals(3, fixture.validationCalls)
            assertEquals(1, fixture.mediaCalls); assertEquals(40_000L, preparation.acceptanceDeadlineMs)
        } finally {
            release.countDown(); fixture.memory.onWrite = {}; fixture.onOwner = {}
            preparation.close(); peer.close(); executor.shutdownNow()
        }
    }

    @Test fun viewerDoesNotAdoptUnprovedRotationReconnectOrForget() {
        for (operation in listOf("unproved", "reconnect", "forget")) {
            val fixture = Fixture(shared = true); fixture.connect()
            val preparation = fixture.preparation(); fixture.resolve(preparation)
            when (operation) {
                "unproved" -> TwitchCatalogSession(fixture.store, { fixture.transport() },
                    { fixture.wall }, { fixture.mono }).use { other ->
                    assertNotNull(other.onUnauthorized(other.validate().lease!!).lease)
                }
                "reconnect" -> fixture.connect(token = "unrelated-replacement")
                "forget" -> fixture.store.forget()
            }
            val requests = fixture.validationCalls + fixture.refreshCalls
            assertFalse(preparation.checkStored()); assertFalse(preparation.canContinue())
            assertEquals(requests, fixture.validationCalls + fixture.refreshCalls)
            preparation.close()
        }
    }

    @Test fun peerRotationDuringAccessExchangeCannotAdmitTheCapturedPredecessorToken() {
        val fixture = Fixture(shared = true); fixture.connect()
        val preparation = fixture.preparation()
        fixture.peerSession().use { peer ->
            val old = peer.validate().lease!!
            fixture.beforeResponse = { assertNotNull(peer.onUnauthorized(old).lease) }
            assertThrows(IOException::class.java) { fixture.resolve(preparation) }
            assertEquals(1, fixture.mediaCalls); assertEquals(1, fixture.refreshCalls)
            assertTrue(fixture.connections.single().disconnected)
            assertFalse(preparation.canContinue()); assertEquals(1, fixture.bindingClosed)
        }
        preparation.close()
    }

    @Test fun uiAdmissionRemainsMemoryOnlyThroughoutSuccessfulWorkerAdoption() {
        val fixture = Fixture(shared = true); fixture.connect()
        val preparation = fixture.preparation(); fixture.resolve(preparation)
        fixture.peerSession().use { peer ->
            assertNotNull(peer.onUnauthorized(peer.validate().lease!!).lease)
            var observed = 0
            fixture.memory.onRead = {
                val reads = fixture.memory.reads; val owners = fixture.ownerReads
                fixture.memory.forbidden = true
                try { assertTrue(preparation.canContinue()); observed++ }
                finally { fixture.memory.forbidden = false }
                assertEquals(reads, fixture.memory.reads); assertEquals(owners, fixture.ownerReads)
            }
            assertTrue(preparation.checkStored())
            fixture.memory.onRead = {}
            assertTrue(observed >= 3)
            assertTrue(preparation.canContinue())
        }
        preparation.close()
    }

    @Test fun lifecycleOrOwnerLossDuringProvedAdoptionCannotPublishViewerLease() {
        for (operation in listOf("inactive", "pause", "close", "owner", "forget")) {
            val fixture = Fixture(shared = true); fixture.connect()
            val preparation = fixture.preparation(); fixture.resolve(preparation)
            fixture.peerSession().use { peer ->
                assertNotNull(peer.onUnauthorized(peer.validate().lease!!).lease)
                var triggered = false
                fixture.memory.onRead = {
                    if (!triggered) {
                        triggered = true
                        when (operation) {
                            "inactive" -> fixture.admission = false
                            "pause" -> fixture.retainedSession.setForeground(false)
                            "close" -> preparation.close()
                            "owner" -> fixture.owner = TwitchCatalogConnectionOwner(fixture.id, "Changed", "Changed", "OTHER")
                            "forget" -> fixture.store.forget()
                        }
                    }
                }
                val requests = fixture.validationCalls + fixture.refreshCalls
                assertFalse(preparation.checkStored()); assertFalse(preparation.canContinue())
                assertTrue(triggered)
                assertEquals(requests, fixture.validationCalls + fixture.refreshCalls)
                fixture.memory.onRead = {}
            }
            preparation.close()
        }
    }

    @Test fun viewerAdoptionCannotOutliveOriginalLeaseDuringProtectedAdmission() {
        for (advance in listOf(10_000L, 30_000L)) {
            val fixture = Fixture(shared = true); fixture.validationSeconds = 10
            fixture.connect(lifetime = 10_000)
            val preparation = fixture.preparation(); fixture.resolve(preparation)
            fixture.validationSeconds = 7200
            fixture.peerSession().use { peer ->
                assertNotNull(peer.onUnauthorized(peer.validate().lease!!).lease)
                var reads = 0
                fixture.memory.onRead = {
                    // Reach the third protected read, after target construction,
                    // so neither UI admission nor final return can extend the old bound.
                    if (++reads == 3) fixture.advance(advance)
                }
                val requests = fixture.validationCalls + fixture.refreshCalls
                assertFalse(preparation.checkStored()); assertFalse(preparation.canContinue())
                assertEquals(requests, fixture.validationCalls + fixture.refreshCalls)
                fixture.memory.onRead = {}
            }
            preparation.close()
        }
    }

    @Test fun cleanupUncertaintyBlocksViewerAdoptionAndLocalAdmission() {
        val fixture = Fixture(shared = true); fixture.connect()
        val preparation = fixture.preparation(); fixture.resolve(preparation)
        fixture.peerSession().use { peer ->
            val old = peer.validate().lease!!
            fixture.onClose = { throw IOException("Fixture cleanup") }
            assertNull(peer.onUnauthorized(old).lease)
            assertFalse(preparation.canContinue()); assertFalse(preparation.checkStored())
            fixture.onClose = {}
        }
        preparation.close()
    }
}
