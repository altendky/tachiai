package net.fstab.tachiai.feature.presentation

import android.os.Handler
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import net.fstab.tachiai.platform.diagnostics.FailureReporter
import net.fstab.tachiai.platform.diagnostics.FailureStage
import net.fstab.tachiai.platform.network.RouteBackend
import net.fstab.tachiai.platform.network.RouteSession
import net.fstab.tachiai.platform.network.parseConnectionProfile
import net.fstab.tachiai.provider.abema.CachedAbemaHelperCleanup
import org.junit.Assert.*
import org.junit.Test

// In-memory owner instances only: no Activity, WebView, ProxyController,
// checkpoint, provider request, saved store or production admission mutation.
class CachedViewerCleanupDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private class Backend : RouteBackend {
        override val proxyPort = 12345
        val closes = AtomicInteger()
        override fun close() { closes.incrementAndGet() }
    }
    private fun route(backend: Backend) = RouteSession.create(
        parseConnectionProfile("http://proxy.example.test:3128".toByteArray()),
        createBackend = { _, _ -> backend },
    )

    @Test fun failedSyntheticHelperDestructionRetainsRealSyntheticRouteWithoutProxyOrBackendClear() {
        val backend = Backend(); val route = route(backend)
        val worker = Executors.newSingleThreadExecutor()
        val main = Handler(Looper.getMainLooper())
        val clears = AtomicInteger()
        val coordinator = CachedViewerCleanupCoordinator(
            post = { main.post(it) }, delay = { _, _ -> }, closeWorker = worker,
            clearProxy = { _, _ -> clears.incrementAndGet() }, changed = {},
        )
        try {
            instrumentation.runOnMainSync {
                val run = checkNotNull(coordinator.begin(FailureReporter.NONE))
                coordinator.route(run, route)
                val helper = CachedAbemaHelperCleanup(Any(), listOf(
                    FailureStage.WEBVIEW_DESTROY to { throw AssertionError("Synthetic destruction failure") },
                ), FailureReporter.NONE)
                coordinator.helper(run, helper, helper::destroy) { !helper.cleanupFailed }
                coordinator.proxyWillChange(run)
                coordinator.end(run)
                assertFalse(helper.destructionConfirmed)
                assertTrue(coordinator.failed)
                assertNull(coordinator.begin(FailureReporter.NONE))
                assertSame(helper, run.helpers.single().retained)
                assertSame(route, run.routes.single())
                assertEquals(0, clears.get()); assertEquals(0, backend.closes.get())
            }
        } finally {
            // Only this test-owned fake route is released; the production
            // coordinator intentionally has no poison-reset operation.
            route.close(); worker.shutdownNow()
        }
    }

    @Test fun latePreparedSharedRouteWaitsForBothHelpersAndExactClearAcknowledgment() {
        val backend = Backend(); val route = route(backend)
        val worker = Executors.newSingleThreadExecutor()
        val main = Handler(Looper.getMainLooper())
        val finished = CountDownLatch(1)
        val destroys = AtomicInteger(); val feeds = AtomicInteger()
        var acknowledgment: (() -> Unit)? = null
        lateinit var coordinator: CachedViewerCleanupCoordinator
        coordinator = CachedViewerCleanupCoordinator(
            post = { main.post(it) }, delay = { _, _ -> }, closeWorker = worker,
            clearProxy = { _, complete ->
                assertEquals(2, destroys.get()); assertEquals(2, feeds.get())
                assertEquals(0, backend.closes.get()); acknowledgment = complete
            }, changed = { if (!coordinator.unavailable) finished.countDown() },
        )
        lateinit var run: CachedViewerCleanupCoordinator.Run
        try {
            instrumentation.runOnMainSync {
                run = checkNotNull(coordinator.begin(FailureReporter.NONE))
                coordinator.preparation(run) { true }
                repeat(2) { slot ->
                    val helper = CachedAbemaHelperCleanup(Any(), listOf(
                        FailureStage.WEBVIEW_DESTROY to { destroys.incrementAndGet(); Unit },
                    ), FailureReporter.NONE)
                    coordinator.helper(run, helper, helper::destroy)
                    coordinator.feed(run, slot, AutoCloseable { feeds.incrementAndGet() }) { true }
                }
                coordinator.proxyWillChange(run); coordinator.end(run)
                assertNull(acknowledgment)
            }
            // Same registration method used by the actual preparation worker.
            coordinator.route(run, route); coordinator.route(run, route)
            instrumentation.runOnMainSync {
                coordinator.prepared(run, true)
                assertNotNull(acknowledgment); assertEquals(0, backend.closes.get())
                checkNotNull(acknowledgment).invoke(); checkNotNull(acknowledgment).invoke()
            }
            assertTrue("Cleanup worker must finish", finished.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                assertEquals(1, backend.closes.get()); assertFalse(coordinator.unavailable)
                val replacement = checkNotNull(coordinator.begin(FailureReporter.NONE))
                checkNotNull(acknowledgment).invoke()
                assertTrue(coordinator.current(replacement))
                coordinator.end(replacement)
            }
        } finally { route.close(); worker.shutdownNow() }
    }
}
