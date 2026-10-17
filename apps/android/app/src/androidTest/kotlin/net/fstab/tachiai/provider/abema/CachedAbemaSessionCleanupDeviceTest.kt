package net.fstab.tachiai.provider.abema

import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import net.fstab.tachiai.platform.media.NativeCleanupReceipt
import org.junit.Assert.*
import org.junit.Test

// Blank helper views and synthetic owned actions only. No provider request,
// account, route or saved record is read or changed by these fixtures.
@UnstableApi
class CachedAbemaSessionCleanupDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private class Queue : Executor {
        val tasks = LinkedBlockingQueue<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun runNext() {
            assertNotSame(Looper.getMainLooper(), Looper.myLooper())
            checkNotNull(tasks.poll(5, TimeUnit.SECONDS)) { "Cleanup task missing" }.run()
        }
    }
    private fun session(executor: Executor): CachedPrototypeAbemaSession {
        lateinit var session: CachedPrototypeAbemaSession
        instrumentation.runOnMainSync {
            session = CachedPrototypeAbemaSession(instrumentation.targetContext, false, { true }, {},
                cleanupExecutor = executor)
        }
        return session
    }
    private inline fun <reified T> field(session: CachedPrototypeAbemaSession, name: String): T =
        CachedPrototypeAbemaSession::class.java.getDeclaredField(name).apply { isAccessible = true }.get(session) as T
    private fun helper(session: CachedPrototypeAbemaSession) = field<CachedAbemaHelperCleanup>(session, "helperCleanup")

    @Test fun transportCleanupRunsOffMainAndHelperWaitsForItsReceipt() {
        val worker = Queue()
        val session = session(worker)
        val receipt = field<NativeCleanupReceipt>(session, "transportCleanup")
        val releases = AtomicInteger()
        receipt.own(10) {
            assertNotSame(Looper.getMainLooper(), Looper.myLooper())
            assertFalse(helper(session).destructionConfirmed)
            releases.incrementAndGet()
        }
        instrumentation.runOnMainSync {
            session.close()
            assertTrue(session.cleanupPending)
            assertFalse(helper(session).destructionConfirmed)
            assertEquals(0, releases.get())
        }
        worker.runNext() // Early transport cancellation.
        worker.runNext() // Final owned transport receipt.
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals(1, releases.get())
            assertTrue(receipt.confirmed)
            assertTrue(helper(session).destructionConfirmed)
            assertTrue(session.cleanupConfirmed)
            assertFalse(session.cleanupPending)
            session.close()
            assertEquals(1, releases.get())
        }
    }

    @Test fun runningPreparationFinallyKeepsHelperAndFinalTransportReceiptPending() {
        val cleanup = Queue()
        val session = session(cleanup)
        field<NativeCleanupReceipt>(session, "transportCleanup").own(10) {}
        val worker = field<CachedCleanupWorker>(session, "worker")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        worker.execute {
            entered.countDown()
            try {
                while (true) {
                    try { check(release.await(5, TimeUnit.SECONDS)); break }
                    catch (_: InterruptedException) { /* Cancellation does not settle this owned finally. */ }
                }
            } finally { finished.countDown() }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { session.close() }
            cleanup.runNext()
            instrumentation.runOnMainSync {
                assertTrue(session.cleanupPending)
                assertFalse(helper(session).destructionConfirmed)
            }
            assertTrue(cleanup.tasks.isEmpty())
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            // The completion callback queues final transport cleanup only after
            // the worker wrapper has counted its running finally as completed.
            cleanup.runNext()
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertTrue(session.cleanupConfirmed) }
        } finally { release.countDown() }
    }

    @Test fun nativeReceiptMustSettleBeforeTransportFinalizationAndHelperDestruction() {
        val cleanup = Queue()
        val native = Queue()
        val session = session(cleanup)
        field<NativeCleanupReceipt>(session, "transportCleanup").own(10) {}
        val receipt = NativeCleanupReceipt()
        receipt.own(10) { assertNotSame(Looper.getMainLooper(), Looper.myLooper()) }
        val main = Handler(Looper.getMainLooper())
        val changed = CachedPrototypeAbemaSession::class.java.getDeclaredMethod("nativeCleanupChanged")
            .apply { isAccessible = true }
        instrumentation.runOnMainSync {
            receipt.closeAsync(native, Executor { task -> check(main.post(task)) },
                onComplete = { changed.invoke(session) }, onFailure = { throw AssertionError(it) })
            CachedPrototypeAbemaSession::class.java.getDeclaredField("nativeCleanup")
                .apply { isAccessible = true }.set(session, receipt)
            session.close()
        }
        cleanup.runNext()
        instrumentation.runOnMainSync {
            assertTrue(session.cleanupPending)
            assertFalse(helper(session).destructionConfirmed)
        }
        assertTrue(cleanup.tasks.isEmpty())
        native.runNext()
        instrumentation.waitForIdleSync()
        cleanup.runNext()
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { assertTrue(session.cleanupConfirmed) }
    }

    @Test fun rejectedCleanupRetainsFailureWithoutRunningNetworkOnMain() {
        val session = session(Executor { throw RejectedExecutionException("Synthetic refusal") })
        val receipt = field<NativeCleanupReceipt>(session, "transportCleanup")
        val releases = AtomicInteger()
        receipt.own(10) { releases.incrementAndGet() }
        instrumentation.runOnMainSync {
            session.close()
            assertEquals(0, releases.get())
            assertTrue(session.cleanupFailed)
            assertFalse(session.cleanupPending)
            assertFalse(session.cleanupConfirmed)
            assertFalse(receipt.confirmed)
        }
    }
}
