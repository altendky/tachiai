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
    private class Memory : PrivateSecretStore {
        var bytes: ByteArray? = null
        var failWrite = false
        var forbid = false
        var reads = 0
        var onRead: () -> Unit = {}
        override fun read(): ByteArray? { check(!forbid); reads++; onRead(); return bytes?.copyOf() }
        override fun write(plaintext: ByteArray) { check(!forbid); if (failWrite) throw IOException(); bytes = plaintext.copyOf() }
    }
    private class Fixture {
        val memory = Memory()
        @Volatile var wall = 1_000_000L
        @Volatile var mono = 10_000L
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
        }, { onScopeOwner(); owner }, { wall }, { mono }, { idle++ })
        fun session(consumerOwner: () -> Boolean = { owner }, participates: () -> Boolean = { true }) = TwitchCatalogSession(store,
            { error("Consumer binding must not own maintenance transport") }, { wall }, { mono }, consumerOwner, coordinator, participates)
        fun save(local: Boolean = false): TwitchCatalogStoredGrant = store.commitConnection(store.beginConnection(),
            TwitchCatalogReplacement(TwitchCatalogCredentials("initial", "initial-refresh", 7_200_000),
                TwitchCatalogValidation("fixture-user", setOf(TWITCH_CATALOG_SCOPE), if (local) null else 7_200_000), 7_200_000,
                localRetentionUntilMs = if (local) wall + TWITCH_CATALOG_LOCAL_RETENTION_MS else null,
                savedAtMs = wall))!!
        fun advance(ms: Long) { wall += ms; mono += ms }
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
            assertEquals(saved.savedAtMs, adopted.grant.savedAtMs)
            assertEquals(saved.localRetentionUntilMs, adopted.grant.localRetentionUntilMs)
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
