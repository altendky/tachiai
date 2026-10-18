package net.fstab.tachiai.platform.media

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class NativeCleanupReceiptTest {
    private class Lane : Executor {
        private val tasks = ArrayDeque<Runnable>()
        var rejection: RejectedExecutionException? = null
        override fun execute(command: Runnable) {
            rejection?.let { throw it }
            tasks.addLast(command)
        }
        fun next() { check(tasks.isNotEmpty()) { "Expected queued cleanup work" }; tasks.removeFirst().run() }
        fun drain() { while (tasks.isNotEmpty()) next() }
        val empty get() = tasks.isEmpty()
    }

    @Test fun asyncCleanupWaitsForBothLanesAndCompletionBeforeConfirmingExactlyOnce() {
        val receipt = NativeCleanupReceipt(); val worker = Lane(); val owner = Lane()
        val events = mutableListOf<String>(); var completed = 0
        receipt.own(40, ownerThread = true) { events += "quality" }
        receipt.own(10) { events += "requests" }
        receipt.own(30, ownerThread = true) { events += "player" }
        receipt.own(25) { events += "input" }
        val failures = mutableListOf<Throwable>()
        receipt.closeAsync(worker, owner, { completed++ }, failures::add)
        receipt.closeAsync(worker, owner, { fail("Repeated pending close must not own completion") }, failures::add)
        assertTrue(receipt.pending); assertFalse(receipt.confirmed); assertTrue(events.isEmpty())
        worker.next()
        assertEquals(listOf("requests", "input"), events)
        assertTrue(receipt.pending); assertFalse(receipt.confirmed)
        owner.drain()
        assertEquals(listOf("requests", "input", "player", "quality"), events)
        assertFalse(receipt.pending); assertTrue(receipt.confirmed); assertEquals(1, completed)
        assertTrue(failures.isEmpty()); assertTrue(worker.empty); assertTrue(owner.empty)
        receipt.close(); receipt.closeAsync(worker, owner, { completed++ }, failures::add)
        assertEquals(1, completed); assertEquals(4, events.size)
    }

    @Test fun asyncTransportFailuresRemainStickyWhilePlayerAndQualityStillRelease() {
        var unconfirmed = 0
        val receipt = NativeCleanupReceipt { unconfirmed++ }; val worker = Lane(); val owner = Lane()
        val first = IOException("Synthetic disconnect failure"); val second = AssertionError("Synthetic input failure")
        val events = mutableListOf<String>(); val failures = mutableListOf<Throwable>(); var completed = 0
        receipt.own(10) { events += "disconnect"; throw first }
        receipt.own(25) { events += "input"; throw second }
        receipt.own(30, ownerThread = true) { events += "player" }
        receipt.own(40, ownerThread = true) { events += "quality" }
        receipt.closeAsync(worker, owner, { completed++ }, failures::add)
        worker.next(); owner.drain()
        assertEquals(listOf("disconnect", "input", "player", "quality"), events)
        assertSame(first, failures.single()); assertArrayEquals(arrayOf(second), first.suppressed)
        assertEquals(1, completed); assertTrue(unconfirmed > 0)
        assertFalse(receipt.pending); assertFalse(receipt.confirmed)
        assertThrows(IOException::class.java) { receipt.close() }
        assertThrows(IOException::class.java) { receipt.closeAsync(worker, owner, {}, failures::add) }
        assertEquals(4, events.size); assertEquals(1, failures.size)
    }

    @Test fun rejectedTransportLaneRetainsItsResourcesButStillDispatchesPlayerReleaseOnOwner() {
        val receipt = NativeCleanupReceipt(); val worker = Lane(); val owner = Lane()
        val refusal = RejectedExecutionException("Synthetic worker refusal")
        worker.rejection = refusal
        var transport = 0; var player = 0; var completed = 0
        val failures = mutableListOf<Throwable>()
        receipt.own(10) { transport++ }
        receipt.own(30, ownerThread = true) { player++ }
        receipt.closeAsync(worker, owner, { completed++ }, failures::add)
        assertTrue(receipt.pending); assertEquals(0, transport); assertEquals(0, player)
        owner.drain()
        assertEquals(0, transport); assertEquals(1, player); assertEquals(1, completed)
        assertSame(refusal, failures.single()); assertFalse(receipt.pending); assertFalse(receipt.confirmed)
        assertThrows(IOException::class.java) { receipt.close() }
        assertEquals(0, transport); assertEquals(1, player)
    }

    @Test fun rejectedOwnerLaneNeverRunsPlayerOnWorkerAndDoesNotConfirmCleanup() {
        val receipt = NativeCleanupReceipt(); val worker = Lane(); val owner = Lane()
        val refusal = RejectedExecutionException("Synthetic owner refusal")
        owner.rejection = refusal
        val events = mutableListOf<String>(); val failures = mutableListOf<Throwable>(); var completed = 0
        receipt.own(10) { events += "transport" }
        receipt.own(30, ownerThread = true) { fail("Owner release must not run on another lane") }
        receipt.own(50) { events += "later transport" }
        receipt.closeAsync(worker, owner, { completed++ }, failures::add)
        worker.drain()
        assertEquals(listOf("transport", "later transport"), events)
        assertSame(refusal, failures.single()); assertEquals(1, completed)
        assertFalse(receipt.pending); assertFalse(receipt.confirmed)
        assertThrows(IOException::class.java) { receipt.close() }
    }

    @Test fun asyncCloseCancelsThenDrainsAnAdmittedFactoryBeforeReleasingPlayer() {
        var notices = 0
        val receipt = NativeCleanupReceipt { notices++ }; val worker = Lane(); val owner = Lane()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var allocationFailure: Throwable? = null; var lateReleases = 0; var playerReleases = 0
        val allocator = Thread {
            try {
                receipt.allocate(20, {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); Any()
                }) { lateReleases++ }
            } catch (error: Throwable) { allocationFailure = error }
        }
        receipt.own(10) { release.countDown() }
        receipt.own(30, ownerThread = true) {
            assertEquals(1, lateReleases); playerReleases++
        }
        allocator.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val failures = mutableListOf<Throwable>(); var completed = 0
            receipt.seal()
            assertEquals(0, notices)
            receipt.closeAsync(worker, owner, { completed++ }, failures::add)
            assertTrue(receipt.pending); assertFalse(receipt.confirmed)
            worker.next(); allocator.join(5_000)
            assertFalse(allocator.isAlive); assertTrue(allocationFailure is IOException)
            assertEquals(1, lateReleases); assertTrue(receipt.pending); assertEquals(0, playerReleases)
            owner.drain()
            assertEquals(1, playerReleases); assertEquals(1, completed)
            assertTrue(failures.isEmpty()); assertEquals(0, notices)
            assertFalse(receipt.pending); assertTrue(receipt.confirmed)
            receipt.close()
            assertEquals(1, lateReleases); assertEquals(1, playerReleases)
        } finally { release.countDown(); allocator.join(5_000) }
    }

    @Test fun selfCloseFailureThenOuterCloseCannotBecomeNoOpConfirmation() {
        val owner = NativeCleanupReceipt(); val order = mutableListOf<String>()
        owner.own(10) { order.add("requests"); throw AssertionError("fixture") }
        owner.own(30) { order.add("player") }; owner.own(40) { order.add("quality") }
        assertThrows(AssertionError::class.java) { owner.close() }
        assertEquals(listOf("requests", "player", "quality"), order)
        assertFalse(owner.confirmed)
        assertThrows(IOException::class.java) { owner.close() }
        assertEquals(3, order.size)
    }
    @Test fun partialConstructionRollbackFailureRemainsOwnedAndRemainingAllocationsAreReleased() {
        val owner = NativeCleanupReceipt(); var releases = 0
        owner.own(30) { releases++; throw IOException("fixture") }
        owner.own(40) { releases++ }
        assertThrows(IOException::class.java) { owner.close() }
        assertEquals(2, releases); assertFalse(owner.confirmed)
        assertThrows(IOException::class.java) { owner.close() }
        assertEquals(2, releases)
    }
    @Test fun observedWorkerDisconnectFailureCannotBeErasedBySuccessfulOuterCleanup() {
        val owner = NativeCleanupReceipt(); var releases = 0
        owner.own(10) { releases++ }
        owner.failed()
        assertThrows(IOException::class.java) { owner.close() }
        assertEquals(1, releases); assertFalse(owner.confirmed)
    }
    @Test fun concurrentLateAllocationRetainsFailedReceiptAndCannotConfirmPriorClose() {
        val owner = NativeCleanupReceipt(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        owner.own(10) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        var failure: Throwable? = null
        val closer = Thread { try { owner.close() } catch (error: Throwable) { failure = error } }
        closer.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertThrows(IOException::class.java) { owner.own(20) {} }
        } finally { release.countDown(); closer.join(5_000) }
        assertFalse(closer.isAlive); assertTrue(failure is IOException); assertFalse(owner.confirmed)
    }
    @Test fun successfulCleanupIsOrderedAndOnceOnlyAndLateOwnershipInvalidatesIt() {
        val owner = NativeCleanupReceipt(); val order = mutableListOf<Int>()
        owner.own(40) { order.add(40) }; owner.own(10) { order.add(10) }
        owner.close(); owner.close()
        assertTrue(owner.confirmed); assertEquals(listOf(10, 40), order)
        assertThrows(IOException::class.java) { owner.own(20) {} }
        assertFalse(owner.confirmed); assertThrows(IOException::class.java) { owner.close() }
    }

    @Test fun sealedAllocationGatePreventsFactorySideEffects() {
        val owner = NativeCleanupReceipt(); var opened = false
        owner.seal()
        assertThrows(IOException::class.java) { owner.allocate(10, { opened = true; Any() }) {} }
        assertFalse(opened); owner.close(); assertTrue(owner.confirmed)
    }
    @Test fun synchronousCloseSealsWithoutPoisoningWhileAnAdmittedFactoryIsPending() {
        var notices = 0
        val owner = NativeCleanupReceipt { notices++ }
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val resource = Any(); var failure: Throwable? = null; var releases = 0
        val allocator = Thread {
            try { owner.allocate(10, { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); resource }) { releases++ } }
            catch (error: Throwable) { failure = error }
        }
        allocator.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertThrows(IOException::class.java) { owner.close() }
            assertFalse(owner.confirmed); assertEquals(0, notices)
            assertThrows(IOException::class.java) { owner.allocate(10, { fail("Admission must be sealed") }) {} }
        } finally { release.countDown(); allocator.join(5_000) }
        assertFalse(allocator.isAlive); assertTrue(failure is IOException)
        assertFalse(owner.confirmed); assertEquals(0, notices)
        owner.close(); owner.close()
        assertTrue(owner.confirmed); assertEquals(1, releases); assertEquals(0, notices)
    }

    @Test fun cancelledFactoryFailureSettlesWithoutInventingACleanupFailure() {
        val receipt = NativeCleanupReceipt(); val worker = Lane(); val owner = Lane()
        val entered = CountDownLatch(1); val cancel = CountDownLatch(1)
        val expected = IOException("Synthetic cancelled open")
        var observed: Throwable? = null
        val allocator = Thread {
            try { receipt.input {
                entered.countDown(); check(cancel.await(5, TimeUnit.SECONDS)); throw expected
            } } catch (error: Throwable) { observed = error }
        }
        receipt.own(10) { cancel.countDown() }
        allocator.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val failures = mutableListOf<Throwable>(); var completed = 0
            receipt.seal(); receipt.closeAsync(worker, owner, { completed++ }, failures::add)
            worker.drain(); owner.drain(); allocator.join(5_000)
            assertFalse(allocator.isAlive); assertSame(expected, observed)
            assertTrue(receipt.confirmed); assertFalse(receipt.pending)
            assertTrue(failures.isEmpty()); assertEquals(1, completed)
        } finally { cancel.countDown(); allocator.join(5_000) }
    }

    @Test fun lateAdmittedResourceCloseFailureStillReleasesPlayerAndBlocksConfirmation() {
        val receipt = NativeCleanupReceipt(); val worker = Lane(); val owner = Lane()
        val entered = CountDownLatch(1); val cancel = CountDownLatch(1)
        val expected = IOException("Synthetic late close failure")
        var releases = 0; var playerReleases = 0
        val allocator = Thread {
            try { receipt.allocate(25, {
                entered.countDown(); check(cancel.await(5, TimeUnit.SECONDS)); Any()
            }) { releases++; throw expected } } catch (_: IOException) { }
        }
        receipt.own(10) { cancel.countDown() }
        receipt.own(30, ownerThread = true) { playerReleases++ }
        allocator.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val failures = mutableListOf<Throwable>()
            receipt.seal(); receipt.closeAsync(worker, owner, {}, failures::add)
            worker.drain(); owner.drain(); allocator.join(5_000)
            assertFalse(allocator.isAlive); assertSame(expected, failures.single())
            assertFalse(receipt.confirmed); assertFalse(receipt.pending)
            assertEquals(1, releases); assertEquals(1, playerReleases)
            assertThrows(IOException::class.java) { receipt.close() }
            assertEquals(1, releases)
        } finally { cancel.countDown(); allocator.join(5_000) }
    }
    @Test fun ownedStreamCloseFailureRemainsStickyAndSuccessIsNeverDoubleClosed() {
        for (error in listOf<Throwable?>(null, IOException("fixture"), AssertionError("fixture"))) {
            val owner = NativeCleanupReceipt(); var closes = 0
            val stream = owner.input { object : java.io.ByteArrayInputStream(byteArrayOf(1)) {
                override fun close() { closes++; error?.let { throw it } }
            } }
            if (error == null) { stream.close(); owner.close(); assertTrue(owner.confirmed) }
            else {
                assertThrows(error.javaClass) { stream.close() }
                assertThrows(IOException::class.java) { owner.close() }
                assertFalse(owner.confirmed)
            }
            assertEquals(1, closes)
        }
    }
    @Test fun releaseTimeoutReportedAsPlayerErrorCannotConfirmVoidRelease() {
        var notices = 0; val owner = NativeCleanupReceipt { notices++ }
        owner.own(30) { owner.playerError(true, 1003) }
        assertThrows(IOException::class.java) { owner.close() }
        assertFalse(owner.confirmed); assertTrue(notices > 0)
        assertThrows(IOException::class.java) { owner.close() }
        val normal = NativeCleanupReceipt()
        normal.playerError(false, 1003); normal.playerError(true, 1002)
        normal.close(); assertTrue(normal.confirmed)
    }

    @Test fun internalSelfStopFailureReturnsForQueuedDisposalButExternalCloseStillRejects() {
        for (error in listOf<Throwable>(IOException("fixture"), AssertionError("fixture"))) {
            val queuedOwner = mutableListOf<() -> Unit>()
            var notices = 0; var releases = 0
            val owner = NativeCleanupReceipt { notices++ }
            owner.own(30) { releases++; throw error }
            val observed = mutableListOf<Throwable>()
            // Same order as the production onPlayerError/ticker callbacks:
            // enqueue the owner event, then contain internal self-stop failure.
            queuedOwner.add { assertThrows(IOException::class.java) { owner.close() } }
            owner.stopInternally({ stage, failure ->
                assertEquals(NativeFailureStage.PLAYER_RELEASE, stage); observed.add(failure)
            }, owner::close)
            assertSame(error, observed.single()); assertFalse(owner.confirmed)
            assertTrue(notices > 0); assertEquals(1, releases)
            queuedOwner.single()()
            assertThrows(IOException::class.java) { owner.close() }
            assertEquals(1, releases); assertFalse(owner.confirmed)
        }
    }

}
