package net.fstab.tachiai.provider.twitch.catalog

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.provider.twitch.DeviceAuthResponse
import net.fstab.tachiai.provider.twitch.SMART_TV_TWITCH_CLIENT_ID
import org.junit.Assert.*
import org.junit.Test

class TwitchCatalogSessionTest {
    private class Clock {
        var wall = 1_000_000L
        var mono = 10_000L
        fun advance(ms: Long) { wall += ms; mono += ms }
    }
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        var failWrite = false
        var denyRead = false
        var reads = 0
        var writes = 0
        var onRead: () -> Unit = {}
        var onWrite: () -> Unit = {}
        override fun read(): ByteArray? { check(!denyRead); reads++; onRead(); return bytes?.copyOf() }
        override fun write(plaintext: ByteArray) {
            if (failWrite) throw IOException("fixture failure")
            bytes = plaintext.copyOf()
            writes++
            onWrite()
        }
    }
    private fun validation(user: String = "123", client: String = SMART_TV_TWITCH_CLIENT_ID,
        scopes: List<String> = listOf(TWITCH_CATALOG_SCOPE), seconds: Int = 7200) = DeviceAuthResponse(200,
        mapOf("client_id" to client, "user_id" to user, "scopes" to scopes, "expires_in" to seconds))
    private fun token(access: String = "rotated-access", refresh: String = "rotated-refresh") = DeviceAuthResponse(200,
        mapOf("access_token" to access, "refresh_token" to refresh, "token_type" to "bearer",
            "scope" to listOf(TWITCH_CATALOG_SCOPE), "expires_in" to 7200))
    private class Requests {
        val validations = AtomicInteger()
        val refreshes = AtomicInteger()
        val closes = AtomicInteger()
        var validate: (String) -> DeviceAuthResponse = { DeviceAuthResponse(500, emptyMap()) }
        var refresh: (String) -> DeviceAuthResponse = { error("Unexpected refresh") }
        fun transport() = object : TwitchCatalogTransport {
            override fun device(): DeviceAuthResponse = error("Session must not request a device code")
            override fun poll(deviceCode: String): DeviceAuthResponse = error("Session must not poll a device code")
            override fun validate(accessToken: String): DeviceAuthResponse { validations.incrementAndGet(); return this@Requests.validate(accessToken) }
            override fun refresh(refreshToken: String): DeviceAuthResponse { refreshes.incrementAndGet(); return this@Requests.refresh(refreshToken) }
            override fun close() { closes.incrementAndGet() }
        }
    }
    private inner class Fixture {
        val clock = Clock()
        val memory = Memory()
        val instance = UUID.randomUUID().toString()
        val store = TwitchCatalogGrantStore(memory, instance, wallMs = { clock.wall })
        val requests = Requests()
        var ownerMatches = true
        fun session() = TwitchCatalogSession(store, { requests.transport() }, { clock.wall }, { clock.mono }, { ownerMatches })
        fun connect(lifetime: Long = 7_200_000L): TwitchCatalogStoredGrant = store.commitConnection(store.beginConnection(),
            TwitchCatalogReplacement(TwitchCatalogCredentials("initial-access", "initial-refresh", lifetime),
                TwitchCatalogValidation("123", setOf(TWITCH_CATALOG_SCOPE), lifetime), lifetime))!!
        fun connectLocal(capMs: Long = TWITCH_CATALOG_LOCAL_RETENTION_MS, providerMs: Long? = null): TwitchCatalogStoredGrant =
            store.commitConnection(store.beginConnection(), TwitchCatalogReplacement(
                TwitchCatalogCredentials("initial-access", "initial-refresh", minOf(capMs, providerMs ?: Long.MAX_VALUE)),
                TwitchCatalogValidation("123", setOf(TWITCH_CATALOG_SCOPE), providerMs), minOf(capMs, providerMs ?: Long.MAX_VALUE),
                providerExpiresAtMs = providerMs?.let { clock.wall + it },
                localRetentionUntilMs = clock.wall + capMs, savedAtMs = clock.wall))!!
        init { requests.validate = { validation() }; requests.refresh = { token() } }
    }

    @Test fun restartIsUnverifiedAndHourlyForegroundGateValidatesBeforeIssuingAnotherLease() {
        val fixture = Fixture(); fixture.connect(); val session = fixture.session()
        assertEquals(TwitchCatalogSessionState.UNVERIFIED, session.readSummary().state)
        val first = session.validate(); assertEquals(TwitchCatalogSessionState.CONNECTED, first.summary.state)
        assertTrue(session.isCurrent(first.lease!!)); assertEquals("123", first.lease.userId)
        assertEquals(1, fixture.requests.validations.get())
        fixture.clock.advance(TWITCH_CATALOG_VALIDATION_INTERVAL_MS - 1)
        assertSame(first.lease, session.validate().lease); assertEquals(1, fixture.requests.validations.get())
        fixture.clock.advance(1)
        assertFalse(session.isCurrent(first.lease))
        val revalidated = session.validate()
        assertEquals(2, fixture.requests.validations.get()); assertNotSame(first.lease, revalidated.lease)
        val restarted = fixture.session()
        assertEquals(TwitchCatalogSessionState.UNVERIFIED, restarted.readSummary().state)
        assertNotNull(restarted.validate().lease); assertEquals(3, fixture.requests.validations.get())
    }

    @Test fun differentClientRecordNeedsReconnectWithoutNetworkOrImplicitWriteAndAcceptsExplicitReplacement() {
        val fixture = Fixture()
        fixture.memory.bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(1); out.writeUTF(fixture.instance); out.writeUTF("FixturePriorClient123456789")
            }
        }.toByteArray()
        val previous = fixture.memory.bytes!!.copyOf(); val session = fixture.session()
        val summary = session.readSummary()
        assertEquals(TwitchCatalogSessionState.RECONNECT_REQUIRED, summary.state)
        assertEquals(TwitchCatalogAuthFailure.CLIENT_MISMATCH, summary.failure)
        val rejected = session.validate(force = true)
        assertEquals(TwitchCatalogSessionState.RECONNECT_REQUIRED, rejected.summary.state)
        assertEquals(TwitchCatalogAuthFailure.CLIENT_MISMATCH, rejected.summary.failure)
        assertNull(rejected.lease); assertEquals(0, fixture.requests.validations.get())
        assertEquals(0, fixture.requests.refreshes.get()); assertEquals(0, fixture.memory.writes)
        assertArrayEquals(previous, fixture.memory.bytes)
        val attempt = session.beginConnection()
        val approved = TwitchCatalogAuthorizationResult.Approved(
            TwitchCatalogCredentials("connected-access", "connected-refresh", 7200_000L),
            TwitchCatalogValidation("123", setOf(TWITCH_CATALOG_SCOPE), 7200_000L),
            fixture.clock.mono + 7200_000L, fixture.clock.mono)
        val accepted = session.accept(approved, attempt)
        assertEquals(TwitchCatalogSessionState.CONNECTED, accepted.summary.state)
        assertTrue(session.isCurrent(accepted.lease!!))
        assertTrue(String(fixture.memory.bytes!!, Charsets.ISO_8859_1).contains(SMART_TV_TWITCH_CLIENT_ID))
        assertFalse(String(fixture.memory.bytes!!, Charsets.ISO_8859_1).contains("FixturePriorClient123456789"))
    }

    @Test fun sharedRefreshAndForgetInvalidatePreviousConsumerLeaseAndCannotSpendOldRefreshAgain() {
        val fixture = Fixture(); fixture.connect()
        val playback = fixture.session(); val catalog = fixture.session()
        val playbackLease = playback.validate(force = true).lease!!
        val catalogLease = catalog.validate().lease!!
        val renewed = catalog.onUnauthorized(catalogLease).lease!!
        assertFalse(playback.isCurrent(playbackLease)); assertFalse(catalog.isCurrent(catalogLease))
        assertTrue(catalog.isCurrent(renewed)); assertEquals(1, fixture.requests.refreshes.get())
        assertNull(playback.onUnauthorized(playbackLease).lease)
        assertEquals(1, fixture.requests.refreshes.get())
        catalog.invalidate()
        assertFalse(catalog.isLocallyCurrent(renewed)); assertFalse(playback.isLocallyCurrent(playbackLease))
        assertEquals(TwitchCatalogSessionState.MISSING, catalog.forget().summary.state)
        assertEquals(TwitchCatalogSessionState.MISSING, playback.validate().summary.state)
    }

    @Test fun localLeaseGatePerformsNoStoreOwnerOrTransportMaintenance() {
        val fixture = Fixture(); fixture.connect()
        var ownerCalls = 0
        var rejectOwner = false
        val session = TwitchCatalogSession(fixture.store, { fixture.requests.transport() },
            { fixture.clock.wall }, { fixture.clock.mono }, { check(!rejectOwner); ownerCalls++; true })
        val lease = session.validate().lease!!
        val reads = fixture.memory.reads; val owners = ownerCalls
        val validations = fixture.requests.validations.get()
        fixture.memory.denyRead = true; rejectOwner = true
        repeat(4) { assertTrue(session.isLocallyCurrent(lease)) }
        assertEquals(reads, fixture.memory.reads); assertEquals(owners, ownerCalls)
        assertEquals(validations, fixture.requests.validations.get())
        fixture.store.invalidate()
        assertFalse(session.isLocallyCurrent(lease))
    }

    @Test fun localGateRejectsForeignReplacedPausedClosedAndExpiredLeases() {
        val fixture = Fixture(); fixture.connect(); val session = fixture.session()
        val lease = session.validate().lease!!
        val foreign = fixture.session()
        assertFalse(foreign.isLocallyCurrent(lease))
        val replacement = session.validate(force = true).lease!!
        assertNotSame(lease, replacement); assertFalse(session.isLocallyCurrent(lease))
        assertTrue(session.isLocallyCurrent(replacement))
        session.setForeground(false); assertFalse(session.isLocallyCurrent(replacement))
        session.setForeground(true); val resumed = session.validate().lease!!
        fixture.clock.advance(TWITCH_CATALOG_VALIDATION_INTERVAL_MS)
        assertFalse(session.isLocallyCurrent(resumed))
        val renewed = session.validate().lease!!
        session.close(); assertFalse(session.isLocallyCurrent(renewed))
    }

    @Test fun backgroundClosesActiveLeaseAndResumeRequiresValidationButPreservesPendingConnect() {
        val fixture = Fixture(); fixture.connect(); val session = fixture.session(); val lease = session.validate().lease!!
        session.setForeground(false)
        assertFalse(session.isCurrent(lease)); assertEquals(TwitchCatalogSessionState.PAUSED, session.validate().summary.state)
        assertEquals(1, fixture.requests.validations.get())
        session.setForeground(true)
        assertEquals(TwitchCatalogSessionState.UNVERIFIED, session.readSummary().state)
        assertNotNull(session.validate().lease); assertEquals(2, fixture.requests.validations.get())
        val attempt = session.beginConnection()
        session.setForeground(false); session.setForeground(true)
        val approved = TwitchCatalogAuthorizationResult.Approved(
            TwitchCatalogCredentials("connected-access", "connected-refresh", 7200_000L),
            TwitchCatalogValidation("456", setOf(TWITCH_CATALOG_SCOPE), 7200_000L),
            fixture.clock.mono + 7200_000L, fixture.clock.mono)
        assertEquals(TwitchCatalogSessionState.CONNECTED, session.accept(approved, attempt).summary.state)
        assertEquals("456", fixture.store.read()!!.userId)
    }

    @Test fun approvalTimestampCannotResetHourlyFreshnessAndMissingTimestampRequiresValidation() {
        val fixture = Fixture(); val session = fixture.session()
        val attempt = session.beginConnection(); val started = fixture.clock.mono
        fixture.clock.advance(TWITCH_CATALOG_VALIDATION_INTERVAL_MS)
        val approved = TwitchCatalogAuthorizationResult.Approved(
            TwitchCatalogCredentials("connected-access", "connected-refresh", 7200_000L),
            TwitchCatalogValidation("123", setOf(TWITCH_CATALOG_SCOPE), 7200_000L), started + 7200_000L, started)
        val delayed = session.accept(approved, attempt)
        assertEquals(TwitchCatalogSessionState.UNVERIFIED, delayed.summary.state); assertNull(delayed.lease)
        assertNotNull(session.validate().lease)
        val second = session.beginConnection()
        val imported = TwitchCatalogAuthorizationResult.Approved(approved.credentials, approved.validation, fixture.clock.mono + 1000)
        assertEquals(TwitchCatalogSessionState.UNVERIFIED, session.accept(imported, second).summary.state)
    }

    @Test fun monotonicExpiryEndsLeaseAndExpiredSavedTokenRefreshesOnceWithoutOldTokenValidation() {
        val fixture = Fixture(); fixture.connect(1000); val session = fixture.session()
        fixture.requests.validate = { validation(seconds = 1) }
        val lease = session.validate().lease!!
        fixture.clock.advance(1000)
        assertFalse(session.isCurrent(lease))
        fixture.requests.validate = { tokenValue -> assertEquals("rotated-access", tokenValue); validation() }
        val renewed = session.validate()
        assertEquals(TwitchCatalogSessionState.CONNECTED, renewed.summary.state)
        assertEquals(1, fixture.requests.refreshes.get()); assertEquals(2, fixture.requests.validations.get())
        assertEquals("rotated-refresh", fixture.store.read()!!.credentials!!.refreshToken)
        assertEquals("rotated-access", renewed.lease!!.accessToken)
    }

    @Test fun unauthorizedValidationRotatesAndValidatesReplacementAndStaleUnauthorizedCannotSpendAgain() {
        val fixture = Fixture(); fixture.connect(); val session = fixture.session()
        fixture.requests.validate = { value -> if (value == "initial-access") DeviceAuthResponse(401, emptyMap()) else validation() }
        fixture.requests.refresh = { value -> assertEquals("initial-refresh", value); token() }
        val result = session.validate()
        assertEquals(TwitchCatalogSessionState.CONNECTED, result.summary.state)
        assertEquals(1, fixture.requests.refreshes.get()); assertEquals(2, fixture.requests.validations.get())
        val first = result.lease!!
        fixture.requests.refresh = { value -> assertEquals("rotated-refresh", value); token("second-access", "second-refresh") }
        val second = session.onUnauthorized(first)
        assertEquals("second-access", second.lease!!.accessToken)
        assertEquals(TwitchCatalogSessionState.SUPERSEDED, session.onUnauthorized(first).summary.state)
        assertEquals(2, fixture.requests.refreshes.get())
        assertTrue(session.isCurrent(second.lease)) // Late 401 cannot discard the new lease.
    }

    @Test fun omittedRefreshLifetimeRotatesOnlyAfterPositiveValidationAndStoresFiniteRemainingDuration() {
        val fixture = Fixture(); val previous = fixture.connect(1000); val session = fixture.session()
        fixture.clock.advance(1000)
        fixture.requests.refresh = {
            fixture.clock.advance(1000)
            DeviceAuthResponse(200, token().fields - "expires_in")
        }
        fixture.requests.validate = { access ->
            assertEquals("rotated-access", access); fixture.clock.advance(2000); validation(seconds = 120)
        }
        val result = session.validate()
        assertEquals(TwitchCatalogSessionState.CONNECTED, result.summary.state)
        assertEquals(1, fixture.requests.refreshes.get()); assertEquals(1, fixture.requests.validations.get())
        val saved = fixture.store.read()!!
        assertNotEquals(previous.generation, saved.generation)
        assertEquals(118_000L, saved.credentials!!.expiresInMs)
        assertEquals(fixture.clock.wall + 118_000L, saved.expiresAtMs)
        assertEquals(fixture.clock.mono + 118_000L, result.lease!!.deadlineMs)
        assertFalse(fixture.store.isStoredCurrent(previous)); assertTrue(session.isCurrent(result.lease))
    }

    @Test fun omittedRefreshBudgetRejectsBeforeValidationOrAfterLateValidationAndCannotRetryConsumedPair() {
        listOf(true, false).forEach { delayRefresh ->
            val fixture = Fixture(); fixture.connect(1000); val session = fixture.session()
            fixture.clock.advance(1000)
            fixture.requests.refresh = {
                if (delayRefresh) fixture.clock.advance(TWITCH_CATALOG_UNKNOWN_GRANT_VALIDATION_MS)
                DeviceAuthResponse(200, token().fields - "expires_in")
            }
            fixture.requests.validate = {
                if (!delayRefresh) fixture.clock.advance(TWITCH_CATALOG_UNKNOWN_GRANT_VALIDATION_MS)
                validation()
            }
            val result = session.validate()
            assertEquals(TwitchCatalogSessionState.RECONNECT_REQUIRED, result.summary.state)
            assertEquals(TwitchCatalogAuthFailure.EXPIRED, result.summary.failure); assertNull(result.lease)
            assertEquals(if (delayRefresh) 0 else 1, fixture.requests.validations.get())
            assertEquals(1, fixture.requests.refreshes.get())
            assertEquals(TwitchCatalogGrantState.REFRESHING, fixture.store.read()!!.state)
            assertNull(fixture.store.read()!!.credentials)
            assertNull(session.validate().lease); assertNull(fixture.session().validate().lease)
            assertEquals(1, fixture.requests.refreshes.get())
        }
    }

    @Test fun omittedRefreshCannotStoreZeroValidationOrMissingRefreshOrMalformedTokenLifetime() {
        val cases = listOf(
            DeviceAuthResponse(200, token().fields - "expires_in") to validation(seconds = 0),
            DeviceAuthResponse(200, (token().fields - "expires_in") + ("refresh_token" to null)) to validation(),
            DeviceAuthResponse(200, token().fields + ("expires_in" to null)) to validation(),
            DeviceAuthResponse(200, token().fields + ("expires_in" to 0)) to validation(),
        )
        cases.forEachIndexed { index, (tokenResponse, validationResponse) ->
            val fixture = Fixture(); fixture.connect(1000); val session = fixture.session()
            fixture.clock.advance(1000)
            fixture.requests.refresh = { tokenResponse }; fixture.requests.validate = { validationResponse }
            val result = session.validate()
            assertEquals(TwitchCatalogSessionState.RECONNECT_REQUIRED, result.summary.state); assertNull(result.lease)
            assertEquals(if (index == 0) 1 else 0, fixture.requests.validations.get())
            assertEquals(1, fixture.requests.refreshes.get())
            assertEquals(TwitchCatalogGrantState.REFRESHING, fixture.store.read()!!.state)
            assertNull(fixture.store.read()!!.credentials)
            assertNull(session.validate().lease); assertNull(fixture.session().validate().lease)
            assertEquals(1, fixture.requests.refreshes.get())
        }
    }

    @Test fun temporaryValidationFailureSuspendsLeaseWithoutDeletingOrClaimingRevocation() {
        val fixture = Fixture(); fixture.connect(); val session = fixture.session(); val old = session.validate().lease!!
        val original = fixture.memory.bytes!!.copyOf()
        fixture.requests.validate = { throw IOException("fixture lost validation") }
        val result = session.validate(force = true)
        assertEquals(TwitchCatalogSessionState.TEMPORARY_FAILURE, result.summary.state)
        assertEquals(TwitchCatalogAuthFailure.NETWORK, result.summary.failure); assertNull(result.lease)
        assertFalse(session.isCurrent(old)); assertArrayEquals(original, fixture.memory.bytes)
        assertEquals(0, fixture.requests.refreshes.get())
        fixture.requests.validate = { validation() }
        assertNotNull(session.validate().lease)
    }

    @Test fun userClientAndScopeMismatchRequireReconnectWithoutUsingRefreshOrSubstitutingAccount() {
        listOf(validation(user = "456"), validation(client = "other-client"), validation(scopes = emptyList())).forEach { response ->
            val fixture = Fixture(); fixture.connect(); val session = fixture.session()
            fixture.requests.validate = { response }
            val result = session.validate()
            assertEquals(TwitchCatalogSessionState.RECONNECT_REQUIRED, result.summary.state)
            assertNull(result.lease); assertEquals(0, fixture.requests.refreshes.get())
            assertEquals(TwitchCatalogGrantState.RECONNECT, fixture.store.read()!!.state)
            assertNull(fixture.store.read()!!.credentials)
        }
    }

    @Test fun lostRefreshResponseAndFailedRotationCannotRetryOnThisSessionOrRestart() {
        listOf(false, true).forEach { failCommit ->
            val fixture = Fixture(); fixture.connect(); val session = fixture.session()
            fixture.requests.validate = { value -> if (value == "initial-access") DeviceAuthResponse(401, emptyMap()) else validation() }
            fixture.requests.refresh = {
                if (!failCommit) throw IOException("fixture lost consumed response")
                fixture.memory.failWrite = true
                token()
            }
            val result = session.validate()
            assertEquals(if (failCommit) TwitchCatalogSessionState.STORAGE_FAILURE else TwitchCatalogSessionState.RECONNECT_REQUIRED,
                result.summary.state)
            assertNull(result.lease); fixture.memory.failWrite = false
            assertEquals(TwitchCatalogGrantState.REFRESHING, fixture.store.read()!!.state)
            assertNull(session.validate().lease); assertNull(fixture.session().validate().lease)
            assertEquals(1, fixture.requests.refreshes.get())
        }
    }

    @Test fun ownerChangeBeforeRequestDeniesTransportAndOwnerChangeDuringRefreshDeniesLatePair() {
        val fixture = Fixture(); fixture.connect(); val session = fixture.session()
        fixture.ownerMatches = false
        assertNull(session.validate().lease); assertEquals(0, fixture.requests.validations.get())
        fixture.ownerMatches = true
        fixture.requests.validate = { DeviceAuthResponse(401, emptyMap()) }
        fixture.requests.refresh = { fixture.ownerMatches = false; token() }
        assertNull(session.validate().lease)
        assertEquals(1, fixture.requests.refreshes.get()); assertEquals(1, fixture.requests.validations.get())
        assertEquals(TwitchCatalogGrantState.REFRESHING, fixture.store.read()!!.state)
    }

    @Test fun forgetInvalidatesOtherControllerAndStaleConnectCannotResurrectAfterClearOrReplacement() {
        val fixture = Fixture(); fixture.connect(); val first = fixture.session(); val second = fixture.session()
        val otherLease = second.validate().lease!!
        val attempt = first.beginConnection()
        first.invalidate()
        assertFalse(second.isCurrent(otherLease))
        assertEquals(TwitchCatalogSessionState.MISSING, first.forget().summary.state)
        val approved = TwitchCatalogAuthorizationResult.Approved(TwitchCatalogCredentials("late", "late-refresh", 1000),
            TwitchCatalogValidation("123", setOf(TWITCH_CATALOG_SCOPE), 1000), fixture.clock.mono + 1000, fixture.clock.mono)
        assertNull(first.accept(approved, attempt).lease)
        assertEquals(TwitchCatalogGrantState.CLEARED, fixture.store.read()!!.state)
        val previous = first.beginConnection(); val newest = second.beginConnection()
        assertNull(first.accept(approved, previous).lease)
        assertNotNull(second.accept(approved, newest).lease)
    }

    @Test fun failedAcceptedClearRemainsBlockedUntilRetryAndDoesNotAffectDifferentInstance() {
        val fixture = Fixture(); fixture.connect(); val session = fixture.session(); val old = session.validate().lease!!
        val other = Fixture(); other.connect(); val otherSession = other.session(); val otherLease = otherSession.validate().lease!!
        session.invalidate(); fixture.memory.failWrite = true
        assertEquals(TwitchCatalogSessionState.STORAGE_FAILURE, session.forget().summary.state)
        assertFalse(session.isCurrent(old)); assertNull(fixture.session().validate().lease)
        assertTrue(otherSession.isCurrent(otherLease))
        fixture.memory.failWrite = false
        assertEquals(TwitchCatalogSessionState.MISSING, session.forget().summary.state)
    }

    @Test fun backgroundDuringValidateClosesTransportAndRejectsCompletionWithoutChangingSavedPair() {
        val fixture = Fixture(); fixture.connect(); val original = fixture.memory.bytes!!.copyOf()
        val session = fixture.session(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val workers = Executors.newSingleThreadExecutor()
        fixture.requests.validate = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); validation() }
        try {
            val work = workers.submit<TwitchCatalogSessionResult> { session.validate() }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); session.setForeground(false)
            assertTrue(fixture.requests.closes.get() >= 1)
            release.countDown()
            assertEquals(TwitchCatalogSessionState.PAUSED, work.get(5, TimeUnit.SECONDS).summary.state)
            assertArrayEquals(original, fixture.memory.bytes)
            session.setForeground(true); fixture.requests.validate = { validation() }
            assertNotNull(session.validate().lease)
        } finally { release.countDown(); workers.shutdownNow() }
    }

    @Test fun backgroundDuringRefreshLeavesNoOldPairToRetryAndReconnectRequiredOnResume() {
        val fixture = Fixture(); fixture.connect(); val session = fixture.session()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val workers = Executors.newSingleThreadExecutor()
        fixture.requests.validate = { DeviceAuthResponse(401, emptyMap()) }
        fixture.requests.refresh = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); token() }
        try {
            val work = workers.submit<TwitchCatalogSessionResult> { session.validate() }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); session.setForeground(false)
            assertTrue(fixture.requests.closes.get() >= 2)
            release.countDown(); assertNull(work.get(5, TimeUnit.SECONDS).lease)
            session.setForeground(true)
            assertEquals(TwitchCatalogSessionState.RECONNECT_REQUIRED, session.validate().summary.state)
            assertEquals(TwitchCatalogGrantState.REFRESHING, fixture.store.read()!!.state)
            assertEquals(1, fixture.requests.refreshes.get())
        } finally { release.countDown(); workers.shutdownNow() }
    }

    @Test fun corruptionAndClockRollbackNeverIssueLeaseOrOverwriteUnrelatedDataAndAllSecretObjectsAreRedacted() {
        val fixture = Fixture(); fixture.connect(); val session = fixture.session(); val lease = session.validate().lease!!
        listOf(lease, fixture.store.read()!!, fixture.store.read()!!.credentials!!, session, fixture.store).forEach { value ->
            assertFalse(value.toString().contains("initial-access")); assertFalse(value.toString().contains("initial-refresh"))
            assertFalse(value.toString().contains("123"))
        }
        session.close(); assertFalse(session.isCurrent(lease))
        fixture.memory.bytes = byteArrayOf(1, 2, 3); val original = fixture.memory.bytes!!.copyOf()
        assertEquals(TwitchCatalogSessionState.STORAGE_FAILURE, fixture.session().validate().summary.state)
        assertArrayEquals(original, fixture.memory.bytes)
        val rollback = Fixture(); rollback.connect(); rollback.clock.wall--
        assertEquals(TwitchCatalogSessionState.RECONNECT_REQUIRED, rollback.session().validate().summary.state)
        assertEquals(0, rollback.requests.validations.get())
    }

    @Test fun delayedMismatchFromOldGenerationDoesNotMisclassifyOrInvalidateOtherControllersNewGrant() {
        val fixture = Fixture(); fixture.connect(); val first = fixture.session(); val second = fixture.session()
        val secondLease = second.validate().lease!!
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val workers = Executors.newSingleThreadExecutor()
        fixture.requests.validate = { value ->
            if (value == "initial-access") {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); validation(user = "mismatched-user")
            } else validation()
        }
        try {
            val oldValidation = workers.submit<TwitchCatalogSessionResult> { first.validate() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val renewed = second.onUnauthorized(secondLease)
            assertNotNull(renewed.lease)
            release.countDown()
            assertEquals(TwitchCatalogSessionState.SUPERSEDED, oldValidation.get(5, TimeUnit.SECONDS).summary.state)
            assertTrue(second.isCurrent(renewed.lease!!))
            assertEquals(TwitchCatalogGrantState.READY, fixture.store.read()!!.state)
        } finally { release.countDown(); workers.shutdownNow() }
    }

    @Test fun unknownProviderExpiryRequiresFreshHourlyAndResumeValidationWithoutWritingOrExtendingRetention() {
        val fixture = Fixture(); val original = fixture.connectLocal()
        fixture.requests.validate = { validation(seconds = 0) }
        val bytes = fixture.memory.bytes!!.copyOf(); val writes = fixture.memory.writes
        val session = fixture.session(); val first = session.validate().lease!!
        assertEquals(fixture.clock.mono + TWITCH_CATALOG_VALIDATION_INTERVAL_MS, first.deadlineMs)
        fixture.clock.advance(TWITCH_CATALOG_VALIDATION_INTERVAL_MS)
        assertFalse(session.isCurrent(first)); assertNotNull(session.validate().lease)
        session.setForeground(false); session.setForeground(true); assertNotNull(session.validate().lease)
        assertNotNull(session.validate(force = true).lease)
        assertEquals(4, fixture.requests.validations.get()); assertEquals(0, fixture.requests.refreshes.get())
        assertEquals(writes, fixture.memory.writes); assertArrayEquals(bytes, fixture.memory.bytes)
        val current = fixture.store.read()!!
        assertEquals(original.savedAtMs, current.savedAtMs); assertEquals(original.localRetentionUntilMs, current.localRetentionUntilMs)
        assertNull(current.providerExpiresAtMs)
    }

    @Test fun positiveValidationDurablyTightensUnknownTokenAndBothConsumersObeyLatestBoundWithoutPairInvalidation() {
        val fixture = Fixture(); val original = fixture.connectLocal()
        fixture.requests.validate = { validation(seconds = 0) }
        val playback = fixture.session(); val playbackLease = playback.validate().lease!!
        val catalog = fixture.session()
        fixture.clock.advance(1000); fixture.requests.validate = { validation(seconds = 60) }
        val tightened = catalog.validate(force = true).lease!!
        val grant = fixture.store.read()!!
        assertEquals(original.generation, grant.generation)
        assertEquals(original.savedAtMs, grant.savedAtMs); assertEquals(original.localRetentionUntilMs, grant.localRetentionUntilMs)
        assertEquals(fixture.clock.wall + 60_000, grant.providerExpiresAtMs)
        assertTrue(playback.isCurrent(playbackLease)); assertTrue(catalog.isCurrent(tightened))
        assertEquals(0, fixture.requests.refreshes.get())
        fixture.requests.validate = { validation(seconds = 120) }
        catalog.validate(force = true)
        assertEquals(grant.providerExpiresAtMs, fixture.store.read()!!.providerExpiresAtMs)
        assertEquals(grant.generation, fixture.store.read()!!.generation)
        val writes = fixture.memory.writes
        fixture.requests.validate = { validation(seconds = 0) }; catalog.validate(force = true)
        assertEquals(writes, fixture.memory.writes)
        assertEquals(grant.providerExpiresAtMs, fixture.store.read()!!.providerExpiresAtMs)
        fixture.clock.advance(60_000)
        assertFalse(playback.isCurrent(playbackLease)); assertFalse(catalog.isCurrent(tightened))
        assertTrue(fixture.store.isStoredPairCurrent(original))
        val rotated = playback.onUnauthorized(playbackLease).lease!!
        assertEquals(1, fixture.requests.refreshes.get()); assertNotEquals(grant.generation, rotated.grant.generation)
        assertNull(catalog.onUnauthorized(tightened).lease); assertEquals(1, fixture.requests.refreshes.get())
    }

    @Test fun refreshMayReplaceProviderBoundButNeverOriginalRetentionOrClockAnchor() {
        val fixture = Fixture(); val original = fixture.connectLocal(providerMs = 1000)
        fixture.clock.advance(1000)
        fixture.requests.refresh = { DeviceAuthResponse(200, token().fields - "expires_in") }
        fixture.requests.validate = { validation(seconds = 0) }
        val session = fixture.session(); val unknown = session.validate().lease!!
        var grant = fixture.store.read()!!
        assertNull(grant.providerExpiresAtMs); assertNotEquals(original.generation, grant.generation)
        assertEquals(original.localRetentionUntilMs, grant.localRetentionUntilMs); assertEquals(original.savedAtMs, grant.savedAtMs)
        fixture.clock.advance(1000)
        fixture.requests.refresh = { token("next-access", "next-refresh") }
        fixture.requests.validate = { validation(seconds = 60) }
        val rotated = session.onUnauthorized(unknown).lease!!
        grant = fixture.store.read()!!
        assertEquals(fixture.clock.wall + 60_000, grant.providerExpiresAtMs)
        assertEquals(original.localRetentionUntilMs, grant.localRetentionUntilMs); assertEquals(original.savedAtMs, grant.savedAtMs)
        assertEquals("next-access", rotated.accessToken)
        session.invalidate(); assertFalse(session.isCurrent(rotated)); session.forget()
        assertEquals(TwitchCatalogGrantState.CLEARED, fixture.store.read()!!.state)
    }

    @Test fun expiredRetentionBlocksStartupCachedLeaseFreshValidationAndStaleUnauthorizedWithoutAnyHttp() {
        listOf(false, true).forEach { cached ->
            val fixture = Fixture(); fixture.connectLocal(capMs = 1000)
            fixture.requests.validate = { validation(seconds = 0) }
            val session = fixture.session(); val value = if (cached) session.validate().lease else null
            val requests = fixture.requests.validations.get()
            fixture.clock.advance(1000)
            if (value != null) {
                assertFalse(session.isCurrent(value)); assertFalse(session.isLocallyCurrent(value))
                val stale = session.onUnauthorized(value)
                assertEquals(TwitchCatalogSessionState.RECONNECT_REQUIRED, stale.summary.state)
                assertEquals(TwitchCatalogAuthFailure.EXPIRED, stale.summary.failure)
            } else {
                val expired = session.validate(force = true)
                assertEquals(TwitchCatalogSessionState.RECONNECT_REQUIRED, expired.summary.state)
                assertEquals(TwitchCatalogAuthFailure.EXPIRED, expired.summary.failure)
            }
            assertNull(session.validate().lease); assertNull(fixture.session().validate().lease)
            assertEquals(requests, fixture.requests.validations.get()); assertEquals(0, fixture.requests.refreshes.get())
        }
    }

    @Test fun delayedValidationAndNetworkGateFailureAtRetentionDeadlineReportExpiredAndPreventFurtherRequests() {
        listOf(false, true).forEach { networkFailure ->
            val fixture = Fixture(); fixture.connectLocal(capMs = 1000)
            fixture.requests.validate = {
                fixture.clock.advance(1000)
                if (networkFailure) throw IOException("fixture route gate")
                validation(seconds = 0)
            }
            val session = fixture.session(); val expired = session.validate()
            assertNull(expired.lease); assertEquals(TwitchCatalogSessionState.RECONNECT_REQUIRED, expired.summary.state)
            assertEquals(TwitchCatalogAuthFailure.EXPIRED, expired.summary.failure)
            assertNull(session.validate().lease)
            assertEquals(1, fixture.requests.validations.get()); assertEquals(0, fixture.requests.refreshes.get())
        }
    }

    @Test fun suppliedRequestGateClosesAfterPreparationConsumesCapAndNoProviderRequestIsMade() {
        val fixture = Fixture(); fixture.connectLocal(capMs = 1000)
        var gate: (() -> Boolean)? = null
        val session = TwitchCatalogSession(fixture.store, { canRequest ->
            gate = canRequest
            assertTrue(canRequest())
            fixture.clock.advance(1000)
            assertFalse(canRequest())
            fixture.requests.transport()
        }, { fixture.clock.wall }, { fixture.clock.mono })
        val expired = session.validate()
        assertEquals(TwitchCatalogAuthFailure.EXPIRED, expired.summary.failure)
        assertEquals(TwitchCatalogSessionState.RECONNECT_REQUIRED, expired.summary.state)
        assertEquals(0, fixture.requests.validations.get()); assertEquals(1, fixture.requests.closes.get())
        assertFalse(gate!!())
    }

    @Test fun refreshCrossingCapOrThirtySecondBudgetLeavesConsumedMarkerAndNeverRetriesOldPair() {
        listOf(true, false).forEach { capExpires ->
            val fixture = Fixture(); fixture.connectLocal(capMs = if (capExpires) 1000 else TWITCH_CATALOG_LOCAL_RETENTION_MS)
            fixture.requests.validate = { validation(seconds = 0) }
            val session = fixture.session(); val old = session.validate().lease!!
            fixture.requests.refresh = {
                fixture.clock.advance(if (capExpires) 1000 else TWITCH_CATALOG_UNKNOWN_GRANT_VALIDATION_MS)
                DeviceAuthResponse(200, token().fields - "expires_in")
            }
            val expired = session.onUnauthorized(old)
            assertEquals(TwitchCatalogSessionState.RECONNECT_REQUIRED, expired.summary.state)
            assertEquals(TwitchCatalogAuthFailure.EXPIRED, expired.summary.failure)
            assertEquals(1, fixture.requests.refreshes.get()); assertEquals(1, fixture.requests.validations.get())
            assertNull(fixture.store.read()!!.credentials)
            assertNull(session.validate().lease); assertNull(fixture.session().validate().lease)
            assertEquals(1, fixture.requests.refreshes.get())
        }
    }

    @Test fun originalCapRejectsWallClockRollbackAndOverridesStickyNetworkFailure() {
        val rollback = Fixture(); rollback.connectLocal(); rollback.clock.wall--
        val denied = rollback.session().validate()
        assertEquals(TwitchCatalogAuthFailure.EXPIRED, denied.summary.failure)
        assertEquals(0, rollback.requests.validations.get())
        val fixture = Fixture(); fixture.connectLocal(capMs = 1000)
        fixture.requests.validate = { DeviceAuthResponse(500, emptyMap()) }
        val session = fixture.session()
        assertEquals(TwitchCatalogSessionState.TEMPORARY_FAILURE, session.validate().summary.state)
        fixture.clock.advance(1000)
        assertEquals(TwitchCatalogAuthFailure.EXPIRED, session.readSummary().failure)
        assertEquals(TwitchCatalogSessionState.RECONNECT_REQUIRED, session.readSummary().state)
        assertNull(session.validate().lease); assertEquals(1, fixture.requests.validations.get())
    }

    @Test fun initialAcceptanceAbsoluteBoundsSurviveWriteDelayAndExpiredWriteCannotPublishGrant() {
        listOf(500L, 1000L).forEach { delay ->
            val fixture = Fixture(); val session = fixture.session(); val attempt = session.beginConnection()
            val saved = fixture.clock.wall; val started = fixture.clock.mono
            val approved = TwitchCatalogAuthorizationResult.Approved(TwitchCatalogCredentials("connected-access", "connected-refresh", 1000),
                TwitchCatalogValidation("123", setOf(TWITCH_CATALOG_SCOPE), null), started + 1000, started,
                localRetentionDeadlineMs = started + 1000, providerDeadlineMs = null)
            fixture.memory.onWrite = { fixture.memory.onWrite = {}; fixture.clock.advance(delay) }
            val accepted = session.accept(approved, attempt)
            if (delay == 500L) {
                assertEquals(TwitchCatalogSessionState.CONNECTED, accepted.summary.state)
                assertEquals(saved + 1000, fixture.store.read()!!.localRetentionUntilMs)
                assertEquals(started + 1000, accepted.lease!!.deadlineMs)
            } else {
                assertEquals(TwitchCatalogAuthFailure.EXPIRED, accepted.summary.failure); assertNull(accepted.lease)
                assertEquals(TwitchCatalogGrantState.RECONNECT, fixture.store.read()!!.state)
                assertNull(fixture.store.read()!!.credentials)
            }
        }
    }

    @Test fun staleUnauthorizedWaitingForStoreLockCannotRefreshAfterCapExpires() {
        val fixture = Fixture(); fixture.connectLocal(capMs = 1000)
        fixture.requests.validate = { validation(seconds = 0) }
        val checkedOwner = CountDownLatch(1); var waitingUnauthorized = false
        val session = TwitchCatalogSession(fixture.store, { fixture.requests.transport() },
            { fixture.clock.wall }, { fixture.clock.mono }, {
                if (waitingUnauthorized) checkedOwner.countDown()
                true
            })
        val old = session.validate().lease!!
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        fixture.memory.onRead = {
            fixture.memory.onRead = {}; entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
        }
        try {
            val reader = workers.submit<TwitchCatalogStoredGrant?> { fixture.store.read() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            waitingUnauthorized = true
            val unauthorized = workers.submit<TwitchCatalogSessionResult> { session.onUnauthorized(old) }
            assertTrue(checkedOwner.await(5, TimeUnit.SECONDS))
            fixture.clock.advance(1000); release.countDown(); reader.get(5, TimeUnit.SECONDS)
            val denied = unauthorized.get(5, TimeUnit.SECONDS)
            assertEquals(TwitchCatalogSessionState.RECONNECT_REQUIRED, denied.summary.state)
            assertEquals(TwitchCatalogAuthFailure.EXPIRED, denied.summary.failure)
            assertEquals(0, fixture.requests.refreshes.get()); assertEquals(1, fixture.requests.validations.get())
        } finally { release.countDown(); workers.shutdownNow() }
    }

    @Test fun versionOnePositiveGrantDoesNotGainZeroValidationEntitlementOrLocalRetention() {
        val fixture = Fixture(); val generation = UUID.randomUUID().toString()
        fixture.memory.bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(1)
                listOf(fixture.instance, SMART_TV_TWITCH_CLIENT_ID, TWITCH_CATALOG_SCOPE,
                    generation, "READY", "123").forEach(out::writeUTF)
                out.writeLong(fixture.clock.wall); out.writeLong(fixture.clock.wall + 7_200_000)
                out.writeUTF("initial-access"); out.writeUTF("initial-refresh")
            }
        }.toByteArray()
        val bytes = fixture.memory.bytes!!.copyOf(); val session = fixture.session()
        fixture.requests.validate = { validation() }
        assertNotNull(session.validate().lease)
        assertEquals(0, fixture.memory.writes); assertArrayEquals(bytes, fixture.memory.bytes)
        assertNull(fixture.store.read()!!.localRetentionUntilMs)
        fixture.requests.validate = { validation(seconds = 0) }
        val rejected = session.validate(force = true)
        assertEquals(TwitchCatalogSessionState.RECONNECT_REQUIRED, rejected.summary.state)
        assertEquals(TwitchCatalogAuthFailure.EXPIRED, rejected.summary.failure)
        assertNull(rejected.lease); assertEquals(0, fixture.requests.refreshes.get())
        assertEquals(TwitchCatalogGrantState.RECONNECT, fixture.store.read()!!.state)
    }

    @Test fun failedExpiryTombstoneDuringLateValidationOrConsumedRefreshReturnsStorageFailureAndNoFurtherHttp() {
        listOf(false, true).forEach { refreshing ->
            val fixture = Fixture(); fixture.connectLocal(capMs = 1000)
            fixture.requests.validate = { validation(seconds = 0) }
            val session = fixture.session()
            val result = if (refreshing) {
                val old = session.validate().lease!!
                fixture.requests.refresh = {
                    fixture.clock.advance(1000); fixture.memory.failWrite = true
                    throw IOException("fixture lost refresh response")
                }
                session.onUnauthorized(old)
            } else {
                fixture.requests.validate = {
                    fixture.clock.advance(1000); fixture.memory.failWrite = true
                    throw IOException("fixture route gate expired")
                }
                session.validate()
            }
            assertEquals(TwitchCatalogSessionState.STORAGE_FAILURE, result.summary.state)
            assertNull(result.lease)
            val validations = fixture.requests.validations.get(); val refreshes = fixture.requests.refreshes.get()
            assertNull(session.validate().lease); assertNull(fixture.session().validate().lease)
            assertEquals(validations, fixture.requests.validations.get()); assertEquals(refreshes, fixture.requests.refreshes.get())
        }
    }

    private fun assertPairedPositiveValidationTightening(providerMs: Long?) {
        val fixture = Fixture(); val original = fixture.connectLocal(providerMs = providerMs)
        fixture.requests.validate = { validation(seconds = 120) }
        val first = fixture.session(); val second = fixture.session()
        val firstLease = first.validate(force = true).lease!!
        fixture.clock.advance(10)
        fixture.requests.validate = { validation(seconds = 119) }
        val secondLease = second.validate(force = true).lease!!
        val tightened = fixture.store.read()!!
        assertEquals(original.generation, tightened.generation)
        assertEquals(firstLease.grant.generation, secondLease.grant.generation)
        assertEquals(fixture.clock.wall + 119_000, tightened.providerExpiresAtMs)
        assertTrue(first.isCurrent(firstLease)); assertTrue(second.isCurrent(secondLease))
        fixture.requests.validate = { validation(seconds = 0) }
        val writes = fixture.memory.writes; val restarted = fixture.session()
        val restartLease = restarted.validate().lease!!
        assertEquals(tightened.providerExpiresAtMs, fixture.store.read()!!.providerExpiresAtMs)
        assertEquals(tightened.expiresAtMs, restartLease.grant.expiresAtMs)
        assertEquals(writes, fixture.memory.writes)
        fixture.clock.advance(118_999)
        assertTrue(first.isCurrent(firstLease)); assertTrue(second.isCurrent(secondLease))
        fixture.clock.advance(1)
        assertFalse(first.isCurrent(firstLease)); assertFalse(second.isCurrent(secondLease))
        assertFalse(restarted.isCurrent(restartLease))
        // The original pair can refresh after the provider bound, while its
        // unchanged local cap still admits it. Rotation then rejects old pairs.
        assertTrue(fixture.store.isStoredPairCurrent(firstLease.grant))
        val rotated = first.onUnauthorized(firstLease).lease!!
        assertNotEquals(tightened.generation, rotated.grant.generation)
        assertFalse(fixture.store.isStoredPairCurrent(secondLease.grant))
        assertNull(second.onUnauthorized(secondLease).lease)
        assertEquals(1, fixture.requests.refreshes.get()); assertTrue(first.isCurrent(rotated))
    }

    @Test fun twoPositiveConsumersOfInitiallyUnknownPairSurviveRoundingTighteningUntilDurableBound() =
        assertPairedPositiveValidationTightening(null)

    @Test fun twoPositiveConsumersOfKnownPairSurviveRoundingTighteningUntilDurableBound() =
        assertPairedPositiveValidationTightening(120_000)

    @Test fun ownTighteningWriteCrossingProviderOrLocalDeadlineReportsExpiredAndPreservesExternalNewPair() {
        listOf(false, true).forEach { localExpires ->
            val fixture = Fixture(); fixture.connectLocal(capMs = if (localExpires) 1000 else TWITCH_CATALOG_LOCAL_RETENTION_MS)
            fixture.requests.validate = { validation(seconds = 1) }
            fixture.memory.onWrite = { fixture.memory.onWrite = {}; fixture.clock.advance(1000) }
            val expired = fixture.session().validate()
            assertEquals(TwitchCatalogSessionState.RECONNECT_REQUIRED, expired.summary.state)
            assertEquals(TwitchCatalogAuthFailure.EXPIRED, expired.summary.failure); assertNull(expired.lease)
            assertEquals(TwitchCatalogGrantState.RECONNECT, fixture.store.read()!!.state)
            assertNull(fixture.store.read()!!.credentials)
        }
        // Replace the pair while the expired tightening operation is unwinding.
        val fixture = Fixture(); fixture.connectLocal(); fixture.requests.validate = { validation(seconds = 1) }
        var replaced = false
        fixture.memory.onWrite = {
            if (!replaced) {
                replaced = true; fixture.memory.onWrite = {}
                fixture.clock.advance(1000)
                fixture.connectLocal()
            }
        }
        val result = fixture.session().validate()
        assertNull(result.lease)
        assertEquals(TwitchCatalogGrantState.READY, fixture.store.read()!!.state)
        assertEquals(fixture.clock.wall + TWITCH_CATALOG_LOCAL_RETENTION_MS, fixture.store.read()!!.localRetentionUntilMs)
        assertNotNull(fixture.store.read()!!.credentials)
    }

    @Test fun sessionCanForgetItsOwnExpiryReconnectMarkerButCannotForgetExternalReplacement() {
        listOf(false, true).forEach { externalReplacement ->
            val fixture = Fixture(); fixture.connectLocal(capMs = 1000)
            fixture.requests.validate = { validation(seconds = 0) }
            val session = fixture.session(); assertNotNull(session.validate().lease)
            fixture.clock.advance(1000)
            val expired = session.validate()
            assertEquals(TwitchCatalogSessionState.RECONNECT_REQUIRED, expired.summary.state)
            assertEquals(TwitchCatalogAuthFailure.EXPIRED, expired.summary.failure)
            val marker = fixture.store.read()!!
            assertEquals(TwitchCatalogGrantState.RECONNECT, marker.state)
            val newer = if (externalReplacement) fixture.connectLocal() else null
            val bytes = fixture.memory.bytes!!.copyOf()
            session.invalidate()
            val forgotten = session.forget()
            if (newer == null) {
                assertEquals(TwitchCatalogSessionState.MISSING, forgotten.summary.state)
                assertEquals(TwitchCatalogGrantState.CLEARED, fixture.store.read()!!.state)
            } else {
                assertEquals(TwitchCatalogSessionState.SUPERSEDED, forgotten.summary.state)
                assertArrayEquals(bytes, fixture.memory.bytes)
                assertEquals(newer.generation, fixture.store.read()!!.generation)
                assertEquals(TwitchCatalogGrantState.READY, fixture.store.read()!!.state)
            }
        }
    }

    @Test fun ownedRollbackAndIdentityRejectionMarkersRemainForgettable() {
        listOf(false, true).forEach { rollback ->
            val fixture = Fixture(); fixture.connect(); val session = fixture.session()
            if (rollback) fixture.clock.wall--
            else fixture.requests.validate = { validation(user = "different-user") }
            assertEquals(TwitchCatalogSessionState.RECONNECT_REQUIRED, session.validate().summary.state)
            session.invalidate()
            assertEquals(TwitchCatalogSessionState.MISSING, session.forget().summary.state)
            assertEquals(TwitchCatalogGrantState.CLEARED, fixture.store.read()!!.state)
        }
    }

    @Test fun postWriteExpiredRefreshCanForgetItsOwnedRotationMarkerWithoutAdoptingExternalReplacement() {
        listOf(false, true).forEach { externalReplacement ->
            val fixture = Fixture(); val original = fixture.connectLocal(capMs = 1000)
            fixture.requests.validate = { validation(seconds = 0) }
            val session = fixture.session(); val old = session.validate().lease!!
            var newer: TwitchCatalogStoredGrant? = null
            fixture.memory.onWrite = {
                if (String(fixture.memory.bytes!!, Charsets.ISO_8859_1).contains("rotated-access")) {
                    fixture.clock.advance(1000)
                    fixture.memory.onWrite = if (externalReplacement) {
                        {
                            fixture.memory.onWrite = {}
                            newer = fixture.connectLocal()
                        }
                    } else ({})
                }
            }
            val expired = session.onUnauthorized(old)
            assertEquals(TwitchCatalogSessionState.RECONNECT_REQUIRED, expired.summary.state)
            assertEquals(TwitchCatalogAuthFailure.EXPIRED, expired.summary.failure); assertNull(expired.lease)
            val current = fixture.store.read()!!
            assertNotEquals(original.generation, current.generation)
            val bytes = fixture.memory.bytes!!.copyOf()
            session.invalidate()
            val forgotten = session.forget()
            if (externalReplacement) {
                assertNotNull(newer)
                assertEquals(TwitchCatalogSessionState.SUPERSEDED, forgotten.summary.state)
                assertEquals(newer!!.generation, fixture.store.read()!!.generation)
                assertEquals(TwitchCatalogGrantState.READY, fixture.store.read()!!.state)
                assertArrayEquals(bytes, fixture.memory.bytes)
            } else {
                assertEquals(TwitchCatalogGrantState.RECONNECT, current.state)
                assertNull(current.credentials)
                assertEquals(TwitchCatalogSessionState.MISSING, forgotten.summary.state)
                assertEquals(TwitchCatalogGrantState.CLEARED, fixture.store.read()!!.state)
            }
            assertEquals(1, fixture.requests.refreshes.get())
        }
    }
}
