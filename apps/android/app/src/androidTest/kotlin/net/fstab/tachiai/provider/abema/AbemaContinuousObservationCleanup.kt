package net.fstab.tachiai.provider.abema

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import net.fstab.tachiai.feature.presentation.CachedViewerCleanupCoordinator
import net.fstab.tachiai.platform.diagnostics.FailureReporter
import org.junit.Assert.*
import org.junit.Test

// The registered feed owns observation removal before native disposal. An
// uncertain first attempt remains part of the coordinator's consumer proof.
internal class AbemaContinuousObservationCleanup(
    private val removeObservation: () -> Boolean,
    private val closeNative: () -> Unit,
    private val nativePending: () -> Boolean,
    private val nativeFailed: () -> Boolean,
    private val nativeConfirmed: () -> Boolean,
) : AutoCloseable {
    private val attempted = AtomicBoolean()
    private val finished = AtomicBoolean()
    private val removalConfirmed = AtomicBoolean()
    private val refused = AtomicBoolean()
    val closed get() = attempted.get()
    val observationConfirmed get() = finished.get() && removalConfirmed.get()
    val pending get() = attempted.get() && !finished.get() || runCatching(nativePending).getOrDefault(true)
    val failed get() = refused.get() || runCatching(nativeFailed).getOrDefault(true)
    val confirmed get() = observationConfirmed && !failed && !pending && runCatching(nativeConfirmed).getOrDefault(false)
    override fun close() {
        if (!attempted.compareAndSet(false, true)) return
        try {
            val removed = runCatching(removeObservation).getOrDefault(false)
            removalConfirmed.set(removed)
            if (!removed) refused.set(true)
            try { closeNative() } catch (_: Throwable) { refused.set(true) }
        } finally { finished.set(true) }
        if (refused.get()) error("Continuous observation cleanup unconfirmed")
    }
    override fun toString() = "AbemaContinuousObservationCleanup(redacted)"
}

class AbemaContinuousObservationCleanupDeviceTest {
    private class Fixture(remove: () -> Boolean, nativeFailure: Boolean = false) {
        val events = mutableListOf<String>()
        var removals = 0
        var nativeCloses = 0
        var nativePending = false
        var nativeConfirmed = false
        val timers = mutableListOf<Pair<Long, () -> Unit>>()
        val coordinator = CachedViewerCleanupCoordinator(post = { it() }, delay = { ms, action -> timers.add(ms to action) },
            closeWorker = Executor { it.run() }, clearProxy = { _, complete -> events.add("clear"); complete() }, changed = {})
        val run = checkNotNull(coordinator.begin(FailureReporter.NONE))
        val route = AutoCloseable { events.add("route") }
        val cleanup = AbemaContinuousObservationCleanup({ removals++; events.add("remove"); remove() }, {
            nativeCloses++; events.add("native")
            if (nativeFailure) error("Synthetic private close failure")
            nativeConfirmed = true
        }, { nativePending }, { false }, { nativeConfirmed })
        init {
            coordinator.route(run, route)
            coordinator.proxyWillChange(run)
            coordinator.feed(run, 0, cleanup, pending = { cleanup.pending }, knownFailed = { cleanup.failed },
                confirmed = { cleanup.confirmed })
        }
    }

    @Test fun uncertainRemovalRetainsActualCoordinatorRouteAndCannotBeRetried() {
        for (throws in listOf(false, true)) {
            val f = Fixture({ if (throws) error("Synthetic private removal failure") else false })
            f.coordinator.end(f.run)
            assertTrue(f.cleanup.closed); assertTrue(f.cleanup.failed); assertFalse(f.cleanup.confirmed)
            assertTrue(f.coordinator.failed); assertTrue(f.coordinator.unavailable)
            assertSame(f.route, f.run.routes.single())
            assertSame(f.cleanup, f.run.feeds.getValue(0).resource)
            assertEquals(listOf("remove", "native"), f.events)
            f.cleanup.close(); f.coordinator.end(f.run)
            assertEquals(1, f.removals); assertEquals(1, f.nativeCloses)
        }
    }
    @Test fun healthyRemovalPrecedesNativeCloseAndOnlyThenProxyAndRouteRelease() {
        val f = Fixture({ true })
        f.coordinator.end(f.run)
        assertEquals(listOf("remove", "native", "clear", "route"), f.events)
        assertTrue(f.cleanup.observationConfirmed); assertTrue(f.cleanup.confirmed)
        assertEquals(CachedViewerCleanupCoordinator.Run.Phase.FINISHED, f.run.phase)
        f.cleanup.close(); assertEquals(1, f.removals); assertEquals(1, f.nativeCloses)
    }
    @Test fun nativeFailureAndPendingTimeoutDoNotLaunderObservationOrConsumerReceipts() {
        val failed = Fixture({ true }, nativeFailure = true)
        failed.coordinator.end(failed.run)
        assertTrue(failed.cleanup.observationConfirmed)
        assertTrue(failed.coordinator.failed); assertFalse(failed.cleanup.confirmed)
        assertEquals(listOf("remove", "native"), failed.events)
        val pending = Fixture({ true }).also { it.nativePending = true }
        pending.coordinator.end(pending.run)
        assertTrue(pending.cleanup.pending); assertEquals(listOf("remove", "native"), pending.events)
        pending.timers.single { it.first == 20_000L }.second()
        pending.nativePending = false
        pending.coordinator.feedCleanupChanged(pending.run)
        assertTrue(pending.coordinator.failed); assertSame(pending.route, pending.run.routes.single())
        assertEquals(listOf("remove", "native"), pending.events)
        assertEquals("AbemaContinuousObservationCleanup(redacted)", pending.cleanup.toString())
    }
}
