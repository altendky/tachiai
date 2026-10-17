package net.fstab.tachiai.platform.network

import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import net.fstab.tachiai.presentation.defaultProviderInstances
import org.junit.Assert.*
import org.junit.Test

class ProviderStartupScopeCleanupTest {
    private val id = "00000000-0000-0000-0000-000000000001"
    private val otherId = "00000000-0000-0000-0000-000000000002"
    private class Queue : Executor {
        val jobs = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { jobs.addLast(command) }
        fun drain() { while (jobs.isNotEmpty()) jobs.removeFirst().run() }
    }
    private class Resource(private val confirms: Boolean = true, private val closeFails: Boolean = false,
        private val cancelFails: Boolean = false) : ProviderStartupCleanupResource {
        var cancellations = 0
        var closes = 0
        override fun cancel() { cancellations++; check(!cancelFails) }
        override fun close() { closes++; check(!closeFails) }
        override val cleanupConfirmed get() = confirms
    }

    @Test fun retirementSignalsImmediatelyButReplacementWaitsForConfirmedWorkerCleanup() {
        val queue = Queue(); val registry = ProviderStartupCleanupRegistry(queue) { 100 }
        val resource = Resource()
        assertTrue(registry.isReady(id))
        registry.retire(id, resource)
        assertEquals(1, resource.cancellations); assertEquals(0, resource.closes)
        assertFalse(registry.isReady(id)); assertFalse(registry.isPoisoned(id)); assertTrue(registry.isReady(otherId))
        assertEquals(ProviderStartupCleanupAdmission.DEADLINE, registry.awaitReady(id, 100) { true })
        queue.drain()
        assertEquals(1, resource.closes); assertTrue(registry.isReady(id))
        assertEquals(ProviderStartupCleanupAdmission.READY, registry.awaitReady(id, 200) { true })
    }

    @Test fun uncertainCleanupIsStickyAcrossReplacementAndCannotUseASecondCloseAsProof() {
        val queue = Queue(); val registry = ProviderStartupCleanupRegistry(queue) { 100 }
        for (resource in listOf(Resource(confirms = false), Resource(closeFails = true), Resource(cancelFails = true))) {
            registry.retire(id, resource); queue.drain()
            assertEquals(ProviderStartupCleanupAdmission.CLEANUP_UNCONFIRMED, registry.awaitReady(id, 200) { true })
            registry.retire(id, resource); queue.drain()
            assertEquals(1, resource.closes); assertEquals(1, resource.cancellations)
            assertFalse(registry.isReady(id))
            assertTrue(registry.isPoisoned(id))
        }
        val replacement = Resource()
        registry.retire(id, replacement); queue.drain()
        assertEquals(1, replacement.closes)
        assertEquals(ProviderStartupCleanupAdmission.CLEANUP_UNCONFIRMED, registry.awaitReady(id, 1000) { true })
        assertTrue(registry.isReady(otherId))
    }

    @Test fun allRetirementsForOneInstanceMustConfirmBeforeAnotherStartupCanPass() {
        val queue = Queue(); val registry = ProviderStartupCleanupRegistry(queue) { 100 }
        registry.retire(id, Resource()); registry.retire(id, Resource())
        queue.jobs.removeFirst().run()
        assertFalse(registry.isReady(id))
        queue.jobs.removeFirst().run()
        assertTrue(registry.isReady(id))
    }

    @Test fun cancellationOrExpiredWaitDoesNotAbandonTheOwnedQueuedClose() {
        val queue = Queue(); val registry = ProviderStartupCleanupRegistry(queue) { 100 }
        val resource = Resource(); registry.retire(id, resource)
        assertEquals(ProviderStartupCleanupAdmission.CANCELLED, registry.awaitReady(id, 1000) { false })
        assertEquals(ProviderStartupCleanupAdmission.CANCELLED, registry.awaitReady(id, 1000) { error("Unavailable caller") })
        assertEquals(ProviderStartupCleanupAdmission.DEADLINE, registry.awaitReady(id, 99) { true })
        assertFalse(registry.isReady(id)); assertEquals(0, resource.closes)
        queue.drain()
        assertEquals(1, resource.closes); assertTrue(registry.isReady(id))
    }

    @Test fun rejectedCleanupQueueQuarantinesTheRetainedScopeAndNeverRetriesItImplicitly() {
        val attempts = AtomicInteger()
        val registry = ProviderStartupCleanupRegistry(Executor { attempts.incrementAndGet(); throw RejectedExecutionException("fixture") }) { 100 }
        val resource = Resource(); registry.retire(id, resource)
        assertEquals(ProviderStartupCleanupAdmission.CLEANUP_UNCONFIRMED, registry.awaitReady(id, 200) { true })
        registry.retire(id, resource)
        assertEquals(1, attempts.get()); assertEquals(0, resource.closes); assertEquals(1, resource.cancellations)
        assertFalse(registry.isReady(id))
    }

    @Test fun lateOwnershipAfterConfirmedRetirementClosesOnTheRegisteringWorkerAndUncertaintyReinstatesTheGate() {
        for (confirms in listOf(true, false)) {
            val queue = Queue(); val registry = ProviderStartupCleanupRegistry(queue) { 100 }
            val instance = defaultProviderInstances().first()
            val pool = captureProviderRoutePool(instance.id, instance.service, { listOf(instance) }) { emptyMap() }
            val scope = ProviderRouteStartup(pool, 1000, { 100 }, { true }, scheduleDeadline = { _, _ -> AutoCloseable {} })
            lateinit var context: ProviderStartupAttemptContext
            scope.runReadOnly { _, attempt -> context = attempt; ProviderStartupAttempt.Admitted("validated response") }
            registry.retire(id, scope); queue.drain()
            assertTrue(registry.isReady(id)); assertTrue(scope.cleanupConfirmed)
            var closes = 0
            context.own(object : ProviderStartupLease {
                override fun cancel() {}
                override fun closeAndConfirm(): Boolean { closes++; return confirms }
            })
            assertEquals(1, closes); assertFalse(context.canContinue())
            assertEquals(confirms, registry.isReady(id))
            if (!confirms) {
                assertEquals(ProviderStartupCleanupAdmission.CLEANUP_UNCONFIRMED, registry.awaitReady(id, 200) { true })
                registry.retire(id, scope); queue.drain()
                assertEquals(1, closes)
            }
        }
    }

    @Test fun heldCloseRunsOffCallerAndAnAwaiterProceedsOnlyAfterItsReceiptConfirms() {
        val executor = Executors.newSingleThreadExecutor()
        val closeStarted = CountDownLatch(1); val release = CountDownLatch(1); val waiting = CountDownLatch(1)
        val caller = Thread.currentThread()
        val registry = ProviderStartupCleanupRegistry(executor) { System.nanoTime() / 1_000_000 }
        val resource = object : ProviderStartupCleanupResource {
            var closed = false
            override fun cancel() { assertSame(caller, Thread.currentThread()) }
            override fun close() {
                assertNotSame(caller, Thread.currentThread()); closeStarted.countDown()
                check(release.await(5, TimeUnit.SECONDS)); closed = true
            }
            override val cleanupConfirmed get() = closed
        }
        var result: ProviderStartupCleanupAdmission? = null
        try {
            registry.retire(id, resource)
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS)); assertFalse(registry.isReady(id))
            val waiter = thread { result = registry.awaitReady(id, System.nanoTime() / 1_000_000 + 5000) {
                waiting.countDown(); true
            } }
            assertTrue(waiting.await(5, TimeUnit.SECONDS)); assertNull(result)
            release.countDown(); waiter.join(5000)
            assertFalse(waiter.isAlive); assertEquals(ProviderStartupCleanupAdmission.READY, result)
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
