package net.fstab.tachiai.provider.twitch.catalog

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*
import net.fstab.tachiai.provider.twitch.DeviceAuthResponse
import net.fstab.tachiai.provider.twitch.TACHIAI_TWITCH_CLIENT_ID
import org.junit.Assert.*
import org.junit.Test

class TwitchLiveIdentityResolverTest {
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        var reads = 0
        var denied = false
        override fun read(): ByteArray? { check(!denied); reads++; return bytes?.copyOf() }
        override fun write(plaintext: ByteArray) { check(!denied); bytes = plaintext.copyOf() }
    }
    private fun resource(id: String = "123") = CatalogResource(ProviderId("twitch"), "broadcaster", id, CatalogIntent.CHANNEL)
    private fun page(rows: List<Map<String, Any?>> = emptyList(), cursor: String? = null) = TwitchHelixResponse(200,
        mapOf("data" to rows, "pagination" to if (cursor == null) emptyMap<String, Any?>() else mapOf("cursor" to cursor)))
    private fun user(id: String = "123", login: String = "newlogin") = mapOf<String, Any?>(
        "id" to id, "login" to login, "display_name" to "Public channel")
    private fun stream(id: String = "123", login: String = "newlogin") = mapOf<String, Any?>(
        "id" to "9123", "user_id" to id, "user_login" to login, "user_name" to "Public channel", "type" to "live")
    private fun <T> value(result: CatalogResult<T>): T = (result as CatalogResult.Value<T>).value
    private fun failure(result: CatalogResult<*>, reason: CatalogFailure) = assertEquals(reason, (result as CatalogResult.Failure).reason)

    private inner class Fixture(saved: Boolean = true) {
        val memory = Memory()
        val id = UUID.randomUUID().toString()
        var mono = 10_000L
        var owner = true
        var localOwner = true
        var ownerCalls = 0
        var rejectOwnerCalls = false
        var refreshes = 0
        var validates = 0
        var closes = 0
        var cancels = 0
        val calls = mutableListOf<TwitchHelixRequest>()
        val waits = mutableListOf<Long>()
        val store = TwitchCatalogGrantStore(memory, id, wallMs = { 1_000_000L })
        val session = TwitchCatalogSession(store, { object : TwitchCatalogTransport {
            override fun device(): DeviceAuthResponse = error("No consent in fixtures")
            override fun poll(deviceCode: String): DeviceAuthResponse = error("No polling in fixtures")
            override fun validate(accessToken: String): DeviceAuthResponse {
                validates++
                return DeviceAuthResponse(200, mapOf("client_id" to TACHIAI_TWITCH_CLIENT_ID, "user_id" to "9000",
                    "scopes" to listOf(TWITCH_CATALOG_SCOPE), "expires_in" to 7200))
            }
            override fun refresh(refreshToken: String): DeviceAuthResponse {
                refreshes++
                return DeviceAuthResponse(200, mapOf("access_token" to "rotated-access", "refresh_token" to "rotated-refresh",
                    "token_type" to "bearer", "scope" to listOf(TWITCH_CATALOG_SCOPE), "expires_in" to 7200))
            }
            override fun close() = Unit
        } }, { 1_000_000L }, { mono }, { check(!rejectOwnerCalls); ownerCalls++; owner })
        var response: (TwitchHelixRequest) -> TwitchHelixResponse = { request ->
            if (request.operation == TwitchHelixOperation.STREAMS) page(listOf(stream())) else page(listOf(user()))
        }
        fun catalog() = TwitchProviderCatalog(id, session, { gate -> object : TwitchHelixTransport {
            override fun execute(accessToken: String, request: TwitchHelixRequest): TwitchHelixResponse {
                check(gate()); calls.add(request); return response(request)
            }
            override fun cancelActiveRequest() { cancels++ }
            override fun close() { closes++ }
        } }, { check(!rejectOwnerCalls); ownerCalls++; owner }, { 1_000_000L }, { waits.add(it) }, { localOwner })
        val resolver = catalog()
        init { if (saved) store.commitConnection(store.beginConnection(), TwitchCatalogReplacement(
            TwitchCatalogCredentials("initial-access", "initial-refresh", 7_200_000L),
            TwitchCatalogValidation("9000", setOf(TWITCH_CATALOG_SCOPE), 7_200_000L), 7_200_000L))!! }
        fun begin() = value(resolver.begin(resource()))
    }

    @Test fun immutableIdMapsToCurrentLoginAndRequiresExplicitMatchingConfirmation() {
        val f = Fixture(); val identity = f.begin()
        assertEquals(resource(), identity.resource); assertEquals("newlogin", identity.login)
        assertTrue(f.calls[0].url.query.contains("id=123"))
        assertTrue(f.calls[1].url.query.contains("user_id=123"))
        assertFalse(f.resolver.canPublish(identity))
        value(f.resolver.confirm(identity))
        assertTrue(f.calls[2].url.query.contains("login=newlogin"))
        assertTrue(f.resolver.canPublish(identity))
        assertFalse(identity.toString().contains(identity.login)); assertFalse(identity.toString().contains("123"))
        failure(f.resolver.confirm(identity), CatalogFailure.INVALID_INPUT)
        assertEquals(3, f.calls.size)
    }

    @Test fun nativeLoginIntersectionDoesNotWidenExistingLiveGrammar() {
        listOf("ab", "x".repeat(26)).forEach { login ->
            val f = Fixture(); f.response = { page(listOf(user(login = login))) }
            failure(f.resolver.begin(resource()), CatalogFailure.UNSUPPORTED)
            assertEquals(1, f.calls.size)
        }
        listOf("abc", "x".repeat(25)).forEach { login ->
            val f = Fixture(); f.response = { request ->
                if (request.operation == TwitchHelixOperation.STREAMS) page(listOf(stream(login = login))) else page(listOf(user(login = login)))
            }
            assertEquals(login, value(f.resolver.begin(resource())).login)
        }
    }

    @Test fun deletedAndOfflineChannelsStopBeforeNativeInputIsReturned() {
        val deleted = Fixture(); deleted.response = { page() }
        failure(deleted.resolver.begin(resource()), CatalogFailure.NOT_FOUND)
        assertEquals(1, deleted.calls.size)
        val offline = Fixture(); offline.response = { request ->
            if (request.operation == TwitchHelixOperation.USERS) page(listOf(user())) else page()
        }
        failure(offline.resolver.begin(resource()), CatalogFailure.NOT_FOUND)
        assertEquals(2, offline.calls.size)
    }

    @Test fun unexpectedOwnerLoginMultipleRowsAndPaginationFailClosed() {
        listOf(page(listOf(user("456"))), page(listOf(user(), user())), page(listOf(user()), "next")).forEach { response ->
            val f = Fixture(); f.response = { response }
            failure(f.resolver.begin(resource()), CatalogFailure.TEMPORARY)
        }
        listOf(page(listOf(stream("456"))), page(listOf(stream(login = "oldlogin"))),
            page(listOf(stream(), stream())), page(listOf(stream()), "next")).forEach { response ->
            val f = Fixture(); f.response = { request -> if (request.operation == TwitchHelixOperation.USERS) page(listOf(user())) else response }
            failure(f.resolver.begin(resource()), CatalogFailure.TEMPORARY)
        }
    }

    @Test fun renamedOrReusedLoginNeverConfirmsDifferentConfiguredIdentity() {
        listOf(page(), page(listOf(user("456"))), page(listOf(user(login = "anotherlogin"))),
            page(listOf(user(), user())), page(listOf(user()), "next")).forEach { response ->
            val f = Fixture(); val identity = f.begin(); f.response = { response }
            failure(f.resolver.confirm(identity), CatalogFailure.NOT_FOUND)
            assertFalse(f.resolver.canPublish(identity))
            failure(f.resolver.confirm(identity), CatalogFailure.INVALID_INPUT)
        }
    }

    @Test fun foreignFabricatedAndUnsupportedInputsDoNotTriggerAuthenticationOrHttp() {
        val f = Fixture(saved = false)
        val fabricated = TwitchLiveIdentity(resource(), "newlogin")
        failure(f.resolver.confirm(fabricated), CatalogFailure.INVALID_INPUT)
        assertFalse(f.resolver.canPublish(fabricated))
        listOf(resource().copy(kind = "channel"), resource().copy(intent = CatalogIntent.VIDEO), resource("0"), resource("0123")).forEach {
            failure(f.resolver.begin(it), CatalogFailure.UNSUPPORTED)
        }
        failure(f.resolver.begin(resource().copy(providerId = ProviderId("abema"))), CatalogFailure.INVALID_INPUT)
        assertEquals(0, f.validates); assertTrue(f.calls.isEmpty())
        val other = Fixture(); val identity = other.begin()
        failure(f.resolver.confirm(identity), CatalogFailure.INVALID_INPUT)
        assertEquals(0, f.validates)
    }

    @Test fun capabilityAccessLossAndGenerationReplacementClearOldHandles() {
        val f = Fixture(); val identity = f.begin(); value(f.resolver.confirm(identity))
        f.store.beginConnection()
        assertFalse(f.resolver.canPublish(identity))
        failure(f.resolver.confirm(identity), CatalogFailure.INVALID_INPUT)
        val paused = Fixture(); val pending = paused.begin(); paused.session.setForeground(false)
        assertEquals(CatalogAccess.NOT_VERIFIED, paused.resolver.capabilities().lookup)
        failure(paused.resolver.confirm(pending), CatalogFailure.INVALID_INPUT)
        assertEquals(2, paused.calls.size)
    }

    @Test fun expiredOrRevalidatedHandleCannotPerformNewAuthorizationMaintenance() {
        val expired = Fixture(); val old = expired.begin()
        val validations = expired.validates
        expired.mono += TWITCH_CATALOG_VALIDATION_INTERVAL_MS
        failure(expired.resolver.confirm(old), CatalogFailure.ACCESS_REQUIRED)
        assertEquals(validations, expired.validates); assertEquals(0, expired.refreshes)
        assertEquals(2, expired.calls.size)
        val replaced = Fixture(); val candidate = replaced.begin()
        replaced.session.validate(force = true)
        val afterValidation = replaced.validates
        failure(replaced.resolver.confirm(candidate), CatalogFailure.ACCESS_REQUIRED)
        assertEquals(afterValidation, replaced.validates); assertEquals(0, replaced.refreshes)
        assertEquals(2, replaced.calls.size)
    }

    @Test fun boundedHandlesEvictOldestAndCloseClearsPendingAndConfirmedAdmissions() {
        val f = Fixture(); val first = f.begin()
        val pending = (1..32).map { f.begin() }
        failure(f.resolver.confirm(first), CatalogFailure.INVALID_INPUT)
        value(f.resolver.confirm(pending.last()))
        assertTrue(f.resolver.canPublish(pending.last()))
        f.resolver.close()
        pending.forEach { assertFalse(f.resolver.canPublish(it)) }
        failure(f.resolver.confirm(pending.first()), CatalogFailure.INVALID_INPUT)
    }

    @Test fun backgroundOrForgetDuringLookupDiscardsLateMappingAndConfirmation() {
        val background = Fixture()
        background.response = { background.session.setForeground(false); page(listOf(user())) }
        failure(background.resolver.begin(resource()), CatalogFailure.ACCESS_REQUIRED)
        assertEquals(1, background.calls.size)
        val forgotten = Fixture(); val identity = forgotten.begin()
        forgotten.response = { forgotten.store.invalidate(); page(listOf(user())) }
        failure(forgotten.resolver.confirm(identity), CatalogFailure.ACCESS_REQUIRED)
        assertFalse(forgotten.resolver.canPublish(identity))
    }

    @Test fun beginRefreshRestartsWholeMappingButConfirmationRefreshDiscardsCandidate() {
        val f = Fixture(); var first = true
        f.response = { request ->
            if (request.operation == TwitchHelixOperation.STREAMS && first) { first = false; TwitchHelixResponse(401) }
            else if (request.operation == TwitchHelixOperation.STREAMS) page(listOf(stream())) else page(listOf(user()))
        }
        val identity = f.begin()
        assertEquals(1, f.refreshes)
        assertEquals(listOf(TwitchHelixOperation.USERS, TwitchHelixOperation.STREAMS, TwitchHelixOperation.USERS, TwitchHelixOperation.STREAMS),
            f.calls.map { it.operation })
        f.response = { TwitchHelixResponse(401) }
        failure(f.resolver.confirm(identity), CatalogFailure.ACCESS_REQUIRED)
        assertEquals(1, f.refreshes); assertFalse(f.resolver.canPublish(identity))
        val fresh = Fixture(); val candidate = fresh.begin(); fresh.response = { TwitchHelixResponse(401) }
        failure(fresh.resolver.confirm(candidate), CatalogFailure.ACCESS_REQUIRED)
        assertEquals(1, fresh.refreshes); assertEquals(3, fresh.calls.size)
        assertFalse(fresh.resolver.canPublish(candidate))
    }

    @Test fun oneServiceRetryIsSharedAcrossMappingAndConfirmation() {
        val f = Fixture(); var first = true
        f.response = { request ->
            if (first) { first = false; TwitchHelixResponse(503) }
            else if (request.operation == TwitchHelixOperation.STREAMS) page(listOf(stream())) else page(listOf(user()))
        }
        val identity = f.begin(); assertEquals(listOf(250L), f.waits)
        f.response = { TwitchHelixResponse(503) }
        failure(f.resolver.confirm(identity), CatalogFailure.TEMPORARY)
        assertEquals(listOf(250L), f.waits); assertEquals(4, f.calls.size)
        assertFalse(f.resolver.canPublish(identity))
    }

    @Test fun rateDeadlineIsPreservedAndNoConfirmationWaitOrAdmissionOccurs() {
        val f = Fixture(); val identity = f.begin()
        f.response = { TwitchHelixResponse(429, retryAtEpochMs = 1_030_000L) }
        assertEquals(CatalogResult.Failure(CatalogFailure.RATE_LIMITED, 1_030_000L), f.resolver.confirm(identity))
        assertTrue(f.waits.isEmpty()); assertFalse(f.resolver.canPublish(identity))
        f.response = { TwitchHelixResponse(429) }
        assertEquals(CatalogResult.Failure(CatalogFailure.RATE_LIMITED, 1_060_000L), f.resolver.begin(resource()))
    }

    @Test fun confirmedPublicationIsCheapAndDeniesLocalOwnerOrLeaseLoss() {
        val f = Fixture(); val identity = f.begin(); value(f.resolver.confirm(identity))
        val reads = f.memory.reads; val owners = f.ownerCalls; val calls = f.calls.size
        f.memory.denied = true; f.rejectOwnerCalls = true
        repeat(4) { assertTrue(f.resolver.canPublish(identity)) }
        assertEquals(reads, f.memory.reads); assertEquals(owners, f.ownerCalls); assertEquals(calls, f.calls.size)
        f.localOwner = false
        assertFalse(f.resolver.canPublish(identity))
        f.localOwner = true
        assertFalse(f.resolver.canPublish(identity))
        val expiry = Fixture(); val expiring = expiry.begin(); value(expiry.resolver.confirm(expiring))
        expiry.mono += TWITCH_CATALOG_VALIDATION_INTERVAL_MS
        assertFalse(expiry.resolver.canPublish(expiring))
    }

    @Test fun closeDoesNotWaitForBlockedConfirmationOrPermitLateRefill() {
        val f = Fixture(); val identity = f.begin()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.response = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); page(listOf(user())) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<CatalogResult<Unit>> { f.resolver.confirm(identity) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            f.resolver.close()
            assertFalse(f.resolver.canPublish(identity)); assertEquals(1, f.cancels)
            release.countDown()
            failure(future.get(5, TimeUnit.SECONDS), CatalogFailure.ACCESS_REQUIRED)
            assertFalse(f.resolver.canPublish(identity))
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun lateOwnerFailureAfterConfirmationCannotLeavePublishableHandle() {
        val f = Fixture(); val identity = f.begin()
        var calls = 0
        // Local admission can precede the outer operation's final durable guard.
        val resolver = TwitchProviderCatalog(f.id, f.session, { _ -> object : TwitchHelixTransport {
            override fun execute(accessToken: String, request: TwitchHelixRequest) =
                if (request.operation == TwitchHelixOperation.STREAMS) page(listOf(stream())) else page(listOf(user()))
            override fun close() = Unit
        } }, { f.owner }, canPublishLocally = { calls++; if (calls == 2) f.owner = false; true })
        val candidate = value(resolver.begin(resource()))
        failure(resolver.confirm(candidate), CatalogFailure.ACCESS_REQUIRED)
        assertFalse(resolver.canPublish(candidate)); assertFalse(f.resolver.canPublish(identity))
    }
}
