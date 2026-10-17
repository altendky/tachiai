package net.fstab.tachiai.provider.twitch

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class TwitchProviderLoginControllerTest {
    private class Storage : PrivateSecretStore {
        var bytes: ByteArray? = null
        var failWrites = false
        var beforeWrite: () -> Unit = {}
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) {
            if (failWrites) throw java.io.IOException()
            beforeWrite()
            bytes = plaintext.copyOf()
        }
    }
    private class Clock {
        var wall = 1_000_000L
        var monotonic = 1_000L
        fun advance(ms: Long) { wall += ms; monotonic += ms }
    }
    private class QueuedDispatcher : CoroutineDispatcher() {
        @Volatile var queueDispatches = true
        private val queued = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (queueDispatches) queued.add(block) else Dispatchers.Default.dispatch(context, block)
        }
        fun runQueued() {
            while (true) (queued.poll() ?: return).run()
        }
    }
    private class Transport : TwitchDeviceTransport {
        var scopes: Any? = null
        var devices = 0
        var polls = 0
        var validations = 0
        var onValidate: () -> Unit = {}
        val closeCount = AtomicInteger()
        val closedAgain = CompletableDeferred<Unit>()
        override fun device(clientId: String): DeviceAuthResponse {
            assertEquals(SMART_TV_TWITCH_CLIENT_ID, clientId)
            devices++
            return DeviceAuthResponse(200, mapOf("device_code" to "invented-provider-code", "user_code" to "LOGIN123",
                "verification_uri" to "https://www.twitch.tv/activate?public=true&device-code=LOGIN123",
                "expires_in" to 60, "interval" to 1))
        }
        override fun poll(clientId: String, deviceCode: String): DeviceAuthResponse {
            assertEquals(SMART_TV_TWITCH_CLIENT_ID, clientId)
            polls++
            return DeviceAuthResponse(200, mapOf("access_token" to "invented-provider-token", "token_type" to "bearer"))
        }
        override fun validate(accessToken: String): DeviceAuthResponse {
            assertEquals("invented-provider-token", accessToken)
            validations++
            onValidate()
            return DeviceAuthResponse(200, mapOf("client_id" to SMART_TV_TWITCH_CLIENT_ID,
                "user_id" to "invented-provider-user", "expires_in" to 0, "scopes" to scopes))
        }
        override fun close() { if (closeCount.incrementAndGet() >= 2) closedAgain.complete(Unit) }
    }
    private fun cache(storage: Storage, clock: Clock) = TwitchSavedAuthorization(storage,
        { clock.wall }, { clock.monotonic }, TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL)
    private suspend fun save(cache: TwitchSavedAuthorization, clock: Clock) =
        cache.saveValidated("invented-provider-token", clock.monotonic + 100_000, cache.revision())
    private suspend fun awaitIdle(controller: TwitchProviderLoginController) = withTimeout(5_000) {
        controller.state.first { !it.busy }
    }

    @Test fun connectUsesExactExistingLocalFlowAndPublishesOnlySafeSummary() = runBlocking {
        val storage = Storage(); val clock = Clock(); val cache = cache(storage, clock); val request = Transport()
        val controller = TwitchProviderLoginController(cache, this, { request }, Dispatchers.Unconfined,
            { clock.monotonic }, { clock.advance(it) })
        controller.connect()
        val state = awaitIdle(controller)
        assertEquals(SavedAuthorizationState.AVAILABLE, state.storage)
        assertEquals(DeviceAuthPhase.SUCCEEDED, state.phase)
        assertEquals(clock.wall + SMART_TV_LOCAL_RETENTION_MS, state.expiresAtMs)
        assertEquals(1, request.polls); assertEquals(1, request.validations)
        assertNull(state.activation)
        assertFalse(state.toString().contains("invented-provider-token"))
        assertFalse(state.toString().contains("invented-provider-user"))
        assertEquals("invented-provider-token", cache.read().lease!!.record.token)
        controller.close()
    }

    @Test fun unexpectedPermissionsAreRejectedWithoutSaving() = runBlocking {
        val storage = Storage(); val clock = Clock(); val request = Transport().apply { scopes = listOf("chat:read") }
        val controller = TwitchProviderLoginController(cache(storage, clock), this, { request }, Dispatchers.Unconfined,
            { clock.monotonic }, { clock.advance(it) })
        controller.connect()
        val state = awaitIdle(controller)
        assertEquals(DeviceAuthPhase.SCOPE_MISMATCH, state.phase)
        assertEquals(SavedAuthorizationState.MISSING, state.storage)
        assertNull(storage.bytes); assertNull(state.activation)
        controller.close()
    }

    @Test fun explicitRevalidationExtendsOnlyTheSavedLocalGrantAndInvalidatesOldLease() = runBlocking {
        val storage = Storage(); val clock = Clock(); val cache = cache(storage, clock); val request = Transport()
        save(cache, clock); val lease = cache.read().lease!!
        val controller = TwitchProviderLoginController(cache, this, { request }, Dispatchers.Unconfined, { clock.monotonic })
        controller.revalidate()
        val state = awaitIdle(controller)
        assertEquals(SavedAuthorizationState.AVAILABLE, state.storage)
        assertEquals(clock.wall + SMART_TV_LOCAL_RETENTION_MS, state.expiresAtMs)
        assertEquals(0, request.devices); assertEquals(0, request.polls); assertEquals(1, request.validations)
        assertFalse(cache.isCurrent(lease))
        assertNull(state.activation)
        controller.close()
    }

    @Test fun failedReconnectSavePreservesPreviousGrantAndReportsFailure() = runBlocking {
        val storage = Storage(); val clock = Clock(); val cache = cache(storage, clock); val request = Transport()
        save(cache, clock); val original = storage.bytes!!.copyOf(); val lease = cache.read().lease!!
        storage.failWrites = true
        val controller = TwitchProviderLoginController(cache, this, { request }, Dispatchers.Unconfined,
            { clock.monotonic }, { clock.advance(it) })
        controller.connect()
        val state = awaitIdle(controller)
        assertEquals(SavedAuthorizationState.AVAILABLE, state.storage)
        assertEquals("Twitch approved the login, but it could not be saved. Reconnect to retry.", state.message)
        assertArrayEquals(original, storage.bytes)
        assertTrue(cache.isCurrent(lease))
        controller.close()
    }

    @Test fun expiredStoredLoginRequiresReconnectAndRevalidationCannotExtendIt() = runBlocking {
        val storage = Storage(); val clock = Clock(); val cache = cache(storage, clock); val request = Transport()
        save(cache, clock); val original = storage.bytes!!.copyOf(); clock.advance(100_000)
        val controller = TwitchProviderLoginController(cache, this, { request }, Dispatchers.Unconfined, { clock.monotonic })
        controller.refresh()
        assertEquals(SavedAuthorizationState.EXPIRED, awaitIdle(controller).storage)
        controller.revalidate()
        assertEquals(SavedAuthorizationState.EXPIRED, awaitIdle(controller).storage)
        assertEquals(0, request.validations); assertArrayEquals(original, storage.bytes)
        controller.close()
    }

    @Test fun browserBackgroundPausesAuthorizationThenResumesSameChallenge() = runBlocking {
        val storage = Storage(); val clock = Clock(); val request = Transport()
        val waitEntered = CompletableDeferred<Unit>(); val continueWait = CompletableDeferred<Unit>()
        val controller = TwitchProviderLoginController(cache(storage, clock), this, { request }, Dispatchers.Unconfined,
            { clock.monotonic }, { waitEntered.complete(Unit); continueWait.await(); clock.advance(it) })
        controller.connect()
        withTimeout(5_000) { waitEntered.await() }
        assertNotNull(controller.state.value.activation)
        controller.setForeground(false); continueWait.complete(Unit)
        withTimeout(5_000) { controller.state.first { it.phase == DeviceAuthPhase.PAUSED } }
        assertEquals(0, request.polls)
        controller.setForeground(true)
        assertEquals(SavedAuthorizationState.AVAILABLE, awaitIdle(controller).storage)
        assertEquals(1, request.polls)
        controller.close()
    }

    @Test fun forgetCancelsBlockedAuthorizationAndRejectsItsDelayedSaveAndStatus() = runBlocking {
        val storage = Storage(); val clock = Clock(); val cache = cache(storage, clock); val request = Transport()
        save(cache, clock); val lease = cache.read().lease!!
        val validating = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        request.onValidate = { validating.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }
        val controller = TwitchProviderLoginController(cache, this, { request }, Dispatchers.Default,
            { clock.monotonic }, { clock.advance(it) })
        try {
            controller.connect()
            withTimeout(5_000) { validating.await() }
            controller.forget()
            val state = awaitIdle(controller)
            assertEquals(SavedAuthorizationState.MISSING, state.storage)
            assertFalse(cache.isCurrent(lease))
            release.countDown()
            withTimeout(5_000) { request.closedAgain.await() }
            assertEquals(SavedAuthorizationState.MISSING, cache.read().state)
            assertEquals("Twitch login forgotten on this device.", controller.state.value.message)
            assertNull(controller.state.value.activation)
        } finally { release.countDown(); controller.close() }
    }

    @Test fun backgroundingRevalidationCancelsWithoutExtendingRetention() = runBlocking {
        val storage = Storage(); val clock = Clock(); val cache = cache(storage, clock); val request = Transport()
        save(cache, clock); val original = storage.bytes!!.copyOf()
        val validating = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        request.onValidate = { validating.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }
        val controller = TwitchProviderLoginController(cache, this, { request }, Dispatchers.Default, { clock.monotonic })
        try {
            controller.revalidate()
            withTimeout(5_000) { validating.await() }
            controller.setForeground(false)
            assertFalse(controller.state.value.busy)
            release.countDown()
            withTimeout(5_000) { request.closedAgain.await() }
            assertArrayEquals(original, storage.bytes)
            assertNull(controller.state.value.activation)
        } finally { release.countDown(); controller.close() }
    }

    @Test fun acceptedForgetCompletesAfterQueuedWorkerEditorDepartureAndBlocksNewCommands() = runBlocking {
        val storage = Storage(); val clock = Clock(); val cache = cache(storage, clock)
        save(cache, clock); val lease = cache.read().lease!!
        val dispatcher = QueuedDispatcher()
        var transports = 0
        val controller = TwitchProviderLoginController(cache, this, { transports++; Transport() }, dispatcher, { clock.monotonic })
        controller.forget() // The clear is accepted, but its worker has not run.
        controller.setForeground(false)
        assertTrue(controller.state.value.busy)
        assertEquals(ProviderLoginOperation.FORGET, controller.state.value.operation)
        assertNull(controller.state.value.phase)
        controller.setForeground(true)
        controller.connect(); controller.revalidate()
        assertTrue(controller.state.value.busy)
        assertEquals(ProviderLoginOperation.FORGET, controller.state.value.operation)
        assertEquals(0, transports)
        assertEquals(SavedAuthorizationState.AVAILABLE, cache.read().state)
        controller.close() // Cancels the UI observer before the queued clear starts.
        dispatcher.runQueued()
        assertEquals(SavedAuthorizationState.MISSING, cache.read().state)
        assertFalse(cache.isCurrent(lease))
        assertNull(controller.state.value.activation)
        assertNull(controller.state.value.phase) // The departed editor no longer observes completion.
    }

    @Test fun synchronousForgetKeepsCompletionMessageAndClearsBusyState() = runBlocking {
        val storage = Storage(); val clock = Clock(); val cache = cache(storage, clock)
        save(cache, clock)
        val uiScope = CoroutineScope(Dispatchers.Unconfined)
        val controller = TwitchProviderLoginController(cache, uiScope, { Transport() }, Dispatchers.Unconfined, { clock.monotonic })
        try {
            controller.forget()
            val state = controller.state.value // Both the worker and UI observation completed inline.
            assertFalse(state.busy)
            assertNull(state.operation)
            assertEquals(SavedAuthorizationState.MISSING, state.storage)
            assertEquals("Twitch login forgotten on this device.", state.message)
            assertEquals(SavedAuthorizationState.MISSING, cache.read().state)
        } finally { controller.close(); uiScope.cancel() }
    }

    @Test fun acceptedForgetStartsEvenWhenUiScopeAlreadyCancelled() = runBlocking {
        val storage = Storage(); val clock = Clock(); val cache = cache(storage, clock)
        save(cache, clock)
        val dispatcher = QueuedDispatcher(); val uiScope = CoroutineScope(Dispatchers.Unconfined)
        val controller = TwitchProviderLoginController(cache, uiScope, { Transport() }, dispatcher, { clock.monotonic })
        uiScope.cancel()
        try {
            controller.forget()
            assertEquals(SavedAuthorizationState.AVAILABLE, cache.read().state)
            dispatcher.runQueued()
            assertEquals(SavedAuthorizationState.MISSING, cache.read().state)
        } finally { controller.close(); dispatcher.runQueued() }
    }

    @Test fun queuedForgetResumesCompletionObservationAfterForegroundReturn() = runBlocking {
        val storage = Storage(); val clock = Clock(); val cache = cache(storage, clock)
        save(cache, clock)
        val dispatcher = QueuedDispatcher(); val uiScope = CoroutineScope(Dispatchers.Unconfined)
        var transports = 0
        val controller = TwitchProviderLoginController(cache, uiScope, { transports++; Transport() }, dispatcher, { clock.monotonic })
        try {
            controller.forget()
            controller.setForeground(false)
            assertTrue(controller.state.value.busy)
            assertEquals(ProviderLoginOperation.FORGET, controller.state.value.operation)
            controller.setForeground(true)
            controller.connect(); controller.revalidate(); controller.refresh(); controller.forget()
            assertEquals(0, transports)
            assertTrue(controller.state.value.busy)
            assertEquals(SavedAuthorizationState.AVAILABLE, cache.read().state)
            dispatcher.runQueued()
            val state = awaitIdle(controller)
            assertEquals(SavedAuthorizationState.MISSING, state.storage)
            assertEquals("Twitch login forgotten on this device.", state.message)
            assertNull(state.activation)
        } finally { controller.close(); uiScope.cancel(); dispatcher.runQueued() }
    }

    @Test fun acceptedForgetClearsAlreadyEnteredSaveEvenAfterEditorDeparture() = runBlocking {
        val storage = Storage(); val clock = Clock(); val cache = cache(storage, clock); val request = Transport()
        save(cache, clock); val lease = cache.read().lease!!
        val writing = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        storage.beforeWrite = { writing.complete(Unit); check(release.await(5, TimeUnit.SECONDS)) }
        val dispatcher = QueuedDispatcher().apply { queueDispatches = false }
        val controller = TwitchProviderLoginController(cache, this, { request }, dispatcher,
            { clock.monotonic }, { clock.advance(it) })
        try {
            controller.connect()
            withTimeout(5_000) { writing.await() } // saveValidated has passed its cancellation checks.
            dispatcher.queueDispatches = true
            controller.forget()
            controller.close() // Old save is entered; the accepted clear is still queued.
            release.countDown()
            withTimeout(5_000) { request.closedAgain.await() }
            assertEquals(SavedAuthorizationState.AVAILABLE, cache.read().state) // Entered write committed.
            storage.beforeWrite = {}
            dispatcher.runQueued()
            assertEquals(SavedAuthorizationState.MISSING, cache.read().state)
            assertFalse(cache.isCurrent(lease))
            assertNull(controller.state.value.activation)
        } finally { release.countDown(); controller.close(); dispatcher.runQueued() }
    }

    @Test fun failedForgetPreservesStoredGrantAndDoesNotClaimCrossProcessInvalidation() = runBlocking {
        val storage = Storage(); val clock = Clock(); val cache = cache(storage, clock)
        save(cache, clock); val original = storage.bytes!!.copyOf(); val lease = cache.read().lease!!
        storage.failWrites = true
        val controller = TwitchProviderLoginController(cache, this, { Transport() }, Dispatchers.Unconfined, { clock.monotonic })
        controller.forget()
        val state = awaitIdle(controller)
        assertEquals(SavedAuthorizationState.AVAILABLE, state.storage)
        assertEquals("The saved login could not be cleared. Retry Forget before using playback.", state.message)
        assertArrayEquals(original, storage.bytes)
        assertFalse(cache.isCurrent(lease)) // Same-process leases only; disk still contains the grant.
        controller.close()
    }
}
