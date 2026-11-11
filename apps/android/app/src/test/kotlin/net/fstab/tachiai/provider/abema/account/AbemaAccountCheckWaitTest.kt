package net.fstab.tachiai.provider.abema.account

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import org.junit.Assert.*
import org.junit.Test

class AbemaAccountCheckWaitTest {
    private fun now() = System.nanoTime() / 1_000_000
    private fun join(thread: Thread, failure: AtomicReference<Throwable?>) {
        thread.join(3_000)
        assertFalse("Bounded account operation did not finish", thread.isAlive)
        failure.get()?.let { throw it }
    }
    private fun worker(failure: AtomicReference<Throwable?>, work: () -> Unit) = Thread {
        try { work() } catch (error: Throwable) { failure.set(error) }
    }.apply { start() }

    @Test fun interruptedLockWaitRefusesOnlyTheCallerAndPreservesItsFlag() {
        val lock = ReentrantLock()
        val failure = AtomicReference<Throwable?>()
        val began = CountDownLatch(1)
        lock.lock()
        try {
            val waiter = worker(failure) {
                began.countDown()
                assertEquals(AbemaAccountCheckLock.INTERRUPTED, acquireAbemaAccountCheckLock(lock, 1000))
                assertTrue(Thread.currentThread().isInterrupted)
                assertFalse(lock.isHeldByCurrentThread)
            }
            assertTrue(began.await(1, TimeUnit.SECONDS))
            waiter.interrupt()
            join(waiter, failure)
            assertTrue("Canceled waiter must not release another owner's check", lock.isHeldByCurrentThread)
        } finally { lock.unlock() }
        assertEquals(AbemaAccountCheckLock.ACQUIRED, acquireAbemaAccountCheckLock(lock, 1000))
        lock.unlock()
    }

    @Test fun dispatchedCheckDrainsBeforeAnotherCheckCanAcquireAndDoesNotConsumeItsReply() {
        val lock = ReentrantLock()
        val reply = CountDownLatch(1)
        val dispatched = CountDownLatch(1)
        val failed = AtomicReference<Throwable?>()
        val drained = AtomicReference<AbemaAccountCheckWait?>()
        val freshEntered = CountDownLatch(1)
        val first = worker(failed) {
            assertEquals(AbemaAccountCheckLock.ACQUIRED, acquireAbemaAccountCheckLock(lock, 1000))
            try {
                val start = now()
                dispatched.countDown()
                drained.set(awaitAbemaAccountCheck(reply, start, start + 2000, ::now) { true })
                assertTrue(Thread.currentThread().isInterrupted)
                assertTrue("CHECK must retain serialization through reply", lock.isHeldByCurrentThread)
            } finally { lock.unlock() }
        }
        assertTrue(dispatched.await(1, TimeUnit.SECONDS))
        first.interrupt()
        val second = worker(failed) {
            assertEquals(AbemaAccountCheckLock.ACQUIRED, acquireAbemaAccountCheckLock(lock, 2000))
            try {
                freshEntered.countDown()
                // A later operation has its own signal: the canceled CHECK's
                // already-completed latch cannot satisfy it.
                val freshReply = CountDownLatch(1)
                val start = now()
                val result = awaitAbemaAccountCheck(freshReply, start, start + 40, ::now) { true }
                assertFalse(result.completed)
                assertFalse(result.interrupted)
            } finally { lock.unlock() }
        }
        try {
            assertFalse("New CHECK must not overlap a canceled in-flight CHECK", freshEntered.await(50, TimeUnit.MILLISECONDS))
        } finally { reply.countDown() }
        join(first, failed)
        join(second, failed)
        assertEquals(AbemaAccountCheckWait(completed = true, interrupted = true), drained.get())
        assertEquals(0L, freshEntered.count)
    }

    @Test fun repeatedInterruptsDoNotRenewTheOriginalDeadline() {
        val clock = AtomicLong(1000)
        val began = CountDownLatch(1)
        val failed = AtomicReference<Throwable?>()
        val result = AtomicReference<AbemaAccountCheckWait?>()
        val waiter = worker(failed) {
            began.countDown()
            result.set(awaitAbemaAccountCheck(CountDownLatch(1), 1000, 1100, clock::get) { true })
            assertTrue(Thread.currentThread().isInterrupted)
        }
        assertTrue(began.await(1, TimeUnit.SECONDS))
        waiter.interrupt()
        clock.set(1099)
        waiter.interrupt()
        clock.set(1100)
        waiter.interrupt()
        join(waiter, failed)
        assertEquals(AbemaAccountCheckWait(completed = false, interrupted = true), result.get())
    }

    @Test fun expiredOwnerOrClockRollbackCannotBecomeACompletedCheck() {
        val completed = CountDownLatch(0)
        assertFalse(awaitAbemaAccountCheck(completed, 1000, 2000, { 1500 }) { false }.completed)
        assertFalse(awaitAbemaAccountCheck(completed, 1000, 2000, { 999 }) { true }.completed)
        assertFalse(awaitAbemaAccountCheck(completed, 1000, 2000, { 2000 }) { true }.completed)
        assertFalse(Thread.currentThread().isInterrupted)
    }

    @Test fun genuineLockTimeoutIsDistinctFromCancellation() {
        val lock = ReentrantLock()
        val failed = AtomicReference<Throwable?>()
        lock.lock()
        try {
            val waiter = worker(failed) {
                assertEquals(AbemaAccountCheckLock.TIMED_OUT, acquireAbemaAccountCheckLock(lock, 20))
                assertFalse(Thread.currentThread().isInterrupted)
            }
            join(waiter, failed)
        } finally { lock.unlock() }
    }

    @Test fun falseMalformedOrMisboundRepliesRemainRefusedEvenAfterACanceledWaitDrains() {
        val request = AbemaAccountAccessIpc.Request("a".repeat(32), "12345678-1234-1234-1234-123456789abc",
            "b".repeat(64), "c".repeat(64), 30000, true, true)
        val fields = AbemaAccountAccessIpc.base(request, 7) + ("current" to true)
        assertTrue(abemaAccountCheckAccepted(fields, request))
        for (bad in listOf(null, fields - "current", fields + ("current" to false), fields + ("current" to "true"),
            fields + ("current" to 1L), fields + ("sequence" to 0L), fields + ("sequence" to 7),
            fields + ("nonce" to "d".repeat(32)), fields + ("proof" to "d".repeat(64)),
            fields + ("source" to "d".repeat(64)), fields + ("deadline" to 30001L), fields + ("extra" to true)))
            assertFalse(abemaAccountCheckAccepted(bad, request))
    }
}
