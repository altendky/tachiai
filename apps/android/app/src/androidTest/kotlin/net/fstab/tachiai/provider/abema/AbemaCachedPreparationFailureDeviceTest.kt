package net.fstab.tachiai.provider.abema

import android.app.Application
import android.os.Build
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.platform.media.NativePlaybackBudget
import net.fstab.tachiai.platform.media.PrototypeFeedEvent
import net.fstab.tachiai.presentation.PrototypeFailureReason
import net.fstab.tachiai.presentation.PrototypeFeedFailure
import net.fstab.tachiai.presentation.PrototypeFeedFailureLatch
import net.fstab.tachiai.presentation.ProviderInstance
import net.fstab.tachiai.presentation.ProviderSetup
import net.fstab.tachiai.presentation.SourceRouteChoice
import net.fstab.tachiai.presentation.SourceRouteMode
import net.fstab.tachiai.presentation.defaultProviderInstances
import net.fstab.tachiai.platform.network.RouteSession
import net.fstab.tachiai.platform.network.captureProviderRoutePool
import net.fstab.tachiai.provider.catalog.CatalogIntent
import net.fstab.tachiai.provider.catalog.CatalogResource
import net.fstab.tachiai.presentation.ProviderId
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

// Synthetic callbacks and route guards only: no account binding, loaded
// document, provider request or player. Skip before WebView/profile APIs outside
// the isolated cached process. Route fixtures own only a System session.
class AbemaCachedPreparationFailureDeviceTest {
    @Test fun historicalLiveRouteGuardRejectsChangedPoolAndCannotRecoverWhenTheOldPoolReturns() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        val context = instrumentation.targetContext
        assumeTrue(Application.getProcessName() == "${context.packageName}:prototype_cached_player")
        val missing = SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION,
            "12345678-1234-1234-1234-123456789abc", "Synthetic unused alternative")
        val configured = defaultProviderInstances().first().copy(setup = ProviderSetup(SourceRouteChoice.system,
            fallbackRoutes = listOf(missing)))
        fun owner(instance: ProviderInstance) = AbemaCatalogRouteOwner(captureProviderRoutePool(
            instance.id, instance.service, { listOf(instance) }, { emptyMap() }).resolve())
        val original = owner(configured)
        val changed = owner(configured.copy(setup = ProviderSetup(SourceRouteChoice.system)))
        assertNotEquals(original.proof(), changed.proof())
        val current = AtomicReference(original)
        val reads = AtomicInteger()
        val guard = AbemaEpisodePlaybackOwnership(original, { reads.incrementAndGet(); current.get() }, { true })
        val route = RouteSession.create(null)
        val session = AtomicReference<CachedPrototypeAbemaSession?>()
        try {
            instrumentation.runOnMainSync {
                configureAbemaCachedPlaybackProfile(context)
                val created = CachedPrototypeAbemaSession(context, false, { true }, {}, route = route,
                    routeOwnership = guard)
                session.set(created)
                (created.providerView as WebView).settings.blockNetworkLoads = true
            }
            // The real worker gate is invoked on this instrumentation worker;
            // no prepare(), helper load or transport-opening method is called.
            val workerGate = CachedPrototypeAbemaSession::class.java.getDeclaredMethod("workerOwned").apply { isAccessible = true }
            val created = checkNotNull(session.get())
            assertEquals(true, workerGate.invoke(created)); assertEquals(1, reads.get())
            current.set(changed)
            assertEquals(false, workerGate.invoke(created)); assertEquals(2, reads.get())
            current.set(original)
            assertEquals(false, workerGate.invoke(created)); assertEquals(2, reads.get())
            assertFalse(guard.canContinue())
            instrumentation.runOnMainSync {
                val account = CachedPrototypeAbemaSession::class.java.getDeclaredField("accountClient").apply { isAccessible = true }
                assertNull((account.get(created) as AtomicReference<*>).get())
                assertNull(created.player); assertNull(created.member); assertNull(created.helperPreparationDiagnostic)
                created.close(); assertTrue(created.cleanupConfirmed)
            }
        } finally {
            instrumentation.runOnMainSync { session.get()?.close() }
            route.close()
        }
    }

    @Test fun configuredPlaybackSharingAccountAndRouteGuardReadsItsOwnerOnlyOncePerWorkerCheck() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        val context = instrumentation.targetContext
        assumeTrue(Application.getProcessName() == "${context.packageName}:prototype_cached_player")
        val configured = defaultProviderInstances().first()
        val captured = AbemaCatalogRouteOwner(captureProviderRoutePool(configured.id, configured.service,
            { listOf(configured) }, { emptyMap() }).resolve())
        val reads = AtomicInteger()
        val guard = AbemaEpisodePlaybackOwnership(captured, { reads.incrementAndGet(); captured }, { true })
        val route = RouteSession.create(null)
        val session = AtomicReference<CachedPrototypeAbemaSession?>()
        try {
            instrumentation.runOnMainSync {
                configureAbemaCachedPlaybackProfile(context)
                val created = CachedPrototypeAbemaSession(context, false, { true }, {}, route = route,
                    channelResource = CatalogResource(ProviderId("abema"), "channel", "abema-news", CatalogIntent.CHANNEL),
                    ownership = guard, routeOwnership = guard)
                session.set(created)
                (created.providerView as WebView).settings.blockNetworkLoads = true
            }
            val workerGate = CachedPrototypeAbemaSession::class.java.getDeclaredMethod("workerOwned").apply { isAccessible = true }
            val created = checkNotNull(session.get())
            assertEquals(true, workerGate.invoke(created)); assertEquals(1, reads.get())
            assertEquals(true, workerGate.invoke(created)); assertEquals(2, reads.get())
            instrumentation.runOnMainSync {
                val account = CachedPrototypeAbemaSession::class.java.getDeclaredField("accountClient").apply { isAccessible = true }
                assertNull((account.get(created) as AtomicReference<*>).get())
                assertNull(created.player); assertNull(created.member); assertNull(created.helperPreparationDiagnostic)
                created.close(); assertTrue(created.cleanupConfirmed)
            }
        } finally {
            instrumentation.runOnMainSync { session.get()?.close() }
            route.close()
        }
    }

    @Test fun actualReadinessCallbackRetainsLostAdmissionWithoutOverwritingAnEarlierCause() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        val context = instrumentation.targetContext
        assumeTrue(Application.getProcessName() == "${context.packageName}:prototype_cached_player")
        for (earlier in listOf<PrototypeFailureReason?>(null, PrototypeFailureReason.NETWORK_FAILED,
            PrototypeFailureReason.PREPARATION_TIMEOUT)) {
            val session = AtomicReference<CachedPrototypeAbemaSession?>()
            val reason = AtomicReference<PrototypeFailureReason?>()
            var failures = 0
            try {
                instrumentation.runOnMainSync {
                    configureAbemaCachedPlaybackProfile(context)
                    val created = CachedPrototypeAbemaSession(context, false, { false }, { event ->
                        if (event == PrototypeFeedEvent.FAILED) {
                            failures++; reason.compareAndSet(null, session.get()?.failure?.reason)
                        }
                    })
                    session.set(created)
                    (created.providerView as WebView).settings.blockNetworkLoads = true
                    // Exercise the real current-run readiness callback without
                    // preparing bundles/account/route or waiting 45 seconds.
                    earlier?.let { prior ->
                        val field = CachedPrototypeAbemaSession::class.java.getDeclaredField("firstFailure").apply { isAccessible = true }
                        (field.get(created) as PrototypeFeedFailureLatch).remember(PrototypeFeedFailure(prior))
                    }
                    val callback = CachedPrototypeAbemaSession::class.java.getDeclaredMethod("waitForHelper",
                        java.lang.Long.TYPE, java.lang.Long.TYPE).apply { isAccessible = true }
                    callback.invoke(created, 0L, SystemClock.elapsedRealtime() + 5000)
                    assertEquals(earlier ?: PrototypeFailureReason.STOPPED, reason.get())
                    assertFalse(created.nativeReadinessDeadlineReached)
                    assertNull(created.helperPreparationDiagnostic)
                    created.close(); callback.invoke(created, 0L, 0L)
                    assertEquals(1, failures); assertEquals(reason.get(), created.failure?.reason)
                    assertFalse(created.nativeReadinessDeadlineReached)
                    assertNull(created.helperPreparationDiagnostic)
                    assertTrue(created.cleanupConfirmed)
                }
            } finally { instrumentation.runOnMainSync { session.get()?.close() } }
        }
    }

    @Test fun actualFailedCallbackReadsTheLatchedCauseAndCleanupCannotInventHelperEvidence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        val context = instrumentation.targetContext
        assumeTrue(Application.getProcessName() == "${context.packageName}:prototype_cached_player")
        val session = AtomicReference<CachedPrototypeAbemaSession?>()
        val reason = AtomicReference<PrototypeFailureReason?>()
        var failures = 0
        try {
            instrumentation.runOnMainSync {
                configureAbemaCachedPlaybackProfile(context)
                val created = CachedPrototypeAbemaSession(context, false, { true }, { event ->
                    if (event == PrototypeFeedEvent.FAILED) {
                        failures++
                        reason.compareAndSet(null, session.get()?.failure?.reason)
                    }
                })
                session.set(created)
                (created.providerView as WebView).settings.blockNetworkLoads = true
                created.prepare(NativePlaybackBudget(0, { true }))
                assertEquals(PrototypeFailureReason.PREPARATION_FAILED, reason.get())
                assertFalse(created.nativeReadinessDeadlineReached)
                assertNull(created.helperPreparationDiagnostic)
                assertNull(created.channelPreparationDiagnostic)
                created.close(); created.prepare(NativePlaybackBudget(0, { true }))
                assertEquals(1, failures)
                assertEquals(reason.get(), created.failure?.reason)
                assertFalse(created.nativeReadinessDeadlineReached)
                assertNull(created.helperPreparationDiagnostic)
                assertTrue(created.cleanupConfirmed)
                assertNull(created.player); assertNull(created.member)
            }
        } finally { instrumentation.runOnMainSync { session.get()?.close() } }
    }
}
