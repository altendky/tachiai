package net.fstab.tachiai.provider.abema.account

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.feature.connections.ProviderInstanceStore
import net.fstab.tachiai.platform.network.ConnectionProfileStore
import net.fstab.tachiai.platform.network.ProviderRouteAdmission
import net.fstab.tachiai.platform.network.ProviderRouteOwnerReads
import net.fstab.tachiai.platform.network.parseConnectionProfile
import net.fstab.tachiai.platform.storage.EnvelopeSecretStore
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.abema.abemaCatalogRouteOwnerRead
import net.fstab.tachiai.provider.abema.captureAbemaCatalogRouteOwner
import org.junit.Assert.*
import org.junit.Test

class AbemaAccountAccessOwnerTest {
    private class Fixture(saved: Boolean = true) {
        val storage = MemoryAbemaAccountStorage()
        val instance = UUID.randomUUID().toString()
        val store = AbemaAccountGrantStore(instance, storage)
        @Volatile var wall = 1000000L
        var wallHook: () -> Long = { wall }
        @Volatile var elapsed = 0L
        var elapsedHook: () -> Long = { elapsed }
        @Volatile var ownerCurrent = true
        // The route owner's protected store check, when a test wires one (#745).
        @Volatile var ownerRead: () -> Boolean = { true }
        var ownerChecks = 0
        var ownerHook: () -> Unit = {}
        val profiles = AtomicInteger()
        val emails = AtomicInteger()
        val closes = AtomicInteger()
        val cancels = AtomicInteger()
        var profile: AbemaAccountProfileResult = AbemaAccountProfileResult.Identity("intended-account")
        var email = AbemaAccountEmailResult.REGISTERED
        var cleanup = true
        var closeThrows = false
        var closeAction: () -> Unit = {}
        var cleanupAction: () -> Unit = {}
        var profileAction: () -> Unit = {}
        val marks = java.util.Collections.synchronizedList(mutableListOf<String>())
        @Volatile var routeRefusal: Any? = null
        val owner: AbemaAccountAccessOwner
        fun awaitCleanup() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!owner.cleanupConfirmed && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue(owner.cleanupConfirmed)
        }
        init {
            if (saved) store.replace(decodedAbemaGrant(fixtureAccountToken(), "intended-account", wall), wall, store.revision()) { true }
            owner = AbemaAccountAccessOwner(instance, "fixture-owner", store, { wallHook() }, { elapsedHook() },
                { ownerChecks++; ownerHook(); ownerCurrent && ownerRead() }, { gate -> object : AbemaAccountTransport {
                    override fun profile(id: String, bearer: String): AbemaAccountProfileResult {
                        assertTrue(gate()); profiles.incrementAndGet(); profileAction(); return profile
                    }
                    override fun emailRegistration(id: String, bearer: String): AbemaAccountEmailResult {
                        assertTrue(gate()); emails.incrementAndGet(); return email
                    }
                    override fun token(challenge: AbemaAccountChallenge): AbemaAccountTokenResult = error("NO TOKEN EXCHANGE")
                    override fun cancelActiveRequest() { cancels.incrementAndGet() }
                    override fun close() { closes.incrementAndGet(); closeAction(); if (closeThrows) error("PRIVATE CLOSE ERROR") }
                } }, { cleanupAction(); cleanup }, mark = { marks += it }, routeRefusal = { routeRefusal })
        }
    }
    @Test fun sameRecordProofIsSharedUntilLastForegroundAttachmentClosesWithoutWrites() {
        val f = Fixture(); val before = f.storage.bytes!!.copyOf()
        val first = f.owner.attach(); val second = f.owner.attach()
        val a = (first.acquire() as AbemaAccountAccessResult.Account).lease
        val b = (second.acquire() as AbemaAccountAccessResult.Account).lease
        assertEquals(1, f.profiles.get()); assertEquals(1, f.emails.get()); assertEquals(1, f.closes.get())
        assertTrue(f.owner.cleanupConfirmed); assertTrue(a.current()); assertTrue(b.checkWorker())
        f.storage.readHook = { error("MAIN MUST NOT READ STORAGE") }
        val ownerChecks = f.ownerChecks
        assertTrue(a.current()); assertEquals(ownerChecks, f.ownerChecks)
        f.storage.readHook = {}
        first.close(); assertFalse(a.current()); assertTrue(b.current())
        second.close(); assertFalse(b.current()); assertTrue(f.owner.cleanupConfirmed)
        val resumed = f.owner.attach()
        assertTrue(resumed.acquire() is AbemaAccountAccessResult.Account)
        assertEquals(2, f.profiles.get()); assertArrayEquals(before, f.storage.bytes)
        resumed.close(); f.owner.close()
    }
    @Test fun onlyGenuineMissingUsableRecordPermitsPinnedGuestAndLaterGrantClosesIt() {
        val f = Fixture(false); val attachment = f.owner.attach()
        val guest = (attachment.acquire() as AbemaAccountAccessResult.Guest).lease
        assertNull(guest.grant); assertTrue(guest.current()); assertTrue(guest.checkWorker())
        assertEquals(0, f.profiles.get()); assertEquals(0, f.emails.get())
        f.store.replace(decodedAbemaGrant(fixtureAccountToken(), "intended-account", f.wall), f.wall, f.store.revision()) { true }
        assertFalse(guest.current()); attachment.close()
        for (pending in listOf(false, true)) {
            val blocked = Fixture(false)
            if (pending) blocked.storage.pending = true else blocked.store.invalidateForget()
            val consumer = blocked.owner.attach()
            assertTrue(consumer.acquire() is AbemaAccountAccessResult.Failed)
            assertEquals(0, blocked.profiles.get()); consumer.close()
        }
        val broken = Fixture(false); broken.storage.readHook = { error("PRIVATE STORAGE ERROR") }
        val consumer = broken.owner.attach()
        assertTrue(consumer.acquire() is AbemaAccountAccessResult.Failed); consumer.close()
    }
    @Test fun invalidProfileEmailExpiredGrantAndBackwardClockNeverPublishOrBecomeGuest() {
        for (failure in 0..4) {
            val f = Fixture()
            when (failure) {
                0 -> f.profile = AbemaAccountProfileResult.Identity("different")
                1 -> f.email = AbemaAccountEmailResult.UNREGISTERED
                2 -> f.wall = 2000000
                3 -> f.wall--
                4 -> f.profile = AbemaAccountProfileResult.Inconclusive
            }
            val attachment = f.owner.attach()
            assertTrue(attachment.acquire() is AbemaAccountAccessResult.Failed)
            assertTrue(f.owner.cleanupConfirmed); attachment.close()
        }
    }
    @Test fun expiryRollbackForgetReplacementAndOwnerChangeRevokeExistingAccount() {
        for (loss in 0..4) {
            val f = Fixture(); val attachment = f.owner.attach()
            val lease = (attachment.acquire() as AbemaAccountAccessResult.Account).lease
            when (loss) {
                0 -> f.wall = 2000000
                1 -> f.wall--
                2 -> f.store.invalidateForget()
                3 -> f.store.replace(decodedAbemaGrant(fixtureAccountToken("replacement"), "replacement", f.wall), f.wall, f.store.revision()) { true }
                4 -> f.ownerCurrent = false
            }
            assertFalse(lease.checkWorker()); assertFalse(lease.current())
            if (loss <= 1) { f.wall = 1000000; assertFalse(lease.current()) }
            attachment.close()
        }
    }
    @Test fun sameAccountRenewalCarriesExistingLeasesPastThePreviousExpiry() {
        val f = Fixture(); val attachment = f.owner.attach()
        val lease = (attachment.acquire() as AbemaAccountAccessResult.Account).lease
        assertEquals(2_000_000L, lease.expiresAtMs())
        f.wall = 1_500_000
        val renewed = decodedAbemaGrant(fixtureAccountToken("intended-account", "3000"), "intended-account", f.wall)
        f.store.replace(renewed, f.wall, f.store.revision()) { true }
        assertTrue(lease.current()); assertTrue(lease.checkWorker())
        assertEquals(3_000_000L, lease.expiresAtMs())
        f.wall = 2_500_000
        assertTrue(lease.current()); assertTrue(lease.checkWorker())
        // A new consumer reuses the renewed proof and receives the renewed grant.
        val second = f.owner.attach()
        val next = (second.acquire() as AbemaAccountAccessResult.Account).lease
        assertEquals(renewed.bearer, next.grant!!.bearer); assertEquals(1, f.profiles.get())
        f.wall = 3_000_000
        assertFalse(lease.current()); assertFalse(next.current())
        second.close(); attachment.close()
    }
    @Test fun aNewConsumerAfterAnUnobservedRenewalReceivesTheRenewedGrant() {
        val f = Fixture(); val first = f.owner.attach()
        assertTrue(first.acquire() is AbemaAccountAccessResult.Account)
        f.wall = 1_500_000
        val renewed = decodedAbemaGrant(fixtureAccountToken("intended-account", "3000"), "intended-account", f.wall)
        f.store.replace(renewed, f.wall, f.store.revision()) { true }
        val second = f.owner.attach()
        val lease = (second.acquire() as AbemaAccountAccessResult.Account).lease
        assertEquals(renewed.bearer, lease.grant!!.bearer); assertEquals(3_000_000L, lease.expiresAtMs())
        second.close(); first.close()
    }
    @Test fun aDurableCheckWaitingOnARenewalPublicationKeepsTheLease() {
        val f = Fixture(); val attachment = f.owner.attach()
        val lease = (attachment.acquire() as AbemaAccountAccessResult.Account).lease
        f.wall = 1_500_000
        val renewed = decodedAbemaGrant(fixtureAccountToken("intended-account", "3000"), "intended-account", f.wall)
        val writing = CountDownLatch(1); val release = CountDownLatch(1); val checking = CountDownLatch(1)
        f.storage.writeHook = { writing.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val published = executor.submit { f.store.replace(renewed, f.wall, f.store.revision()) { true } }
            assertTrue(writing.await(3, TimeUnit.SECONDS))
            f.ownerHook = { checking.countDown() }
            val checked = executor.submit<Boolean> { lease.checkWorker() }
            assertTrue(checking.await(3, TimeUnit.SECONDS))
            Thread.sleep(100) // Let the check reach the store read that waits for the write.
            release.countDown(); published.get(3, TimeUnit.SECONDS)
            assertTrue(checked.get(3, TimeUnit.SECONDS))
            assertTrue(lease.current()); assertEquals(3_000_000L, lease.expiresAtMs())
        } finally { release.countDown(); executor.shutdownNow() }
        attachment.close()
    }
    @Test fun onlyADirectLaterSameAccountReplacementIsASuccessor() {
        for (change in 0..3) {
            val f = Fixture(); val attachment = f.owner.attach()
            val lease = (attachment.acquire() as AbemaAccountAccessResult.Account).lease
            fun replace(user: String, expiry: String) = f.store.replaceSnapshot(
                decodedAbemaGrant(fixtureAccountToken(user, expiry), user, f.wall), f.wall, f.store.revision()) { true }
            when (change) {
                0 -> replace("intended-account", "2000") // Not later.
                1 -> { replace("intended-account", "3000"); replace("intended-account", "4000") } // Unobserved step.
                2 -> f.store.rollbackPublished(replace("intended-account", "3000"))
                3 -> { replace("intended-account", "3000"); f.store.invalidateForget() }
            }
            assertFalse(lease.current()); assertFalse(lease.checkWorker())
            attachment.close()
        }
    }
    @Test fun concurrentIncreasingWallSamplesCannotRevokeAValidSharedLease() {
        val f = Fixture(); val attachment = f.owner.attach()
        val lease = (attachment.acquire() as AbemaAccountAccessResult.Account).lease
        val base = f.wall
        val enteredA = CountDownLatch(1); val attemptedB = CountDownLatch(1); val completedB = CountDownLatch(1)
        val firstThread = AtomicReference<Thread?>()
        f.wallHook = {
            if (Thread.currentThread() === firstThread.get()) {
                val captured = base
                enteredA.countDown()
                check(attemptedB.await(3, TimeUnit.SECONDS))
                // A bounded wait forces the old outside-monitor sampler to
                // commit B's newer clock first. With sampling under the owner
                // monitor, B waits and both genuine forward samples succeed.
                completedB.await(500, TimeUnit.MILLISECONDS)
                captured
            } else base + 1
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val a = executor.submit<Boolean> { firstThread.set(Thread.currentThread()); lease.current() }
            assertTrue(enteredA.await(3, TimeUnit.SECONDS))
            val b = executor.submit<Boolean> {
                attemptedB.countDown()
                try { lease.current() } finally { completedB.countDown() }
            }
            assertTrue(a.get(3, TimeUnit.SECONDS))
            assertTrue(b.get(3, TimeUnit.SECONDS))
            f.wall = base + 1; f.wallHook = { f.wall }
            assertTrue(lease.current()); assertTrue(lease.checkWorker())
        } finally {
            attemptedB.countDown(); completedB.countDown()
            executor.shutdownNow()
            try { assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS)) }
            finally {
                f.wallHook = { f.wall }
                attachment.close(); f.owner.close()
            }
        }
    }
    @Test fun durableCheckRejectsChangedRecordEvenWhenExternalChangeDidNotAdvanceRevision() {
        val f = Fixture(); val attachment = f.owner.attach()
        val lease = (attachment.acquire() as AbemaAccountAccessResult.Account).lease
        val other = Fixture()
        // Valid record for a different storage binding must not escape decode.
        f.storage.bytes = other.storage.bytes!!.copyOf()
        assertFalse(lease.checkWorker()); assertFalse(lease.current()); attachment.close()
    }
    @Test fun originalBudgetAndStoreRevocationDuringProfilePreventEmailAndPublication() {
        for (loss in 0..2) {
            val f = Fixture()
            f.profileAction = { when (loss) { 0 -> f.elapsed = 30000; 1 -> f.elapsed = -1; 2 -> f.store.invalidateForget() } }
            val attachment = f.owner.attach()
            assertTrue(attachment.acquire() is AbemaAccountAccessResult.Failed)
            f.awaitCleanup()
            assertEquals(1, f.profiles.get()); assertEquals(0, f.emails.get()); assertEquals(1, f.closes.get())
            attachment.close()
        }
    }
    @Test fun lateRejectedOrInconclusiveProfileIsOperationTimeoutBeforeResponseClassification() {
        for (response in listOf(AbemaAccountProfileResult.Rejected, AbemaAccountProfileResult.Inconclusive)) {
            val f = Fixture(); f.profile = response; f.profileAction = { f.elapsed = 30000 }
            val attachment = f.owner.attach()
            val failure = attachment.acquire() as AbemaAccountAccessResult.Failed
            assertEquals(AbemaAccountFailure.TIME_LIMIT_REACHED, failure.category)
            f.awaitCleanup()
            assertEquals(0, f.emails.get()); attachment.close()
        }
    }
    @Test fun uncertainOrThrowingCleanupIsStickyAndCannotPublishPositiveAccount() {
        for (throwing in listOf(false, true)) {
            val f = Fixture(); f.cleanup = false; f.closeThrows = throwing
            val attachment = f.owner.attach()
            val failed = attachment.acquire() as AbemaAccountAccessResult.Failed
            assertEquals(AbemaAccountFailure.CLEANUP_FAILED, failed.category)
            assertFalse(f.owner.cleanupConfirmed); attachment.close()
            assertThrows(AbemaAccountException::class.java) { f.owner.attach() }
            assertFalse(failed.toString().contains("PRIVATE"))
        }
    }
    @Test fun knownCleanupFailureSurvivesExpiredFinalPublicationSample() {
        val f = Fixture(); f.cleanup = false
        val monitor = checkNotNull(AbemaAccountAccessOwner::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(f.owner))
        val sampled = CountDownLatch(1)
        // Expire only the final worker sample, after cleanup has been classified.
        // The waiter must not time out before that failure is known.
        f.elapsedHook = {
            if (f.closes.get() > 0 && Thread.holdsLock(monitor)) {
                sampled.countDown(); 30000L
            } else f.elapsed
        }
        val attachment = f.owner.attach()
        try {
            assertEquals(AbemaAccountFailure.CLEANUP_FAILED, (attachment.acquire() as AbemaAccountAccessResult.Failed).category)
            assertTrue(sampled.await(3, TimeUnit.SECONDS))
            assertFalse(f.owner.cleanupConfirmed)
            assertEquals(AbemaAccountFailure.CLEANUP_FAILED,
                assertThrows(AbemaAccountException::class.java) { f.owner.attach() }.category)
        } finally { attachment.close(); f.owner.close() }
    }
    @Test fun cleanupCrossingDeadlineTimesOutBeforeLaterStickyFailure() {
        val f = Fixture(); f.cleanup = false
        val monitor = checkNotNull(AbemaAccountAccessOwner::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(f.owner))
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val sampled = CountDownLatch(1)
        f.elapsedHook = {
            if (f.closes.get() > 0 && Thread.holdsLock(monitor)) sampled.countDown()
            f.elapsed
        }
        // Hold confirmation after close so the receipt's real timeout cannot
        // classify cleanup before the waiter observes its operation deadline.
        f.cleanupAction = { f.elapsed = 30000; entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
        val attachment = f.owner.attach(); val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<AbemaAccountAccessResult> { attachment.acquire(0, 30000) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertEquals(AbemaAccountFailure.TIME_LIMIT_REACHED,
                (result.get(3, TimeUnit.SECONDS) as AbemaAccountAccessResult.Failed).category)
            assertFalse(f.owner.cleanupConfirmed)
            release.countDown(); assertTrue(sampled.await(3, TimeUnit.SECONDS))
            assertFalse(f.owner.cleanupConfirmed)
            assertEquals(AbemaAccountFailure.CLEANUP_FAILED,
                assertThrows(AbemaAccountException::class.java) { f.owner.attach() }.category)
        } finally { release.countDown(); attachment.close(); f.owner.close(); executor.shutdownNow() }
    }
    @Test fun overlappingConsumersCoalesceAndClosingInitiatorDoesNotCancelRemainingConsumer() {
        val f = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.profileAction = { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)) }
        val first = f.owner.attach(); val second = f.owner.attach()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val a = executor.submit<AbemaAccountAccessResult> { first.acquire() }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val b = executor.submit<AbemaAccountAccessResult> { second.acquire() }
            first.close(); release.countDown()
            assertTrue(a.get(3, TimeUnit.SECONDS) is AbemaAccountAccessResult.Failed)
            val lease = (b.get(3, TimeUnit.SECONDS) as AbemaAccountAccessResult.Account).lease
            assertTrue(lease.current()); assertEquals(1, f.profiles.get()); assertEquals(1, f.emails.get())
            assertEquals(0, f.cancels.get())
        } finally { release.countDown(); first.close(); second.close(); executor.shutdownNow() }
    }
    @Test fun detachedFirstWaiterExitsPromptlyAndTransfersOwnedFlightToHealthyPeer() {
        val f = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.profileAction = { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)) }
        val first = f.owner.attach(); val peer = f.owner.attach(); val executor = Executors.newFixedThreadPool(2)
        try {
            val a = executor.submit<AbemaAccountAccessResult> { first.acquire() }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val b = executor.submit<AbemaAccountAccessResult> { peer.acquire() }
            first.close()
            assertTrue(a.get(500, TimeUnit.MILLISECONDS) is AbemaAccountAccessResult.Failed)
            assertTrue(first.cleanupConfirmed); assertFalse(f.owner.cleanupConfirmed)
            assertEquals(0, f.cancels.get()); assertFalse(b.isDone)
            release.countDown()
            val lease = (b.get(3, TimeUnit.SECONDS) as AbemaAccountAccessResult.Account).lease
            assertTrue(lease.current()); assertEquals(1, f.profiles.get()); assertEquals(1, f.emails.get())
            peer.close(); assertTrue(first.cleanupConfirmed); assertTrue(peer.cleanupConfirmed)
        } finally { release.countDown(); first.close(); peer.close(); executor.shutdownNow() }
    }
    @Test fun finalDetachedWaiterCannotConfirmCleanupWhileOwnedNativeFlightIsStillHeld() {
        val f = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.profileAction = { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)) }
        val attachment = f.owner.attach(); val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<AbemaAccountAccessResult> { attachment.acquire() }
            assertTrue(entered.await(3, TimeUnit.SECONDS)); attachment.close()
            assertTrue(result.get(500, TimeUnit.MILLISECONDS) is AbemaAccountAccessResult.Failed)
            assertFalse(attachment.cleanupConfirmed); assertFalse(f.owner.cleanupConfirmed)
            release.countDown(); f.awaitCleanup(); assertTrue(attachment.cleanupConfirmed)
        } finally { release.countDown(); attachment.close(); executor.shutdownNow() }
    }
    @Test fun earlierCallerBeginAndDeadlineCannotBeExtendedBySharedAcquisition() {
        val f = Fixture(); f.elapsed = 20000; f.profileAction = { f.elapsed = 30000 }
        val attachment = f.owner.attach()
        assertEquals(AbemaAccountFailure.TIME_LIMIT_REACHED,
            (attachment.acquire(0, 30000) as AbemaAccountAccessResult.Failed).category)
        f.awaitCleanup(); assertEquals(0, f.emails.get()); attachment.close()
    }
    @Test fun lastDetachCancelsBlockedValidationAndCannotJoinAnUnrelatedGeneration() {
        val f = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.profileAction = { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)) }
        val attachment = f.owner.attach(); val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<AbemaAccountAccessResult> { attachment.acquire() }
            assertTrue(entered.await(3, TimeUnit.SECONDS)); attachment.close()
            assertThrows(AbemaAccountException::class.java) { f.owner.attach() }
            release.countDown()
            assertTrue(result.get(3, TimeUnit.SECONDS) is AbemaAccountAccessResult.Failed)
            f.awaitCleanup()
            assertEquals(1, f.cancels.get()); assertEquals(0, f.emails.get()); assertTrue(f.owner.cleanupConfirmed)
        } finally { release.countDown(); attachment.close(); executor.shutdownNow() }
    }
    @Test fun invalidatedFlightCannotBeJoinedByAnotherActiveConsumerOrPublishLateResult() {
        val f = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.profileAction = { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)) }
        val first = f.owner.attach(); val second = f.owner.attach(); val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<AbemaAccountAccessResult> { first.acquire() }
            assertTrue(entered.await(3, TimeUnit.SECONDS)); f.owner.invalidate()
            assertTrue(second.acquire() is AbemaAccountAccessResult.Failed)
            release.countDown()
            assertTrue(result.get(3, TimeUnit.SECONDS) is AbemaAccountAccessResult.Failed)
            f.awaitCleanup()
            assertEquals(1, f.profiles.get()); assertEquals(0, f.emails.get()); assertTrue(f.owner.cleanupConfirmed)
        } finally { release.countDown(); first.close(); second.close(); executor.shutdownNow() }
    }
    @Test fun finalPublicationSamplesOriginalDeadlineInsideMonitorAndCannotCacheLateProof() {
        val f = Fixture(); val monitor = checkNotNull(AbemaAccountAccessOwner::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(f.owner))
        f.elapsedHook = {
            if (f.closes.get() > 0 && Thread.holdsLock(monitor)) f.elapsed = 30000
            f.elapsed
        }
        val attachment = f.owner.attach()
        val failed = attachment.acquire() as AbemaAccountAccessResult.Failed
        assertEquals(AbemaAccountFailure.TIME_LIMIT_REACHED, failed.category)
        f.awaitCleanup()
        f.elapsedHook = { f.elapsed }; f.elapsed = 30001
        assertTrue(attachment.acquire() is AbemaAccountAccessResult.Account)
        assertEquals(2, f.profiles.get()); attachment.close()
    }
    @Test fun staleWorkerCannotRevokeNewerProofAfterOldOwnerEpochWasInvalidated() {
        val f = Fixture(); val first = f.owner.attach()
        val old = (first.acquire() as AbemaAccountAccessResult.Account).lease
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.ownerHook = {
            if (Thread.currentThread().name == "stale-A") { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)) }
        }
        val executor = Executors.newSingleThreadExecutor { Thread(it, "stale-A") }
        var second: AbemaAccountAccessOwner.Attachment? = null
        try {
            val checking = executor.submit<Boolean> { old.checkWorker() }
            assertTrue(entered.await(3, TimeUnit.SECONDS)); f.owner.invalidate()
            second = f.owner.attach()
            val fresh = (second.acquire() as AbemaAccountAccessResult.Account).lease
            assertTrue(fresh.current()); release.countDown()
            assertFalse(checking.get(3, TimeUnit.SECONDS)); assertTrue(fresh.current()); assertTrue(fresh.checkWorker())
        } finally { release.countDown(); first.close(); second?.close(); executor.shutdownNow() }
    }
    // #729: a playing feed's recovery acquired while a renewal was adopted.
    @Test fun aSameAccountRenewalDuringValidationValidatesTheRenewedRecordInsteadOfFailing() {
        val f = Fixture()
        val renewed = decodedAbemaGrant(fixtureAccountToken("intended-account", "3000"), "intended-account", f.wall)
        f.profileAction = { if (f.profiles.get() == 1) f.store.replace(renewed, f.wall, f.store.revision()) { true } }
        val attachment = f.owner.attach()
        val lease = (attachment.acquire() as AbemaAccountAccessResult.Account).lease
        assertEquals(renewed.bearer, lease.grant!!.bearer); assertEquals(3_000_000L, lease.expiresAtMs())
        assertEquals(2, f.profiles.get()); assertEquals(1, f.emails.get())
        assertTrue(lease.current()); assertTrue(lease.checkWorker())
        assertTrue("VALIDATION_RENEWED" in f.marks)
        attachment.close(); f.awaitCleanup()
    }
    @Test fun onlyADirectSameAccountRenewalDuringValidationIsRetriedAndOnlyOnce() {
        for (change in 0..3) {
            val f = Fixture()
            fun replace(user: String, expiry: String) = f.store.replaceSnapshot(
                decodedAbemaGrant(fixtureAccountToken(user, expiry), user, f.wall), f.wall, f.store.revision()) { true }
            f.profileAction = { when (change) {
                0 -> if (f.profiles.get() == 1) replace("different-account", "3000")
                1 -> if (f.profiles.get() == 1) f.store.rollbackPublished(replace("intended-account", "3000"))
                2 -> if (f.profiles.get() == 1) { replace("intended-account", "3000"); f.store.invalidateForget() }
                // Every validation sees another renewal: one retry, then the failure stands.
                else -> replace("intended-account", (3000 + f.profiles.get()).toString())
            } }
            val attachment = f.owner.attach()
            val result = attachment.acquire() as AbemaAccountAccessResult.Failed
            assertEquals(AbemaAccountFailure.CANCELLED, result.category)
            assertEquals(if (change == 3) 2 else 1, f.profiles.get()); assertEquals(0, f.emails.get())
            assertEquals(change == 3, "VALIDATION_RENEWED" in f.marks)
            attachment.close(); f.awaitCleanup()
        }
    }
    @Test fun aLostSharedProofNamesItsClosedCause() {
        val expected = listOf("GRANT_EXPIRED", "CLOCK_ROLLBACK", "STORE_CHANGED store=FORGET", "STORE_CHANGED store=REPLACED",
            "DURABLE_OWNER_MISMATCH route=NONE", "DURABLE_OWNER_MISMATCH route=READ_FAILED",
            "DURABLE_READ_FAILED store=STORAGE_FAILED", "OWNER_RETIRED route=POOL_CHANGED", "OWNER_CLOSED")
        for ((loss, cause) in expected.withIndex()) {
            val f = Fixture(); val attachment = f.owner.attach(); val peer = f.owner.attach()
            val lease = (attachment.acquire() as AbemaAccountAccessResult.Account).lease
            when (loss) {
                0 -> f.wall = 2000000
                1 -> f.wall--
                2 -> f.store.invalidateForget()
                3 -> f.store.replace(decodedAbemaGrant(fixtureAccountToken("replacement"), "replacement", f.wall), f.wall, f.store.revision()) { true }
                4 -> f.ownerCurrent = false
                5 -> { f.ownerCurrent = false; f.routeRefusal = net.fstab.tachiai.platform.network.ProviderRouteRefusal.READ_FAILED }
                6 -> f.storage.readHook = { error("PRIVATE STORAGE ERROR") }
                7 -> { f.routeRefusal = net.fstab.tachiai.platform.network.ProviderRouteRefusal.POOL_CHANGED; f.owner.retire() }
                8 -> f.owner.close()
            }
            assertFalse(lease.checkWorker()); assertFalse(lease.current())
            assertEquals(listOf("PROOF_REVOKED cause=$cause attachments=2"), f.marks.filter { it.startsWith("PROOF_REVOKED") })
            peer.close(); attachment.close()
            // Detaching the last consumer is ordinary teardown, not a lost proof.
            assertEquals(1, f.marks.count { it.startsWith("PROOF_REVOKED") })
            f.storage.readHook = {}
        }
    }
    @Test fun aLostProofMarkerCarriesOnlyClosedTokens() {
        val f = Fixture(); val attachment = f.owner.attach()
        assertTrue(attachment.acquire() is AbemaAccountAccessResult.Account)
        f.routeRefusal = "not a closed token: https://example.test/?token=secret"
        f.owner.retire()
        assertEquals(listOf("PROOF_REVOKED cause=OWNER_RETIRED route=NONE attachments=1"),
            f.marks.filter { it.startsWith("PROOF_REVOKED") })
        attachment.close()
    }
    // #729: a held lease's durable check that read the store just before an adoption.
    private fun durableCheckAcrossAdoption(change: Int): Pair<Fixture, Boolean> {
        val f = Fixture(); val attachment = f.owner.attach(); val peer = f.owner.attach()
        val lease = (attachment.acquire() as AbemaAccountAccessResult.Account).lease
        assertTrue((peer.acquire() as AbemaAccountAccessResult.Account).lease.current())
        f.wall = 1_500_000
        fun replace(user: String, expiry: String) = f.store.replaceSnapshot(
            decodedAbemaGrant(fixtureAccountToken(user, expiry), user, f.wall), f.wall, f.store.revision()) { true }
        val base = f.ownerChecks
        // The second owner read of a durable check follows its store read.
        f.ownerHook = { when (f.ownerChecks - base) {
            2 -> when (change) {
                1 -> replace("different-account", "3000")
                2 -> f.store.rollbackPublished(replace("intended-account", "3000"))
                3 -> { replace("intended-account", "3000"); f.store.invalidateForget() }
                else -> replace("intended-account", "3000")
            }
            4 -> if (change == 4) replace("intended-account", "4000") // A second renewal during the repeat.
        } }
        val checked = lease.checkWorker()
        f.ownerHook = {}
        assertEquals(checked, lease.current())
        peer.close(); attachment.close()
        return f to checked
    }
    @Test fun aHeldLeaseCheckThatReadBeforeAnAdoptionFollowsTheRenewedGrant() {
        val (f, checked) = durableCheckAcrossAdoption(0)
        assertTrue(checked)
        assertTrue("DURABLE_RENEWED" in f.marks)
        assertTrue(f.marks.none { it.startsWith("PROOF_REVOKED") })
        assertEquals(1, f.profiles.get())
    }
    @Test fun aHeldLeaseCheckStillFailsClosedForAnyOtherChangeOrASecondRenewal() {
        for (change in 1..4) {
            val (f, checked) = durableCheckAcrossAdoption(change)
            assertFalse("change $change", checked)
            assertEquals("change $change", change == 4, "DURABLE_RENEWED" in f.marks)
            assertEquals("change $change", 1, f.marks.count { it.startsWith("PROOF_REVOKED") })
        }
    }

    // #745: the provider-instance and connection-profile stores behind the
    // route owner check, with the Android binding's verdict reads.
    private class RouteStores(decryptMs: Long = 0, maxAgeMs: Long = ProviderRouteOwnerReads.VERDICT_MAX_AGE_MS) {
        val instanceSecrets = EnvelopeSecretStore(decryptMs)
        val profileSecrets = EnvelopeSecretStore(decryptMs)
        val instances = ProviderInstanceStore(instanceSecrets)
        val profiles = ConnectionProfileStore(profileSecrets)
        val abema = defaultProviderInstanceId(PrototypeService.ABEMA)
        val routeId: String = profiles.add("Japan", routeProfile("first")).single().id
        init { instances.saveRoutes(abema, null, listOf(routeChoice(routeId, "Japan")), ::routeDefaults) }
        val captured = captureAbemaCatalogRouteOwner(abema, { instances.read(::routeDefaults) }, profiles::snapshot)
        private val admission = ProviderRouteAdmission(captured.proof())
        private val reads = ProviderRouteOwnerReads(instances, profiles, ::routeDefaults, maxAgeMs = maxAgeMs)
        // The shape of the Android binding's sameOwner.
        fun sameOwner() = admission.check { expected ->
            abemaCatalogRouteOwnerRead(reads, captured, abema, expected).takeIf(captured::sameOwnership)?.proof()
        }
        fun decrypts() = instanceSecrets.decrypts.get() + profileSecrets.decrypts.get()
    }
    private fun routedLease(r: RouteStores = RouteStores()): Triple<Fixture, AbemaAccountAccessOwner.Attachment, AbemaAccountAccessLease> {
        val f = Fixture(); f.ownerRead = r::sameOwner
        val attachment = f.owner.attach()
        val lease = (attachment.acquire() as AbemaAccountAccessResult.Account).lease
        return Triple(f, attachment, lease)
    }

    @Test fun routedDurableChecksDecryptNoOwnerStoreWhileItIsUnchanged() {
        val r = RouteStores()
        val (_, attachment, lease) = routedLease(r)
        assertTrue(lease.checkWorker())
        val decrypts = r.decrypts()
        repeat(10) { assertTrue(lease.checkWorker()) }
        assertEquals(decrypts, r.decrypts())
        attachment.close()
    }

    @Test fun everyStoreChangeStillTakesEffectForTheNextRoutedDurableCheck() {
        for (change in listOf("FORGET", "ROLLBACK", "ACCOUNT_REPLACEMENT", "PROFILE_REMOVED", "ROUTE_CHANGED", "RENEWAL")) {
            val r = RouteStores()
            val (f, attachment, lease) = routedLease(r)
            repeat(3) { assertTrue(change, lease.checkWorker()) }
            f.wall = 1_500_000
            fun replace(user: String, expiry: String) = f.store.replaceSnapshot(
                decodedAbemaGrant(fixtureAccountToken(user, expiry), user, f.wall), f.wall, f.store.revision()) { true }
            when (change) {
                "FORGET" -> f.store.forget()
                "ROLLBACK" -> {
                    val renewed = replace("intended-account", "3000")
                    assertTrue(change, lease.checkWorker())
                    f.store.rollbackPublished(renewed)
                }
                "ACCOUNT_REPLACEMENT" -> replace("different-account", "3000")
                "PROFILE_REMOVED" -> ConnectionProfileStore(r.profileSecrets).remove(r.routeId)
                "ROUTE_CHANGED" -> {
                    val other = ConnectionProfileStore(r.profileSecrets).add("Osaka", routeProfile("second")).last().id
                    ProviderInstanceStore(r.instanceSecrets).saveRoutes(r.abema, null,
                        listOf(routeChoice(other, "Osaka")), ::routeDefaults)
                }
                "RENEWAL" -> replace("intended-account", "3000")
            }
            val renewal = change == "RENEWAL"
            assertEquals(change, renewal, lease.checkWorker())
            assertEquals(change, renewal, lease.current())
            if (renewal) assertEquals(3_000_000L, lease.expiresAtMs())
            attachment.close()
        }
    }

    // Before/after harness for the PR: the same durable checks with every
    // owner check reading fully (maxAgeMs = 0, the previous behavior) and
    // with revision verdicts, while each store decrypt costs decryptMs. Two
    // threads stand in for two playing feeds sharing the store locks.
    @Test fun routedDurableCheckCostBeforeAndAfterRevisionVerdicts() {
        fun measure(maxAgeMs: Long): Pair<Double, Double> {
            val r = RouteStores(decryptMs = 10, maxAgeMs = maxAgeMs)
            val (_, attachment, lease) = routedLease(r)
            assertTrue(lease.checkWorker())
            val checks = 10
            val decrypts = r.decrypts()
            val pool = Executors.newFixedThreadPool(2)
            val began = System.nanoTime()
            try {
                val runs = List(2) { pool.submit<Boolean> { (1..checks).all { lease.checkWorker() } } }
                runs.forEach { assertTrue(it.get(30, TimeUnit.SECONDS)) }
            } finally { pool.shutdownNow() }
            val elapsedMs = (System.nanoTime() - began) / 1_000_000.0
            attachment.close()
            return (r.decrypts() - decrypts).toDouble() / (2 * checks) to elapsedMs / (2 * checks)
        }
        val (beforeDecrypts, beforeMs) = measure(0)
        val (afterDecrypts, afterMs) = measure(ProviderRouteOwnerReads.VERDICT_MAX_AGE_MS)
        println("routedDurableCheck before decryptsPerCheck=$beforeDecrypts msPerCheck=$beforeMs " +
            "after decryptsPerCheck=$afterDecrypts msPerCheck=$afterMs")
        assertEquals(8.0, beforeDecrypts, 0.0)
        assertEquals(0.0, afterDecrypts, 0.0)
        // Times are reported for comparison only; the decryption counts are the assertion.
        assertTrue(afterMs >= 0 && beforeMs >= 0)
    }
}

private fun routeDefaults() = legacyProviderSetups(defaultSourceSetups())
private fun routeProfile(secret: String) = parseConnectionProfile("http://user:$secret@proxy.example.test:3128".toByteArray())
private fun routeChoice(id: String, name: String) = SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, id, name)
