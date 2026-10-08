package net.fstab.tachiai.platform.network

import org.junit.Assert.*
import org.junit.Test

class AbemaWebViewRouteTest {
    private class Backend(override var supported: Boolean = true) : AbemaWebViewProxyBackend {
        val operations = mutableListOf<String>()
        val callbacks = mutableListOf<() -> Unit>()
        var throwOnClear = false
        var throwOnInstall = false
        override fun installLocalProxy(port: Int, onApplied: () -> Unit) {
            operations += "proxy:$port"
            if (throwOnInstall) error("fixture")
            callbacks += onApplied
        }
        override fun clear(onApplied: () -> Unit) {
            operations += "clear"
            if (throwOnClear) error("fixture")
            callbacks += onApplied
        }
    }
    private fun route(backend: Backend) = AbemaWebViewRoute(backend, assertThread = {})

    @Test fun importedRouteWaitsForProxyAppliedAndRejectsConcurrentInstall() {
        val backend = Backend()
        val route = route(backend)
        val results = mutableListOf<Boolean>()
        route.installProxy(12345) { results += it }
        assertTrue(route.busy)
        assertTrue(results.isEmpty())
        route.installProxy(23456) { results += it }
        assertEquals(listOf(false), results)
        assertEquals(listOf("proxy:12345"), backend.operations)
        backend.callbacks.single()()
        assertEquals(listOf(false, true), results)
        assertFalse(route.busy)
    }

    @Test fun unsupportedWebViewRefusesImportedRouteButAllowsUntouchedSystemRoute() {
        val backend = Backend(false)
        val route = route(backend)
        val results = mutableListOf<Boolean>()
        route.installProxy(12345) { results += it }
        route.installProxy(null) { results += it }
        assertEquals(listOf(false, true), results)
        assertTrue(backend.operations.isEmpty())
    }

    @Test fun systemRouteClearsPreviousOverrideAndWaitsForCompletion() {
        val backend = Backend()
        val route = route(backend)
        val results = mutableListOf<Boolean>()
        route.installProxy(12345) { results += it }
        backend.callbacks[0]()
        route.installProxy(null) { results += it }
        assertTrue(route.busy)
        assertEquals(listOf(true), results)
        assertEquals(listOf("proxy:12345", "clear"), backend.operations)
        backend.callbacks[1]()
        assertEquals(listOf(true, true), results)
    }

    @Test fun clearCancelsPendingInstallAndStaleCallbacksCannotMutateNextRun() {
        val backend = Backend()
        val route = route(backend)
        val installed = mutableListOf<Boolean>()
        var cleared = 0
        route.installProxy(12345) { installed += it }
        route.clear { cleared++ }
        assertEquals(listOf(false), installed)
        assertTrue(route.busy)
        backend.callbacks[1]()
        assertEquals(1, cleared)
        route.installProxy(23456) { installed += it }
        val generation = route.currentGeneration
        backend.callbacks[0]() // Obsolete first install listener.
        backend.callbacks[1]() // Obsolete clear listener.
        assertEquals(generation, route.currentGeneration)
        assertTrue(route.busy)
        assertEquals(listOf(false), installed)
        assertEquals(1, cleared)
        backend.callbacks[2]()
        assertEquals(listOf(false, true), installed)
    }

    @Test fun concurrentClearRequestsShareOneChangeAndBlockInstallUntilApplied() {
        val backend = Backend()
        val route = route(backend)
        var cleared = 0
        route.clear { cleared++ }
        route.clear { cleared++ }
        var installed: Boolean? = null
        route.installProxy(12345) { installed = it }
        assertEquals(false, installed)
        assertEquals(listOf("clear"), backend.operations)
        backend.callbacks.single()()
        assertEquals(2, cleared)
        assertFalse(route.busy)
    }

    @Test fun failedCleanupStaysBusyRatherThanAllowingDirectPlayback() {
        val backend = Backend()
        val route = route(backend)
        route.installProxy(12345) {}
        backend.callbacks.single()()
        backend.throwOnClear = true
        var cleared = false
        route.clear { cleared = true }
        assertFalse(cleared)
        assertTrue(route.busy)
        var installed: Boolean? = null
        route.installProxy(null) { installed = it }
        assertEquals(false, installed)
    }

    @Test fun failedInstallIsReportedAndStillNeedsCleanup() {
        val backend = Backend()
        backend.throwOnInstall = true
        val route = route(backend)
        var installed: Boolean? = null
        route.installProxy(12345) { installed = it }
        assertEquals(false, installed)
        backend.supported = false
        var system: Boolean? = null
        route.installProxy(null) { system = it }
        assertEquals(false, system)
        var cleared = false
        route.clear { cleared = true }
        assertEquals(listOf("proxy:12345", "clear"), backend.operations)
        assertFalse(cleared)
        backend.callbacks.single()()
        assertTrue(cleared)
    }

    @Test fun malformedProxyPortCannotCauseDirectConfiguration() {
        val backend = Backend()
        val route = route(backend)
        val results = mutableListOf<Boolean>()
        route.installProxy(0) { results += it }
        route.installProxy(65536) { results += it }
        assertEquals(listOf(false, false), results)
        assertTrue(backend.operations.isEmpty())
    }
}
