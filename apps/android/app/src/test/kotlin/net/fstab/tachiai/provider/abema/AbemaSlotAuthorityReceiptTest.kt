package net.fstab.tachiai.provider.abema

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class AbemaSlotAuthorityReceiptTest {
    private fun scope(client: Any = Any(), run: Long = 1, deadline: Long = 5000) =
        AbemaSlotAuthorityScope(client, "instance", "route-owner", "exact-source", "channel", "slot", run, deadline)
    private fun receipt(scope: AbemaSlotAuthorityScope, expiry: Long = 10000) = checkNotNull(
        AbemaSlotAuthorityReceipt.publish(scope, expiry, 1000, 100, 1000, 1200, 200))

    @Test fun freshPublicationUsesOriginalReadinessAndPlaybackBudgets() {
        val owner = scope()
        assertNull(AbemaSlotAuthorityReceipt.publish(owner, 10000, 1000, 100, 1000, 1200, 1000))
        assertNull(AbemaSlotAuthorityReceipt.publish(owner, 10000, 1000, 100, 6000, 1200, 200))
        assertNull(AbemaSlotAuthorityReceipt.publish(owner, 10000, 1000, 100, 1000, 999, 200))
        assertNull(AbemaSlotAuthorityReceipt.publish(owner, 10000, 1000, 100, 1000, 1200, 99))
        assertNull(AbemaSlotAuthorityReceipt.publish(owner, 1200, 1000, 100, 1000, 1200, 200))
        assertNull(AbemaSlotAuthorityReceipt.publish(owner, Long.MAX_VALUE, 1000, 100, 1000, 1200, 200))
        assertTrue(receipt(owner).current(owner, 1300, 300))
    }

    @Test fun originalPlaybackDeadlineAndSubscriptionExpiryRemainIndependentAndSticky() {
        val owner = scope()
        val budget = receipt(owner)
        assertTrue(budget.current(owner, 1500, 4999))
        assertFalse(budget.current(owner, 1500, 5000))
        assertFalse(budget.current(owner, 1500, 4000))
        val expiry = receipt(owner, 2000)
        assertTrue(expiry.current(owner, 1999, 300))
        assertFalse(expiry.current(owner, 2000, 300))
        assertFalse(expiry.current(owner, 1999, 300))
    }

    @Test fun wallAndMonotonicRollbackEndAuthorityWithoutStorageReads() {
        for (wallRollback in listOf(true, false)) {
            val owner = scope(); val proof = receipt(owner)
            assertTrue(proof.current(owner, 1500, 500))
            assertFalse(proof.current(owner, if (wallRollback) 1499 else 1500,
                if (wallRollback) 500 else 499))
            assertFalse(proof.current(owner, 1600, 600))
        }
    }

    @Test fun concurrentIncreasingClockSamplesCannotRevokeTheSameSlotAuthority() {
        for (pauseOnWall in listOf(true, false)) {
            val owner = scope(); val proof = receipt(owner)
            val enteredA = CountDownLatch(1); val attemptedB = CountDownLatch(1); val completedB = CountDownLatch(1)
            val firstThread = AtomicReference<Thread?>()
            fun sample(first: Long, next: Long, pause: Boolean): Long {
                if (Thread.currentThread() !== firstThread.get()) return next
                val captured = first
                if (pause) {
                    enteredA.countDown()
                    check(attemptedB.await(3, TimeUnit.SECONDS))
                    // Sampling within the receipt's monitor keeps B from
                    // publishing a newer clock before A commits this one.
                    completedB.await(500, TimeUnit.MILLISECONDS)
                }
                return captured
            }
            val executor = Executors.newFixedThreadPool(2)
            try {
                val a = executor.submit<Boolean> {
                    firstThread.set(Thread.currentThread())
                    proof.current(owner, { sample(1300, 1400, pauseOnWall) }, { sample(300, 400, !pauseOnWall) })
                }
                assertTrue(enteredA.await(3, TimeUnit.SECONDS))
                val b = executor.submit<Boolean> {
                    attemptedB.countDown()
                    try { proof.current(owner, { 1400 }, { 400 }) }
                    finally { completedB.countDown() }
                }
                assertTrue("A valid earlier sample must not be a clock rollback", a.get(3, TimeUnit.SECONDS))
                assertTrue(b.get(3, TimeUnit.SECONDS))
                assertTrue(proof.current(owner, { 1500 }, { 500 }))
            } finally {
                attemptedB.countDown(); completedB.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun aQueuedForwardSampleCannotBypassOrUndoAnObservedClockRollback() {
        val owner = scope(); val proof = receipt(owner)
        val enteredRollback = CountDownLatch(1); val releaseRollback = CountDownLatch(1); val attemptedForward = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val rollback = executor.submit<Boolean> {
                proof.current(owner, {
                    enteredRollback.countDown()
                    check(releaseRollback.await(3, TimeUnit.SECONDS))
                    1199 // Earlier than the published 1200, so actual revocation is required.
                }, { 300 })
            }
            assertTrue(enteredRollback.await(3, TimeUnit.SECONDS))
            val forward = executor.submit<Boolean> {
                attemptedForward.countDown()
                proof.current(owner, { 1300 }, { 400 })
            }
            assertTrue(attemptedForward.await(3, TimeUnit.SECONDS))
            releaseRollback.countDown()
            assertFalse(rollback.get(3, TimeUnit.SECONDS))
            assertFalse(forward.get(3, TimeUnit.SECONDS))
            assertFalse(proof.current(owner, { 1400 }, { 500 }))
        } finally {
            releaseRollback.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test fun authorityCannotMoveToAnotherClientSourceRouteSlotOrRun() {
        val owner = scope()
        val replacements = listOf(scope(), scope(owner.client, run = 2),
            AbemaSlotAuthorityScope(owner.client, owner.instanceId, "replacement-route", owner.sourceProof,
                owner.channelId, owner.slotId, owner.run, owner.playbackDeadline),
            AbemaSlotAuthorityScope(owner.client, owner.instanceId, owner.ownerProof, "replacement-source",
                owner.channelId, "other-slot", owner.run, owner.playbackDeadline),
            scope(owner.client))
        for (replacement in replacements) {
            val proof = receipt(owner)
            assertFalse(proof.current(replacement, 1300, 300))
            assertFalse(proof.current(owner, 1400, 400))
        }
        assertFalse(owner.toString().contains("route-owner"))
        assertFalse(receipt(owner).toString().contains("10000"))
    }
}
