package net.fstab.tachiai.feature.presentation

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

// Provider-free main-process fixture. The invalid PID is rejected before any
// process polling, player launch, provider/account access or checkpoint read.
class PlaybackRestartActivityTest {
    @Test fun nonexportedMainProcessRelayRejectsOwnPidAcrossRecreationAndCanClose() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Restart relay requires Android 9", Build.VERSION.SDK_INT >= 28)
        assumeTrue("Run in the ordinary app process", Application.getProcessName() == context.packageName)

        @Suppress("DEPRECATION")
        val component = context.packageManager.getActivityInfo(
            ComponentName(context, PlaybackRestartActivity::class.java), PackageManager.GET_META_DATA)
        assertFalse("External apps must not supply restart requests", component.exported)
        assertTrue(component.enabled)
        assertEquals(context.packageName, component.processName)

        val ownPid = Process.myPid()
        val request = Intent(context, PlaybackRestartActivity::class.java)
            .putExtra(PlaybackRestartActivity.PRIOR_PID, ownPid)
        ActivityScenario.launch<PlaybackRestartActivity>(request).use { scenario ->
            scenario.onActivity { activity -> assertRejected(activity, ownPid) }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertRejected(activity, ownPid)
                val close = textViews(activity.findViewById(android.R.id.content)).filterIsInstance<Button>()
                    .single { it.text.toString() == "Close" }
                assertTrue("Close click must be handled", close.performClick())
                assertTrue("Close must request Activity finish immediately", activity.isFinishing)
            }
            // ActivityTaskManager delivers the finish lifecycle asynchronously;
            // an idle main Looper does not imply that delivery has happened.
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (scenario.state != Lifecycle.State.DESTROYED && SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(25)
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
        assertEquals("The instrumentation process must remain alive", ownPid, Process.myPid())
    }

    private fun assertRejected(activity: PlaybackRestartActivity, ownPid: Int) {
        assertFalse(activity.isFinishing)
        assertEquals(setOf(PlaybackRestartActivity.PRIOR_PID), activity.intent.extras!!.keySet())
        assertEquals(ownPid, activity.intent.getIntExtra(PlaybackRestartActivity.PRIOR_PID, 0))
        val text = textViews(activity.findViewById(android.R.id.content))
        assertTrue(text.any { it.text.toString() ==
            "The player could not be restarted safely. Saved setup and picker choices have been kept." })
        assertFalse(text.any { it.text.toString() == "Restarting the player…" || it.text.toString() == "Check again" })
        assertTrue(text.filterIsInstance<Button>().single { it.text.toString() == "Close" }.isEnabled)
    }

    private fun textViews(view: View): List<TextView> {
        val children = if (view is ViewGroup) (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
            else emptyList()
        return if (view is TextView) listOf(view) + children else children
    }
}
