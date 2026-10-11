package net.fstab.tachiai.provider.abema

import java.net.URL
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Test

class AbemaGuestBootstrapLifecycleTest {
    private class Proxy : AbemaWebViewProxyBackend {
        override val supported = true
        var installed: (() -> Unit)? = null
        var cleared: (() -> Unit)? = null
        var failClear = false
        override fun installLocalProxy(port: Int, onApplied: () -> Unit) { installed = onApplied }
        override fun clear(onApplied: () -> Unit) { if (failClear) error("Fixture cleanup"); cleared = onApplied }
    }

    @Test fun runtimeRequiresAppliedRouteAndLateInstallCannotReviveCancelledAttempt() {
        var now = 1000L
        val gate = AbemaGuestBootstrapLifecycle { now }
        val proxy = Proxy(); val route = AbemaWebViewRoute(proxy) {}
        assertNull(gate.begin(false))
        val run = checkNotNull(gate.begin(true))
        var runtimeLoads = 0
        route.installProxy(12345) { applied -> if (applied && gate.routeApplied(run, true)) runtimeLoads++ }
        assertFalse(gate.canRunRuntime(run, true)); assertEquals(0, runtimeLoads)
        assertTrue(gate.beginCleanup(run))
        gate.destroyBeforeRouteClear(run, { true }) {
            route.clear { gate.finishCleanup(run, true, {}) {} }
        }
        proxy.installed!!.invoke()
        assertEquals(0, runtimeLoads); assertNull(gate.begin(true))
        proxy.cleared!!.invoke()
        val next = checkNotNull(gate.begin(true))
        assertFalse(gate.routeApplied(run, true)); assertTrue(gate.routeApplied(next, true))
        assertTrue(gate.canRunRuntime(next, true)); assertFalse(gate.canRunRuntime(next, false))
        now += 45_000
        assertFalse(gate.canRunRuntime(next, true)); assertFalse(gate.routeApplied(next, true))
    }

    @Test fun failedOrUnconfirmedClearBlocksRecreationAndLateSuccessCannotUnblock() {
        val gate = AbemaGuestBootstrapLifecycle { 1000L }
        val proxy = Proxy(); val route = AbemaWebViewRoute(proxy) {}
        val run = checkNotNull(gate.begin(true))
        route.installProxy(12345) { gate.routeApplied(run, true) }; proxy.installed!!.invoke()
        assertTrue(gate.beginCleanup(run)); proxy.failClear = true
        var completed = false
        gate.destroyBeforeRouteClear(run, { true }) {
            route.clear { completed = true; gate.finishCleanup(run, true, {}) {} }
        }
        assertTrue(route.busy); assertFalse(completed); assertNull(gate.begin(true))
        assertFalse(gate.completeCleanup(run, false)) // Lifecycle timeout.
        assertFalse(gate.completeCleanup(run, true))
        assertNull(gate.begin(true)); assertFalse(gate.canRunRuntime(run, true))
        assertTrue(gate.unavailable)
    }

    @Test fun destructionFailureNeverClearsOverrideOrClosesBackendAndSuccessfulTeardownIsOrdered() {
        for (destroyed in listOf(false, true)) {
            val gate = AbemaGuestBootstrapLifecycle { 1000L }
            val proxy = Proxy(); val override = AbemaWebViewRoute(proxy) {}
            val events = mutableListOf<String>()
            val selected = parseConnectionProfile("http://proxy.example.test:3128".toByteArray())
            val backend = RouteSession.createAbemaGuest(selected) { _, _ ->
                object : RouteBackend { override val proxyPort = 12345
                    override fun close() { events.add("BACKEND_CLOSED") }
                }
            }
            val run = checkNotNull(gate.begin(true))
            override.installProxy(backend.proxyPort) { gate.routeApplied(run, true) }
            proxy.installed!!.invoke()
            assertTrue(gate.beginCleanup(run))
            val dispatched = gate.destroyBeforeRouteClear(run, {
                events.add("DESTROY_ATTEMPT"); destroyed
            }) {
                events.add("CLEAR_DISPATCH")
                override.clear {
                    events.add("CLEAR_CONFIRMED"); backend.close()
                    gate.finishCleanup(run, true, { events.add("OLD_CALLBACKS_REMOVED") }) {
                        assertTrue(it); events.add("OUTCOME")
                    }
                }
            }
            assertEquals(destroyed, dispatched)
            if (!destroyed) {
                assertEquals(listOf("DESTROY_ATTEMPT"), events)
                assertNull(proxy.cleared); assertNull(gate.begin(true))
                assertFalse(gate.completeCleanup(run, true)); assertTrue(gate.unavailable)
                // Test-only final resource release. Production retains this
                // backend/context until process death, never a clear fallback.
                backend.close()
            } else {
                assertEquals(listOf("DESTROY_ATTEMPT", "CLEAR_DISPATCH"), events)
                assertNull(gate.begin(true)); proxy.cleared!!.invoke()
                assertEquals(listOf("DESTROY_ATTEMPT", "CLEAR_DISPATCH", "CLEAR_CONFIRMED", "BACKEND_CLOSED",
                    "OLD_CALLBACKS_REMOVED", "OUTCOME"), events)
                assertNotNull(gate.begin(true))
            }
        }
    }

    @Test fun staleRuntimeAndCleanupCallbacksCannotAffectReplacementRunOrWrongView() {
        val gate = AbemaGuestBootstrapLifecycle { 1000L }
        val first = checkNotNull(gate.begin(true)); assertTrue(gate.routeApplied(first, true))
        assertTrue(gate.beginCleanup(first))
        gate.destroyBeforeRouteClear(first, { true }) {}
        var callbackRemovals = 0; var outcomes = 0
        assertTrue(gate.finishCleanup(first, true, { callbackRemovals++ }) { outcomes++ })
        val second = checkNotNull(gate.begin(true)); assertTrue(gate.routeApplied(second, true))
        var stops = 0
        assertFalse(gate.runtimeCallback(first, true, true) { stops++ })
        assertFalse(gate.runtimeCallback(second, true, false) { stops++ })
        assertFalse(gate.runtimeCallback(second, false, true) { stops++ })
        assertFalse(gate.finishCleanup(first, true, { callbackRemovals++ }) { outcomes++ })
        assertEquals(0, stops); assertEquals(1, callbackRemovals); assertEquals(1, outcomes)
        assertTrue(gate.canRunRuntime(second, true))
        assertTrue(gate.runtimeCallback(second, true, true) { stops++ })
        assertEquals(1, stops)
    }

    @Test fun throwingDestructionPoisonsAdmissionWithoutClearingOrClosingBackend() {
        for (failure in listOf<Throwable>(IllegalStateException("Fixture destroy failure"), AssertionError("Fixture destroy error"))) {
            val gate = AbemaGuestBootstrapLifecycle { 1000L }
            val proxy = Proxy(); val override = AbemaWebViewRoute(proxy) {}
            var closes = 0; var clears = 0
            val selected = parseConnectionProfile("http://proxy.example.test:3128".toByteArray())
            val backend = RouteSession.createAbemaGuest(selected) { _, _ ->
                object : RouteBackend { override val proxyPort = 12345; override fun close() { closes++ } }
            }
            try {
                val run = checkNotNull(gate.begin(true))
                override.installProxy(backend.proxyPort) { gate.routeApplied(run, true) }
                proxy.installed!!.invoke()
                assertTrue(gate.beginCleanup(run))
                assertFalse(gate.destroyBeforeRouteClear(run, { throw failure }) {
                    clears++
                    override.clear { backend.close() }
                })
                assertEquals(0, clears); assertEquals(0, closes); assertNull(proxy.cleared)
                assertTrue(gate.unavailable); assertNull(gate.begin(true))
                assertFalse(gate.completeCleanup(run, true))
                assertFalse(gate.canRunRuntime(run, true))
            } finally { backend.close() } // Fixture resource release after the no-close assertions.
        }
    }

    @Test fun ownerChecksDistinguishProviderInstanceRouteAndProfileWithoutFallback() {
        val id = "12345678-1234-1234-1234-123456789abc"
        val connection = "12345678-1234-1234-1234-123456789abd"
        val instance = ProviderInstance(id, PrototypeService.ABEMA, "ABEMA",
            setup = ProviderSetup(SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, connection, "Selected")))
        var current = instance
        var profile = parseConnectionProfile("http://fixture:private@proxy.example.test:3128".toByteArray())
        fun read() = readAbemaCatalogRouteOwner(id, { listOf(current) }, { profile })
        val captured = read()
        assertTrue(captured.sameOwnership(read()))
        current = instance.copy(customName = "Renamed")
        assertTrue(captured.sameOwnership(read()))
        profile = parseConnectionProfile("http://fixture:private@changed.example.test:3128".toByteArray())
        assertFalse(captured.sameOwnership(read()))
        current = instance.copy(setup = ProviderSetup(SourceRouteChoice.system))
        assertFalse(captured.sameOwnership(read()))
        current = instance.copy(service = PrototypeService.TWITCH)
        assertThrows(IllegalStateException::class.java) { read() }
        assertThrows(IllegalStateException::class.java) {
            readAbemaCatalogRouteOwner(id, { listOf(instance) }, { error("Missing saved profile") })
        }
        assertFalse(captured.toString().contains("private"))
    }

    @Test fun guestRoutePurposeIsClosedAndFailedImportedPreparationNeverBecomesSystem() {
        var closes = 0
        val selected = parseConnectionProfile("http://proxy.example.test:3128".toByteArray())
        RouteSession.createAbemaGuest(selected) { actual, security ->
            assertSame(selected, actual); assertEquals("abema.tv", security.allowedHosts)
            object : RouteBackend { override val proxyPort = 12345; override fun close() { closes++ } }
        }.use { route ->
            assertFalse(route.isSystem)
            route.open(URL("https://abema.tv/api/auth/login/guest")).disconnect() // Object creation only.
            for (host in listOf(
                "api.abema.io",
                "api.p-c3-e.abema-tv.com",
                "license.p-c3-e.abema-tv.com",
                "other.invalid",
            ))
                assertThrows(IllegalArgumentException::class.java) { route.open(URL("https://$host/")) }
        }
        assertEquals(1, closes)
        assertThrows(java.io.IOException::class.java) {
            RouteSession.createAbemaGuest(selected) { _, _ -> error("Failed imported route") }
        }
    }

    @Test fun profileRoutePurposeIsExplicitClosedAndCannotSubstituteLegacyAPIOrSystem() {
        var closes = 0
        val selected = parseConnectionProfile("http://proxy.example.test:3128".toByteArray())
        RouteSession.createAbemaGuestProfile(selected) { actual, security ->
            assertSame(selected, actual); assertEquals("abema.tv,api.p-c3-e.abema-tv.com", security.allowedHosts)
            object : RouteBackend { override val proxyPort = 12345; override fun close() { closes++ } }
        }.use { route ->
            assertFalse(route.isSystem)
            route.open(URL("https://abema.tv/api/auth/login/guest")).disconnect()
            route.open(URL("https://api.p-c3-e.abema-tv.com/v1/users/FIXTURE_USER")).disconnect()
            for (host in listOf("api.abema.io", "auth.abema.tv", "license.p-c3-e.abema-tv.com", "other.invalid"))
                assertThrows(IllegalArgumentException::class.java) { route.open(URL("https://$host/")) }
        }
        assertEquals(1, closes)
        RouteSession.createAbemaGuestProfile(null).use { system ->
            assertTrue(system.isSystem)
            assertThrows(IllegalArgumentException::class.java) { system.open(URL("https://api.abema.io/v1/users/FIXTURE_USER")) }
        }
        assertThrows(java.io.IOException::class.java) {
            RouteSession.createAbemaGuestProfile(selected) { _, _ -> error("Failed imported route") }
        }
    }

    @Test fun boundProfileOutcomeWaitsForConfirmedCleanupAndCannotSurviveBackgroundOrStaleRun() {
        val gate = AbemaGuestBootstrapLifecycle { 1000L }
        val proxy = Proxy(); val route = AbemaWebViewRoute(proxy) {}
        val run = checkNotNull(gate.begin(true))
        route.installProxy(12345) { gate.routeApplied(run, true) }; proxy.installed!!.invoke()
        var published: String? = null
        assertFalse(gate.runtimeCallback(run, false, true) { published = "GUEST_PROFILE_BOUND" })
        assertTrue(gate.beginCleanup(run))
        gate.destroyBeforeRouteClear(run, { true }) {
            route.clear {
                gate.finishCleanup(run, true, {}) { confirmed -> if (confirmed) published = "GUEST_PROFILE_BOUND" }
            }
        }
        assertNull(published); assertNull(gate.begin(true))
        proxy.cleared!!.invoke(); assertEquals("GUEST_PROFILE_BOUND", published)
        val next = checkNotNull(gate.begin(true)); assertTrue(gate.routeApplied(next, true))
        assertFalse(gate.runtimeCallback(run, true, true) { published = "STALE" })
        assertEquals("GUEST_PROFILE_BOUND", published)
    }
}
