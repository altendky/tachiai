package net.fstab.tachiai.provider.abema.account

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class AbemaAccountAccessOwnerTest {
    private class Fixture(saved: Boolean = true) {
        val storage = MemoryAbemaAccountStorage()
        val instance = UUID.randomUUID().toString()
        val store = AbemaAccountGrantStore(instance, storage)
        @Volatile var wall = 1000000L
        @Volatile var elapsed = 0L
        var elapsedHook: () -> Long = { elapsed }
        @Volatile var ownerCurrent = true
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
        val owner: AbemaAccountAccessOwner
        fun awaitCleanup() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!owner.cleanupConfirmed && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue(owner.cleanupConfirmed)
        }
        init {
            if (saved) store.replace(decodedAbemaGrant(fixtureAccountToken(), "intended-account", wall), wall, store.revision()) { true }
            owner = AbemaAccountAccessOwner(instance, "fixture-owner", store, { wall }, { elapsedHook() },
                { ownerChecks++; ownerHook(); ownerCurrent }, { gate -> object : AbemaAccountTransport {
                    override fun profile(id: String, bearer: String): AbemaAccountProfileResult {
                        assertTrue(gate()); profiles.incrementAndGet(); profileAction(); return profile
                    }
                    override fun emailRegistration(id: String, bearer: String): AbemaAccountEmailResult {
                        assertTrue(gate()); emails.incrementAndGet(); return email
                    }
                    override fun token(challenge: AbemaAccountChallenge): AbemaAccountTokenResult = error("NO TOKEN EXCHANGE")
                    override fun cancelActiveRequest() { cancels.incrementAndGet() }
                    override fun close() { closes.incrementAndGet(); closeAction(); if (closeThrows) error("PRIVATE CLOSE ERROR") }
                } }, { cleanupAction(); cleanup })
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
}
