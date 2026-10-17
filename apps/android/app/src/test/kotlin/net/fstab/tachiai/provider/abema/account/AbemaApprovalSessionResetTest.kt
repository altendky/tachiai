package net.fstab.tachiai.provider.abema.account

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AbemaApprovalSessionResetTest {
    private class Fixture(waitMs: Long = 1) {
        var supported = true
        var ownerCurrent = true
        var now = 0L
        var calls = 0
        var callback: () -> Unit = {}
        val replies = mutableListOf<Boolean>()
        var delete: (() -> Unit) -> Unit = { calls++; callback = it }
        val reset = AbemaApprovalSessionReset({ supported }, { delete(it) },
            { ownerCurrent && now in 0 until 1000 }, { replies.add(it) }, waitMs)
    }

    @Test fun successfulDeletionAuthorizesExactlyOnceOnlyAfterDispatchReturns() {
        val f = Fixture()
        f.delete = { callback ->
            f.calls++; f.callback = callback
            callback(); callback()
            assertTrue(f.replies.isEmpty())
            assertFalse(f.reset.completionConfirmed)
        }
        f.reset.begin(); f.reset.begin(); f.callback()
        assertEquals(1, f.calls); assertEquals(listOf(true), f.replies)
        assertTrue(f.reset.completionConfirmed); assertTrue(f.reset.awaitCleanup())
    }

    @Test fun unsupportedOrPredispatchAuthorityLossNeverDeletesAndPermitsCleanup() {
        for (reason in 0..2) {
            val f = Fixture()
            when (reason) { 0 -> f.supported = false; 1 -> f.ownerCurrent = false; 2 -> f.now = 1000 }
            f.reset.begin()
            assertEquals(0, f.calls); assertEquals(listOf(false), f.replies)
            assertTrue(f.reset.completionConfirmed); assertTrue(f.reset.awaitCleanup())
        }
    }

    @Test fun cancellationOwnerLossAndClockExpiryBlockLateLoadButKeepPhysicalReceipt() {
        for (reason in 0..3) {
            val f = Fixture(); f.reset.begin()
            when (reason) { 0 -> f.reset.cancel(); 1 -> f.ownerCurrent = false; 2 -> f.now = 1000; 3 -> f.now = -1 }
            assertFalse(f.reset.completionConfirmed)
            f.callback(); f.callback()
            assertEquals(listOf(false), f.replies)
            assertTrue(f.reset.completionConfirmed); assertTrue(f.reset.awaitCleanup())
        }
    }

    @Test fun cleanupBeforeBeginRevokesAnyFutureDispatch() {
        val f = Fixture()
        assertTrue(f.reset.awaitCleanup())
        f.reset.begin(); f.reset.begin()
        assertEquals(0, f.calls); assertEquals(listOf(false), f.replies)
    }

    @Test fun lostCallbackTimeoutIsStickyEvenAfterLatePhysicalCompletion() {
        val f = Fixture(); f.reset.begin()
        assertFalse(f.reset.awaitCleanup()); assertFalse(f.reset.completionConfirmed)
        f.callback(); f.callback(); f.reset.begin()
        assertEquals(1, f.calls); assertEquals(listOf(false), f.replies)
        assertFalse(f.reset.completionConfirmed); assertFalse(f.reset.awaitCleanup())
    }

    @Test fun thrownDispatchStaysUnconfirmedEvenWhenItCalledBackBeforeThrowing() {
        for (callbackFirst in listOf(false, true)) {
            val f = Fixture()
            f.delete = { callback -> f.calls++; f.callback = callback
                if (callbackFirst) callback()
                throw AssertionError("Synthetic deletion dispatch failure")
            }
            f.reset.begin(); f.callback()
            assertEquals(listOf(false), f.replies)
            assertFalse(f.reset.completionConfirmed); assertFalse(f.reset.awaitCleanup())
        }
    }

    @Test fun heldCleanupWaitsForDeletionRatherThanCancellationOrDispatchReturn() {
        val f = Fixture(1000); f.reset.begin(); f.reset.cancel()
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        try {
            val cleanup = worker.submit<Boolean> { entered.countDown(); f.reset.awaitCleanup() }
            assertTrue(entered.await(1, TimeUnit.SECONDS)); assertFalse(cleanup.isDone)
            f.callback()
            assertTrue(cleanup.get(1, TimeUnit.SECONDS))
            assertEquals(listOf(false), f.replies); assertTrue(f.reset.completionConfirmed)
        } finally { worker.shutdownNow() }
    }
}
