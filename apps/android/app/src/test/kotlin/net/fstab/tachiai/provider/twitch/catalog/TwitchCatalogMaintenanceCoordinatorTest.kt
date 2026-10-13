package net.fstab.tachiai.provider.twitch.catalog

import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.provider.twitch.DeviceAuthResponse
import net.fstab.tachiai.provider.twitch.SMART_TV_TWITCH_CLIENT_ID
import org.junit.Assert.*
import org.junit.Test

class TwitchCatalogMaintenanceCoordinatorTest {
    private class Tasks(private val now: () -> Long) {
        private class Scheduled(val due: Long, val callback: () -> Unit) { var closed = false }
        private val timers = mutableListOf<Scheduled>()
        private val workers = ArrayDeque<() -> Unit>()
        var reject = false
        val scheduled get() = timers.count { !it.closed }
        val queued get() = workers.size
        fun schedule(delay: Long, callback: () -> Unit): AutoCloseable {
            val task = Scheduled(now() + delay, callback); timers.add(task)
            return AutoCloseable { task.closed = true }
        }
        fun dispatch(work: () -> Unit): Boolean {
            if (reject) return false
            workers.addLast(work); return true
        }
        fun fire() {
            timers.filter { !it.closed && it.due <= now() }.forEach { it.closed = true; it.callback() }
        }
        fun fireEarly() { timers.first { !it.closed }.also { it.closed = true; it.callback() } }
        fun drain() { var count = 0; while (workers.isNotEmpty()) { check(++count < 100); workers.removeFirst().invoke() } }
    }
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        var failWrite = false
        var forbid = false
        var reads = 0
        var onRead: () -> Unit = {}
        override fun read(): ByteArray? { check(!forbid); reads++; onRead(); return bytes?.copyOf() }
        override fun write(plaintext: ByteArray) { check(!forbid); if (failWrite) throw IOException(); bytes = plaintext.copyOf() }
    }
    private class Fixture(timer: Boolean = false) {
        val memory = Memory()
        @Volatile var wall = 1_000_000L
        @Volatile var mono = 10_000L
        val tasks = Tasks { mono }
        val store = TwitchCatalogGrantStore(memory, UUID.randomUUID().toString(), wallMs = { wall })
        val validations = AtomicInteger()
        val refreshes = AtomicInteger()
        val closes = AtomicInteger()
        var seconds = 7200
        var status = 200
        var user = "fixture-user"
        var owner = true
        var onValidate: () -> Unit = {}
        var onRefresh: () -> Unit = {}
        var onClose: () -> Unit = {}
        var onScopeOwner: () -> Unit = {}
        var idle = 0
        val coordinator = TwitchCatalogMaintenanceCoordinator(store, { gate ->
            object : TwitchCatalogTransport {
                override fun device(): DeviceAuthResponse = error("No consent")
                override fun poll(deviceCode: String): DeviceAuthResponse = error("No consent")
                override fun validate(accessToken: String): DeviceAuthResponse {
                    check(gate()); validations.incrementAndGet(); onValidate(); check(gate())
                    return DeviceAuthResponse(status, mapOf("client_id" to SMART_TV_TWITCH_CLIENT_ID,
                        "user_id" to user, "scopes" to listOf(TWITCH_CATALOG_SCOPE), "expires_in" to seconds))
                }
                override fun refresh(refreshToken: String): DeviceAuthResponse {
                    check(gate()); val number = refreshes.incrementAndGet(); onRefresh(); check(gate())
                    return DeviceAuthResponse(200, mapOf("access_token" to "fresh-$number", "refresh_token" to "refresh-$number",
                        "scope" to listOf(TWITCH_CATALOG_SCOPE), "expires_in" to 7200, "token_type" to "bearer"))
                }
                override fun close() { closes.incrementAndGet(); onClose() }
            }
        }, { onScopeOwner(); owner }, { wall }, { mono }, { idle++ },
            schedule = if (timer) tasks::schedule else null, dispatch = if (timer) tasks::dispatch else null)
        fun session(consumerOwner: () -> Boolean = { owner }, participates: () -> Boolean = { true }) = TwitchCatalogSession(store,
            { error("Consumer binding must not own maintenance transport") }, { wall }, { mono }, consumerOwner, coordinator, participates)
        fun save(local: Boolean = false): TwitchCatalogStoredGrant = store.commitConnection(store.beginConnection(),
            TwitchCatalogReplacement(TwitchCatalogCredentials("initial", "initial-refresh", 7_200_000),
                TwitchCatalogValidation("fixture-user", setOf(TWITCH_CATALOG_SCOPE), if (local) null else 7_200_000), 7_200_000,
                localRetentionUntilMs = if (local) wall + TWITCH_CATALOG_LOCAL_RETENTION_MS else null,
                savedAtMs = wall))!!
        fun advance(ms: Long) { wall += ms; mono += ms }
    }

    @Test fun actualForegroundPeersReceiveOneDueValidationAndAdoptWithoutAnotherRequest() {
        val f = Fixture(timer = true); f.save(local = true); f.seconds = 0
        val first = f.session(); val second = f.session()
        try {
            val old = first.validate().lease!!; second.validate()
            f.tasks.drain()
            var firstSignals = 0; var secondSignals = 0
            first.observeMaintenance { firstSignals++ }; second.observeMaintenance { secondSignals++ }
            val count = f.validations.get(); assertEquals(1, f.tasks.scheduled)
            f.advance(TWITCH_CATALOG_VALIDATION_INTERVAL_MS - 60_000 - 1); f.tasks.fire()
            assertEquals(0, f.tasks.queued); assertEquals(count, f.validations.get())
            f.advance(1); val reads = f.memory.reads
            f.memory.forbid = true
            try { f.tasks.fire(); assertEquals(reads, f.memory.reads) } finally { f.memory.forbid = false }
            assertEquals(1, f.tasks.queued); assertEquals(count, f.validations.get())
            f.tasks.drain()
            assertEquals(count + 1, f.validations.get()); assertEquals(0, f.refreshes.get())
            assertEquals(1, firstSignals); assertEquals(1, secondSignals)
            val adopted = first.adoptAvailableMaintenance()!!.lease!!
            assertEquals(old.grant.generation, adopted.grant.generation)
            assertTrue(adopted.deadlineMs > old.deadlineMs); assertTrue(first.isCurrent(adopted))
            assertNotNull(second.adoptAvailableMaintenance()!!.lease)
            assertEquals(count + 1, f.validations.get()); assertEquals(1, f.tasks.scheduled)
        } finally { first.close(); second.close(); f.tasks.drain() }
        assertTrue(f.coordinator.isIdle)
    }

    @Test fun unvalidatedEnrollmentDisabledSchedulerAndDetachedConsumersCannotCreateMaintenanceInterest() {
        val disabled = Fixture(); disabled.save(); disabled.session().use { it.validate() }
        assertEquals(0, disabled.tasks.scheduled)
        val f = Fixture(timer = true); val saved = f.save()
        val bare = f.coordinator.attach({ true }, { true })
        bare.enroll(saved); assertEquals(0, f.tasks.scheduled); bare.close()
        val session = f.session(); session.validate(); assertEquals(1, f.tasks.scheduled)
        session.setForeground(false); assertEquals(0, f.tasks.scheduled)
        val count = f.validations.get(); f.advance(TWITCH_CATALOG_VALIDATION_INTERVAL_MS); f.tasks.fire(); f.tasks.drain()
        assertEquals(count, f.validations.get()); session.close(); assertTrue(f.coordinator.isIdle)
    }

    @Test fun shortFiniteExpiryGetsOneClampedAttemptWithoutZeroDelayLoopOrLifetimeExtension() {
        val f = Fixture(timer = true); f.save(); f.seconds = 10
        val session = f.session()
        try {
            val old = session.validate().lease!!
            f.advance(4_999); f.tasks.fire(); assertEquals(0, f.tasks.queued)
            f.advance(1); f.tasks.fire(); f.tasks.drain()
            assertEquals(2, f.validations.get()); assertEquals(1, f.tasks.scheduled)
            // This provider-bound record is long-lived; positive validation can
            // supply a later short freshness deadline, forming the next cycle.
            val adopted = session.adoptAvailableMaintenance()!!.lease!!
            assertTrue(adopted.deadlineMs > old.deadlineMs)
        } finally { session.close(); f.tasks.drain() }
        val fixed = Fixture(timer = true)
        fixed.store.commitConnection(fixed.store.beginConnection(), TwitchCatalogReplacement(
            TwitchCatalogCredentials("initial", "initial-refresh", 10_000),
            TwitchCatalogValidation("fixture-user", setOf(TWITCH_CATALOG_SCOPE), 10_000), 10_000))
        fixed.seconds = 10
        fixed.session().use { held ->
            val old = held.validate().lease!!
            fixed.advance(5_000); fixed.tasks.fire(); fixed.tasks.drain()
            val adopted = held.adoptAvailableMaintenance()!!.lease!!
            assertEquals(old.deadlineMs, adopted.deadlineMs); assertEquals(0, fixed.tasks.scheduled)
            fixed.advance(5_000); fixed.tasks.fire(); fixed.tasks.drain()
            assertFalse(held.isCurrent(adopted)); assertEquals(2, fixed.validations.get())
            assertEquals(0, fixed.refreshes.get())
        }
    }

    @Test fun lateEarlyRolledBackAndQueueExpiredTimersNeverRestartProofOrOperationBudgets() {
        for (failure in 0..4) {
            val f = Fixture(timer = true); f.save(local = true); f.seconds = 0
            val session = f.session(); val old = session.validate().lease!!
            val count = f.validations.get()
            when (failure) {
                0 -> { f.advance(TWITCH_CATALOG_VALIDATION_INTERVAL_MS); f.tasks.fire() }
                1 -> { f.advance(TWITCH_CATALOG_VALIDATION_INTERVAL_MS + 1); f.tasks.fire() }
                2 -> { f.mono--; f.tasks.fireEarly() }
                3 -> { f.advance(TWITCH_CATALOG_VALIDATION_INTERVAL_MS - 60_000); f.tasks.fire(); f.advance(30_000) }
                else -> { f.tasks.fireEarly(); assertEquals(1, f.tasks.scheduled) }
            }
            f.tasks.drain(); assertEquals(count, f.validations.get()); assertEquals(0, f.refreshes.get())
            if (failure == 3) assertTrue(session.isCurrent(old))
            session.close(); f.tasks.drain(); assertTrue(f.coordinator.isIdle)
        }
    }

    @Test fun temporaryFailureDoesNotResetFreshnessOrRetryAtTheOriginalHourlyBound() {
        val f = Fixture(timer = true); f.save(local = true); f.seconds = 0
        val session = f.session(); val old = session.validate().lease!!
        f.status = 503; f.advance(TWITCH_CATALOG_VALIDATION_INTERVAL_MS - 60_000)
        f.tasks.fire(); f.tasks.drain()
        assertEquals(2, f.validations.get()); assertNull(session.adoptSuccessor(old))
        val hint = session.adoptAvailableMaintenance()!!
        assertEquals(TwitchCatalogSessionState.TEMPORARY_FAILURE, hint.summary.state); assertNull(hint.lease)
        assertTrue(session.isCurrent(old)); assertEquals(0, f.tasks.scheduled)
        f.advance(60_000); f.tasks.fire(); f.tasks.drain()
        assertFalse(session.isCurrent(old)); assertEquals(2, f.validations.get())
        session.close(); assertTrue(f.coordinator.isIdle)
    }

    @Test fun queuedWorkAndReceiptSignalsRespectOwnerForgetForegroundAndOriginalAdoptionBudget() {
        for (failure in 0..4) {
            val f = Fixture(timer = true); f.save(); val session = f.session()
            val old = session.validate().lease!!; var signals = 0
            val listener = session.observeMaintenance { signals++ }
            f.advance(TWITCH_CATALOG_VALIDATION_INTERVAL_MS - 60_000); f.tasks.fire()
            when (failure) {
                0 -> f.owner = false
                1 -> { session.invalidate(); session.forget() }
                2 -> session.setForeground(false)
                3 -> f.memory.onRead = { f.memory.onRead = {}; f.advance(60_000) }
                else -> f.onScopeOwner = { f.onScopeOwner = {}; f.advance(60_000) }
            }
            f.tasks.drain(); assertEquals(1, f.validations.get()); assertEquals(0, signals)
            assertNull(session.adoptAvailableMaintenance())
            listener.close(); session.close(); f.tasks.drain(); assertTrue(f.coordinator.isIdle)
        }
        val f = Fixture(timer = true); f.save(); val session = f.session(); session.validate()
        var signals = 0; val listener = session.observeMaintenance { signals++ }
        session.setForeground(false); session.setForeground(true); session.validate(); f.tasks.drain()
        f.advance(TWITCH_CATALOG_VALIDATION_INTERVAL_MS - 60_000); f.tasks.fire(); f.tasks.drain()
        assertEquals(1, signals)
        val began = f.mono; f.advance(30_000)
        assertNull(session.adoptAvailableMaintenance(began)!!.lease)
        listener.close(); session.close(); f.tasks.drain()
    }

    @Test fun explicitApprovedAcceptanceSeedsFromValidationReceiptWithoutAnExtraStartupRequest() {
        val f = Fixture(timer = true); val session = f.session()
        val attempt = session.beginConnection()
        val approved = TwitchCatalogAuthorizationResult.Approved(
            TwitchCatalogCredentials("approved", "approved-refresh", 7_200_000),
            TwitchCatalogValidation("fixture-user", setOf(TWITCH_CATALOG_SCOPE), 7_200_000),
            f.mono + 7_200_000, validatedAtMs = f.mono)
        assertNotNull(session.accept(approved, attempt).lease)
        assertEquals(0, f.validations.get()); assertEquals(1, f.tasks.scheduled)
        f.advance(TWITCH_CATALOG_VALIDATION_INTERVAL_MS - 60_000); f.tasks.fire(); f.tasks.drain()
        assertEquals(1, f.validations.get()); assertNotNull(session.adoptAvailableMaintenance()!!.lease)
        session.close(); f.tasks.drain(); assertTrue(f.coordinator.isIdle)
        val unverified = Fixture(timer = true); val other = unverified.session()
        val noReceipt = TwitchCatalogAuthorizationResult.Approved(approved.credentials, approved.validation,
            unverified.mono + 7_200_000)
        assertNull(other.accept(noReceipt, other.beginConnection()).lease)
        assertEquals(0, unverified.tasks.scheduled); other.close()
    }

    @Test fun actualHealthyPeerKeepsDueWorkWhenSelectedConsumerClosesAndSignalsAreOutsideMonitors() {
        val f = Fixture(timer = true); f.save(); val first = f.session(); val second = f.session()
        first.validate(); second.validate(); f.tasks.drain()
        val ownerLock = TwitchCatalogMaintenanceCoordinator::class.java.getDeclaredField("lock").apply { isAccessible = true }
            .get(f.coordinator).let(::checkNotNull)
        val sessionLock = checkNotNull(TwitchCatalogSession::class.java.getDeclaredField("operationLock")
            .apply { isAccessible = true }.get(second))
        var signals = 0
        second.observeMaintenance {
            assertFalse(Thread.holdsLock(ownerLock)); assertFalse(Thread.holdsLock(sessionLock)); signals++
        }
        f.onValidate = { first.close() }
        f.advance(TWITCH_CATALOG_VALIDATION_INTERVAL_MS - 60_000); f.tasks.fire(); f.tasks.drain()
        assertEquals(3, f.validations.get()); assertEquals(1, signals)
        assertNotNull(second.adoptAvailableMaintenance()!!.lease)
        assertTrue(f.coordinator.canPublishLocally); assertEquals(1, f.tasks.scheduled)
        second.close(); f.tasks.drain(); assertTrue(f.coordinator.isIdle)
    }

    @Test fun proofExpiryDuringHttpOrUncertainCleanupCannotPublishAReplacementOrRearm() {
        for (uncertain in listOf(false, true)) {
            val f = Fixture(timer = true); f.save(); f.seconds = 10
            val session = f.session(); val old = session.validate().lease!!; var signals = 0
            session.observeMaintenance { signals++ }
            if (uncertain) f.onClose = { throw IOException("fixture cleanup") }
            else f.onValidate = { f.advance(5_000) }
            f.advance(5_000); f.tasks.fire(); f.tasks.drain()
            assertEquals(2, f.validations.get()); assertEquals(1, signals)
            assertNull(session.adoptSuccessor(old)); assertFalse(session.isCurrent(old))
            val status = session.adoptAvailableMaintenance()
            if (uncertain) assertNull(status)
            else assertEquals(TwitchCatalogSessionState.TEMPORARY_FAILURE, status!!.summary.state)
            assertEquals(0, f.tasks.scheduled)
            assertEquals(!uncertain, f.coordinator.canPublishLocally)
            session.close(); f.tasks.drain()
            assertEquals(!uncertain, f.coordinator.isIdle)
        }
    }

    @Test fun boundedDispatcherRefusalDoesNotPerformHttpOrGrantAnotherTimerBudget() {
        val f = Fixture(timer = true); f.save(local = true); f.seconds = 0
        val session = f.session(); val old = session.validate().lease!!
        f.tasks.reject = true; f.advance(TWITCH_CATALOG_VALIDATION_INTERVAL_MS - 60_000)
        f.tasks.fire(); f.tasks.drain()
        assertEquals(1, f.validations.get()); assertEquals(0, f.tasks.queued); assertEquals(0, f.tasks.scheduled)
        assertTrue(session.isCurrent(old)); assertTrue(f.coordinator.canPublishLocally)
        f.advance(60_000); f.tasks.reject = false; f.tasks.fire(); f.tasks.drain()
        assertEquals(1, f.validations.get()); assertFalse(session.isCurrent(old))
        session.close(); assertTrue(f.coordinator.isIdle)
    }

    @Test fun temporaryStatusPreservesPositiveProofAndNativeReceiptAndLatestSuccessClearsTheHint() {
        val f = Fixture(timer = true); f.save(local = true); f.seconds = 0
        val first = f.session(); val second = f.session()
        try {
            val old = first.validate().lease!!
            f.advance(100); second.validate(); f.tasks.drain()
            val bytes = f.memory.bytes!!.copyOf(); val count = f.validations.get(); var signals = 0
            first.observeMaintenance { signals++ }
            f.status = 503; f.advance(TWITCH_CATALOG_VALIDATION_INTERVAL_MS - 60_000)
            f.tasks.fire(); f.tasks.drain()
            assertEquals(count + 1, f.validations.get()); assertEquals(1, signals)
            // The temporary notice did not erase the previously proved real
            // receipt, which the native adoption seam continues to ignore it.
            val proved = first.adoptSuccessor(old)!!.lease!!
            assertTrue(first.isCurrent(proved))
            val hint = first.adoptAvailableMaintenance()!!
            assertEquals(TwitchCatalogSessionState.TEMPORARY_FAILURE, hint.summary.state); assertNull(hint.lease)
            assertTrue(first.isCurrent(proved)); assertArrayEquals(bytes, f.memory.bytes)
            assertEquals(TwitchCatalogSessionState.CONNECTED, first.validate().summary.state)
            assertEquals(count + 1, f.validations.get()); assertEquals(0, f.refreshes.get())
            assertNull(first.adoptAvailableMaintenance())
            f.status = 200; f.advance(1); second.validate(force = true); f.tasks.drain()
            val recovered = first.adoptAvailableMaintenance()!!
            assertEquals(TwitchCatalogSessionState.CONNECTED, recovered.summary.state)
            assertNotNull(recovered.lease); assertTrue(first.isCurrent(recovered.lease!!))
            assertArrayEquals(bytes, f.memory.bytes)
        } finally { first.close(); second.close(); f.tasks.drain() }
    }

    @Test fun temporaryStatusCannotCrossReplacementForgetRouteLifecycleCapOrItsQueuedWorkerBudget() {
        for (failure in 0..5) {
            val f = Fixture(timer = true); f.save(local = true); f.seconds = 0
            val session = f.session(); val old = session.validate().lease!!
            f.status = 503; f.advance(TWITCH_CATALOG_VALIDATION_INTERVAL_MS - 60_000)
            f.tasks.fire(); f.tasks.drain(); val began = f.mono
            when (failure) {
                0 -> f.save(local = true)
                1 -> { session.invalidate(); session.forget() }
                2 -> f.owner = false
                3 -> session.setForeground(false)
                4 -> f.wall = old.grant.localRetentionUntilMs!!
                else -> f.advance(30_000)
            }
            val bytes = f.memory.bytes!!.copyOf(); val count = f.validations.get()
            assertNull(session.adoptAvailableMaintenance(began))
            assertArrayEquals(bytes, f.memory.bytes); assertEquals(count, f.validations.get())
            if (failure == 5) assertTrue(session.isCurrent(old))
            session.close(); f.tasks.drain()
        }
    }

    // Hold only the already-cleaned worker's final owner admission. At this
    // point the durable rotation exists but no successor receipt is published.
    private class PublicationHold(f: Fixture) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val held = java.util.concurrent.atomic.AtomicBoolean()
        init {
            val field = TwitchCatalogMaintenanceCoordinator::class.java.getDeclaredField("flight").apply { isAccessible = true }
            f.onScopeOwner = {
                val flight = field.get(f.coordinator)
                val worker = flight?.javaClass?.getDeclaredField("worker")?.apply { isAccessible = true }
                    ?.get(flight) as? AtomicReference<*>
                if (flight != null && worker?.get() == null && held.compareAndSet(false, true)) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                }
            }
        }
    }

    private fun assertAwaiting(thread: AtomicReference<Thread>) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (thread.get()?.state !in setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING) && System.nanoTime() < end)
            Thread.yield()
        assertTrue(thread.get()?.state in setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING))
    }

    @Test fun viewerWaitsForDurableRotationReceiptWithoutStartingMaintenanceAndInitiatorMayClose() {
        val f = Fixture(); val saved = f.save(local = true); val viewer = f.session(); val initiator = f.session()
        val executor = Executors.newFixedThreadPool(2)
        var hold: PublicationHold? = null
        try {
            val initial = viewer.validate().lease!!; val first = initiator.validate().lease!!
            val old = viewer.adoptSuccessor(initial)!!.lease!!
            f.advance(100)
            hold = PublicationHold(f)
            val rotation = executor.submit<TwitchCatalogSessionResult> { initiator.onUnauthorized(first) }
            assertTrue(hold.entered.await(2, TimeUnit.SECONDS))
            val durable = f.store.read()!!
            assertNotEquals(old.grant.generation, durable.generation)
            val requests = f.validations.get(); val thread = AtomicReference<Thread>()
            val adoption = executor.submit<TwitchCatalogSessionResult?> {
                thread.set(Thread.currentThread()); viewer.adoptSuccessor(old, waitForExistingFlight = true)
            }
            assertAwaiting(thread); assertFalse(adoption.isDone)
            initiator.close(); hold.release.countDown()
            assertNull(rotation.get(5, TimeUnit.SECONDS).lease)
            val adopted = adoption.get(5, TimeUnit.SECONDS)!!.lease!!
            assertEquals(durable.generation, adopted.grant.generation); assertTrue(viewer.isCurrent(adopted))
            assertEquals(f.wall, adopted.grant.savedAtMs)
            assertNotEquals(saved.savedAtMs, adopted.grant.savedAtMs)
            assertNull(adopted.grant.localRetentionUntilMs)
            assertEquals(requests, f.validations.get()); assertEquals(1, f.refreshes.get())
        } finally { hold?.release?.countDown(); viewer.close(); initiator.close(); executor.shutdownNow() }
    }

    @Test fun viewerPrefersActiveProvenTipFlightToAlreadySupersededIntermediateReceipt() {
        val f = Fixture(); f.save(); val viewer = f.session(); val initiator = f.session()
        val executor = Executors.newFixedThreadPool(2); var hold: PublicationHold? = null
        try {
            val old = viewer.validate().lease!!; val first = initiator.validate().lease!!
            val intermediate = initiator.onUnauthorized(first).lease!!
            hold = PublicationHold(f)
            val rotation = executor.submit<TwitchCatalogSessionResult> { initiator.onUnauthorized(intermediate) }
            assertTrue(hold.entered.await(2, TimeUnit.SECONDS))
            val thread = AtomicReference<Thread>()
            val adoption = executor.submit<TwitchCatalogSessionResult?> {
                thread.set(Thread.currentThread()); viewer.adoptSuccessor(old, waitForExistingFlight = true)
            }
            assertAwaiting(thread); hold.release.countDown()
            val latest = rotation.get(5, TimeUnit.SECONDS).lease!!
            val adopted = adoption.get(5, TimeUnit.SECONDS)!!.lease!!
            assertEquals(latest.grant.generation, adopted.grant.generation)
            assertNotEquals(intermediate.grant.generation, adopted.grant.generation)
            assertEquals(2, f.refreshes.get()); assertEquals(4, f.validations.get())
        } finally { hold?.release?.countDown(); viewer.close(); initiator.close(); executor.shutdownNow() }
    }

    @Test fun capturedViewerWaitRefusesTimeoutRollbackExpiryPauseCloseAndForgetWithoutDurableFallback() {
        for (failure in listOf("timeout", "rollback", "expiry", "pause", "close", "forget")) {
            val f = Fixture(); f.save(); if (failure == "expiry") f.seconds = 1
            val viewer = f.session(); val initiator = f.session(); val executor = Executors.newFixedThreadPool(2)
            var hold: PublicationHold? = null
            try {
                val initial = viewer.validate().lease!!; val first = initiator.validate().lease!!
                val old = viewer.adoptSuccessor(initial)!!.lease!!
                f.seconds = 7200; hold = PublicationHold(f)
                val rotation = executor.submit<TwitchCatalogSessionResult> { initiator.onUnauthorized(first) }
                assertTrue(hold.entered.await(2, TimeUnit.SECONDS))
                val thread = AtomicReference<Thread>()
                val adoption = executor.submit<TwitchCatalogSessionResult?> {
                    thread.set(Thread.currentThread()); viewer.adoptSuccessor(old, waitForExistingFlight = true)
                }
                assertAwaiting(thread)
                when (failure) {
                    "timeout" -> f.advance(30_000)
                    "rollback" -> f.mono--
                    "expiry" -> f.advance(1_000)
                    "pause" -> viewer.setForeground(false)
                    "close" -> viewer.close()
                    "forget" -> {
                        assertTrue(f.store.forget())
                        val cleared = f.store.read()!!
                        assertEquals(TwitchCatalogGrantState.CLEARED, cleared.state); assertNull(cleared.credentials)
                    }
                }
                val refused = adoption.get(2, TimeUnit.SECONDS)
                assertNotNull("Captured wait must fail explicitly: $failure", refused)
                assertNull(refused!!.lease); assertFalse(viewer.isLocallyCurrent(old))
                hold.release.countDown(); rotation.get(5, TimeUnit.SECONDS)
                assertEquals(1, f.refreshes.get()); assertEquals(3, f.validations.get())
                if (failure == "forget") {
                    val cleared = f.store.read()!!
                    assertEquals(TwitchCatalogGrantState.CLEARED, cleared.state); assertNull(cleared.credentials)
                }
            } finally { hold?.release?.countDown(); viewer.close(); initiator.close(); executor.shutdownNow() }
        }
    }

    @Test fun viewerAdoptionKeepsOldExpiryThroughLateProtectedAdmissionButDefaultAdoptionIsUnchanged() {
        for (viewerMode in listOf(false, true)) {
            val f = Fixture(); f.save(); f.seconds = 1
            var trigger = false
            val viewer = f.session(consumerOwner = { if (trigger) { trigger = false; f.advance(1_000) }; true })
            val initiator = f.session()
            try {
                val initial = viewer.validate().lease!!; val first = initiator.validate().lease!!
                val old = viewer.adoptSuccessor(initial)!!.lease!!
                f.seconds = 7200; initiator.onUnauthorized(first)
                trigger = true
                val result = viewer.adoptSuccessor(old, waitForExistingFlight = viewerMode)
                if (viewerMode) { assertNotNull(result); assertNull(result!!.lease) }
                else assertNotNull(result!!.lease)
                assertEquals(1, f.refreshes.get()); assertEquals(3, f.validations.get())
            } finally { viewer.close(); initiator.close() }
        }
    }

    @Test fun viewerLocalPredecessorBridgesFinalProtectedReadsWithoutExtendingItsExpiryOrWorkerAdmission() {
        for (expire in listOf(false, true)) {
            val f = Fixture(); f.save(); f.seconds = 1
            val viewer = f.session(); val initiator = f.session(); val swappedReads = AtomicInteger()
            try {
                val initial = viewer.validate().lease!!; val first = initiator.validate().lease!!
                val old = viewer.adoptSuccessor(initial)!!.lease!!
                f.seconds = 7200; initiator.onUnauthorized(first)
                f.memory.onRead = {
                    if (!viewer.isLocallyCurrent(old)) {
                        assertTrue(viewer.isLocallyCurrentForViewer(old))
                        if (swappedReads.incrementAndGet() == 1 && expire) {
                            f.advance(1_000)
                            assertFalse(viewer.isLocallyCurrentForViewer(old))
                        }
                    }
                }
                val adopted = viewer.adoptSuccessor(old, waitForExistingFlight = true)!!
                assertTrue(swappedReads.get() > 0)
                assertFalse(viewer.isLocallyCurrent(old))
                f.memory.onRead = {}
                if (expire) {
                    assertNull(adopted.lease); assertFalse(viewer.isLocallyCurrentForViewer(old))
                } else {
                    assertNotNull(adopted.lease); assertFalse(viewer.isCurrent(old))
                    f.memory.forbid = true
                    assertTrue(viewer.isLocallyCurrentForViewer(old))
                    assertTrue(viewer.isLocallyCurrentForViewer(adopted.lease!!))
                    f.advance(1_000); assertFalse(viewer.isLocallyCurrentForViewer(old))
                    assertTrue(viewer.isLocallyCurrentForViewer(adopted.lease))
                    viewer.close(); assertFalse(viewer.isLocallyCurrentForViewer(adopted.lease))
                }
            } finally { f.memory.forbid = false; f.memory.onRead = {}; viewer.close(); initiator.close() }
        }
    }

    @Test fun viewerWaitDoesNotJoinAnUnrelatedFlightOrInventProofForReplacement() {
        val f = Fixture(); f.save(); val viewer = f.session(); val unrelated = f.session()
        val executor = Executors.newSingleThreadExecutor(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            val old = viewer.validate().lease!!
            // A rotation outside this coordinator preserves the revision but
            // supplies no successor proof to the viewer's attachment.
            f.store.refresh(old.grant, { true }) {
                TwitchCatalogReplacement(TwitchCatalogCredentials("unrelated", "unrelated-refresh", 7_200_000),
                    TwitchCatalogValidation("fixture-user", setOf(TWITCH_CATALOG_SCOPE), 7_200_000), 7_200_000)
            }
            f.onValidate = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            val running = executor.submit<TwitchCatalogSessionResult> { unrelated.validate() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertNull(viewer.adoptSuccessor(old, waitForExistingFlight = true))
            assertFalse(viewer.isCurrent(old)); assertEquals(0, f.refreshes.get())
            release.countDown(); running.get(5, TimeUnit.SECONDS)
            assertNull(viewer.adoptSuccessor(old, waitForExistingFlight = true)?.lease)
            assertEquals(2, f.validations.get())
        } finally { release.countDown(); viewer.close(); unrelated.close(); executor.shutdownNow() }
    }

    @Test fun capturedViewerWaitRejectsCleanupPoisonWithoutNewTransportOrReceipt() {
        val f = Fixture(); f.save(); val viewer = f.session(); val initiator = f.session()
        val executor = Executors.newFixedThreadPool(2); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            val initial = viewer.validate().lease!!; val first = initiator.validate().lease!!
            val old = viewer.adoptSuccessor(initial)!!.lease!!
            f.onRefresh = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            f.onClose = { throw IOException("Synthetic cleanup uncertainty") }
            val rotation = executor.submit<TwitchCatalogSessionResult> { initiator.onUnauthorized(first) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val thread = AtomicReference<Thread>()
            val adoption = executor.submit<TwitchCatalogSessionResult?> {
                thread.set(Thread.currentThread()); viewer.adoptSuccessor(old, waitForExistingFlight = true)
            }
            assertAwaiting(thread); release.countDown()
            rotation.get(5, TimeUnit.SECONDS)
            val refused = adoption.get(5, TimeUnit.SECONDS)
            assertNotNull(refused); assertNull(refused!!.lease)
            assertFalse(f.coordinator.canPublishLocally); assertFalse(viewer.isLocallyCurrent(old))
            assertEquals(1, f.refreshes.get()); assertEquals(3, f.validations.get())
        } finally { release.countDown(); viewer.close(); initiator.close(); executor.shutdownNow() }
    }

    @Test fun eagerAttachmentAndLifecycleRetirementUseOnlyMemoryBeforeAnyGrantAdmission() {
        val f = Fixture(); val session = f.session(); f.memory.forbid = true
        try {
            assertTrue(f.coordinator.isIdle)
            session.attach(); session.attach()
            assertFalse(f.coordinator.isIdle); assertEquals(0, f.memory.reads)
            session.setForeground(false); assertTrue(f.coordinator.isIdle)
            session.setForeground(true); assertFalse(f.coordinator.isIdle)
            session.close(); assertTrue(f.coordinator.isIdle)
            assertEquals(0, f.validations.get()); assertEquals(0, f.refreshes.get())
            assertTrue(f.idle > 0)
        } finally { f.memory.forbid = false; session.close() }
    }

    @Test fun nonwaitingConsumerAdoptsOnlyProvenRotationAndKeepsOriginalValidationFreshness() {
        val f = Fixture(); f.save()
        val initiator = f.session(); val follower = f.session()
        try {
            val old = follower.validate().lease!!; val first = initiator.validate().lease!!
            val rotated = initiator.onUnauthorized(first).lease!!
            assertFalse(follower.isCurrent(old)); assertEquals(1, f.refreshes.get())
            assertNull(follower.onUnauthorized(old).lease) // Stale callback cannot spend again or discard the proof.
            val started = f.mono; f.advance(1000)
            val adopted = follower.adoptSuccessor(old)!!.lease!!
            assertEquals(rotated.grant.generation, adopted.grant.generation)
            assertEquals(started + TWITCH_CATALOG_VALIDATION_INTERVAL_MS, adopted.deadlineMs)
            assertTrue(follower.isCurrent(adopted)); assertEquals(1, f.refreshes.get())
            assertEquals(3, f.validations.get())
        } finally { initiator.close(); follower.close() }
        assertTrue(f.coordinator.isIdle)
    }

    @Test fun successiveRotationsKeepOneProvenTipForANonwaitingOrigin() {
        val f = Fixture(); f.save(); val follower = f.session(); val initiator = f.session()
        try {
            val old = follower.validate().lease!!
            val first = initiator.validate().lease!!
            val second = initiator.onUnauthorized(first).lease!!
            val third = initiator.onUnauthorized(second).lease!!
            val adopted = follower.adoptSuccessor(old)!!.lease!!
            assertEquals(third.grant.generation, adopted.grant.generation)
            assertEquals(2, f.refreshes.get()); assertTrue(follower.isCurrent(adopted))
        } finally { follower.close(); initiator.close() }
    }

    @Test fun unrelatedSameUserReplacementAndForgetCannotBecomeSuccessorProofs() {
        for (forget in listOf(false, true)) {
            val f = Fixture(); f.save(); val follower = f.session(); val initiator = f.session()
            try {
                val old = follower.validate().lease!!; val first = initiator.validate().lease!!
                initiator.onUnauthorized(first)
                if (forget) { initiator.invalidate(); initiator.forget() } else f.save()
                assertNull(follower.adoptSuccessor(old)?.lease)
                assertFalse(follower.isCurrent(old)); assertEquals(1, f.refreshes.get())
            } finally { follower.close(); initiator.close() }
        }
    }

    @Test fun sameGenerationTighteningPreservesPeersAndAdoptionCannotExtendDurableBound() {
        val f = Fixture(); val original = f.save(local = true); f.seconds = 0
        val first = f.session(); val second = f.session()
        try {
            val old = first.validate().lease!!
            f.seconds = 60; second.validate(force = true)
            assertTrue(first.isCurrent(old))
            val adopted = first.adoptSuccessor(old)!!.lease!!
            assertEquals(original.generation, adopted.grant.generation)
            assertEquals(f.mono + 60_000, adopted.deadlineMs)
            val bytes = f.memory.bytes!!.copyOf()
            f.seconds = 120; second.validate(force = true)
            assertArrayEquals(bytes, f.memory.bytes)
            assertEquals(original.localRetentionUntilMs, f.store.read()!!.localRetentionUntilMs)
            f.advance(60_000); assertFalse(first.isCurrent(adopted))
        } finally { first.close(); second.close() }
    }

    @Test fun completedReceiptsNeverSatisfyForcedValidationAndLocalGatesDoNoProtectedWork() {
        val f = Fixture(); f.save(); val first = f.session(); val second = f.session()
        try {
            val old = first.validate().lease!!; second.validate(force = true)
            val before = f.validations.get(); val reads = f.memory.reads
            f.memory.forbid = true
            assertTrue(first.isLocallyCurrent(old)); assertEquals(reads, f.memory.reads)
            f.memory.forbid = false
            first.validate(force = true); assertEquals(before + 1, f.validations.get())
        } finally { f.memory.forbid = false; first.close(); second.close() }
    }

    @Test fun initiatorCloseDoesNotCancelIndependentlyOwnedFlightNeededByLiveNonwaitingConsumer() {
        val f = Fixture(); f.save(); val initiator = f.session(); val follower = f.session()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val old = follower.validate().lease!!; val first = initiator.validate().lease!!
            f.onRefresh = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            val future = executor.submit<TwitchCatalogSessionResult> { initiator.onUnauthorized(first) }
            assertTrue(entered.await(2, TimeUnit.SECONDS)); initiator.close()
            release.countDown(); assertNull(future.get(5, TimeUnit.SECONDS).lease)
            assertNotNull(follower.adoptSuccessor(old)!!.lease)
            assertEquals(1, f.refreshes.get())
        } finally { release.countDown(); initiator.close(); follower.close(); executor.shutdownNow() }
        assertTrue(f.coordinator.isIdle)
    }

    @Test fun concurrentlyRegisteredConsumersCoalesceBeforeAnyBlockingGrantRead() {
        val f = Fixture(); f.save(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val waiting = CountDownLatch(1); val calls = AtomicInteger()
        val first = f.session(); val second = f.session {
            if (calls.incrementAndGet() >= 3) waiting.countDown()
            true
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            f.onValidate = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            val one = executor.submit<TwitchCatalogSessionResult> { first.validate() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val reads = f.memory.reads
            val two = executor.submit<TwitchCatalogSessionResult> { second.validate() }
            assertTrue(waiting.await(2, TimeUnit.SECONDS)); assertEquals(reads, f.memory.reads)
            release.countDown()
            assertNotNull(one.get(5, TimeUnit.SECONDS).lease); assertNotNull(two.get(5, TimeUnit.SECONDS).lease)
            assertEquals(1, f.validations.get())
        } finally { release.countDown(); first.close(); second.close(); executor.shutdownNow() }
    }

    @Test fun refreshInterruptionAndFailedPersistenceNeverReplayConsumedCredentialAcrossRestart() {
        for (failure in 0..2) {
            val f = Fixture(); f.save(); val session = f.session()
            try {
                val old = session.validate().lease!!
                f.onRefresh = { when (failure) {
                    0 -> throw IOException()
                    1 -> f.memory.failWrite = true
                    2 -> f.advance(30_000)
                } }
                assertNull(session.onUnauthorized(old).lease)
                f.memory.failWrite = false
                f.session().use { restarted -> assertNull(restarted.validate().lease) }
                assertEquals(1, f.refreshes.get())
            } finally { f.memory.failWrite = false; session.close() }
        }
    }

    @Test fun unknownExpiryRotationAndAdoptionNeverRenewOriginalRetentionAndExpiredCapBlocksHttp() {
        val f = Fixture(); val original = f.save(local = true); f.seconds = 0
        val first = f.session(); val second = f.session()
        try {
            val old = second.validate().lease!!; val initial = first.validate().lease!!
            first.onUnauthorized(initial); assertNotNull(second.adoptSuccessor(old)!!.lease)
            assertEquals(original.savedAtMs, f.store.read()!!.savedAtMs)
            assertEquals(original.localRetentionUntilMs, f.store.read()!!.localRetentionUntilMs)
            val requests = f.validations.get() + f.refreshes.get()
            f.advance(TWITCH_CATALOG_LOCAL_RETENTION_MS)
            assertNull(first.validate(force = true).lease)
            assertEquals(requests, f.validations.get() + f.refreshes.get())
            assertEquals(TwitchCatalogSessionState.MISSING, first.forget().summary.state)
        } finally { first.close(); second.close() }
    }

    @Test fun ownedIdentityFailureMarkerRemainsForgettableAndTemporaryValidationPreservesGrant() {
        for (mismatch in listOf(false, true)) {
            val f = Fixture(); f.save(); val before = f.memory.bytes!!.copyOf(); val session = f.session()
            try {
                if (mismatch) f.user = "different" else f.status = 500
                val failed = session.validate()
                assertNull(failed.lease)
                assertEquals(if (mismatch) TwitchCatalogSessionState.RECONNECT_REQUIRED else TwitchCatalogSessionState.TEMPORARY_FAILURE,
                    failed.summary.state)
                if (!mismatch) assertArrayEquals(before, f.memory.bytes)
                session.invalidate(); assertEquals(TwitchCatalogSessionState.MISSING, session.forget().summary.state)
            } finally { session.close() }
        }
    }

    @Test fun concurrentUnauthorizedWaitersReceiveOneValidatedRotation() {
        val f = Fixture(); f.save(); val caller = AtomicReference<Thread>()
        val waiting = CountDownLatch(1); val calls = AtomicInteger()
        val first = f.session(); val second = f.session(participates = {
            if (Thread.currentThread() === caller.get() && calls.incrementAndGet() >= 4) waiting.countDown()
            true
        })
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val one = first.validate().lease!!; val two = second.validate().lease!!
            f.onRefresh = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            val primary = executor.submit<TwitchCatalogSessionResult> { first.onUnauthorized(one) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val follower = executor.submit<TwitchCatalogSessionResult> { caller.set(Thread.currentThread()); second.onUnauthorized(two) }
            assertTrue(waiting.await(2, TimeUnit.SECONDS)); release.countDown()
            val a = primary.get(5, TimeUnit.SECONDS).lease!!; val b = follower.get(5, TimeUnit.SECONDS).lease!!
            assertEquals(a.grant.generation, b.grant.generation); assertNotSame(a, b)
            assertEquals(1, f.refreshes.get()); assertEquals(3, f.validations.get())
            assertNull(second.onUnauthorized(two).lease); assertEquals(1, f.refreshes.get())
        } finally { release.countDown(); first.close(); second.close(); executor.shutdownNow() }
    }

    @Test fun forceCannotJoinAValidationThatStartedBeforeItsOwnFreshnessWindow() {
        val f = Fixture(); f.save(); val caller = AtomicReference<Thread>(); val calls = AtomicInteger()
        val waiting = CountDownLatch(1); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val first = f.session(); val second = f.session(participates = {
            if (Thread.currentThread() === caller.get() && calls.incrementAndGet() >= 3) waiting.countDown()
            true
        })
        val executor = Executors.newFixedThreadPool(2)
        try {
            f.onValidate = { if (f.validations.get() == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
            val original = executor.submit<TwitchCatalogSessionResult> { first.validate() }
            assertTrue(entered.await(2, TimeUnit.SECONDS)); f.advance(1)
            val fresh = executor.submit<TwitchCatalogSessionResult> { caller.set(Thread.currentThread()); second.validate(force = true) }
            assertTrue(waiting.await(2, TimeUnit.SECONDS)); release.countDown()
            assertNotNull(original.get(5, TimeUnit.SECONDS).lease)
            val result = fresh.get(5, TimeUnit.SECONDS)
            assertNotNull(result.lease); assertEquals(f.mono, result.validationStartedMs)
            assertEquals(2, f.validations.get())
        } finally { release.countDown(); first.close(); second.close(); executor.shutdownNow() }
    }

    @Test fun allParticipantsClosingEndsFlightAndNeverReplaysInterruptedRefresh() {
        val f = Fixture(); f.save(); val first = f.session()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val old = first.validate().lease!!
            f.onRefresh = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            val result = executor.submit<TwitchCatalogSessionResult> { first.onUnauthorized(old) }
            assertTrue(entered.await(2, TimeUnit.SECONDS)); first.close(); release.countDown()
            assertNull(result.get(5, TimeUnit.SECONDS).lease)
            assertTrue(f.coordinator.isIdle)
            f.session().use { assertNull(it.validate().lease) }
            assertEquals(1, f.refreshes.get()); assertEquals(1, f.validations.get())
        } finally { release.countDown(); first.close(); executor.shutdownNow() }
    }

    @Test fun acceptedForgetCancelsSharedRefreshBeforeDurableClearAndLateResultsCannotResurrectIt() {
        val f = Fixture(); f.save(); val first = f.session(); val forgetting = f.session()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val original = first.validate().lease!!; forgetting.validate()
            f.onRefresh = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            val refresh = executor.submit<TwitchCatalogSessionResult> { first.onUnauthorized(original) }
            assertTrue(entered.await(2, TimeUnit.SECONDS)); forgetting.invalidate()
            assertFalse(first.isLocallyCurrent(original))
            val clear = executor.submit<TwitchCatalogSessionResult> { forgetting.forget() }
            release.countDown(); assertNull(refresh.get(5, TimeUnit.SECONDS).lease)
            assertEquals(TwitchCatalogSessionState.MISSING, clear.get(5, TimeUnit.SECONDS).summary.state)
            assertNull(first.adoptSuccessor(original)?.lease)
            assertEquals(TwitchCatalogGrantState.CLEARED, f.store.read()!!.state)
            assertEquals(1, f.refreshes.get())
        } finally { release.countDown(); first.close(); forgetting.close(); executor.shutdownNow() }
    }

    @Test fun lateConsumerAdmissionAndOperationClockRollbackCannotPublishConnected() {
        for (failure in 0..2) {
            val f = Fixture(); f.save(); val before = f.memory.bytes!!.copyOf(); val checks = AtomicInteger()
            if (failure == 2) f.onValidate = { f.mono-- }
            val session = f.session(consumerOwner = {
                if (failure != 2 && checks.incrementAndGet() == failure + 1) f.advance(30_000)
                true
            })
            try {
                val result = session.validate()
                assertNull(result.lease); assertEquals(TwitchCatalogSessionState.TEMPORARY_FAILURE, result.summary.state)
                assertArrayEquals(before, f.memory.bytes); assertEquals(1, f.validations.get())
            } finally { session.close() }
        }
    }

    @Test fun uncertainTransportClosePoisonsCoordinatorWithoutTreatingOrdinaryNetworkFailureAsPoison() {
        val f = Fixture(); f.save(); val session = f.session(); val peer = f.session()
        try {
            val old = peer.validate().lease!!
            f.onClose = { throw IOException() }
            assertNull(session.validate().lease)
            val reads = f.memory.reads; f.memory.forbid = true
            assertFalse(peer.isLocallyCurrent(old)); assertFalse(peer.isCurrent(old)); assertEquals(reads, f.memory.reads)
            f.memory.forbid = false
            val requests = f.validations.get(); f.onClose = {}
            assertNull(session.validate(force = true).lease)
            assertEquals(requests, f.validations.get())
        } finally { f.memory.forbid = false; f.onClose = {}; session.close(); peer.close() }
        assertFalse(f.coordinator.isIdle) // The uncertain worker/transport owner remains retained.
        val temporary = Fixture(); temporary.save(); val recovering = temporary.session()
        try {
            temporary.status = 500; assertNull(recovering.validate().lease)
            temporary.status = 200; assertNotNull(recovering.validate(force = true).lease)
            assertEquals(2, temporary.validations.get())
        } finally { recovering.close() }
    }

    @Test fun coordinatorScopeRequiresTheSameStoreObjectAndOwnerLossBlocksRequestsAndSuccessors() {
        val f = Fixture(); f.save()
        val other = TwitchCatalogGrantStore(Memory(), f.store.instanceId, wallMs = { f.wall })
        assertThrows(IllegalArgumentException::class.java) {
            TwitchCatalogSession(other, { error("No HTTP") }, maintenance = f.coordinator)
        }
        val session = f.session()
        try {
            f.owner = false; assertNull(session.validate().lease); assertEquals(0, f.validations.get())
            f.owner = true; val old = session.validate().lease!!
            f.owner = false; assertNull(session.adoptSuccessor(old)?.lease)
            assertFalse(session.isCurrent(old))
        } finally { session.close() }
    }

    @Test fun ordinaryValidationAdoptsCompletedProofWithoutAnotherHttpRequest() {
        val f = Fixture(); f.save(); val first = f.session(); val peer = f.session()
        try {
            val original = first.validate().lease!!; val other = peer.validate().lease!!
            val target = peer.onUnauthorized(other).lease!!
            val requests = f.validations.get()
            val adopted = first.validate().lease!!
            assertNotSame(original, adopted); assertEquals(target.grant.generation, adopted.grant.generation)
            assertEquals(requests, f.validations.get()); assertEquals(1, f.refreshes.get())
        } finally { first.close(); peer.close() }
    }

    @Test fun finalProtectedReadOrOwnerLossRevokesNewLeaseAndEnrollment() {
        for (expired in listOf(false, true)) {
            val f = Fixture(); f.save(); val before = f.memory.bytes!!.copyOf(); var consumer = false
            val checks = AtomicInteger()
            val session = f.session(consumerOwner = {
                consumer = true
                expired || checks.incrementAndGet() != 3
            })
            if (expired) f.memory.onRead = { if (consumer) { consumer = false; f.advance(30_000) } }
            try {
                val result = session.validate()
                assertNull(result.lease)
                assertEquals(if (expired) TwitchCatalogSessionState.TEMPORARY_FAILURE else TwitchCatalogSessionState.SUPERSEDED,
                    result.summary.state)
                assertTrue(f.coordinator.isIdle); assertArrayEquals(before, f.memory.bytes)
                assertEquals(1, f.validations.get())
            } finally { session.close() }
        }
    }

    @Test fun lateFinalCoordinatorOwnerReadCannotPublishSuccessorToNonwaitingPeer() {
        val f = Fixture(); f.save(); val initiator = f.session(); val peer = f.session()
        try {
            val initial = peer.validate().lease!!; val current = initiator.validate().lease!!
            val baseline = peer.adoptSuccessor(initial)!!.lease!! // Remove the earlier same-pair receipt.
            val finalReads = AtomicInteger()
            val flightField = TwitchCatalogMaintenanceCoordinator::class.java.getDeclaredField("flight")
                .apply { isAccessible = true }
            f.onScopeOwner = {
                val flight = flightField.get(f.coordinator)
                if (flight != null && f.refreshes.get() != 0) {
                    // Select only the final owner read after the independent
                    // worker has closed; no production test hook is required.
                    val worker = flight.javaClass.getDeclaredField("worker").apply { isAccessible = true }
                        .get(flight) as AtomicReference<*>
                    if (worker.get() == null) { finalReads.incrementAndGet(); f.advance(30_000) }
                }
            }
            assertNull(initiator.onUnauthorized(current).lease)
            assertEquals(1, finalReads.get()); assertEquals(1, f.refreshes.get())
            assertNull(peer.adoptSuccessor(baseline)?.lease)
        } finally { initiator.close(); peer.close() }
    }

    @Test fun completedReceiptMonotonicRollbackRefusesAdoption() {
        val f = Fixture(); f.save(); val initiator = f.session(); val peer = f.session()
        try {
            val initial = peer.validate().lease!!; val current = initiator.validate().lease!!
            val baseline = peer.adoptSuccessor(initial)!!.lease!!
            f.advance(1000)
            val rotated = initiator.onUnauthorized(current)
            assertNotNull(rotated.lease); assertEquals(f.mono, rotated.validationStartedMs)
            val bytes = f.memory.bytes!!.copyOf(); val requests = f.validations.get()
            f.mono-- // Persisted wall time remains valid; only the local clock rolls back.
            assertNull(peer.adoptSuccessor(baseline)?.lease)
            assertArrayEquals(bytes, f.memory.bytes); assertEquals(requests, f.validations.get())
            assertEquals(1, f.refreshes.get())
        } finally { initiator.close(); peer.close() }
    }

    @Test fun unauthorizedCallerWaitingForSessionLockCannotStartANewRefreshBudget() {
        val f = Fixture(); f.save(); val session = f.session()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val started = CountDownLatch(1)
        val holder = AtomicReference<Thread>(); val caller = AtomicReference<Thread>(); val firstRead = java.util.concurrent.atomic.AtomicBoolean(true)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val lease = session.validate().lease!!
            f.memory.onRead = {
                if (Thread.currentThread() === holder.get() && firstRead.compareAndSet(true, false)) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                }
            }
            val reading = executor.submit<TwitchCatalogSessionSummary> {
                holder.set(Thread.currentThread()); session.readSummary()
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val unauthorized = executor.submit<TwitchCatalogSessionResult> {
                caller.set(Thread.currentThread()); started.countDown(); session.onUnauthorized(lease)
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (caller.get().state != Thread.State.BLOCKED && System.nanoTime() < end) Thread.yield()
            assertEquals(Thread.State.BLOCKED, caller.get().state) // Actual session monitor contention, before advancing time.
            f.advance(30_000); release.countDown()
            reading.get(5, TimeUnit.SECONDS)
            assertNull(unauthorized.get(5, TimeUnit.SECONDS).lease)
            assertEquals(0, f.refreshes.get()); assertEquals(1, f.validations.get())
        } finally { release.countDown(); session.close(); executor.shutdownNow() }
    }
}
