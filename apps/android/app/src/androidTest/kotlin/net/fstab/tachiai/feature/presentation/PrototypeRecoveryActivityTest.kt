package net.fstab.tachiai.feature.presentation

import android.app.AlertDialog
import android.app.Application
import android.os.Build
import android.view.View
import android.widget.TextView
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import net.fstab.tachiai.platform.media.NativePairMember
import net.fstab.tachiai.platform.media.NativePlaybackBudget
import net.fstab.tachiai.platform.media.PrototypeFeedSession
import net.fstab.tachiai.platform.network.RouteBackend
import net.fstab.tachiai.platform.network.RoutePreparation
import net.fstab.tachiai.platform.network.RouteSession
import net.fstab.tachiai.platform.network.parseConnectionProfile
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

// Run only with instrumentation targeted at the cached prototype process on a
// disposable emulator. Ordinary main-process instrumentation skips these tests.
// All failures are local fake owners; no provider, route or grant is persisted.
@UnstableApi
class PrototypeRecoveryActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Before fun requireRealActivityProcess() {
        assumeTrue("Prototype Activity requires API 28", Build.VERSION.SDK_INT >= 28)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Requires dedicated cached prototype instrumentation process",
            Application.getProcessName() == "${context.packageName}:prototype_cached_player")
    }

    @Test fun failedSessionCloseShowsRecoveryAndIgnoreKeepsPlaybackBlocked() = withCleanRecovery {
        ActivityScenario.launch(CachedPrototypeActivity::class.java).use { scenario ->
            awaitStartupReads(scenario)
            installAvailableCatalogue(scenario)
            compose.onNodeWithText("Open viewer").assertIsEnabled()
            val session = FailedSession()
            scenario.onActivity { activity ->
                field("sessions").set(activity, listOf(session, null))
                invoke(activity, "dispose")
                assertEquals(1, session.closes.get())
                assertTrue(field("cleanupFailed").getBoolean(activity))
                assertTrue(dialog(activity).isShowing)
                dialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                invoke(activity, "updateRecoveryState")
                assertNull(field("recoveryDialog").get(activity))
            }
            assertBlockedPicker(scenario)
            compose.onNodeWithText("Recovery options").performClick()
            scenario.onActivity { activity -> assertTrue(dialog(activity).isShowing) }
        }
    }

    @Test fun actualCancellationFailureWaitsForResumeAndIgnoreSurvivesRecreation() = withCleanRecovery {
        ActivityScenario.launch(CachedPrototypeActivity::class.java).use { scenario ->
            awaitStartupReads(scenario)
            val calls = AtomicInteger()
            val owner = RoutePreparation().apply {
                onCancel { calls.incrementAndGet(); throw IOException("Injected cancellation failure") }
            }
            scenario.onActivity { activity ->
                field("routePreparation").set(activity, owner)
                field("budget").set(activity, activeBudget())
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            assertEquals(1, calls.get())
            assertTrue(owner.isCancelled)
            assertFalse(owner.cleanupConfirmed)
            scenario.onActivity { activity -> assertNull(field("recoveryDialog").get(activity)) }
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitStartupReads(scenario)
            scenario.onActivity { activity ->
                val first = dialog(activity)
                assertTrue(first.isShowing)
                repeat(3) { invoke(activity, "updateRecoveryState") }
                assertSame(first, dialog(activity))
            }
            // A lifecycle dismissal does not acknowledge the incident.
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitStartupReads(scenario)
            scenario.onActivity { activity ->
                assertTrue(dialog(activity).isShowing)
                dialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            }
            scenario.recreate()
            awaitStartupReads(scenario)
            installAvailableCatalogue(scenario)
            scenario.onActivity { activity ->
                repeat(3) { invoke(activity, "updateRecoveryState") }
                assertNull(field("recoveryDialog").get(activity))
                assertTrue(field("routeCleanupFailed").getBoolean(null))
            }
            assertEquals(1, calls.get())
            assertBlockedPicker(scenario)
        }
    }

    @Test fun lateRouteCloseFailureFromDestroyedActivityNotifiesResumedReplacement() = withCleanRecovery {
        val backend = DelayedFailedBackend()
        try {
            ActivityScenario.launch(CachedPrototypeActivity::class.java).use { scenario ->
                awaitStartupReads(scenario)
                var old: CachedPrototypeActivity? = null
                val route = RouteSession.create(parseConnectionProfile("http://proxy.example.test:3128".toByteArray())) { _, _ -> backend }
                scenario.onActivity { activity ->
                    old = activity
                    field("routes").set(activity, mapOf("injected-owner" to route))
                }
                scenario.recreate()
                awaitStartupReads(scenario)
                assertTrue("Old Activity route cleanup did not start", backend.entered.await(10, TimeUnit.SECONDS))
                scenario.onActivity { activity ->
                    assertNotSame(old, activity)
                    assertTrue(checkNotNull(old).isDestroyed)
                    assertNull(field("recoveryDialog").get(activity))
                    assertTrue(field("routeCleanupPending").getBoolean(null))
                }
                backend.release.countDown()
                compose.waitUntil(10_000) {
                    var showing = false
                    scenario.onActivity { activity ->
                        showing = (field("recoveryDialog").get(activity) as AlertDialog?)?.isShowing == true
                    }
                    showing
                }
                assertEquals(1, backend.closes.get())
                scenario.onActivity { activity ->
                    assertTrue(field("routeCleanupFailed").getBoolean(null))
                    assertFalse("Replacement must receive shared state, not old local state",
                        field("cleanupFailed").getBoolean(activity))
                    dialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                }
                installAvailableCatalogue(scenario)
                assertBlockedPicker(scenario)
            }
        } finally {
            backend.release.countDown()
        }
    }

    @Test fun failedRestartShowsSafeFeedbackAndRetryInvokesOperationOnce() = withCleanRecovery {
        ActivityScenario.launch(CachedPrototypeActivity::class.java).use { scenario ->
            awaitStartupReads(scenario)
            installAvailableCatalogue(scenario)
            scenario.onActivity { activity ->
                val owner = RoutePreparation().apply { onCancel { throw IOException("Injected cancellation failure") } }
                field("routePreparation").set(activity, owner)
                invoke(activity, "cancelRoutePreparation")
                field("restartOperation").set(activity, { throw IOException("Injected restart failure") })
                dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            compose.waitForIdle()
            scenario.onActivity { activity ->
                val feedback = dialog(activity).findViewById<TextView>(android.R.id.message).text.toString()
                assertTrue(feedback.contains("Restart could not be completed. Playback remains blocked"))
                assertFalse(feedback.contains("Injected restart failure"))
                dialog(activity).getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            }
            assertBlockedPicker(scenario)
            val restarts = AtomicInteger()
            scenario.onActivity { activity -> field("restartOperation").set(activity, { restarts.incrementAndGet(); Unit }) }
            compose.onNodeWithText("Recovery options").performClick()
            scenario.onActivity { activity ->
                dialog(activity).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                repeat(3) { invoke(activity, "updateRecoveryState") }
                assertNull(field("recoveryDialog").get(activity))
                assertFalse(field("playbackAvailable\$delegate").get(activity).let { (it as MutableState<*>).value as Boolean })
            }
            assertEquals(1, restarts.get())
        }
    }

    private class FailedSession : PrototypeFeedSession {
        val closes = AtomicInteger()
        override val providerView: View? = null
        override val member: NativePairMember? = null
        override val player: Player? = null
        override fun prepare(budget: NativePlaybackBudget) = Unit
        override fun pauseOriginal(onResult: (Boolean) -> Unit) = onResult(true)
        override fun canContinue() = false
        override fun checkAuthorization() = true
        override fun close() { closes.incrementAndGet(); throw IOException("Injected session failure") }
    }

    private class DelayedFailedBackend : RouteBackend {
        override val proxyPort = 12345
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closes = AtomicInteger()
        override fun close() {
            closes.incrementAndGet()
            entered.countDown()
            check(release.await(15, TimeUnit.SECONDS)) { "Test did not release route cleanup" }
            throw IOException("Injected route cleanup failure")
        }
    }

    private fun activeBudget() = NativePlaybackBudget(300_000, { true }, maximumDurationMs = 300_000)

    private fun assertBlockedPicker(scenario: ActivityScenario<CachedPrototypeActivity>) {
        compose.onNodeWithText("Open viewer").assertIsNotEnabled()
        compose.onNodeWithText("Recovery options").assertIsDisplayed()
        scenario.onActivity { activity ->
            assertNull(field("budget").get(activity))
            assertNull(field("routePreparation").get(activity))
            assertTrue((field("routes").get(activity) as Map<*, *>).isEmpty())
        }
    }

    private fun awaitStartupReads(scenario: ActivityScenario<CachedPrototypeActivity>) {
        val drained = CountDownLatch(1)
        scenario.onActivity { activity -> (field("worker").get(activity) as ExecutorService).execute { drained.countDown() } }
        assertTrue("Read-only setup worker did not drain", drained.await(10, TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        compose.waitForIdle()
    }

    @Suppress("UNCHECKED_CAST")
    private fun installAvailableCatalogue(scenario: ActivityScenario<CachedPrototypeActivity>) {
        scenario.onActivity { activity ->
            (field("sourceSetups\$delegate").get(activity) as MutableState<Map<PrototypeSource, SourceSetup>>).value = defaultSourceSetups()
            (field("providerInstances\$delegate").get(activity) as MutableState<List<ProviderInstance>>).value = defaultProviderInstances()
            (field("setupMessage\$delegate").get(activity) as MutableState<String?>).value = null
            (field("setupReady\$delegate").get(activity) as MutableState<Boolean>).value = true
        }
        compose.waitForIdle()
    }

    private fun withCleanRecovery(test: () -> Unit) {
        val names = listOf("routesPending", "routeCleanupPending", "routeCleanupFailed")
        val saved = mutableMapOf<String, Boolean>()
        val state = field("recoveryState").get(null)
        val incident = state.javaClass.getDeclaredField("incident").apply { isAccessible = true }
        var savedIncident: Any? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            savedIncident = incident.get(state)
            incident.set(state, null)
            names.forEach { name -> saved[name] = field(name).getBoolean(null); field(name).setBoolean(null, false) }
        }
        try { test() } finally {
            // The test body releases every delayed backend before returning.
            // Drain its real cleanup worker and posted main-thread callbacks
            // before restoring process-wide state, including after assertions.
            val drained = CountDownLatch(1)
            (field("routeCloser").get(null) as ExecutorService).execute { drained.countDown() }
            assertTrue("Route cleanup worker did not drain", drained.await(10, TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                incident.set(state, savedIncident)
                saved.forEach { (name, value) -> field(name).setBoolean(null, value) }
            }
        }
    }

    private fun dialog(activity: PrototypeActivity) = checkNotNull(field("recoveryDialog").get(activity) as AlertDialog?)
    private fun invoke(activity: PrototypeActivity, name: String) =
        PrototypeActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)
    private fun field(name: String) = PrototypeActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
}
