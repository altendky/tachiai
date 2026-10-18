package net.fstab.tachiai.feature.presentation

import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForegroundPairMediaSessionTest {
    private class Fixture : AutoCloseable {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        val admitted = AtomicBoolean(true)
        val requested = AtomicBoolean(false)
        val admissionChecks = AtomicInteger()
        val plays = AtomicInteger()
        val pauses = AtomicInteger()
        lateinit var session: ForegroundPairMediaSession
        lateinit var controller: MediaController

        init {
            main {
                session = ForegroundPairMediaSession(instrumentation.targetContext,
                    admitted = { admissionChecks.incrementAndGet(); admitted.get() },
                    playingRequested = requested::get,
                    play = { plays.incrementAndGet(); requested.set(true) },
                    pause = { pauses.incrementAndGet(); requested.set(false) })
                controller = MediaController(instrumentation.targetContext, session.token)
            }
        }

        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

        fun press(code: Int, repeat: Int = 0, action: Int = KeyEvent.ACTION_DOWN) {
            val now = SystemClock.uptimeMillis()
            controller.dispatchMediaButtonEvent(KeyEvent(now, now, action, code, repeat))
        }

        fun settled() {
            val latch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).postDelayed({ latch.countDown() }, 300)
            assertTrue("Main-thread media callback observation completed", latch.await(3, TimeUnit.SECONDS))
        }

        override fun close() = main { session.close() }
    }

    @Test fun publishedStateExposesPairTransportWithoutInventingASharedTimeline() {
        Fixture().use { fixture ->
            val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
            val initial = checkNotNull(fixture.controller.playbackState)
            assertEquals(PlaybackState.STATE_PAUSED, initial.state)
            assertEquals(actions, initial.actions)
            assertEquals(PlaybackState.PLAYBACK_POSITION_UNKNOWN, initial.position)
            assertEquals(0f, initial.playbackSpeed, 0f)

            fixture.main { fixture.session.publish(PlaybackState.STATE_PLAYING) }
            val playing = checkNotNull(fixture.controller.playbackState)
            assertEquals(PlaybackState.STATE_PLAYING, playing.state)
            assertEquals(1f, playing.playbackSpeed, 0f)
            assertEquals(actions, playing.actions)
            assertEquals(PlaybackState.PLAYBACK_POSITION_UNKNOWN, playing.position)

            fixture.main { fixture.session.publish(PlaybackState.STATE_BUFFERING) }
            assertEquals(0f, checkNotNull(fixture.controller.playbackState).playbackSpeed, 0f)
            fixture.controller.transportControls.seekTo(30_000)
            fixture.settled()
            assertEquals(0, fixture.plays.get())
            assertEquals(0, fixture.pauses.get())
        }
    }

    @Test fun controllerTransportRequiresAdmissionAndReleasedSessionCannotControlThePair() {
        Fixture().use { fixture ->
            fixture.controller.transportControls.play()
            await("one pair play") { fixture.plays.get() == 1 }
            fixture.controller.transportControls.pause()
            await("one pair pause") { fixture.pauses.get() == 1 }

            fixture.admitted.set(false)
            val checks = fixture.admissionChecks.get()
            fixture.controller.transportControls.play()
            fixture.controller.transportControls.pause()
            await("both rejected commands reached the admission gate") { fixture.admissionChecks.get() >= checks + 2 }
            assertEquals(1, fixture.plays.get())
            assertEquals(1, fixture.pauses.get())

            val destroyed = CountDownLatch(1)
            val callback = object : MediaController.Callback() {
                override fun onSessionDestroyed() { destroyed.countDown() }
            }
            fixture.controller.registerCallback(callback, Handler(Looper.getMainLooper()))
            fixture.main { fixture.session.close(); fixture.session.close() }
            assertTrue("Foreground session was released", destroyed.await(3, TimeUnit.SECONDS))
            fixture.admitted.set(true)
            fixture.controller.transportControls.play()
            fixture.controller.transportControls.pause()
            fixture.press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            fixture.settled()
            assertEquals(1, fixture.plays.get())
            assertEquals(1, fixture.pauses.get())
            fixture.controller.unregisterCallback(callback)
        }
    }

    @Test fun toggleAndHeadsetPressesDispatchOnceAcrossInitialDownRepeatsAndUp() {
        for (code in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK)) {
            Fixture().use { fixture ->
                fixture.press(code)
                await("initial down plays the pair") { fixture.plays.get() == 1 }
                fixture.press(code, repeat = 1)
                fixture.press(code, repeat = 2)
                fixture.press(code, action = KeyEvent.ACTION_UP)
                fixture.settled()
                assertEquals(1, fixture.plays.get())
                assertEquals(0, fixture.pauses.get())

                fixture.press(code)
                await("next distinct press pauses the pair") { fixture.pauses.get() == 1 }
                fixture.press(code, repeat = 1)
                fixture.press(code, action = KeyEvent.ACTION_UP)
                fixture.settled()
                assertEquals(1, fixture.plays.get())
                assertEquals(1, fixture.pauses.get())

                fixture.admitted.set(false)
                val checks = fixture.admissionChecks.get()
                fixture.press(code)
                await("media button reached the closed admission gate") { fixture.admissionChecks.get() > checks }
                assertEquals(1, fixture.plays.get())
                assertEquals(1, fixture.pauses.get())
            }
        }
    }

    @Test fun dedicatedPlayAndPauseKeysDoNotToggleOrRepeatTheOtherCommand() {
        Fixture().use { fixture ->
            fixture.press(KeyEvent.KEYCODE_MEDIA_PLAY)
            await("dedicated play") { fixture.plays.get() == 1 }
            fixture.press(KeyEvent.KEYCODE_MEDIA_PLAY, repeat = 1)
            fixture.press(KeyEvent.KEYCODE_MEDIA_PLAY, action = KeyEvent.ACTION_UP)
            fixture.settled()
            assertEquals(1, fixture.plays.get())
            assertEquals(0, fixture.pauses.get())

            fixture.press(KeyEvent.KEYCODE_MEDIA_PAUSE)
            await("dedicated pause") { fixture.pauses.get() == 1 }
            fixture.press(KeyEvent.KEYCODE_MEDIA_PAUSE, repeat = 1)
            fixture.press(KeyEvent.KEYCODE_MEDIA_PAUSE, action = KeyEvent.ACTION_UP)
            fixture.settled()
            assertEquals(1, fixture.plays.get())
            assertEquals(1, fixture.pauses.get())
        }
    }

    @Test fun foregroundHardwareMediaKeysReachThePairOnceWithoutTokenDispatch() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val focused = AtomicBoolean()
            await("Blank foreground activity owns the input window") {
                scenario.onActivity { focused.set(it.hasWindowFocus()) }
                focused.get()
            }
            Fixture().use { fixture ->
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                // Mirror the foreground viewer's Activity ownership, then send
                // genuine input rather than dispatching directly through its token.
                scenario.onActivity { it.mediaController = fixture.controller }
                try {
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    await("hardware toggle plays the pair") { fixture.plays.get() == 1 }
                    fixture.settled()
                    assertEquals(1, fixture.plays.get())
                    assertEquals(0, fixture.pauses.get())
                    fixture.main { fixture.session.publish(PlaybackState.STATE_PLAYING) }

                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    await("next hardware toggle pauses the pair") { fixture.pauses.get() == 1 }
                    fixture.settled()
                    assertEquals(1, fixture.plays.get())
                    assertEquals(1, fixture.pauses.get())
                    fixture.main { fixture.session.publish(PlaybackState.STATE_PAUSED) }

                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY)
                    await("dedicated hardware play reaches the pair") { fixture.plays.get() == 2 }
                    fixture.settled()
                    assertEquals(2, fixture.plays.get())
                    assertEquals(1, fixture.pauses.get())
                    fixture.main { fixture.session.publish(PlaybackState.STATE_PLAYING) }

                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE)
                    await("dedicated hardware pause reaches the pair") { fixture.pauses.get() == 2 }
                    fixture.settled()
                    assertEquals(2, fixture.plays.get())
                    assertEquals(2, fixture.pauses.get())
                } finally {
                    scenario.onActivity { it.mediaController = null }
                }
            }
        }
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 3_000
        while (!condition() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(10)
        assertTrue(description, condition())
    }
}
