package net.fstab.tachiai.feature.presentation

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

// Dedicated-process test: temporarily target the test APK's instrumentation at
// net.fstab.tachiai:prototype_cached_player. Ordinary main-process runs skip it.
// No app component, grant, saved route or provider request is added by this test.
@UnstableApi
class PrototypeBindingActivityRecreationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val extra = ProviderInstance("12345678-1234-1234-1234-123456789abc", PrototypeService.TWITCH, "Twitch 2")

    @Before fun requireRealActivityProcess() {
        assumeTrue("Prototype Activity requires API 28", Build.VERSION.SDK_INT >= 28)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Requires dedicated cached prototype instrumentation process",
            Application.getProcessName() == "${context.packageName}:prototype_cached_player")
    }

    @Test fun nativeViewReplacementAndActivityRecreationRetainAccountWithoutDefaultFallback() {
        ActivityScenario.launch(CachedPrototypeActivity::class.java).use { scenario ->
            awaitStartupReads(scenario)
            installInstances(scenario, defaultProviderInstances() + extra)
            val stream = PrototypeSource.TWITCH_LIVE
            compose.onNodeWithContentDescription("Assign ${stream.title} using Twitch 2 to feed A").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Assign ${stream.title} to feed B").performScrollTo().assertIsOn()
            val expected = PrototypeFeedAssignments(PrototypeFeedChoice(stream, extra.id),
                PrototypeFeedChoice(stream, defaultProviderInstanceId(PrototypeService.TWITCH)))
            scenario.onActivity { assertEquals(expected, assignments(it)) }
            replacePickerWithNativeView(scenario)
            scenario.recreate()
            awaitStartupReads(scenario)
            // The test instance was never persisted. Its restored UUID is now
            // unresolved, which must disable playback rather than select LOCAL.
            installInstances(scenario, defaultProviderInstances())
            scenario.onActivity { assertEquals(expected, assignments(it)) }
            compose.onNodeWithContentDescription("Assign ${stream.title} to feed A").performScrollTo().assertIsOff()
            compose.onNodeWithText("Open viewer").assertIsNotEnabled()
            installInstances(scenario, defaultProviderInstances() + extra)
            compose.onNodeWithContentDescription("Assign ${stream.title} using Twitch 2 to feed A").performScrollTo().assertIsOn()
            compose.onNodeWithContentDescription("Assign ${stream.title} to feed B").performScrollTo().assertIsOn()
            scenario.onActivity { assertEquals(expected, assignments(it)) }
        }
    }

    @Test fun malformedSavedSlotStaysUnassignedAndPreservesTheOtherFeedAcrossRecreation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val corruptOnce = AtomicBoolean(true)
        val callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
                if (activity is CachedPrototypeActivity && corruptOnce.compareAndSet(true, false)) {
                    outState.putString("prototype.feed.a", "TWITCH_LIVE|../grant")
                }
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        app.registerActivityLifecycleCallbacks(callback)
        try {
            ActivityScenario.launch(CachedPrototypeActivity::class.java).use { scenario ->
                awaitStartupReads(scenario)
                installInstances(scenario, defaultProviderInstances())
                val replay = PrototypeSource.TWITCH_REPLAY
                compose.onNodeWithContentDescription("Assign ${replay.title} to feed B").performScrollTo().performClick()
                val expected = PrototypeFeedAssignments(null,
                    PrototypeFeedChoice(replay, defaultProviderInstanceId(PrototypeService.TWITCH)))
                replacePickerWithNativeView(scenario)
                scenario.recreate()
                awaitStartupReads(scenario)
                scenario.onActivity { assertEquals(expected, assignments(it)) }
                compose.onNodeWithContentDescription("Assign ${PrototypeSource.ABEMA_LIVE.title} to feed A").performScrollTo().assertIsOff()
                compose.onNodeWithContentDescription("Assign ${replay.title} to feed B").performScrollTo().assertIsOn()
                compose.onNodeWithText("Open viewer").assertIsNotEnabled()
                // A second save writes null explicitly; it must remain null
                // without the one-time malformed-state injection.
                replacePickerWithNativeView(scenario)
                scenario.recreate()
                awaitStartupReads(scenario)
                scenario.onActivity { assertEquals(expected, assignments(it)) }
                compose.onNodeWithText("Open viewer").assertIsNotEnabled()
            }
        } finally { app.unregisterActivityLifecycleCallbacks(callback) }
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
    private fun installInstances(scenario: ActivityScenario<CachedPrototypeActivity>, instances: List<ProviderInstance>) {
        scenario.onActivity { activity ->
            (field("providerInstances\$delegate").get(activity) as MutableState<List<ProviderInstance>>).value = instances
        }
        compose.waitForIdle()
    }

    private fun assignments(activity: PrototypeActivity) = field("pickerAssignments").get(activity) as PrototypeFeedAssignments
    private fun field(name: String) = PrototypeActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun replacePickerWithNativeView(scenario: ActivityScenario<CachedPrototypeActivity>) {
        compose.waitForIdle()
        scenario.onActivity { activity ->
            // Exercise the exact setContentView boundary that disposes the
            // picker before a running native viewer is saved. Do not call Watch.
            activity.setContentView(FrameLayout(activity).apply {
                addView(TextView(activity).apply { text = "Provider-free native view" })
            })
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}
