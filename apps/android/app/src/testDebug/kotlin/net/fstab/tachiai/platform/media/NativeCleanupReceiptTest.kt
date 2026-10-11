package net.fstab.tachiai.platform.media

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class NativeCleanupReceiptTest {
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
    @Test fun inFlightFactoryCannotConfirmCleanupAndItsEventualResourceIsRetained() {
        var notices = 0
        val owner = NativeCleanupReceipt { notices++ }
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val resource = Any(); var failure: Throwable? = null
        val allocator = Thread {
            try { owner.allocate(10, { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); resource }) {} }
            catch (error: Throwable) { failure = error }
        }
        allocator.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); owner.seal()
            assertThrows(IOException::class.java) { owner.close() }
        } finally { release.countDown(); allocator.join(5_000) }
        assertFalse(allocator.isAlive); assertTrue(failure is IOException)
        assertFalse(owner.confirmed); assertTrue(notices > 0)
        val field = NativeCleanupReceipt::class.java.getDeclaredField("actions").apply { isAccessible = true }
        assertEquals(1, (field.get(owner) as List<*>).size)
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
