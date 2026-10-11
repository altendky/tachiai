package net.fstab.tachiai.provider.twitch.catalog

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*
import net.fstab.tachiai.provider.twitch.DeviceAuthResponse
import net.fstab.tachiai.provider.twitch.SMART_TV_TWITCH_CLIENT_ID
import org.junit.Assert.*
import org.junit.Test

class TwitchContinueWatchingCatalogTest {
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        var hold: (() -> Unit)? = null
        override fun read(): ByteArray? { hold?.invoke(); return bytes?.copyOf() }
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf() }
    }
    private fun entry(id: String = "123") = CatalogEntry(CatalogResource(ProviderId("twitch"), "video", id, CatalogIntent.VIDEO), "Video $id")
    private inner class Fixture(saved: Boolean = true) {
        val instance = UUID.randomUUID().toString()
        val memory = Memory()
        val store = TwitchCatalogGrantStore(memory, instance, wallMs = { 1_000_000L })
        var owner = true; var now = 10_000L
        var cleanup = true; var kind = TWITCH_CONTINUE_WATCHING_ID
        val tokens = mutableListOf<String>()
        var fetch: (String) -> TwitchContinueWatchingResponse = { token ->
            if (token == "tachiai-invalid-bearer") TwitchContinueWatchingResponse(401)
            else TwitchContinueWatchingResponse(200, TwitchContinueWatchingPage("9000", listOf(entry())))
        }
        val session = TwitchCatalogSession(store, { object : TwitchCatalogTransport {
            override fun device(): DeviceAuthResponse = error("No Connect")
            override fun poll(deviceCode: String): DeviceAuthResponse = error("No consent")
            override fun validate(accessToken: String) = DeviceAuthResponse(200, mapOf("client_id" to SMART_TV_TWITCH_CLIENT_ID,
                "user_id" to "9000", "scopes" to listOf(TWITCH_CATALOG_SCOPE), "expires_in" to 7200))
            override fun refresh(refreshToken: String): DeviceAuthResponse = error("No refresh")
            override fun close() = Unit
        } }, { 1_000_000L }, { now }, { owner })
        val helix = TwitchProviderCatalog(instance, session, { gate -> object : TwitchHelixTransport {
            override fun execute(accessToken: String, request: TwitchHelixRequest): TwitchHelixResponse {
                check(gate())
                return TwitchHelixResponse(200, mapOf("data" to if (request.operation == TwitchHelixOperation.VIDEOS)
                    listOf(mapOf("id" to "123", "user_id" to "42", "title" to "Refreshed", "type" to "archive")) else emptyList<Map<String, Any>>(),
                    "pagination" to emptyMap<String, Any>()))
            }
            override fun cancelActiveRequest() = Unit
            override fun close() = Unit
        } }, { owner && !TwitchContinueWatchingAdmission.poisoned(instance) })
        val catalog = TwitchContinueWatchingCatalog(helix, session, { gate -> object : TwitchContinueWatchingTransport {
            override val collectionId get() = kind
            override val cleanupConfirmed get() = cleanup
            override fun fetch(accessToken: String, expectedUserId: String, nowEpochMs: Long): TwitchContinueWatchingResponse {
                check(gate()); assertEquals("9000", expectedUserId); tokens.add(accessToken); return fetch(accessToken)
            }
            override fun cancelActiveRequest() = Unit
            override fun close() = Unit
        } }, { owner }, { now }, { 2_000_000_000_000L }, cleanupWaitMs = 50)
        init { if (saved) store.commitConnection(store.beginConnection(), TwitchCatalogReplacement(
            TwitchCatalogCredentials("initial-access", "initial-refresh", 7200_000),
            TwitchCatalogValidation("9000", setOf(TWITCH_CATALOG_SCOPE), 7200_000), 7200_000))!! }
        fun continueAccess() = catalog.capabilities().collections.single { it.id == TWITCH_CONTINUE_WATCHING_ID }.access
        fun browse() = catalog.browse(CatalogQuery(collectionId = TWITCH_CONTINUE_WATCHING_ID))
    }
    private fun failure(result: CatalogResult<*>, reason: CatalogFailure = CatalogFailure.NOT_VERIFIED) =
        assertEquals(reason, (result as CatalogResult.Failure).reason)
    private fun page(result: CatalogResult<CatalogPage>) = (result as CatalogResult.Value).value
    @Test fun maintainedFollowingAloneCannotVerifyHistoryOrCollectionAndNoAccountDoesNoCollectionHttp() {
        val missing = Fixture(saved = false)
        assertEquals(CatalogAccess.AUTHORIZATION_REQUIRED, missing.continueAccess())
        failure(missing.browse()); assertTrue(missing.tokens.isEmpty()); missing.catalog.close()
        val fixture = Fixture()
        fixture.fetch = { TwitchContinueWatchingResponse(200, TwitchContinueWatchingPage("9000", emptyList())) }
        val capabilities = fixture.catalog.capabilities()
        assertEquals(CatalogAccess.AVAILABLE, capabilities.collections.single { it.id == "following" }.access)
        assertEquals(CatalogAccess.NOT_VERIFIED, capabilities.collections.single { it.id == "history" }.access)
        assertEquals(CatalogAccess.NOT_VERIFIED, capabilities.collections.single { it.id == TWITCH_CONTINUE_WATCHING_ID }.access)
        assertEquals(listOf("tachiai-invalid-bearer"), fixture.tokens); fixture.catalog.close()
    }
    @Test fun intendedEmptyIsAvailableOnlyAfterObservedSentinelRefusalAndPositivePageSeedIsOneUse() {
        val fixture = Fixture()
        fixture.fetch = { if (it == "tachiai-invalid-bearer") TwitchContinueWatchingResponse(403)
            else TwitchContinueWatchingResponse(200, TwitchContinueWatchingPage("9000", emptyList())) }
        assertEquals(CatalogAccess.AVAILABLE, fixture.continueAccess())
        assertTrue(page(fixture.browse()).entries.isEmpty()); assertEquals(2, fixture.tokens.size)
        assertTrue(page(fixture.browse()).entries.isEmpty()); assertEquals(3, fixture.tokens.size)
        assertNull(page(fixture.browse()).nextCursor)
        fixture.catalog.close()
    }
    @Test fun wrongUserKindLateOwnerAndExpiredProofNeverPublishAccountEntries() {
        for (type in listOf("user", "kind", "owner", "deadline")) {
            val fixture = Fixture()
            fixture.fetch = { token ->
                if (token == "tachiai-invalid-bearer") TwitchContinueWatchingResponse(401)
                else {
                    if (type == "owner") fixture.owner = false
                    if (type == "deadline") fixture.now += 30_000
                    TwitchContinueWatchingResponse(200, TwitchContinueWatchingPage(if (type == "user") "9001" else "9000", listOf(entry())),
                        if (type == "kind") "history" else TWITCH_CONTINUE_WATCHING_ID)
                }
            }
            failure(fixture.browse()); fixture.catalog.close()
        }
        val fixture = Fixture(); fixture.kind = "history"
        failure(fixture.browse()); assertTrue(fixture.tokens.isEmpty()); fixture.catalog.close()
    }
    @Test fun endpointRefusalPreservesFollowingGrantAndExactNonLiveSelectionsDelegateRefreshAndResolve() {
        val fixture = Fixture()
        val selected = page(fixture.browse()).entries.single()
        assertEquals(CatalogAvailability.UNKNOWN, selected.availability)
        assertEquals(selected.resource, (fixture.catalog.refresh(selected.resource) as CatalogResult.Value).value.resource)
        failure(fixture.catalog.resolve(selected.resource), CatalogFailure.NOT_VERIFIED)
        val before = fixture.memory.bytes!!.copyOf()
        fixture.fetch = { TwitchContinueWatchingResponse(401) }
        failure(fixture.browse())
        assertArrayEquals(before, fixture.memory.bytes)
        assertEquals(TwitchCatalogGrantState.READY, fixture.store.read()!!.state)
        assertEquals(CatalogAccess.AVAILABLE, fixture.catalog.capabilities().collections.single { it.id == "following" }.access)
        assertEquals(CatalogAccess.NOT_VERIFIED, fixture.catalog.capabilities().collections.single { it.id == "history" }.access)
        fixture.catalog.close()
    }
    @Test fun noArbitraryCursorSearchOrAutomaticConfigurationAndFailureIsNeverEmptySuccess() {
        val fixture = Fixture()
        failure(fixture.catalog.browse(CatalogQuery(collectionId = TWITCH_CONTINUE_WATCHING_ID, cursor = "opaque")), CatalogFailure.INVALID_INPUT)
        failure(fixture.catalog.browse(CatalogQuery(collectionId = TWITCH_CONTINUE_WATCHING_ID, search = "term")), CatalogFailure.INVALID_INPUT)
        assertTrue(fixture.tokens.isEmpty())
        fixture.fetch = { throw TwitchContinueWatchingException(TwitchContinueWatchingFailure.INVALID_RESPONSE) }
        failure(fixture.browse()); fixture.catalog.close()
    }
    @Test fun closeDoesNotWaitForHeldFinalProtectedReadAndSuppressesLatePage() {
        val fixture = Fixture()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        fixture.fetch = { token ->
            if (token == "tachiai-invalid-bearer") TwitchContinueWatchingResponse(401)
            else { fixture.memory.hold = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                TwitchContinueWatchingResponse(200, TwitchContinueWatchingPage("9000", listOf(entry()))) }
        }
        val workers = Executors.newSingleThreadExecutor()
        try {
            val result = workers.submit<CatalogResult<CatalogPage>> { fixture.browse() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            fixture.catalog.close()
            release.countDown(); failure(result.get(5, TimeUnit.SECONDS))
        } finally { release.countDown(); fixture.memory.hold = null; fixture.catalog.close(); workers.shutdownNow() }
    }
    @Test fun uncertainCleanupPoisonsOnlyExactInstanceAndCannotBeClearedByForgetOrLateReceipt() {
        val fixture = Fixture(); fixture.cleanup = false
        failure(fixture.browse())
        assertTrue(TwitchContinueWatchingAdmission.poisoned(fixture.instance))
        val calls = fixture.tokens.size
        fixture.cleanup = true; fixture.session.forget()
        failure(fixture.browse()); assertEquals(calls, fixture.tokens.size)
        assertTrue(TwitchContinueWatchingAdmission.poisoned(fixture.instance))
        val other = Fixture(); assertEquals(CatalogAccess.AVAILABLE, other.continueAccess())
        other.catalog.close(); fixture.catalog.close()
    }
}
