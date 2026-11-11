package net.fstab.tachiai.provider.youtube

import android.content.Intent
import android.os.SystemClock
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicBoolean
import net.fstab.tachiai.MainActivity
import net.fstab.tachiai.platform.media.NativePlaybackBudget
import net.fstab.tachiai.platform.media.PrototypeFeedEvent
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

// Explicit real-network acceptance probe. Owns only a temporary foreground
// player/session: no provider registry, route, account or saved-source writes.
@UnstableApi
@RunWith(AndroidJUnit4::class)
class YouTubePlaybackDeviceTest {
    @Test fun publicVideoProducesNativeFramesAndAdvancesMuted() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Real YouTube playback requires explicit opt-in", args.getString("youtubePlayback") == "true")
        val id = args.getString("youtubeVideoId") ?: "M7lc1UVf-VE"
        assertTrue("Probe requires a public video identity", validYouTubeVideoId(id))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val active = AtomicBoolean(true)
        val failed = AtomicBoolean(false)
        val frame = AtomicBoolean(false)
        val budget = NativePlaybackBudget(75_000L, { active.get() }, maximumDurationMs = 75_000L)
        var session: YouTubeFeedSession? = null
        var surface: PlayerView? = null
        val scenario = ActivityScenario.launch<MainActivity>(Intent(instrumentation.targetContext, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra("net.fstab.tachiai.extra.NATIVE_ACCESS_PROBE", true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        try {
            scenario.onActivity { activity ->
                surface = PlayerView(activity).apply { useController = false }
                activity.findViewById<ViewGroup>(android.R.id.content).addView(surface,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                session = YouTubeFeedSession(activity, id, { active.get() }, { event ->
                    if (event == PrototypeFeedEvent.FAILED) failed.set(true)
                    if (event == PrototypeFeedEvent.VIDEO_FRAME) frame.set(true)
                })
                session!!.prepare(budget)
            }
            val deadline = SystemClock.elapsedRealtime() + 65_000L
            var playRequested = false
            var baseline: Long? = null
            var advanced = false
            while (!failed.get() && SystemClock.elapsedRealtime() < deadline && !(advanced && frame.get())) {
                instrumentation.runOnMainSync {
                    val owned = session ?: return@runOnMainSync
                    val player = owned.player
                    val member = owned.member
                    if (!playRequested && player != null && member != null && player.playbackState == Player.STATE_READY) {
                        surface!!.player = player
                        assertTrue("Native playback must accept mute before Play", member.setVolume(0f))
                        baseline = player.currentPosition
                        assertTrue("Native playback must accept Play", member.setTimingPlaying(true))
                        playRequested = true
                    }
                    if (playRequested && player != null && player.isPlaying &&
                        player.currentPosition >= (baseline ?: Long.MAX_VALUE) + 1_500L) advanced = true
                }
                if (!(advanced && frame.get())) SystemClock.sleep(200L)
            }
            assertFalse("YouTube failed: ${session?.failure?.reason}; ${session?.failureDiagnostic}; request=${session?.requestFailure}; http=${session?.failureHttpStatus}; extraction=${session?.preparationDiagnostic}; play=$playRequested; frame=${frame.get()}", failed.get())
            assertTrue("YouTube did not reach native READY and accept muted Play", playRequested)
            assertTrue("Native video did not produce a decoded frame", frame.get())
            assertTrue("Native playback did not advance at least 1500 ms while playing", advanced)
        } finally {
            active.set(false)
            budget.stop()
            instrumentation.runOnMainSync {
                surface?.player = null
                session?.close()
                (surface?.parent as? ViewGroup)?.removeView(surface)
            }
            scenario.close()
        }
    }
}
