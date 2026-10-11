package net.fstab.tachiai.provider.abema

import java.io.IOException
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class CachedCleanupWorkerTest {
    @Test fun shutdownDoesNotConfirmRunningFinallyAndFailureArrivesBeforeReceiptSettles() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val settled = CountDownLatch(1)
        val failed = AtomicBoolean()
        val worker = CachedCleanupWorker(executor) { assertTrue(failed.get()); settled.countDown() }
        worker.execute {
            try { entered.countDown(); release.await() }
            catch (_: InterruptedException) { release.await() }
            finally { failed.set(true) }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            worker.shutdownNow(); assertTrue(worker.pending)
            release.countDown(); assertTrue(settled.await(5, TimeUnit.SECONDS)); assertFalse(worker.pending)
        } finally { release.countDown(); executor.shutdownNow() }
    }
    @Test fun removedQueuedTasksSettleOnceWithoutRunningTheirProviderAction() {
        val queued = mutableListOf<Runnable>()
        val executor = object : AbstractExecutorService() {
            override fun execute(command: Runnable) { queued.add(command) }
            override fun shutdown() {}
            override fun shutdownNow(): MutableList<Runnable> = queued.toMutableList().also { queued.clear() }
            override fun isShutdown() = false
            override fun isTerminated() = false
            override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
        }
        var notifications = 0
        val worker = CachedCleanupWorker(executor) { notifications++ }
        worker.execute { throw IOException("Must not run") }
        assertTrue(worker.pending); worker.shutdownNow(); worker.shutdownNow()
        assertFalse(worker.pending); assertEquals(1, notifications)
    }
}
