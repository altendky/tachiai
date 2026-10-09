package net.fstab.tachiai.feature.presentation

import android.app.Application
import android.os.Build
import android.os.Looper
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import net.fstab.tachiai.platform.media.NativePlaybackBudget
import net.fstab.tachiai.platform.network.RoutePreparation
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

// Temporarily target the test APK at the real cached prototype process to run
// these tests. Ordinary main-process instrumentation skips them. No Watch is
// allowed past its cleanup guard; no grant or route store is changed.
@UnstableApi
class PrototypeRoutePreparationActivityTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Before fun requireRealActivityProcess() {
        assumeTrue("Prototype Activity requires API 28", Build.VERSION.SDK_INT >= 28)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Requires dedicated cached prototype instrumentation process",
            Application.getProcessName() == "${context.packageName}:prototype_cached_player")
    }

    @Test fun backCancelsPreparationBeforeAnyRouteHandleExists() = withCleanRouteFlags {
        ActivityScenario.launch(CachedPrototypeActivity::class.java).use { scenario ->
            awaitStartupReads(scenario)
            val signal = Signal()
            installPreparation(scenario, signal.owner, activeBudget())
            Espresso.pressBack()
            compose.waitForIdle()
            signal.assertOnceOnMain()
            scenario.onActivity { activity ->
                assertNull(field("routePreparation").get(activity))
                assertNull(field("budget").get(activity))
            }
        }
    }

    @Test fun backgroundAndDestructionCancelTheirOwnedPreparation() = withCleanRouteFlags {
        val scenario = ActivityScenario.launch(CachedPrototypeActivity::class.java)
        try {
            awaitStartupReads(scenario)
            val background = Signal()
            installPreparation(scenario, background.owner, activeBudget())
            scenario.moveToState(Lifecycle.State.CREATED)
            background.assertOnceOnMain()
            scenario.onActivity { activity ->
                assertNull(field("routePreparation").get(activity))
                assertNull(field("budget").get(activity))
            }
            // onPause has already run. A fresh owner without a playback budget
            // isolates onDestroy's unconditional cancellation from that path.
            val destruction = Signal()
            installPreparation(scenario, destruction.owner, null)
            scenario.close()
            destruction.assertOnceOnMain()
            background.assertOnceOnMain()
        } finally { scenario.close() }
    }

    @Test fun budgetExpiryCancelsPreparationEvenWhenNoRoutesWereReturned() = withCleanRouteFlags {
        ActivityScenario.launch(CachedPrototypeActivity::class.java).use { scenario ->
            awaitStartupReads(scenario)
            val signal = Signal()
            installPreparation(scenario, signal.owner, NativePlaybackBudget(0, { true }))
            scenario.onActivity { activity ->
                assertTrue((field("routes").get(activity) as Map<*, *>).isEmpty())
                (field("ticker").get(activity) as Runnable).run()
                assertNull(field("routePreparation").get(activity))
                assertTrue((field("routes").get(activity) as Map<*, *>).isEmpty())
            }
            signal.assertOnceOnMain()
        }
    }

    @Test fun cleanupFailureBlocksActualWatchAfterRecreationWithoutPendingRoutes() = withCleanRouteFlags {
        ActivityScenario.launch(CachedPrototypeActivity::class.java).use { scenario ->
            awaitStartupReads(scenario)
            scenario.onActivity {
                field("routeCleanupFailed").setBoolean(null, true)
                assertFalse(field("routesPending").getBoolean(null))
                assertFalse(field("routeCleanupPending").getBoolean(null))
            }
            scenario.recreate()
            awaitStartupReads(scenario)
            Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Ignore"))
                .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .perform(androidx.test.espresso.action.ViewActions.click())
            installAvailableCatalogue(scenario)
            val stream = PrototypeSource.TWITCH_LIVE
            compose.onNodeWithContentDescription("Assign ${stream.title} to feed A")
                .performScrollTo().assertIsOff().performClick()
            compose.onNodeWithContentDescription("Assign ${stream.title} to feed B")
                .performScrollTo().assertIsOn()
            compose.onNodeWithText("Open viewer").assertIsNotEnabled()
            compose.onNodeWithText("Recovery options").assertIsEnabled()
            scenario.onActivity { activity ->
                // Even a programmatic admission attempt must retain the guard.
                PrototypeActivity::class.java.getDeclaredMethod("watch", PrototypeSelection::class.java)
                    .apply { isAccessible = true }.invoke(activity, PrototypeSelection(stream, stream))
                assertNull(field("budget").get(activity))
                assertNull(field("routePreparation").get(activity))
                assertTrue((field("routes").get(activity) as Map<*, *>).isEmpty())
                assertFalse(field("routesPending").getBoolean(null))
                assertTrue(field("routeCleanupFailed").getBoolean(null))
            }
            awaitStartupReads(scenario)
        }
    }

    private class Signal {
        val owner = RoutePreparation()
        private val calls = AtomicInteger()
        private val mainThread = AtomicBoolean()

        init {
            owner.onCancel {
                mainThread.set(Looper.myLooper() == Looper.getMainLooper())
                calls.incrementAndGet()
            }
        }

        fun assertOnceOnMain() {
            assertEquals("Activity must signal its owner once", 1, calls.get())
            assertTrue("Cancellation must be signalled on the Activity thread", mainThread.get())
        }
    }

    private fun activeBudget() = NativePlaybackBudget(300_000, { true }, maximumDurationMs = 300_000)

    private fun installPreparation(scenario: ActivityScenario<CachedPrototypeActivity>, owner: RoutePreparation,
        budget: NativePlaybackBudget?) {
        scenario.onActivity { activity ->
            field("routePreparation").set(activity, owner)
            field("budget").set(activity, budget)
        }
    }

    private fun awaitStartupReads(scenario: ActivityScenario<CachedPrototypeActivity>) {
        val drained = CountDownLatch(1)
        scenario.onActivity { activity ->
            val worker = field("worker").get(activity) as ExecutorService
            worker.execute { drained.countDown() }
        }
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

    private fun withCleanRouteFlags(test: () -> Unit) {
        val names = listOf("routesPending", "routeCleanupPending", "routeCleanupFailed")
        val saved = mutableMapOf<String, Boolean>()
        val recovery = field("recoveryState").get(null) as PrototypeRecoveryState
        val incident = PrototypeRecoveryState::class.java.getDeclaredField("incident").apply { isAccessible = true }
        var savedIncident: Any? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            savedIncident = incident.get(recovery)
            incident.set(recovery, null)
            names.forEach { name ->
                saved[name] = field(name).getBoolean(null)
                field(name).setBoolean(null, false)
            }
        }
        try {
            test()
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                saved.forEach { (name, value) -> field(name).setBoolean(null, value) }
                incident.set(recovery, savedIncident)
            }
        }
    }

    private fun field(name: String) = PrototypeActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
}
