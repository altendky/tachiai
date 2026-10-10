package net.fstab.tachiai.provider.twitch.catalog

import java.util.UUID
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*
import net.fstab.tachiai.provider.twitch.DeviceAuthResponse
import net.fstab.tachiai.provider.twitch.DeviceNetworkFailure
import net.fstab.tachiai.provider.twitch.SMART_TV_TWITCH_CLIENT_ID
import org.junit.Assert.*
import org.junit.Test

class TwitchProviderCatalogTest {
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf() }
    }
    private fun page(rows: List<Map<String, Any?>> = emptyList(), cursor: String? = null) = TwitchHelixResponse(200,
        mapOf("data" to rows, "pagination" to if (cursor == null) emptyMap<String, Any?>() else mapOf("cursor" to cursor)))
    private fun followed(id: String, login: String = "channel$id", name: String = "Channel $id") =
        mapOf<String, Any?>("broadcaster_id" to id, "broadcaster_login" to login, "broadcaster_name" to name)
    private fun user(id: String, login: String = "channel$id", name: String = "Channel $id") =
        mapOf<String, Any?>("id" to id, "login" to login, "display_name" to name)
    private fun found(id: String, live: Boolean) = mapOf<String, Any?>("id" to id, "broadcaster_login" to "channel$id",
        "display_name" to "Channel $id", "is_live" to live)
    private fun stream(id: String, broadcast: String = "9$id") = mapOf<String, Any?>("id" to broadcast,
        "user_id" to id, "user_login" to "channel$id", "user_name" to "Channel $id", "type" to "live")
    private fun video(id: String, owner: String = "123") = mapOf<String, Any?>("id" to id, "user_id" to owner,
        "title" to "Video $id", "type" to "archive")
    private fun segment(start: Long, end: Long = start + 60_000L, canceledUntil: String? = null) =
        mapOf<String, Any?>("start_time" to Instant.ofEpochMilli(start).toString(),
            "end_time" to Instant.ofEpochMilli(end).toString(), "canceled_until" to canceledUntil)
    private fun schedule(owner: String = "123", segments: List<Map<String, Any?>> = emptyList(),
        vacation: Map<String, Any?>? = null) = TwitchHelixResponse(200, mapOf("data" to
        mapOf("broadcaster_id" to owner, "segments" to segments, "vacation" to vacation),
        "pagination" to emptyMap<String, Any?>()))
    private fun broadcaster(id: String) = CatalogResource(ProviderId("twitch"), "broadcaster", id, CatalogIntent.CHANNEL)
    private fun vod(id: String) = CatalogResource(ProviderId("twitch"), "video", id, CatalogIntent.VIDEO)
    private fun <T> value(result: CatalogResult<T>): T {
        assertTrue("Expected value, got $result", result is CatalogResult.Value)
        return (result as CatalogResult.Value<T>).value
    }
    private fun failure(result: CatalogResult<*>, expected: CatalogFailure) {
        assertEquals(expected, (result as CatalogResult.Failure).reason)
    }
    private fun retainedContinuations(catalog: TwitchProviderCatalog): Int {
        val field = TwitchProviderCatalog::class.java.getDeclaredField("cursors").also { it.isAccessible = true }
        return (field.get(catalog) as Map<*, *>).size
    }
    private fun assertContinuationsCleared(catalog: TwitchProviderCatalog) {
        assertEquals(0, retainedContinuations(catalog))
        val field = TwitchProviderCatalog::class.java.getDeclaredField("cursorGeneration").also { it.isAccessible = true }
        assertNull(field.get(catalog))
    }
    private inner class Fixture(saved: Boolean = true) {
        val instance = UUID.randomUUID().toString()
        val memory = Memory()
        val store = TwitchCatalogGrantStore(memory, instance, wallMs = { 1_000_000L })
        val authValidations = AtomicInteger(); val refreshes = AtomicInteger(); val cancels = AtomicInteger(); val closes = AtomicInteger()
        val calls = mutableListOf<TwitchHelixRequest>(); val tokens = mutableListOf<String>(); val waits = mutableListOf<Long>()
        var publish = true
        var now = 1_000_000L
        var authUser = "9000"
        var execute: (TwitchHelixRequest) -> TwitchHelixResponse = { page() }
        val session = TwitchCatalogSession(store, { object : TwitchCatalogTransport {
            override fun device(): DeviceAuthResponse = error("No consent in catalog fixtures")
            override fun poll(deviceCode: String): DeviceAuthResponse = error("No polling in catalog fixtures")
            override fun validate(accessToken: String): DeviceAuthResponse {
                authValidations.incrementAndGet()
                return DeviceAuthResponse(200, mapOf("client_id" to SMART_TV_TWITCH_CLIENT_ID, "user_id" to authUser,
                    "scopes" to listOf(TWITCH_CATALOG_SCOPE), "expires_in" to 7200))
            }
            override fun refresh(refreshToken: String): DeviceAuthResponse {
                val count = refreshes.incrementAndGet()
                assertEquals(if (count == 1) "initial-refresh" else "rotated-refresh-${count - 1}", refreshToken)
                return DeviceAuthResponse(200, mapOf("access_token" to "rotated-access-$count", "refresh_token" to "rotated-refresh-$count",
                    "token_type" to "bearer", "scope" to listOf(TWITCH_CATALOG_SCOPE), "expires_in" to 7200))
            }
            override fun close() = Unit
        } }, { 1_000_000L }, { 10_000L }, { publish })
        val catalog = createCatalog()
        fun createCatalog() = TwitchProviderCatalog(instance, session, { gate -> object : TwitchHelixTransport {
            override fun execute(accessToken: String, request: TwitchHelixRequest): TwitchHelixResponse {
                check(gate()); calls.add(request); tokens.add(accessToken)
                return execute(request)
            }
            override fun cancelActiveRequest() { cancels.incrementAndGet() }
            override fun close() { closes.incrementAndGet() }
        } }, { publish }, { now }, { waits.add(it) })
        init { if (saved) seed() }
        fun seed(user: String = "9000") {
            authUser = user
            store.commitConnection(store.beginConnection(), TwitchCatalogReplacement(
                TwitchCatalogCredentials("initial-access", "initial-refresh", 7200_000L),
                TwitchCatalogValidation(user, setOf(TWITCH_CATALOG_SCOPE), 7200_000L), 7200_000L))!!
        }
    }

    @Test fun connectedCapabilitiesValidateWithoutHelixAndTruthfullyDistinguishMissingReconnectAndBlockedRoute() {
        val missing = Fixture(saved = false)
        assertEquals(CatalogAccess.AUTHORIZATION_REQUIRED, missing.catalog.capabilities().browse)
        failure(missing.catalog.browse(CatalogQuery(collectionId = "following")), CatalogFailure.ACCESS_REQUIRED)
        assertTrue(missing.calls.isEmpty())
        val fixture = Fixture(); val caps = fixture.catalog.capabilities()
        assertEquals(CatalogAccess.AVAILABLE, caps.browse); assertEquals("Live channels", caps.browseTitle)
        assertEquals("following", caps.initialCollectionId); assertEquals(CatalogAccess.NOT_VERIFIED, caps.playback)
        assertEquals(1, fixture.authValidations.get()); assertTrue(fixture.calls.isEmpty())
        fixture.store.beginConnection()
        assertEquals(CatalogAccess.RECONNECT_REQUIRED, fixture.catalog.capabilities().browse)
        val blocked = Fixture(); blocked.publish = false
        assertEquals(CatalogAccess.NOT_VERIFIED, blocked.catalog.capabilities().lookup)
        failure(blocked.catalog.lookup("channel123"), CatalogFailure.TEMPORARY)
        assertEquals(0, blocked.authValidations.get()); assertTrue(blocked.calls.isEmpty())
    }

    @Test fun historyIsUnverifiedWithOrWithoutCatalogAccessAndNeverTriggersAuthorizationOrMetadata() {
        listOf(false, true).forEach { saved ->
            val fixture = Fixture(saved)
            listOf(CatalogQuery(collectionId = "history"),
                CatalogQuery(collectionId = "history", cursor = "unverified-cursor", search = "private query")).forEach {
                failure(fixture.catalog.browse(it), CatalogFailure.NOT_VERIFIED)
            }
            assertEquals(0, fixture.authValidations.get()); assertEquals(0, fixture.refreshes.get())
            assertTrue(fixture.calls.isEmpty())
            val capabilities = fixture.catalog.capabilities()
            assertEquals(listOf("following", "history"), capabilities.collections.map { it.id })
            assertEquals(CatalogAccess.NOT_VERIFIED, capabilities.collections.single { it.id == "history" }.access)
            assertEquals(if (saved) CatalogAccess.AVAILABLE else CatalogAccess.AUTHORIZATION_REQUIRED,
                capabilities.collections.single { it.id == "following" }.access)
            val validations = fixture.authValidations.get()
            failure(fixture.catalog.browse(CatalogQuery(collectionId = "history")), CatalogFailure.NOT_VERIFIED)
            assertEquals(validations, fixture.authValidations.get()); assertEquals(0, fixture.refreshes.get())
            assertTrue(fixture.calls.isEmpty())
            if (saved) {
                assertTrue(value(fixture.catalog.browse(CatalogQuery(collectionId = "following"))).entries.isEmpty())
                assertEquals(listOf(TwitchHelixOperation.FOLLOWED), fixture.calls.map { it.operation })
            } else failure(fixture.catalog.browse(CatalogQuery(collectionId = "following")), CatalogFailure.ACCESS_REQUIRED)
            fixture.catalog.close()
        }
        val blocked = Fixture().apply { publish = false }
        failure(blocked.catalog.browse(CatalogQuery(collectionId = "history")), CatalogFailure.NOT_VERIFIED)
        assertEquals(0, blocked.authValidations.get()); assertTrue(blocked.calls.isEmpty())
        blocked.catalog.close()
    }

    @Test fun followingUsesValidatedOwnUserAndKeepsOfflineNeverStreamedChannelsAsCanonicalItems() {
        val fixture = Fixture()
        fixture.execute = { request -> when (request.operation) {
            TwitchHelixOperation.FOLLOWED -> {
                assertTrue(request.url.query.contains("user_id=9000"))
                page(listOf(followed("123"), followed("456")))
            }
            TwitchHelixOperation.STREAMS -> page(listOf(stream("123")))
            else -> error("Unexpected operation")
        } }
        val entries = value(fixture.catalog.browse(CatalogQuery(collectionId = "following"))).entries
        assertEquals(listOf(broadcaster("123"), broadcaster("456")), entries.map { it.resource })
        assertEquals(listOf(CatalogAvailability.LIVE, CatalogAvailability.OFFLINE), entries.map { it.availability })
        assertFalse(entries.any { it.resource.identity == "9123" })
        assertTrue(entries.all { it.scheduledStartEpochMs == null })
        assertEquals(2, fixture.calls.size)
    }

    @Test fun searchIncludesOfflineAndLiveDirectoryUsesBroadcasterIdInsteadOfTransientBroadcastIdentity() {
        val fixture = Fixture()
        fixture.execute = { request -> when (request.operation) {
            TwitchHelixOperation.SEARCH -> {
                assertTrue(request.url.query.contains("live_only=false")); assertTrue(request.url.query.contains("query=sumo"))
                page(listOf(found("123", false), found("456", true)))
            }
            TwitchHelixOperation.STREAMS -> page(listOf(stream("123", "99999")))
            else -> error("Unexpected operation")
        } }
        val search = value(fixture.catalog.browse(CatalogQuery(search = " sumo ")))
        assertEquals(listOf(CatalogAvailability.OFFLINE, CatalogAvailability.LIVE), search.entries.map { it.availability })
        val live = value(fixture.catalog.browse(CatalogQuery()))
        assertEquals(broadcaster("123"), live.entries.single().resource)
        assertEquals(CatalogAvailability.LIVE, live.entries.single().availability)
    }

    @Test fun exactLoginAndPublicUrlVerifyImmutableIdAndNeverStreamedUsersAreAddable() {
        val fixture = Fixture()
        fixture.execute = { request -> when (request.operation) {
            TwitchHelixOperation.USERS -> { assertTrue(request.url.query.contains("login=neverstarted")); page(listOf(user("123", "neverstarted", "Never Started"))) }
            TwitchHelixOperation.STREAMS -> page()
            TwitchHelixOperation.SCHEDULE -> TwitchHelixResponse(404)
            else -> error("Unexpected operation")
        } }
        val first = value(fixture.catalog.lookup("NeverStarted"))
        val second = value(fixture.catalog.lookup("https://www.twitch.tv/neverstarted"))
        assertEquals(broadcaster("123"), first.resource); assertEquals(first.resource, second.resource)
        assertEquals(CatalogAvailability.OFFLINE, first.availability)
        assertEquals("Never Started", first.title)
    }

    @Test fun numericRefreshSurvivesRenameAndRecycledAliasBecomesDifferentSavedIdentity() {
        val fixture = Fixture()
        fixture.execute = { request -> when (request.operation) {
            TwitchHelixOperation.USERS -> if (request.url.query.contains("id=123")) page(listOf(user("123", "newlogin", "Renamed")))
                else page(listOf(user("456", "oldlogin", "New Account")))
            TwitchHelixOperation.STREAMS -> page()
            TwitchHelixOperation.SCHEDULE -> TwitchHelixResponse(404)
            else -> error("Unexpected operation")
        } }
        val renamed = value(fixture.catalog.refresh(broadcaster("123")))
        val recycled = value(fixture.catalog.lookup("oldlogin"))
        assertEquals(broadcaster("123"), renamed.resource); assertEquals("Renamed", renamed.title)
        assertEquals(broadcaster("456"), recycled.resource)
        assertNotEquals(renamed.resource, recycled.resource)
    }

    @Test fun exactBroadcasterScheduleAddsOnlyFutureContextWithoutReplacingChannelIdentityOrLiveState() {
        listOf(false, true).forEach { live ->
            val fixture = Fixture()
            fixture.execute = { request -> when (request.operation) {
                TwitchHelixOperation.USERS -> page(listOf(user("123")))
                TwitchHelixOperation.STREAMS -> if (live) page(listOf(stream("123"))) else page()
                TwitchHelixOperation.SCHEDULE -> {
                    assertTrue(request.url.query.contains("broadcaster_id=123"))
                    assertTrue(request.url.query.contains("first=20"))
                    assertFalse(request.url.query.contains("after="))
                    schedule(segments = listOf(segment(1_120_000L), segment(1_060_000L)))
                }
                else -> error("Unexpected operation")
            } }
            val lookedUp = value(fixture.catalog.lookup("channel123"))
            val refreshed = value(fixture.catalog.refresh(broadcaster("123")))
            listOf(lookedUp, refreshed).forEach {
                assertEquals(broadcaster("123"), it.resource)
                assertEquals(if (live) CatalogAvailability.LIVE else CatalogAvailability.OFFLINE, it.availability)
                assertEquals(1_060_000L, it.scheduledStartEpochMs)
                assertEquals("Channel 123", it.title)
            }
            assertEquals(listOf(TwitchHelixOperation.USERS, TwitchHelixOperation.STREAMS, TwitchHelixOperation.SCHEDULE,
                TwitchHelixOperation.USERS, TwitchHelixOperation.STREAMS, TwitchHelixOperation.SCHEDULE), fixture.calls.map { it.operation })
            assertEquals(0, fixture.refreshes.get())
        }
    }

    @Test fun scheduleUsesPostResponseTimeAndOmitsAbsentPastCanceledAndVacationContext() {
        val omitted = listOf(TwitchHelixResponse(404), schedule(),
            schedule(segments = listOf(segment(999_000L), segment(1_000_000L))),
            schedule(segments = listOf(segment(1_060_000L, canceledUntil = "1970-01-01T01:00:00Z"))),
            schedule(segments = listOf(segment(1_060_000L)), vacation = mapOf(
                "start_time" to "1970-01-01T00:17:00Z", "end_time" to "1970-01-01T00:19:00Z")))
        omitted.forEach { response ->
            val fixture = Fixture()
            fixture.execute = { request -> when (request.operation) {
                TwitchHelixOperation.USERS -> page(listOf(user("123")))
                TwitchHelixOperation.STREAMS -> page()
                TwitchHelixOperation.SCHEDULE -> response
                else -> error("Unexpected operation")
            } }
            val result = value(fixture.catalog.lookup("123"))
            assertEquals(broadcaster("123"), result.resource)
            assertEquals(CatalogAvailability.OFFLINE, result.availability)
            assertNull(result.scheduledStartEpochMs)
            assertEquals(3, fixture.calls.size)
        }
        val delayed = Fixture()
        delayed.execute = { request -> when (request.operation) {
            TwitchHelixOperation.USERS -> page(listOf(user("123")))
            TwitchHelixOperation.STREAMS -> page()
            TwitchHelixOperation.SCHEDULE -> {
                assertEquals(TwitchHelixRequest.schedule("123", 1_000_000L).url.toExternalForm(), request.url.toExternalForm())
                delayed.now = 1_060_000L
                schedule(segments = listOf(segment(1_020_000L), segment(1_120_000L)))
            }
            else -> error("Unexpected operation")
        } }
        assertEquals(1_120_000L, value(delayed.catalog.lookup("123")).scheduledStartEpochMs)
    }

    @Test fun malformedOrWrongOwnerScheduleFailsAndOnlyScheduleTreats404AsAbsent() {
        listOf(schedule(owner = "456", segments = listOf(segment(1_060_000L))),
            schedule(segments = listOf(segment(1_060_000L) + ("start_time" to "not-a-time"))),
            TwitchHelixResponse(200, mapOf("data" to emptyList<Any>()))).forEach { response ->
            val fixture = Fixture()
            fixture.execute = { request -> when (request.operation) {
                TwitchHelixOperation.USERS -> page(listOf(user("123")))
                TwitchHelixOperation.STREAMS -> page()
                TwitchHelixOperation.SCHEDULE -> response
                else -> error("Unexpected operation")
            } }
            failure(fixture.catalog.lookup("123"), CatalogFailure.TEMPORARY)
            assertEquals(3, fixture.calls.size)
        }
        val missingUsers = Fixture()
        assertEquals(CatalogAvailability.UNAVAILABLE, value(missingUsers.catalog.refresh(broadcaster("123"))).availability)
        failure(missingUsers.catalog.lookup("123"), CatalogFailure.NOT_FOUND)
        assertTrue(missingUsers.calls.all { it.operation == TwitchHelixOperation.USERS })
        listOf(TwitchHelixOperation.USERS, TwitchHelixOperation.STREAMS, TwitchHelixOperation.VIDEOS).forEach { failed ->
            val fixture = Fixture()
            fixture.execute = { request -> if (request.operation == failed) TwitchHelixResponse(404)
                else page(listOf(user("123"))) }
            failure(fixture.catalog.lookup(if (failed == TwitchHelixOperation.VIDEOS) "https://www.twitch.tv/videos/789" else "123"),
                CatalogFailure.TEMPORARY)
            assertFalse(fixture.calls.any { it.operation == TwitchHelixOperation.SCHEDULE })
        }
    }

    @Test fun schedule401RestartsUsersStatusAndScheduleTogetherAndRefreshBudgetRemainsOne() {
        val fixture = Fixture(); var users = 0; var status = 0; var schedules = 0
        fixture.execute = { request -> when (request.operation) {
            TwitchHelixOperation.USERS -> page(listOf(user(if (++users == 1) "123" else "456", "sumochannel")))
            TwitchHelixOperation.STREAMS -> if (++status == 1) page(listOf(stream("123"))) else page()
            TwitchHelixOperation.SCHEDULE -> if (++schedules == 1) TwitchHelixResponse(401)
                else schedule(owner = "456", segments = listOf(segment(1_060_000L)))
            else -> error("Unexpected operation")
        } }
        val result = value(fixture.catalog.lookup("sumochannel"))
        assertEquals(broadcaster("456"), result.resource)
        assertEquals(CatalogAvailability.OFFLINE, result.availability)
        assertEquals(1_060_000L, result.scheduledStartEpochMs)
        assertEquals(2, users); assertEquals(2, status); assertEquals(2, schedules)
        assertEquals(1, fixture.refreshes.get())
        assertEquals(List(3) { "initial-access" } + List(3) { "rotated-access-1" }, fixture.tokens)

        val refused = Fixture()
        refused.execute = { request -> when (request.operation) {
            TwitchHelixOperation.USERS -> page(listOf(user("123")))
            TwitchHelixOperation.STREAMS -> page()
            TwitchHelixOperation.SCHEDULE -> TwitchHelixResponse(401)
            else -> error("Unexpected operation")
        } }
        failure(refused.catalog.refresh(broadcaster("123")), CatalogFailure.ACCESS_REQUIRED)
        assertEquals(1, refused.refreshes.get()); assertEquals(6, refused.calls.size)
    }

    @Test fun scheduleRateAndServiceFailuresKeepExistingOperationWideRetryBounds() {
        fun fixtureWith(response: () -> TwitchHelixResponse) = Fixture().also { fixture ->
            fixture.execute = { request -> when (request.operation) {
                TwitchHelixOperation.USERS -> page(listOf(user("123")))
                TwitchHelixOperation.STREAMS -> page()
                TwitchHelixOperation.SCHEDULE -> response()
                else -> error("Unexpected operation")
            } }
        }
        val limited = fixtureWith { TwitchHelixResponse(429, retryAtEpochMs = Long.MAX_VALUE) }
        val rate = limited.catalog.lookup("123") as CatalogResult.Failure
        assertEquals(CatalogFailure.RATE_LIMITED, rate.reason); assertEquals(87_400_000L, rate.retryAtEpochMs)
        assertEquals(3, limited.calls.size); assertTrue(limited.waits.isEmpty())
        var attempts = 0
        val recovered = fixtureWith { if (++attempts == 1) TwitchHelixResponse(503) else TwitchHelixResponse(404) }
        assertNull(value(recovered.catalog.lookup("123")).scheduledStartEpochMs)
        assertEquals(4, recovered.calls.size); assertEquals(listOf(250L), recovered.waits)
        val unavailable = fixtureWith { TwitchHelixResponse(503) }
        failure(unavailable.catalog.lookup("123"), CatalogFailure.TEMPORARY)
        assertEquals(4, unavailable.calls.size); assertEquals(listOf(250L), unavailable.waits)
        val spent = Fixture(); var statuses = 0
        spent.execute = { request -> when (request.operation) {
            TwitchHelixOperation.USERS -> page(listOf(user("123")))
            TwitchHelixOperation.STREAMS -> if (++statuses == 1) TwitchHelixResponse(503) else page()
            TwitchHelixOperation.SCHEDULE -> TwitchHelixResponse(503)
            else -> error("Unexpected operation")
        } }
        failure(spent.catalog.lookup("123"), CatalogFailure.TEMPORARY)
        assertEquals(4, spent.calls.size); assertEquals(listOf(250L), spent.waits)
    }

    @Test fun ownerOrGrantChangeDuringScheduleRejectsPublicationWithoutAnotherRequest() {
        listOf(false, true).forEach { replaceGrant ->
            val fixture = Fixture(); val original = fixture.memory.bytes!!.copyOf()
            fixture.execute = { request -> when (request.operation) {
                TwitchHelixOperation.USERS -> page(listOf(user("123")))
                TwitchHelixOperation.STREAMS -> page()
                TwitchHelixOperation.SCHEDULE -> {
                    if (replaceGrant) fixture.seed(user = "9001") else fixture.publish = false
                    schedule(segments = listOf(segment(1_060_000L)))
                }
                else -> error("Unexpected operation")
            } }
            failure(fixture.catalog.lookup("123"), CatalogFailure.ACCESS_REQUIRED)
            assertEquals(3, fixture.calls.size); assertEquals(0, fixture.refreshes.get())
            if (replaceGrant) assertEquals("9001", fixture.store.read()!!.userId) else assertArrayEquals(original, fixture.memory.bytes)
        }
    }

    @Test fun closeDuringScheduleCancelsTransportAndCannotPublishLateContext() {
        val fixture = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val workers = Executors.newSingleThreadExecutor()
        fixture.execute = { request -> when (request.operation) {
            TwitchHelixOperation.USERS -> page(listOf(user("123")))
            TwitchHelixOperation.STREAMS -> page()
            TwitchHelixOperation.SCHEDULE -> {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                schedule(segments = listOf(segment(1_060_000L)))
            }
            else -> error("Unexpected operation")
        } }
        try {
            val work = workers.submit<CatalogResult<CatalogEntry>> { fixture.catalog.lookup("123") }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); fixture.catalog.close()
            assertEquals(1, fixture.cancels.get())
            release.countDown()
            failure(work.get(5, TimeUnit.SECONDS), CatalogFailure.ACCESS_REQUIRED)
            assertEquals(3, fixture.calls.size); assertEquals(0, fixture.refreshes.get())
        } finally { release.countDown(); workers.shutdownNow() }
    }

    @Test fun disconnectedLocalVideoImportDoesNotRequestScheduleAuthorizationOrMetadata() {
        val fixture = Fixture(saved = false)
        val local = TwitchLocalVideoImportCatalog(fixture.catalog)
        assertEquals(CatalogAccess.AVAILABLE, local.capabilities().lookup)
        assertEquals(CatalogAccess.NOT_VERIFIED, local.capabilities().collections.single { it.id == "history" }.access)
        val entry = value(local.lookup("https://www.twitch.tv/videos/789"))
        assertEquals(vod("789"), entry.resource)
        assertEquals(CatalogAvailability.UNKNOWN, entry.availability)
        assertNull(entry.scheduledStartEpochMs)
        assertTrue(fixture.calls.isEmpty()); assertEquals(0, fixture.authValidations.get())
        local.close()
    }

    @Test fun exactPublishedVideoAndChannelChildrenKeepExactVideoIdentityAndParentScopedContinuation() {
        val fixture = Fixture()
        fixture.execute = { request ->
            assertEquals(TwitchHelixOperation.VIDEOS, request.operation)
            if (request.url.query.contains("id=789") && !request.url.query.contains("user_id=")) page(listOf(video("789")))
            else if (request.url.query.contains("after=")) page(listOf(video("790")))
            else page(listOf(video("789")), "provider-child-cursor")
        }
        val lookup = value(fixture.catalog.lookup("https://www.twitch.tv/videos/789"))
        assertEquals(vod("789"), lookup.resource); assertEquals(CatalogAvailability.AVAILABLE, lookup.availability)
        val children = value(fixture.catalog.browse(CatalogQuery(parent = broadcaster("123"))))
        assertEquals(vod("789"), children.entries.single().resource)
        assertFalse(children.nextCursor!!.contains("provider-child-cursor"))
        val next = value(fixture.catalog.browse(CatalogQuery(parent = broadcaster("123"), cursor = children.nextCursor)))
        assertEquals(vod("790"), next.entries.single().resource)
        failure(fixture.catalog.browse(CatalogQuery(parent = broadcaster("456"), cursor = children.nextCursor)), CatalogFailure.INVALID_INPUT)
    }

    @Test fun missingUserOrVideoMetadataPreservesConfiguredIdentityAndNeverSubstitutesPrototypeSample() {
        val fixture = Fixture()
        val missingUser = value(fixture.catalog.refresh(broadcaster("123")))
        val missingVideo = value(fixture.catalog.refresh(vod("789")))
        assertEquals(broadcaster("123"), missingUser.resource); assertEquals(CatalogAvailability.UNAVAILABLE, missingUser.availability)
        assertEquals(vod("789"), missingVideo.resource); assertEquals(CatalogAvailability.UNAVAILABLE, missingVideo.availability)
        failure(fixture.catalog.lookup("123"), CatalogFailure.NOT_FOUND)
        val historical = CatalogResource(ProviderId("twitch"), "channel", "midnightsumo", CatalogIntent.CHANNEL)
        val before = fixture.calls.size
        failure(fixture.catalog.refresh(historical), CatalogFailure.NOT_VERIFIED)
        failure(fixture.catalog.resolve(historical), CatalogFailure.NOT_VERIFIED)
        failure(fixture.catalog.resolve(broadcaster("123")), CatalogFailure.NOT_VERIFIED)
        failure(fixture.catalog.resolve(vod("789")), CatalogFailure.NOT_VERIFIED)
        assertEquals(before, fixture.calls.size)
    }

    @Test fun duplicateAndEmptyPagesKeepOpaqueContinuationAndRepeatedProviderCursorCycleFailsClosed() {
        val fixture = Fixture(); var pages = 0
        fixture.execute = { request ->
            assertEquals(TwitchHelixOperation.SEARCH, request.operation)
            when (++pages) {
                1 -> page(listOf(found("123", false), found("123", false)), "raw-first")
                2 -> page(cursor = "raw-second")
                else -> page(cursor = "raw-first")
            }
        }
        val one = value(fixture.catalog.browse(CatalogQuery(search = "sumo")))
        assertEquals(1, one.entries.size)
        val two = value(fixture.catalog.browse(CatalogQuery(search = "sumo", cursor = one.nextCursor)))
        assertTrue(two.entries.isEmpty()); assertNotNull(two.nextCursor)
        assertFalse(two.nextCursor!!.contains("raw-second"))
        failure(fixture.catalog.browse(CatalogQuery(search = "sumo", cursor = two.nextCursor)), CatalogFailure.TEMPORARY)
    }

    @Test fun cursorsRejectOtherQueryInstanceGenerationAndAreBoundedInsteadOfPersisted() {
        val fixture = Fixture(); fixture.execute = { page(cursor = "raw-${it.url.query}") }
        val first = value(fixture.catalog.browse(CatalogQuery(search = "first"))).nextCursor!!
        failure(fixture.catalog.browse(CatalogQuery(search = "other", cursor = first)), CatalogFailure.INVALID_INPUT)
        val other = Fixture(); other.execute = { page() }
        failure(other.catalog.browse(CatalogQuery(search = "first", cursor = first)), CatalogFailure.INVALID_INPUT)
        repeat(33) { value(fixture.catalog.browse(CatalogQuery(search = "query$it"))) }
        failure(fixture.catalog.browse(CatalogQuery(search = "first", cursor = first)), CatalogFailure.INVALID_INPUT)
        val current = value(fixture.catalog.browse(CatalogQuery(search = "fresh"))).nextCursor!!
        fixture.seed()
        failure(fixture.catalog.browse(CatalogQuery(search = "fresh", cursor = current)), CatalogFailure.INVALID_INPUT)
        assertFalse(String(fixture.memory.bytes!!, Charsets.ISO_8859_1).contains("raw-"))
    }

    @Test fun unauthorizedRetriesCompleteOperationOnceDiscardingOldIntermediatePageAndRotatingOnlyCatalogPair() {
        val fixture = Fixture(); var followingCalls = 0; var statusCalls = 0
        fixture.execute = { request -> when (request.operation) {
            TwitchHelixOperation.FOLLOWED -> page(listOf(followed(if (++followingCalls == 1) "123" else "456")))
            TwitchHelixOperation.STREAMS -> if (++statusCalls == 1) TwitchHelixResponse(401) else page()
            else -> error("Unexpected operation")
        } }
        val result = value(fixture.catalog.browse(CatalogQuery(collectionId = "following")))
        assertEquals(broadcaster("456"), result.entries.single().resource)
        assertEquals(1, fixture.refreshes.get()); assertEquals(2, followingCalls); assertEquals(2, statusCalls)
        assertEquals(listOf("initial-access", "initial-access", "rotated-access-1", "rotated-access-1"), fixture.tokens)
        assertEquals("rotated-refresh-1", fixture.store.read()!!.credentials!!.refreshToken)
    }

    @Test fun secondUnauthorizedStopsAndContinuationCannotMigrateAcrossRefreshGeneration() {
        val fixture = Fixture(); fixture.execute = { TwitchHelixResponse(401) }
        failure(fixture.catalog.browse(CatalogQuery()), CatalogFailure.ACCESS_REQUIRED)
        assertEquals(1, fixture.refreshes.get()); assertEquals(2, fixture.calls.size)
        val paging = Fixture(); var calls = 0
        paging.execute = { if (++calls == 1) page(cursor = "raw-cursor") else TwitchHelixResponse(401) }
        val first = value(paging.catalog.browse(CatalogQuery(search = "sumo")))
        failure(paging.catalog.browse(CatalogQuery(search = "sumo", cursor = first.nextCursor)), CatalogFailure.INVALID_INPUT)
        assertEquals(1, paging.refreshes.get()); assertEquals(2, paging.calls.size)
    }

    @Test fun rateLimitHasBoundedDeadlineAndNoWaitWhileServiceUnavailableRetriesOnlyOnce() {
        val limited = Fixture(); limited.execute = { TwitchHelixResponse(429, retryAtEpochMs = Long.MAX_VALUE) }
        val rate = limited.catalog.browse(CatalogQuery()) as CatalogResult.Failure
        assertEquals(CatalogFailure.RATE_LIMITED, rate.reason); assertEquals(87_400_000L, rate.retryAtEpochMs)
        assertTrue(limited.waits.isEmpty()); assertEquals(1, limited.calls.size)
        val transient = Fixture(); var attempts = 0
        transient.execute = { if (++attempts == 1) TwitchHelixResponse(503) else page(listOf(stream("123"))) }
        assertEquals(broadcaster("123"), value(transient.catalog.browse(CatalogQuery())).entries.single().resource)
        assertEquals(listOf(250L), transient.waits); assertEquals(2, transient.calls.size)
        val unavailable = Fixture(); unavailable.execute = { TwitchHelixResponse(503) }
        failure(unavailable.catalog.browse(CatalogQuery()), CatalogFailure.TEMPORARY)
        assertEquals(2, unavailable.calls.size); assertEquals(listOf(250L), unavailable.waits)
    }

    @Test fun malformedOrUnrelatedMetadataFailsWithoutPublishingWrongIdentityOrRawProviderData() {
        val fixture = Fixture(); fixture.execute = { page(listOf(user("456"))) }
        failure(fixture.catalog.lookup("123"), CatalogFailure.TEMPORARY)
        fixture.execute = { page(listOf(video("789", owner = "456"))) }
        failure(fixture.catalog.browse(CatalogQuery(parent = broadcaster("123"))), CatalogFailure.TEMPORARY)
        fixture.execute = { page(listOf(found("123", true) + ("display_name" to "unsafe\ntext"))) }
        failure(fixture.catalog.browse(CatalogQuery(search = "sumo")), CatalogFailure.TEMPORARY)
        fixture.execute = { TwitchHelixResponse(200, mapOf("data" to "not-a-list")) }
        failure(fixture.catalog.browse(CatalogQuery()), CatalogFailure.TEMPORARY)
    }

    @Test fun signedUrlsAndCredentialsAreRejectedBeforeAuthorizationOrHelixAndLegacyPlaybackRemainsUnverified() {
        val fixture = Fixture()
        listOf("https://www.twitch.tv/channel123?token=synthetic", "https://user:pass@www.twitch.tv/channel123",
            "https://www.twitch.tv/videos/789#token", "https://evil.invalid/channel123").forEach {
            failure(fixture.catalog.lookup(it), CatalogFailure.INVALID_INPUT)
        }
        assertEquals(0, fixture.authValidations.get()); assertTrue(fixture.calls.isEmpty())
        assertFalse(fixture.catalog.toString().contains("initial-access"))
    }

    @Test fun statusLookupCanTraverseEmptyContinuationAndNeverTreatsOtherUserAsRequestedChannelsLiveState() {
        val fixture = Fixture(); var status = 0
        fixture.execute = { request -> when (request.operation) {
            TwitchHelixOperation.FOLLOWED -> page(listOf(followed("123"), followed("456")))
            TwitchHelixOperation.STREAMS -> if (++status == 1) page(cursor = "status-cursor") else page(listOf(stream("456")))
            else -> error("Unexpected operation")
        } }
        val result = value(fixture.catalog.browse(CatalogQuery(collectionId = "following")))
        assertEquals(listOf(CatalogAvailability.OFFLINE, CatalogAvailability.LIVE), result.entries.map { it.availability })
        assertEquals(2, status)
        val unrelated = Fixture(); unrelated.execute = { request -> if (request.operation == TwitchHelixOperation.FOLLOWED)
            page(listOf(followed("123"))) else page(listOf(stream("456"))) }
        failure(unrelated.catalog.browse(CatalogQuery(collectionId = "following")), CatalogFailure.TEMPORARY)
    }

    @Test fun selectedOwnerOrGrantChangeAfterResponseRejectsPublicationAndPreservesStoredPublicSelection() {
        val fixture = Fixture(); val original = fixture.memory.bytes!!.copyOf()
        fixture.execute = { fixture.publish = false; page(listOf(stream("123"))) }
        failure(fixture.catalog.browse(CatalogQuery()), CatalogFailure.ACCESS_REQUIRED)
        assertArrayEquals(original, fixture.memory.bytes)
        val replaced = Fixture(); replaced.execute = { replaced.seed(user = "9001"); page(listOf(stream("123"))) }
        failure(replaced.catalog.browse(CatalogQuery()), CatalogFailure.ACCESS_REQUIRED)
        assertEquals("9001", replaced.store.read()!!.userId)
    }

    @Test fun externalForgetDuringFollowingContinuationRequiresClearingPreviouslyDisplayedAccountResults() {
        val fixture = Fixture()
        fixture.execute = { request -> when (request.operation) {
            TwitchHelixOperation.FOLLOWED -> if (request.url.query.contains("after=")) {
                assertTrue(fixture.store.forget())
                page(listOf(followed("456")))
            } else page(listOf(followed("123")), "account-a-next")
            TwitchHelixOperation.STREAMS -> page()
            else -> error("Unexpected operation")
        } }
        val first = value(fixture.catalog.browse(CatalogQuery(collectionId = "following")))
        assertEquals(listOf(broadcaster("123")), first.entries.map { it.resource })
        failure(fixture.catalog.browse(CatalogQuery(collectionId = "following", cursor = first.nextCursor)),
            CatalogFailure.ACCESS_REQUIRED)
        assertEquals(3, fixture.calls.size) // Forgotten response cannot dispatch status enrichment or publish its rows.
        assertEquals(0, fixture.refreshes.get())
        assertEquals(TwitchCatalogGrantState.CLEARED, fixture.store.read()!!.state)
        failure(fixture.catalog.browse(CatalogQuery(collectionId = "following")), CatalogFailure.ACCESS_REQUIRED)
        assertEquals(3, fixture.calls.size)
    }

    @Test fun replacementAccountBeforeMoreRejectsOldContinuationWithoutDispatchAndRestartsOnlyFromItsOwnFirstPage() {
        val fixture = Fixture()
        fixture.execute = { request -> when (request.operation) {
            TwitchHelixOperation.FOLLOWED -> {
                assertFalse(request.url.query.contains("after="))
                if (request.url.query.contains("user_id=9000")) page(listOf(followed("123")), "account-a-next")
                else {
                    assertTrue(request.url.query.contains("user_id=9001"))
                    page(listOf(followed("456")))
                }
            }
            TwitchHelixOperation.STREAMS -> page()
            else -> error("Unexpected operation")
        } }
        val first = value(fixture.catalog.browse(CatalogQuery(collectionId = "following")))
        fixture.seed(user = "9001")
        val calls = fixture.calls.size
        failure(fixture.catalog.browse(CatalogQuery(collectionId = "following", cursor = first.nextCursor)),
            CatalogFailure.INVALID_INPUT) // An unusable More continuation tells the manager to clear and retry from page one.
        assertEquals(calls, fixture.calls.size)
        val replacement = value(fixture.catalog.browse(CatalogQuery(collectionId = "following")))
        assertEquals(listOf(broadcaster("456")), replacement.entries.map { it.resource })
        assertNull(replacement.nextCursor)
        assertEquals(listOf(broadcaster("123")), first.entries.map { it.resource })
        assertEquals("9001", fixture.store.read()!!.userId)
        assertEquals(0, fixture.refreshes.get())
    }

    @Test fun forgottenGrantDuringFailedExchangeStillClearsDiscoveryInsteadOfRetainingAProviderTemporaryFailure() {
        val fixture = Fixture()
        fixture.execute = { request -> if (request.operation == TwitchHelixOperation.FOLLOWED)
            page(listOf(followed("123")), "account-a-next") else page() }
        val first = value(fixture.catalog.browse(CatalogQuery(collectionId = "following")))
        fixture.execute = {
            assertTrue(fixture.store.forget())
            throw TwitchHelixNetworkException(DeviceNetworkFailure.IO)
        }
        failure(fixture.catalog.browse(CatalogQuery(collectionId = "following", cursor = first.nextCursor)),
            CatalogFailure.ACCESS_REQUIRED)
        assertEquals(3, fixture.calls.size)
        assertEquals(0, fixture.refreshes.get())
    }

    @Test fun deniedCapabilitiesAndAccessFailureEraseAllPreviouslyCachedPrivateContinuations() {
        listOf(false, true).forEach { throughCapabilities ->
            val fixture = Fixture()
            fixture.execute = { page(cursor = "private-provider-cursor") }
            value(fixture.catalog.browse(CatalogQuery(search = "private-account-query")))
            value(fixture.catalog.browse(CatalogQuery(search = "another-private-query")))
            assertEquals(2, retainedContinuations(fixture.catalog))
            assertTrue(fixture.store.forget())
            if (throughCapabilities) assertEquals(CatalogAccess.AUTHORIZATION_REQUIRED, fixture.catalog.capabilities().browse)
            else failure(fixture.catalog.browse(CatalogQuery()), CatalogFailure.ACCESS_REQUIRED)
            assertContinuationsCleared(fixture.catalog)
            fixture.publish = false
            repeat(2) { failure(fixture.catalog.browse(CatalogQuery()), CatalogFailure.ACCESS_REQUIRED) }
            assertContinuationsCleared(fixture.catalog)
            assertEquals(2, fixture.calls.size)
        }
    }

    @Test fun closeImmediatelyInterruptsActiveTransportAndLateResponseCannotBecomeCatalogPage() {
        val fixture = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val workers = Executors.newSingleThreadExecutor()
        fixture.execute = { page(cursor = "previous-private-cursor") }
        value(fixture.catalog.browse(CatalogQuery(search = "previous-private-query")))
        assertEquals(1, retainedContinuations(fixture.catalog))
        fixture.execute = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS));
            page(listOf(stream("123")), "late-private-cursor") }
        try {
            val work = workers.submit<CatalogResult<CatalogPage>> { fixture.catalog.browse(CatalogQuery()) }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); fixture.catalog.close()
            assertContinuationsCleared(fixture.catalog)
            assertEquals(1, fixture.cancels.get()); assertTrue(fixture.closes.get() >= 1)
            release.countDown()
            failure(work.get(5, TimeUnit.SECONDS), CatalogFailure.ACCESS_REQUIRED)
            assertContinuationsCleared(fixture.catalog)
            assertEquals(CatalogAccess.NOT_VERIFIED, fixture.catalog.capabilities().browse)
            failure(fixture.catalog.lookup("123"), CatalogFailure.ACCESS_REQUIRED)
        } finally { release.countDown(); workers.shutdownNow() }
    }

    @Test fun continuationIsSingleUseButWrongScopeCannotConsumeTheOwnersHandle() {
        val fixture = Fixture(); fixture.execute = { request ->
            if (request.url.query.contains("after=")) page(listOf(found("123", false))) else page(cursor = "raw-single-use")
        }
        val first = value(fixture.catalog.browse(CatalogQuery(search = "sumo")))
        failure(fixture.catalog.browse(CatalogQuery(search = "other", cursor = first.nextCursor)), CatalogFailure.INVALID_INPUT)
        assertEquals(broadcaster("123"), value(fixture.catalog.browse(CatalogQuery(search = "sumo", cursor = first.nextCursor))).entries.single().resource)
        val calls = fixture.calls.size
        failure(fixture.catalog.browse(CatalogQuery(search = "sumo", cursor = first.nextCursor)), CatalogFailure.INVALID_INPUT)
        assertEquals(calls, fixture.calls.size)
    }

    @Test fun catalogCannotBorrowAnotherInstancesSessionEvenBeforeAnyAuthorizationOrDiscovery() {
        val fixture = Fixture()
        assertThrows(IllegalArgumentException::class.java) {
            TwitchProviderCatalog(UUID.randomUUID().toString(), fixture.session,
                transportFactory = { error("Mismatched instance must not create a transport") })
        }
        assertEquals(0, fixture.authValidations.get()); assertTrue(fixture.calls.isEmpty())
        assertEquals(fixture.instance, fixture.session.instanceId)
    }
}
