package net.fstab.tachiai.feature.presentation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.AssetDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import net.fstab.tachiai.R
import net.fstab.tachiai.platform.media.NativeMixedSide
import net.fstab.tachiai.platform.media.NativePairMember
import net.fstab.tachiai.platform.media.NativePlaybackAudioGroup
import net.fstab.tachiai.platform.media.NativeSeekOutcome
import net.fstab.tachiai.platform.media.NativeSeekPlan
import net.fstab.tachiai.platform.media.NativeTimingSnapshot
import net.fstab.tachiai.platform.media.PrototypePlaybackController
import net.fstab.tachiai.platform.media.nativeSeekPlan
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real local media only. No provider, network, saved-state or capture APIs. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ControlledMirroringDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun buildGateRejectsReleaseAndInspectionRuntimeFlags() {
        assertFalse(ordinaryDebugFlags(0))
        assertTrue(ordinaryDebugFlags(ApplicationInfo.FLAG_DEBUGGABLE))
        assertFalse(ordinaryDebugFlags(ApplicationInfo.FLAG_TEST_ONLY))
        assertFalse(ordinaryDebugFlags(ApplicationInfo.FLAG_DEBUGGABLE or ApplicationInfo.FLAG_TEST_ONLY))
    }

    @Test fun twoLocalPlayersRenderAdvanceAndApplyViewerMixTimingThenStop() = withFixture { fixture ->
        awaitReady(fixture)
        main { fixture.beginObservation(30); assertTrue(fixture.controller!!.play()) }
        await("both local players render and advance") {
            main { fixture.samples().all { it.playing && it.positionMs >= 750 && it.renderedBuffers >= 3 } }
        }
        val before = main { fixture.samples() }
        await("both local content clocks advance independently") {
            main { fixture.samples().zip(before).all { (now, old) ->
                now.positionMs - old.positionMs >= 500 && now.renderedBuffers > old.renderedBuffers
            } }
        }
        main {
            fixture.assertHeaderVisible()
            val viewer = checkNotNull(fixture.viewer)
            button(viewer, "Audio").performClick()
            assertEquals(0.5f, fixture.players[0].player.volume, 0f)
            assertEquals(0.5f, fixture.players[1].player.volume, 0f)
            button(viewer, "Mute A").performClick()
            assertEquals(0f, fixture.players[0].player.volume, 0f)
            assertEquals(0.5f, fixture.players[1].player.volume, 0f)
            button(viewer, "Unmute A").performClick()
            button(viewer, "◀").performClick()
            assertTrue(fixture.players[0].player.volume > fixture.players[1].player.volume)
            button(viewer, "Centre mix").performClick()
            assertEquals(0.5f, fixture.players[0].player.volume, 0f)
            assertEquals(0.5f, fixture.players[1].player.volume, 0f)
            assertTrue(fixture.controller!!.pause())
            fixture.players.forEach { assertEquals(NativeSeekOutcome.REQUESTED, it.seekToMs(15_000).outcome) }
        }
        await("both local replays settle paused at the baseline") {
            main { fixture.samples().all { it.ready && !it.playing && abs(it.positionMs - 15_000) <= 100 } }
        }
        main {
            val viewer = checkNotNull(fixture.viewer)
            button(viewer, "Timing").performClick()
            descendants(viewer).filterIsInstance<Button>().single {
                it.contentDescription == "Advance A by 1 s relative to B"
            }.performClick()
            assertEquals(1_000L, fixture.controller!!.pair!!.requestedAdjustmentMs)
        }
        await("actual players complete the Rust-planned relative seek") {
            main { !fixture.controller!!.busy && fixture.samples().let { samples ->
                samples.all { it.ready && !it.playing } &&
                    abs(samples[0].positionMs - samples[1].positionMs - 1_000) <= 100
            } }
        }
        main { fixture.stopButton.performClick() }
        assertEquals("STOP", fixture.stopped.get())
        main { fixture.assertReleased() }
        report(fixture, "SMOKE_COMPLETE")
    }

    @Test fun manualControlledMirroringObservation() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Manual media observation requires controlledMirror=true",
            arguments.getString("controlledMirror") == "true")
        val seconds = arguments.getString("controlledMirrorSeconds")?.toIntOrNull()
        require(seconds != null && seconds in 1..600) {
            "Supply controlledMirrorSeconds as an integer from 1 through 600"
        }
        withFixture { fixture ->
            awaitReady(fixture)
            main { fixture.beginObservation(seconds); assertTrue(fixture.controller!!.play()) }
            report(fixture, "MANUAL_OBSERVATION_STARTED")
            // Main-thread deadline also closes the fixture if this worker is
            // interrupted. Stop/Back/background may end observation sooner.
            val end = SystemClock.elapsedRealtime() + seconds * 1_000L + 3_000L
            while (fixture.stopped.get() == null && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50)
            assertNotNull("The bounded observation must terminate", fixture.stopped.get())
            assertTrue("Player/fixture failures are not successful manual observations", fixture.stopped.get() in
                setOf("STOP", "BACK", "DEADLINE", "ON_PAUSE", "ON_STOP", "ON_DESTROY"))
            main { fixture.assertReleased() }
            report(fixture, "MANUAL_OBSERVATION_ENDED")
        }
    }

    @Test fun finishingTheHostStopsAndReleasesBothActualPlayersOnPause() = withFixture { fixture ->
        awaitReady(fixture)
        main { fixture.beginObservation(20); assertTrue(fixture.controller!!.play()) }
        await("both local players render before leaving the host") {
            main { fixture.samples().all { it.playing && it.renderedBuffers >= 3 } }
        }
        // Finish only this fixture's Activity, letting Android dispatch the real
        // pause/stop lifecycle. Do not press Home or alter other tasks/settings.
        main { fixture.finishHost() }
        await("the host pause closes the fixture") { fixture.stopped.get() != null }
        assertEquals("ON_PAUSE", fixture.stopped.get())
        main { fixture.assertReleased() }
        report(fixture, "LIFECYCLE_COMPLETE")
    }

    private fun awaitReady(fixture: Fixture) = await("two local replay timelines are READY", 15_000) {
        check(fixture.stopped.get() == null) { "Controlled fixture stopped before preparation completed" }
        main { fixture.samples().size == 2 && fixture.samples().all { it.ready } }
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        // Ordinary debug policy is intentional: inspect removes protections and
        // cannot establish ordinary-build mirroring behavior.
        val target = instrumentation.targetContext
        check(target.packageName == "net.fstab.tachiai")
        // BuildConfig constants belong to the test's compile-time variant and
        // are inlined. Read the installed target's manifest flags instead.
        assumeTrue("Use the ordinary debug target", ordinaryDebugFlags(target.applicationInfo.flags))
        verifyMedia(instrumentation.context)
        var activity: ComponentActivity? = null
        var fixture: Fixture? = null
        try {
            // The existing blank debug host is supplied by ui-test-manifest.
            // Unlike ActivityScenario.launch, do not clear the application's
            // task. Instrumentation can still restart the target process: the
            // documented manual path uses whole-screen sharing, not app-only.
            val intent = Intent().setComponent(ComponentName(target.packageName, ComponentActivity::class.java.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val host = instrumentation.startActivitySync(intent) as ComponentActivity
            activity = host
            val running = main { Fixture(host, instrumentation.context).also { it.start() } }
            fixture = running
            test(running)
        } finally {
            main { fixture?.close("TEST_FINISHED"); activity?.finish() }
        }
    }

    private fun report(fixture: Fixture, phase: String) {
        val report = main { Bundle().apply {
            putString("controlledMirror", phase)
            putString("stopReason", fixture.stopped.get() ?: "RUNNING")
            fixture.samples().forEachIndexed { index, sample ->
                val side = if (index == 0) "A" else "B"
                putLong("${side}PositionMs", sample.positionMs)
                putInt("${side}RenderedBuffers", sample.renderedBuffers)
                putFloat("${side}Volume", sample.volume)
            }
        } }
        instrumentation.sendStatus(0, report)
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private fun await(description: String, timeoutMs: Long = 8_000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < end) { "Timed out: $description" }
            SystemClock.sleep(50)
        }
    }

    private fun verifyMedia(context: Context) {
        val manifest = context.assets.open("controlled-mirroring/media.json").bufferedReader().use {
            JSONObject(it.readText())
        }
        check(manifest.getInt("schema") == 1)
        for (name in listOf("a.mp4", "b.mp4")) {
            val expected = manifest.getJSONObject("files").getJSONObject(name)
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            context.assets.open("controlled-mirroring/$name").use { input ->
                val buffer = ByteArray(16_384)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    check(size <= 8 * 1024 * 1024) { "Controlled media exceeds its fixed size limit" }
                    digest.update(buffer, 0, count)
                }
            }
            check(size == expected.getLong("bytes"))
            check(digest.digest().joinToString("") { "%02x".format(it) } == expected.getString("sha256"))
        }
    }

    private data class Sample(val positionMs: Long, val ready: Boolean, val playing: Boolean,
        val renderedBuffers: Int, val volume: Float)

    private class LocalMember(val player: ExoPlayer) : NativePairMember {
        var released = false
            private set

        private fun active(): Boolean {
            check(Looper.myLooper() == player.applicationLooper)
            return !released
        }

        override fun timingSnapshot(): NativeTimingSnapshot? {
            if (!active() || player.currentTimeline.isEmpty) return null
            val window = player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
            fun known(value: Long) = value.takeIf { it != C.TIME_UNSET && it >= 0 }
            return NativeTimingSnapshot(known(player.currentPosition), known(player.duration), known(player.bufferedPosition),
                null, null, known(window.defaultPositionMs), false, false,
                player.isCurrentMediaItemSeekable && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
                player.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION), player.isPlayingAd,
                player.isPlaying, player.playWhenReady, player.playbackState, player.playbackParameters.speed)
        }

        override fun seekToMs(targetMs: Long): NativeSeekPlan {
            val plan = nativeSeekPlan(timingSnapshot(), targetMs)
            if (active() && plan.outcome in setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.CLAMPED))
                player.seekTo(checkNotNull(plan.targetMs))
            return plan
        }

        override fun setTimingPlaying(playing: Boolean): Boolean {
            if (!active() || !player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) return false
            player.playWhenReady = playing
            return true
        }

        override fun setVolume(volume: Float): Boolean {
            if (!active() || !volume.isFinite() || volume !in 0f..1f) return false
            player.volume = volume
            return true
        }

        fun sample(): Sample {
            check(active())
            val counters = player.videoDecoderCounters
            counters?.ensureUpdated()
            return Sample(player.currentPosition, player.playbackState == Player.STATE_READY,
                player.isPlaying, counters?.renderedOutputBufferCount ?: 0, player.volume)
        }

        override fun close() {
            if (!active()) return
            player.pause()
            player.release()
            released = player.isReleased
        }
    }

    private class Fixture(private val activity: ComponentActivity, private val assets: Context) {
        val stopped = AtomicReference<String?>(null)
        val players = mutableListOf<LocalMember>()
        var controller: PrototypePlaybackController? = null
            private set
        var viewer: NativePairViewer? = null
            private set
        lateinit var stopButton: Button
            private set
        private var audio: NativePlaybackAudioGroup? = null
        private val handler = Handler(Looper.getMainLooper())
        private val cleanupFailures = mutableListOf<String>()
        private var finalSamples = emptyList<Sample>()
        private var deadlineMs = 0L
        private lateinit var readback: TextView
        private var back: OnBackPressedCallback? = null
        private val lifecycle = LifecycleEventObserver { _, event ->
            if (event in setOf(Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY))
                close(event.name)
        }
        private val ticker = object : Runnable {
            override fun run() {
                if (stopped.get() != null) return
                if (SystemClock.elapsedRealtime() >= deadlineMs) { finish("DEADLINE"); return }
                try {
                    controller?.poll()
                    viewer?.refresh()
                    readback.text = "CONTROLLED LOCAL MEDIA · ${((deadlineMs - SystemClock.elapsedRealtime()) / 1_000).coerceAtLeast(0)} s\n" +
                        samples().mapIndexed { index, sample ->
                            "${if (index == 0) "A" else "B"} ${sample.positionMs} ms · frames ${sample.renderedBuffers} · gain ${sample.volume}"
                        }.joinToString("\n")
                    handler.postDelayed(this, 100)
                } catch (_: Exception) { finish("FIXTURE_ERROR") }
            }
        }

        fun start() {
            check(Looper.myLooper() == Looper.getMainLooper())
            try {
                activity.lifecycle.addObserver(lifecycle)
                back = activity.onBackPressedDispatcher.addCallback(activity) { finish("BACK") }
                audio = NativePlaybackAudioGroup(activity) { controller?.focusLost(); viewer?.refresh() }
                controller = PrototypePlaybackController({ audio?.acquire() == true },
                    admission = { member -> stopped.get() == null && players.any { it === member } },
                    playAllowed = { stopped.get() == null && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) })
                val root = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    fitsSystemWindows = true
                    setBackgroundColor(Color.rgb(23, 26, 28))
                }
                val header = LinearLayout(activity).apply { isBaselineAligned = false }
                readback = TextView(activity).apply {
                    setTextColor(Color.WHITE); textSize = 12f
                    text = "CONTROLLED LOCAL MEDIA\nA preparing\nB preparing"
                    setLines(3)
                }
                stopButton = Button(activity).apply {
                    text = "Stop fixture"; isAllCaps = false
                    setOnClickListener { finish("STOP") }
                }
                header.addView(readback, LinearLayout.LayoutParams(0, -2, 1f))
                header.addView(stopButton, LinearLayout.LayoutParams(-2, -2))
                root.addView(header, LinearLayout.LayoutParams(-1, -2))
                val surfaces = NativeMixedSide.entries.map { side ->
                    val member = LocalMember(ExoPlayer.Builder(activity).build())
                    players.add(member)
                    member.player.addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) { finish("PLAYER_ERROR_${error.errorCode}") }
                    })
                    member.player.repeatMode = Player.REPEAT_MODE_ONE
                    // AssetDataSource cannot fall through to a network request.
                    val source = ProgressiveMediaSource.Factory { AssetDataSource(assets) }.createMediaSource(
                        MediaItem.fromUri(Uri.parse("asset:///controlled-mirroring/${if (side == NativeMixedSide.A) "a" else "b"}.mp4")))
                    member.player.setMediaSource(source)
                    member.player.prepare()
                    controller!!.setMember(side, member)
                    (activity.layoutInflater.inflate(R.layout.native_viewer_player, root, false) as PlayerView).apply {
                        player = member.player; useController = false; keepScreenOn = true
                    }
                }
                viewer = NativePairViewer(activity, surfaces[0], surfaces[1], "A", "B", { controller?.pair },
                    { controller?.play() }, { controller?.pause() }, { controller?.shiftRelative(it) },
                    { controller?.catchUp(it) }, { controller?.pause() }, { finish("STOP") },
                    { landscape -> activity.requestedOrientation = if (landscape)
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT },
                    setupLabel = "Pause controlled media", statusOverride = { controller?.status },
                    members = { NativeMixedSide.entries.map { controller?.member(it) } },
                    volumeRequest = { side, value -> controller?.setVolume(side, value) == true },
                    onSources = { finish("BACK") }, playbackBusy = { controller?.busy == true })
                root.addView(viewer, LinearLayout.LayoutParams(-1, 0, 1f))
                activity.setContentView(root)
                viewer!!.open()
                deadlineMs = SystemClock.elapsedRealtime() + 20_000
                handler.post(ticker)
            } catch (error: Throwable) { close("START_FAILED"); throw error }
        }

        fun beginObservation(seconds: Int) {
            check(seconds in 1..600 && stopped.get() == null)
            deadlineMs = SystemClock.elapsedRealtime() + seconds * 1_000L
        }

        fun samples(): List<Sample> = if (stopped.get() != null) finalSamples else players.map { it.sample() }

        fun finishHost() { activity.finish() }

        private fun finish(reason: String) { close(reason); activity.finish() }

        fun close(reason: String) {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (!stopped.compareAndSet(null, reason)) return
            handler.removeCallbacksAndMessages(null)
            fun release(owner: String, action: () -> Unit) {
                try { action() } catch (_: Throwable) { cleanupFailures.add(owner) }
            }
            release("SAMPLES") { finalSamples = players.map { it.sample() } }
            release("VIEWER") { viewer?.endSession() }
            release("CONTROLLER") { controller?.close() }
            players.forEach { release("PLAYER") { it.close() } }
            release("AUDIO_FOCUS") { audio?.close() }
            release("BACK") { back?.remove() }
            activity.lifecycle.removeObserver(lifecycle)
        }

        fun assertReleased() {
            assertNotNull(stopped.get())
            assertEquals(emptyList<String>(), cleanupFailures)
            assertEquals(2, players.size)
            assertTrue(players.all { it.released && it.player.isReleased })
        }

        @Suppress("DEPRECATION")
        fun assertHeaderVisible() {
            val topInset = activity.window.decorView.rootWindowInsets?.systemWindowInsetTop ?: 0
            for (view in listOf(readback, stopButton)) {
                val position = IntArray(2)
                view.getLocationOnScreen(position)
                assertTrue("Fixture header must sit below the status bar", position[1] >= topInset)
                val visible = Rect()
                assertTrue(view.getGlobalVisibleRect(visible))
                assertEquals("Fixture header must not be clipped", view.height, visible.height())
            }
            assertEquals(3, readback.layout.lineCount)
        }
    }

    private companion object {
        fun ordinaryDebugFlags(flags: Int): Boolean =
            flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && flags and ApplicationInfo.FLAG_TEST_ONLY == 0

        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        fun button(view: View, label: String) = descendants(view).filterIsInstance<Button>().single { it.text == label }
    }
}
