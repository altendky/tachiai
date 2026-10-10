package net.fstab.tachiai.provider.twitch.catalog

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.provider.twitch.*
import net.fstab.tachiai.platform.diagnostics.*
import org.junit.Assert.*
import org.junit.Test

class TwitchCatalogConnectionControllerTest {
    private class Storage : PrivateSecretStore {
        var bytes: ByteArray? = null
        var failWrites = false
        var beforeWrite: () -> Unit = {}
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) {
            beforeWrite()
            if (failWrites) throw java.io.IOException()
            bytes = plaintext.copyOf()
        }
    }
    private class Fixture : TwitchCatalogConnectionBinding {
        val id = UUID.randomUUID().toString()
        var selected = TwitchCatalogConnectionOwner(id, "Fixture Twitch", "Fixture route", "first")
        val storage = Storage()
        var clock = 1_000L
        val store = TwitchCatalogGrantStore(storage, id, wallMs = { 1_000_000L + clock })
        val polls = AtomicInteger()
        var closed = false
        var ownerFailure = false
        override fun owner(): TwitchCatalogConnectionOwner {
            if (ownerFailure) throw java.io.IOException("fixture-access")
            return selected
        }
        override fun session() = TwitchCatalogSession(store, { transport { true } },
            wallMs = { 1_000_000L + clock }, monotonicMs = { clock })
        override fun transport(canRequest: () -> Boolean) = object : TwitchCatalogTransport {
            override fun device(): DeviceAuthResponse {
                check(canRequest())
                return DeviceAuthResponse(200, mapOf("device_code" to "fixture-device", "user_code" to "FIXTURE123",
                    "verification_uri" to "https://www.twitch.tv/activate", "expires_in" to 60, "interval" to 1))
            }
            override fun poll(deviceCode: String): DeviceAuthResponse {
                check(canRequest()); polls.incrementAndGet()
                return DeviceAuthResponse(200, mapOf("access_token" to "fixture-access", "refresh_token" to "fixture-refresh",
                    "token_type" to "bearer", "scope" to listOf(TWITCH_CATALOG_SCOPE), "expires_in" to 3600))
            }
            override fun validate(accessToken: String): DeviceAuthResponse {
                check(canRequest())
                return DeviceAuthResponse(200, mapOf("client_id" to TACHIAI_TWITCH_CLIENT_ID, "user_id" to "fixture-user",
                    "scopes" to listOf(TWITCH_CATALOG_SCOPE), "expires_in" to 3600))
            }
            override fun refresh(refreshToken: String): DeviceAuthResponse = error("No refresh in this fixture")
            override fun close() = Unit
        }
        override fun close() { closed = true }
        fun controller(scope: CoroutineScope, io: CoroutineDispatcher = Dispatchers.Unconfined,
            wait: suspend (Long) -> Unit = { clock += it }, diagnostics: FailureReporter = FailureReporter.NONE) =
            TwitchCatalogConnectionController(this, selected, session(), scope, io, { clock }, wait,
                initiallyForeground = true, diagnostics = diagnostics)
    }
    private suspend fun idle(controller: TwitchCatalogConnectionController) = withTimeout(5_000) {
        controller.state.first { !it.busy && it.ready }
    }

    @Test fun connectUsesCatalogGrantAndPublishesNoCredentialsOrUserIdentity() = runBlocking {
        val fixture = Fixture(); val controller = fixture.controller(this)
        idle(controller); controller.connect()
        val state = idle(controller)
        assertTrue(state.hasSavedGrant); assertEquals(DeviceAuthPhase.SUCCEEDED, state.phase)
        assertEquals(1, fixture.polls.get()); assertNull(state.activation)
        listOf("fixture-access", "fixture-refresh", "fixture-user", "FIXTURE123").forEach {
            assertFalse(state.toString().contains(it))
        }
        assertEquals(TwitchCatalogGrantState.READY, fixture.store.read()!!.state)
        controller.close()
    }

    @Test fun unchangedOwnerResumesOriginalPausedActivationWithoutAnotherDeviceRequest() = runBlocking {
        val fixture = Fixture(); val waiting = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val controller = fixture.controller(this, wait = { waiting.complete(Unit); release.await(); fixture.clock += it })
        idle(controller); controller.connect(); waiting.await()
        controller.setForeground(false); release.complete(Unit)
        withTimeout(5_000) { controller.state.first { it.phase == DeviceAuthPhase.PAUSED } }
        assertEquals(0, fixture.polls.get()); assertNotNull(controller.state.value.activation)
        controller.setForeground(true)
        assertTrue(idle(controller).hasSavedGrant); assertEquals(1, fixture.polls.get())
        controller.close()
    }

    @Test fun changedSavedRouteOnReturnRejectsPendingConsentAndLeavesStorageEmpty() = runBlocking {
        val fixture = Fixture(); val waiting = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val controller = fixture.controller(this, wait = { waiting.complete(Unit); release.await(); fixture.clock += it })
        idle(controller); controller.connect(); waiting.await(); controller.setForeground(false)
        fixture.selected = TwitchCatalogConnectionOwner(fixture.id, "Fixture Twitch", "Replacement route", "second")
        controller.setForeground(true)
        withTimeout(5_000) { controller.state.first { !it.busy && it.message != null } }
        assertFalse(controller.state.value.ready); assertNull(controller.state.value.activation)
        assertEquals(0, fixture.polls.get()); assertEquals(TwitchCatalogGrantState.RECONNECT, fixture.store.read()!!.state)
        release.complete(Unit); controller.close()
    }

    @Test fun acceptedForgetSurvivesCloseAndNewControllerWaitsForItsClear() = runBlocking {
        val fixture = Fixture(); val controller = fixture.controller(this)
        idle(controller); controller.connect(); idle(controller)
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        fixture.storage.beforeWrite = { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }
        // The accepted mutation owns an independent worker, not this UI scope.
        val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val writer = fixture.controller(writerScope, Dispatchers.Default)
        idle(writer); writer.forget(); withTimeout(5_000) { entered.await() }
        writer.close(); writerScope.cancel()
        assertTrue(TwitchCatalogConnectionWrites.isPending(fixture.id))
        val recreated = fixture.controller(this)
        assertFalse(recreated.state.value.ready)
        release.countDown()
        withTimeout(5_000) { TwitchCatalogConnectionWrites.await(fixture.id) }
        assertFalse(idle(recreated).hasSavedGrant)
        assertEquals(TwitchCatalogGrantState.CLEARED, fixture.store.read()!!.state)
        recreated.close(); controller.close()
    }

    @Test fun failedForgetKeepsAccessBlockedAcrossControllerRecreationUntilRetry() = runBlocking {
        val fixture = Fixture(); val controller = fixture.controller(this)
        idle(controller); controller.connect(); idle(controller)
        fixture.storage.failWrites = true; controller.forget()
        withTimeout(5_000) { TwitchCatalogConnectionWrites.await(fixture.id) }
        assertTrue(TwitchCatalogConnectionWrites.isFailed(fixture.id)); assertFalse(controller.state.value.ready)
        assertTrue(controller.state.value.canForget)
        controller.close()
        val recreated = fixture.controller(this)
        withTimeout(5_000) { recreated.state.first { it.canForget } }
        assertFalse(recreated.state.value.ready)
        fixture.storage.failWrites = false; recreated.forget()
        withTimeout(5_000) { TwitchCatalogConnectionWrites.await(fixture.id) }
        assertFalse(TwitchCatalogConnectionWrites.isFailed(fixture.id))
        assertFalse(idle(recreated).hasSavedGrant)
        recreated.close()
    }

    @Test fun missingSavedRouteBlocksNetworkActionsButAllowsLocalForget() = runBlocking {
        val fixture = Fixture(); val original = fixture.controller(this)
        idle(original); original.connect(); idle(original); original.close()
        fixture.selected = TwitchCatalogConnectionOwner(fixture.id, "Fixture Twitch", "Saved route unavailable", "unavailable",
            routeUsable = false)
        val controller = fixture.controller(this)
        withTimeout(5_000) { controller.state.first { it.canForget && !it.busy } }
        assertFalse(controller.state.value.ready)
        controller.connect(); controller.revalidate()
        assertEquals(1, fixture.polls.get())
        controller.forget(); withTimeout(5_000) { TwitchCatalogConnectionWrites.await(fixture.id) }
        assertEquals(TwitchCatalogGrantState.CLEARED, fixture.store.read()!!.state)
        assertFalse(controller.state.value.ready); assertFalse(controller.state.value.hasSavedGrant)
        controller.close()
    }

    @Test fun unreadableOwnerReportsOnlyRedactedDiagnosticAndBlocksAccess() = runBlocking {
        val fixture = Fixture(); val observations = mutableListOf<FailureObservation>()
        val controller = fixture.controller(this, diagnostics = FailureReporter({ observations.add(it) }))
        idle(controller); fixture.ownerFailure = true; controller.setForeground(true)
        withTimeout(5_000) { controller.state.first { it.message != null } }
        assertFalse(controller.state.value.ready)
        assertTrue(observations.any { it.stage == FailureStage.CATALOG_AUTH_OWNER })
        assertFalse(observations.toString().contains("fixture-access"))
        controller.close()
    }

    @Test fun failedPostCommitOwnerReadPreservesGrantButEndsAccessUntilReopen() = runBlocking {
        val fixture = Fixture(); val controller = fixture.controller(this)
        idle(controller)
        var writes = 0
        fixture.storage.beforeWrite = { if (++writes == 2) fixture.ownerFailure = true }
        controller.connect()
        withTimeout(5_000) { controller.state.first { !it.busy && it.message != null } }
        assertEquals(TwitchCatalogGrantState.READY, fixture.store.read()!!.state)
        assertFalse(controller.state.value.ready); assertTrue(controller.state.value.canForget)
        assertFalse(controller.state.value.message!!.contains("Nothing was accepted"))
        fixture.ownerFailure = false
        controller.setForeground(true); controller.revalidate()
        assertFalse(controller.state.value.ready)
        controller.close()
    }
}
