package net.fstab.tachiai.provider.abema

import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// Receipt includes running finally blocks and cancelled queued tasks. A closed
// executor alone does not prove worker-owned transports have finished cleanup.
internal class CachedCleanupWorker(private val executor: ExecutorService, private val changed: () -> Unit) {
    private val count = AtomicInteger()
    val pending get() = count.get() != 0
    private inner class Task(private val action: () -> Unit) : Runnable {
        private val completed = AtomicBoolean()
        override fun run() { try { action() } finally { complete() } }
        fun complete() { if (completed.compareAndSet(false, true)) { count.decrementAndGet(); changed() } }
    }
    fun execute(action: () -> Unit) {
        count.incrementAndGet()
        val task = Task(action)
        try { executor.execute(task) } catch (error: Throwable) { task.complete(); throw error }
    }
    fun shutdownNow() { executor.shutdownNow().forEach { (it as Task).complete() } }
}
