package net.fstab.tachiai.provider.twitch.catalog

import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.provider.twitch.DeviceAuthResponse
import net.fstab.tachiai.provider.twitch.TACHIAI_TWITCH_CLIENT_ID
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
        override fun read(): ByteArray? { check(!denyRead); reads++; return bytes?.copyOf() }
        override fun write(plaintext: ByteArray) {
            if (failWrite) throw IOException("fixture failure")
            bytes = plaintext.copyOf()
        }
    }
    private fun validation(user: String = "123", client: String = TACHIAI_TWITCH_CLIENT_ID,
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
        fun session() = TwitchCatalogSession(store, requests::transport, { clock.wall }, { clock.mono }, { ownerMatches })
        fun connect(lifetime: Long = 7_200_000L): TwitchCatalogStoredGrant = store.commitConnection(store.beginConnection(),
            TwitchCatalogReplacement(TwitchCatalogCredentials("initial-access", "initial-refresh", lifetime),
                TwitchCatalogValidation("123", setOf(TWITCH_CATALOG_SCOPE), lifetime), lifetime))!!
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

    @Test fun localLeaseGatePerformsNoStoreOwnerOrTransportMaintenance() {
        val fixture = Fixture(); fixture.connect()
        var ownerCalls = 0
        var rejectOwner = false
        val session = TwitchCatalogSession(fixture.store, fixture.requests::transport,
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
}
