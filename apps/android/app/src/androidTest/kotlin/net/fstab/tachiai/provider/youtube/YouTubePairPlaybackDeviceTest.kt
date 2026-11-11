package net.fstab.tachiai.provider.youtube

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import net.fstab.tachiai.MainActivity
import net.fstab.tachiai.feature.presentation.NativePairViewer
import net.fstab.tachiai.platform.media.NativeMixedSide
import net.fstab.tachiai.platform.media.NativePlaybackAudioGroup
import net.fstab.tachiai.platform.media.NativePlaybackBudget
import net.fstab.tachiai.platform.media.PrototypeFeedEvent
import net.fstab.tachiai.platform.media.PrototypePlaybackController
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in foreground public-network probe; never changes saved sources or accounts. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class YouTubePairPlaybackDeviceTest {
    @Test fun separateSessionsAdvanceTogetherAndShareViewerTransportAndMix() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Real YouTube pair playback requires explicit opt-in", args.getString("youtubePairPlayback") == "true")
        val id = args.getString("youtubeVideoId") ?: "M7lc1UVf-VE"
        assertTrue("Probe requires a public video identity", validYouTubeVideoId(id))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val active = AtomicBoolean(true)
        val failures = List(2) { AtomicBoolean(false) }
        val frames = List(2) { AtomicBoolean(false) }
        val budget = NativePlaybackBudget(110_000L, { active.get() }, maximumDurationMs = 110_000L)
        val sessions = arrayOfNulls<YouTubeFeedSession>(2)
        val surfaces = arrayOfNulls<PlayerView>(2)
        var controller: PrototypePlaybackController? = null
        var focus: NativePlaybackAudioGroup? = null
        var viewer: NativePairViewer? = null
        val scenario = ActivityScenario.launch<MainActivity>(Intent(instrumentation.targetContext, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra("net.fstab.tachiai.extra.NATIVE_ACCESS_PROBE", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        try {
            scenario.onActivity { activity ->
                focus = NativePlaybackAudioGroup(activity) { controller?.focusLost() }
                controller = PrototypePlaybackController(
                    acquireFocus = { focus!!.acquire() },
                    admission = { member -> sessions.any { it != null && it.member === member && it.canContinue() } },
                    playAllowed = { active.get() },
                )
                for (index in sessions.indices) {
                    surfaces[index] = PlayerView(activity).apply { useController = false }
                    sessions[index] = YouTubeFeedSession(activity, id, { active.get() }, { event ->
                        if (event == PrototypeFeedEvent.FAILED) failures[index].set(true)
                        if (event == PrototypeFeedEvent.VIDEO_FRAME) frames[index].set(true)
                    })
                }
                viewer = NativePairViewer(activity, surfaces[0]!!, surfaces[1]!!, "YouTube A", "YouTube B",
                    pair = { controller?.pair },
                    onPlay = { assertTrue("Shared Play must be accepted", controller!!.play()) },
                    onPause = { assertTrue("Shared Pause must be accepted", controller!!.pause()) },
                    onRelative = { controller!!.shiftRelative(it) },
                    onCatchUp = { controller!!.catchUp(it) },
                    onDiagnostics = {}, onStop = {}, onLandscape = {},
                    members = { NativeMixedSide.entries.map { controller?.member(it) } },
                    volumeRequest = { side, gain -> controller!!.setVolume(side, gain) },
                )
                activity.findViewById<ViewGroup>(android.R.id.content).addView(viewer,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                sessions.forEach { it!!.prepare(budget.child()) }
            }

            val baseline = LongArray(2)
            var started = false
            var concurrentProgress = false
            val deadline = SystemClock.elapsedRealtime() + 85_000L
            while (!failures.any { it.get() } && SystemClock.elapsedRealtime() < deadline && !concurrentProgress) {
                instrumentation.runOnMainSync {
                    if (!started && sessions.all { it?.player?.playbackState == Player.STATE_READY }) {
                        assertNotSame("Duplicate choices must own different sessions", sessions[0], sessions[1])
                        assertNotSame("Duplicate choices must own different players", sessions[0]!!.player, sessions[1]!!.player)
                        for (index in sessions.indices) {
                            surfaces[index]!!.player = sessions[index]!!.player
                            controller!!.setMember(NativeMixedSide.entries[index], sessions[index]!!.member)
                            baseline[index] = sessions[index]!!.player!!.currentPosition
                        }
                        viewer!!.open()
                        button(viewer!!, "Audio").performClick()
                        button(viewer!!, "Mute A").performClick()
                        button(viewer!!, "Mute B").performClick()
                        assertTrue("Both feeds must be muted before Play", sessions.all { it!!.player!!.volume == 0f })
                        assertTrue(button(viewer!!, "Play").performClick())
                        started = true
                    }
                    if (started) {
                        controller!!.poll()
                        viewer!!.refresh()
                        concurrentProgress = sessions.indices.all { index ->
                            val player = sessions[index]!!.player!!
                            player.isPlaying && player.currentPosition >= baseline[index] + 1_500L && frames[index].get()
                        }
                    }
                }
                if (!concurrentProgress) SystemClock.sleep(200L)
            }
            val diagnostics = sessions.mapIndexed { index, session ->
                "$index:${session?.failure?.reason}:${session?.failureDiagnostic}:request=${session?.requestFailure}:http=${session?.failureHttpStatus}:extraction=${session?.preparationDiagnostic}"
            }
            assertFalse("A YouTube feed failed: $diagnostics", failures.any { it.get() })
            assertTrue("Both feeds must decode frames and advance together", concurrentProgress)

            instrumentation.runOnMainSync {
                viewer!!.open()
                assertTrue(button(viewer!!, "Pause").performClick())
                assertTrue("Joint Pause must clear both play requests", sessions.all { !it!!.player!!.playWhenReady })
            }
            SystemClock.sleep(250L)
            val held = LongArray(2)
            instrumentation.runOnMainSync {
                sessions.indices.forEach { held[it] = sessions[it]!!.player!!.currentPosition }
            }
            SystemClock.sleep(900L)
            instrumentation.runOnMainSync {
                for (index in sessions.indices) {
                    val player = sessions[index]!!.player!!
                    assertFalse("Paused feed must stop advancing", player.isPlaying)
                    assertTrue("Joint Pause must hold each position", abs(player.currentPosition - held[index]) <= 100L)
                }
                // Exercise the common mix controls while both players are paused,
                // then restore both mutes before another playback request.
                button(viewer!!, "Unmute A").performClick()
                button(viewer!!, "Unmute B").performClick()
                val previous = sessions.map { it!!.player!!.volume }
                val volume = descendants(viewer!!).filterIsInstance<SeekBar>()
                    .single { it.contentDescription == "Overall mix volume" }
                assertTrue("Common volume control must be visible", volume.isShown)
                volume.requestFocus()
                volume.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
                volume.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT))
                for (index in sessions.indices) assertTrue("Overall volume must update both native players",
                    sessions[index]!!.player!!.volume > previous[index])
                button(viewer!!, "Mute A").performClick()
                button(viewer!!, "Mute B").performClick()
                assertTrue("Probe must remain muted", sessions.all { it!!.player!!.volume == 0f })
            }
        } finally {
            instrumentation.runOnMainSync {
                controller?.close()
                viewer?.endSession()
                surfaces.forEach { it?.player = null }
                active.set(false)
                budget.stop()
                sessions.forEach { it?.close() }
                focus?.close()
                (viewer?.parent as? ViewGroup)?.removeView(viewer)
            }
            val cleanupDeadline = SystemClock.elapsedRealtime() + 15_000L
            while (sessions.any { it?.cleanupPending == true } && SystemClock.elapsedRealtime() < cleanupDeadline) SystemClock.sleep(100L)
            try {
                assertTrue("Both sessions must finish cleanup", sessions.all { it == null || it.cleanupConfirmed })
                assertFalse("Neither session may report cleanup failure", sessions.any { it?.cleanupFailed == true })
            } finally { scenario.close() }
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun button(view: View, label: String): Button = descendants(view).filterIsInstance<Button>()
        .single { it.text.toString() == label }
}
