package net.fstab.tachiai.provider.abema

import android.app.Application
import android.os.Build
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.platform.media.NativePlaybackBudget
import net.fstab.tachiai.platform.media.PrototypeFeedEvent
import net.fstab.tachiai.presentation.PrototypeFailureReason
import net.fstab.tachiai.presentation.PrototypeFeedFailure
import net.fstab.tachiai.presentation.PrototypeFeedFailureLatch
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

// Actual session callback, with no route, account binding, loaded document,
// provider request or player. Skip before WebView/profile APIs outside the
// isolated cached process; an inactive budget refuses before worker setup.
class AbemaCachedPreparationFailureDeviceTest {
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
