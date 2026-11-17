package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.network.ProviderRouteStartup
import net.fstab.tachiai.platform.network.ProviderStartupAttempt
import net.fstab.tachiai.platform.network.ProviderStartupAttemptContext
import net.fstab.tachiai.platform.network.ProviderStartupFailure
import net.fstab.tachiai.platform.network.ProviderStartupPhase
import net.fstab.tachiai.platform.network.ProviderStartupProgress
import net.fstab.tachiai.platform.network.ProviderStartupResult
import net.fstab.tachiai.platform.network.captureProviderRoutePool
import org.junit.Assert.*
import org.junit.Test

class ViewerStartupStatusTest {
    private class Fixture : AutoCloseable {
        val queued = mutableListOf<() -> Unit>()
        val navigation = mutableListOf<ProviderStartupProgress>()
        var current = true
        var resumed = true
        var checking = true
        var viewerOpen = false
        var check = 1
        var now = 0L
        val status = statusFor(check)
        private val instance = defaultProviderInstances().single { it.service == PrototypeService.ABEMA }
            .copy(setup = ProviderSetup(SourceRouteChoice.system))
        private val pool = captureProviderRoutePool(instance.id, instance.service, { listOf(instance) }) { emptyMap() }
        val scope = ProviderRouteStartup(pool, 1_000, { now }, { current && resumed }, status::publish,
            { _, _ -> AutoCloseable {} })
        lateinit var provider: ProviderStartupAttemptContext
        fun statusFor(expected: Int) = ViewerStartupStatus(
            post = { queued += it }, current = { current && resumed && check == expected },
            checking = { checking }, viewerOpen = { viewerOpen }, show = { navigation += it })
        fun admit() {
            val result = scope.runReadOnly { _, context ->
                provider = context
                ProviderStartupAttempt.Admitted(Unit)
            }
            assertTrue(result is ProviderStartupResult.Ready)
            assertEquals(SourceRouteChoice.system, scope.selectedRoute?.choice)
            assertTrue(scope.canContinue()); assertTrue(provider.canContinue())
        }
        fun deliver() {
            while (queued.isNotEmpty()) queued.removeAt(0).invoke()
        }
        override fun close() { scope.close() }
    }
    private fun progress(phase: ProviderStartupPhase) = ProviderStartupProgress(phase, 1, 1,
        failure = ProviderStartupFailure.CANCELLED.takeIf { phase == ProviderStartupPhase.CANCELLED })

    @Test fun `current startup displays real trying and selected progress through deferred delivery`() {
        Fixture().use { fixture ->
            fixture.admit()
            assertTrue(fixture.navigation.isEmpty())
            fixture.deliver()
            assertEquals(listOf(ProviderStartupPhase.TRYING, ProviderStartupPhase.SELECTED),
                fixture.navigation.map { it.phase })
            assertTrue(fixture.provider.canContinue())
        }
    }

    @Test fun `ending startup UI and opening viewer suppress queued cancellation without retiring provider scope`() {
        Fixture().use { fixture ->
            fixture.admit()
            fixture.status.publish(progress(ProviderStartupPhase.CANCELLED))
            fixture.checking = false
            fixture.viewerOpen = true
            fixture.now = 2_000 // Admitted route lifetime outlives startup's deadline.
            fixture.deliver()
            assertTrue(fixture.navigation.isEmpty())
            assertTrue(fixture.scope.canContinue()); assertTrue(fixture.provider.canContinue())
            assertTrue(fixture.scope.admitted)
        }
    }

    @Test fun `an open viewer rejects late selected and cancelled even if checking flag remains true`() {
        Fixture().use { fixture ->
            fixture.admit()
            fixture.status.publish(progress(ProviderStartupPhase.CANCELLED))
            fixture.viewerOpen = true
            fixture.deliver()
            assertTrue(fixture.navigation.isEmpty())
            assertTrue(fixture.scope.canContinue()); assertTrue(fixture.provider.canContinue())
        }
    }

    @Test fun `real scope cancellation ends provider authority but cannot navigate away from active viewer`() {
        Fixture().use { fixture ->
            fixture.admit()
            fixture.viewerOpen = true
            fixture.checking = false
            fixture.scope.cancel()
            assertEquals(ProviderStartupPhase.CANCELLED, fixture.scope.progress.phase)
            fixture.deliver()
            assertTrue(fixture.navigation.isEmpty())
            assertFalse(fixture.scope.canContinue()); assertFalse(fixture.provider.canContinue())
        }
    }

    @Test fun `completed startup suppresses cleanup status even before a viewer budget opens`() {
        Fixture().use { fixture ->
            fixture.admit()
            fixture.checking = false
            fixture.viewerOpen = false
            for (phase in listOf(ProviderStartupPhase.CLEANING, ProviderStartupPhase.CANCELLED,
                ProviderStartupPhase.CLEANUP_UNCONFIRMED)) fixture.status.publish(progress(phase))
            fixture.deliver()
            assertTrue(fixture.navigation.isEmpty())
            assertTrue(fixture.scope.canContinue())
        }
    }

    @Test fun `an older check cannot publish into a newer check even when its callback was already queued`() {
        Fixture().use { fixture ->
            fixture.status.publish(progress(ProviderStartupPhase.CANCELLED))
            fixture.check = 2
            val latest = fixture.statusFor(2)
            latest.publish(progress(ProviderStartupPhase.TRYING))
            fixture.status.publish(progress(ProviderStartupPhase.SELECTED))
            fixture.deliver()
            assertEquals(listOf(ProviderStartupPhase.TRYING), fixture.navigation.map { it.phase })
            fixture.checking = false
            latest.publish(progress(ProviderStartupPhase.CANCELLED)); fixture.deliver()
            assertEquals(1, fixture.navigation.size)
        }
    }

    @Test fun `current and resumed gates are evaluated at delivery instead of at enqueue time`() {
        for (pause in listOf(false, true)) Fixture().use { fixture ->
            fixture.status.publish(progress(ProviderStartupPhase.TRYING))
            if (pause) fixture.resumed = false else fixture.current = false
            fixture.deliver()
            assertTrue(fixture.navigation.isEmpty())
            fixture.current = true; fixture.resumed = true
            fixture.status.publish(progress(ProviderStartupPhase.TRYING)); fixture.deliver()
            assertEquals(listOf(ProviderStartupPhase.TRYING), fixture.navigation.map { it.phase })
        }
    }
}
