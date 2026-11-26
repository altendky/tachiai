package net.fstab.tachiai.provider.abema.account

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

    @Test fun queuedCheckTimeoutDoesNotRevokeTheHealthyCheckAlreadyInFlight() {
        val lock = ReentrantLock()
        val firstEntered = CountDownLatch(1); val finishFirst = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val valid = AtomicBoolean(true)
        val revocations = AtomicInteger()
        val dispatched = AtomicInteger()
        val firstResult = AtomicReference<Boolean?>()
        val revoke = { revocations.incrementAndGet(); valid.set(false) }
        val first = worker(failure) {
            firstResult.set(performAbemaAccountCheck(lock, 2_000, ::now, valid::get,
                dispatch = { _, _ ->
                    dispatched.incrementAndGet(); firstEntered.countDown()
                    check(finishFirst.await(2, TimeUnit.SECONDS))
                    AbemaAccountCheckCompletion(accepted = true, interrupted = false)
                }, revoke = revoke))
        }
        try {
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
            assertFalse(performAbemaAccountCheck(lock, 20, ::now, valid::get,
                dispatch = { _, _ -> dispatched.incrementAndGet(); AbemaAccountCheckCompletion(true, false) }, revoke = revoke))
            assertEquals(1, dispatched.get())
            assertEquals("A local queue timeout owns no remote CHECK to revoke", 0, revocations.get())
            assertTrue(valid.get())
        } finally { finishFirst.countDown(); join(first, failure) }
        assertEquals(true, firstResult.get())
        assertEquals(0, revocations.get())
    }

    @Test fun budgetExhaustedBeforeDispatchDoesNotRevokeAnOtherwiseCurrentLease() {
        for (elapsed in listOf(100L, 101L)) {
            val lock = ReentrantLock()
            var reads = 0
            var valid = true
            var dispatched = 0
            var revocations = 0
            assertFalse(performAbemaAccountCheck(lock, 100, { if (reads++ == 0) 1_000 else 1_000 + elapsed }, { valid },
                dispatch = { _, _ -> dispatched++; AbemaAccountCheckCompletion(true, false) },
                revoke = { revocations++; valid = false }))
            assertEquals(0, dispatched)
            assertEquals("Expired pre-dispatch work must not revoke the account", 0, revocations)
            assertTrue(valid)
            assertFalse(lock.isLocked)
            assertTrue(performAbemaAccountCheck(lock, 100, { 2_000 }, { valid },
                dispatch = { _, _ -> dispatched++; AbemaAccountCheckCompletion(true, false) },
                revoke = { revocations++; valid = false }))
            assertEquals(1, dispatched)
        }
    }

    @Test fun aDispatchedRefusalStillRevokesEvenWhenItsCallerWasInterrupted() {
        val lock = ReentrantLock()
        var valid = true
        var revocations = 0
        try {
            assertFalse(performAbemaAccountCheck(lock, 100, { 1_000 }, { valid },
                dispatch = { _, _ ->
                    Thread.currentThread().interrupt()
                    AbemaAccountCheckCompletion(accepted = false, interrupted = true)
                }, revoke = { revocations++; valid = false }))
            assertTrue(Thread.currentThread().isInterrupted)
            assertFalse(valid); assertEquals(1, revocations)
            assertFalse(lock.isLocked)
        } finally { Thread.interrupted() }
    }

    // #729: a check that queued behind a slow one still gets its whole reply budget.
    @Test fun timeSpentQueuedNeverShortensTheDispatchedReplyBudget() {
        val lock = ReentrantLock()
        val clock = ArrayDeque(listOf(1_000L, 1_090L))
        var dispatchedAt = -1L; var budget = -1L
        assertTrue(performAbemaAccountCheck(lock, 100, { clock.removeFirstOrNull() ?: 1_090L }, { true },
            dispatch = { at, reply -> dispatchedAt = at; budget = reply; AbemaAccountCheckCompletion(true, false) },
            revoke = { error("A healthy check must not revoke") }, replyMs = 12_000))
        assertEquals(1_090L, dispatchedAt); assertEquals(12_000L, budget)
    }

    @Test fun queuedCallersAdoptAnAcceptedCheckDispatchedAfterTheyBegan() {
        val lock = ReentrantLock(true)
        val history = AbemaAccountCheckHistory()
        val failure = AtomicReference<Throwable?>()
        val firstEntered = CountDownLatch(1); val finishFirst = CountDownLatch(1)
        val dispatched = AtomicInteger(); val revocations = AtomicInteger()
        val results = java.util.concurrent.ConcurrentLinkedQueue<Boolean>()
        val paths = java.util.concurrent.ConcurrentLinkedQueue<AbemaAccountCheckPath>()
        fun attempt(block: Boolean) = performAbemaAccountCheck(lock, 2_000, ::now, { true },
            dispatch = { _, _ ->
                dispatched.incrementAndGet()
                if (block) { firstEntered.countDown(); check(finishFirst.await(2, TimeUnit.SECONDS)) }
                AbemaAccountCheckCompletion(true, false)
            }, revoke = { revocations.incrementAndGet() }, history = history,
            observe = { paths += it; error("A counter failure cannot change the verdict") })
        val first = worker(failure) { results += attempt(true) }
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
        // Both began after the in-flight check was dispatched, so neither may adopt it.
        val waiters = List(2) { worker(failure) { results += attempt(false) } }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (lock.queueLength < 2 && System.nanoTime() < deadline) Thread.sleep(2)
        assertEquals(2, lock.queueLength)
        finishFirst.countDown()
        join(first, failure); waiters.forEach { join(it, failure) }
        assertEquals(listOf(true, true, true), results.toList())
        assertEquals("One of the queued callers adopts the other's later accepted check", 2, dispatched.get())
        assertEquals(0, revocations.get())
        // #745 counters name each caller's path.
        assertEquals(2, paths.count { it == AbemaAccountCheckPath.DISPATCHED })
        assertEquals(1, paths.count { it == AbemaAccountCheckPath.COALESCED })
    }

    @Test fun anAcceptedLaterCheckCannotAdmitALeaseThatIsNoLongerCurrent() {
        val history = AbemaAccountCheckHistory()
        val stale = history.ticket(); history.accept(history.ticket())
        assertTrue(history.covers(stale)); assertFalse(history.covers(history.ticket()))
        var revocations = 0
        assertFalse(performAbemaAccountCheck(ReentrantLock(), 100, { 1_000 }, { false },
            dispatch = { _, _ -> error("A lost lease must not dispatch") }, revoke = { revocations++ }, history = history))
        assertEquals(1, revocations)
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
