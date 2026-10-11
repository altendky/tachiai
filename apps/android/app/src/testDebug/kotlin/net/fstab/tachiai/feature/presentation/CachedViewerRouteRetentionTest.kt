package net.fstab.tachiai.feature.presentation

import java.io.IOException
import java.util.concurrent.Executor
import net.fstab.tachiai.platform.diagnostics.FailureReporter
import net.fstab.tachiai.platform.network.*
import org.junit.Assert.*
import org.junit.Test

class CachedViewerRouteRetentionTest {
    @Test fun cancelledCreationWithFailedRollbackRetainsActualBackendBeforeRouteExists() {
        val preparation = RoutePreparation(); var closes = 0
        val raw = object : RouteBackend {
            override val proxyPort = 12345
            override fun close() { closes++; throw AssertionError("fixture") }
        }
        var clear = 0
        val owner = CachedViewerCleanupCoordinator({ it() }, { _, _ -> }, Executor { it.run() }, { _, _ -> clear++ }, {})
        val run = checkNotNull(owner.begin(FailureReporter.NONE))
        owner.preparation(run, preparation::cancel)
        lateinit var partial: CachedViewerRouteBackend
        assertThrows(IOException::class.java) {
            RouteSession.createRetained(parseConnectionProfile("http://proxy.example.test:3128".toByteArray()),
                preparation, ownBackend = { backend ->
                    CachedViewerRouteBackend(backend).also { partial = it; owner.route(run, it) }
                }, createBackend = { _, _ -> preparation.cancel(); raw })
        }
        owner.prepared(run, preparation.cleanupConfirmed); owner.end(run)
        assertFalse(partial.cleanupConfirmed); assertFalse(preparation.cleanupConfirmed)
        assertTrue(owner.failed); assertNull(owner.begin(FailureReporter.NONE))
        assertSame(partial, run.routes.single()); assertEquals(1, closes); assertEquals(0, clear)
        assertThrows(IOException::class.java) { partial.close() }
        assertEquals(1, closes)
    }
    @Test fun completedRouteOwnershipReplacesPartialWrapperAndClosesBackendOnce() {
        var closes = 0
        val raw = object : RouteBackend { override val proxyPort = 12345; override fun close() { closes++ } }
        val owner = CachedViewerCleanupCoordinator({ it() }, { _, _ -> }, Executor { it.run() }, { _, complete -> complete() }, {})
        val run = checkNotNull(owner.begin(FailureReporter.NONE)); val preparation = RoutePreparation()
        owner.preparation(run, preparation::cancel)
        lateinit var partial: CachedViewerRouteBackend
        val route = RouteSession.createRetained(parseConnectionProfile("http://proxy.example.test:3128".toByteArray()),
            preparation, ownBackend = { backend -> CachedViewerRouteBackend(backend).also { partial = it; owner.route(run, it) } },
            createBackend = { _, _ -> raw })
        owner.route(run, route, partial)
        assertSame(route, run.routes.single())
        owner.prepared(run, true); owner.end(run)
        assertEquals(1, closes); assertTrue(partial.cleanupConfirmed); assertFalse(owner.unavailable)
    }
}
