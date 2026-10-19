package net.fstab.tachiai.feature.presentation

import android.media.session.MediaController
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.media3.common.util.UnstableApi
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Root
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import net.fstab.tachiai.feature.connections.DISCOVERY_ALL_TAB
import net.fstab.tachiai.feature.connections.ProviderInstancesScreen
import net.fstab.tachiai.feature.connections.StreamManagementScreen
import net.fstab.tachiai.feature.connections.StreamManagementState
import net.fstab.tachiai.platform.media.NativeMixedSide
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.TypeSafeMatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** TV-emulator evidence only: public test assets, synthetic setup, no accounts or network. */
@UnstableApi
class AndroidTvFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val media = ControlledMirroringDeviceTest()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val composeMounted = AtomicBoolean(false)

    @Test(timeout = 120_000) fun dpadSetupDiscoveryAndPickerLeadToTwoActualPlayersPanelsLayoutAndStop() =
        media.withFixture(television = true) { fixture ->
            val setup = Setup(fixture)
            media.main { fixture.beginObservation(90); setup.show() }
            media.awaitReady(fixture)
            compose.onNodeWithText("Providers").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithContentDescription("Back to streams").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            // Spatial navigation reaches the narrower Routes button first;
            // Up from it enters the wide configure row.
            key(KeyEvent.KEYCODE_DPAD_UP)
            compose.onNodeWithTag("Provider row ${setup.instance.id}").assert(isFocused()) {
                "Actual focus before configuring: " + compose.onNode(isFocused(), useUnmergedTree = true).printToString()
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithContentDescription("Back to providers").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithText("Local videos").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithContentDescription("Add Local sample A").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithContentDescription("Back to providers").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithText("Local videos").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithContentDescription("Add Local sample B").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            media.main { assertEquals(2, setup.configured.size) }
            key(KeyEvent.KEYCODE_BACK); key(KeyEvent.KEYCODE_BACK)
            compose.onNodeWithText("Providers").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_DOWN)
            setup.assignment("Local sample A", "A").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            setup.assignment("Local sample A", "A").assertIsSelected()
            key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_RIGHT)
            setup.assignment("Local sample B", "B").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            setup.assignment("Local sample B", "B").assertIsSelected()
            key(KeyEvent.KEYCODE_DPAD_UP); key(KeyEvent.KEYCODE_DPAD_UP)
            compose.onNodeWithText("Watch").assertIsFocused().assertIsEnabled()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitPlaying(fixture)
            val before = media.main { fixture.samples() }
            media.await("both local clocks and frame counts continue") {
                media.main { fixture.samples().zip(before).all { (now, old) ->
                    now.positionMs - old.positionMs >= 500 && now.renderedBuffers > old.renderedBuffers
                } }
            }
            media.main {
                assertEquals(1, setup.watches)
                assertEquals(setup.configured[0].choice, setup.assignments.a)
                assertEquals(setup.configured[1].choice, setup.assignments.b)
                assertNativeFocus(fixture, "Pause")
            }

            val playingBeforeBack = media.main { fixture.samples() }
            key(KeyEvent.KEYCODE_BACK)
            media.main {
                val viewer = checkNotNull(fixture.viewer)
                assertEquals(View.VISIBLE, viewer.visibility)
                assertEquals(View.GONE, viewer.getChildAt(1).visibility)
                assertNull(fixture.stopped.get())
            }
            media.await("both actual players continue while Back hides the controls") {
                media.main { fixture.samples().zip(playingBeforeBack).all { (now, old) ->
                    now.playing && now.positionMs - old.positionMs >= 500 && now.renderedBuffers > old.renderedBuffers
                } }
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            media.main {
                assertEquals(View.VISIBLE, fixture.viewer!!.getChildAt(1).visibility)
                assertNativeFocus(fixture, "Pause")
                assertTrue(fixture.samples().all { it.playing })
            }

            // Actual remote commands, with readback from the two real players.
            reachNative(fixture, "Audio"); key(KeyEvent.KEYCODE_DPAD_CENTER)
            media.main { assertEquals("Back to playback controls", fixture.viewer!!.findFocus()?.contentDescription) }
            reachNative(fixture, "◀")
            media.main { assertNativeFocus(fixture, "◀") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            media.main {
                assertTrue(fixture.currentMember(NativeMixedSide.A)!!.player.volume >
                    fixture.currentMember(NativeMixedSide.B)!!.player.volume)
            }
            reachNative(fixture, "Mute A")
            media.main { assertNativeFocus(fixture, "Mute A") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            media.main { assertEquals(0f, fixture.currentMember(NativeMixedSide.A)!!.player.volume, 0f) }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            key(KeyEvent.KEYCODE_BACK)
            media.main { assertNativeFocus(fixture, "Audio") }
            key(KeyEvent.KEYCODE_MEDIA_PAUSE)
            media.await("both actual players pause through the foreground pair session") {
                media.main { fixture.samples().all { it.ready && !it.playing } }
            }
            reachNative(fixture, "Timing"); key(KeyEvent.KEYCODE_DPAD_CENTER)
            reachNative(fixture, description = "Advance A by 1 s relative to B")
            media.main {
                assertEquals("Advance A by 1 s relative to B", fixture.viewer!!.findFocus()?.contentDescription)
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            media.await("actual players complete the relative timing request") {
                media.main { fixture.controller!!.busy.not() && fixture.samples().let { values ->
                    values.all { it.ready && !it.playing } &&
                        abs(values[0].positionMs - values[1].positionMs - 1_000) <= 150
                } }
            }
            key(KeyEvent.KEYCODE_BACK)
            media.main { assertNativeFocus(fixture, "Timing") }
            val playerA = media.main { fixture.currentMember(NativeMixedSide.A)!!.player }
            val playerB = media.main { fixture.currentMember(NativeMixedSide.B)!!.player }
            val surfaceA = media.main { fixture.playerSurface(NativeMixedSide.A) }
            val surfaceB = media.main { fixture.playerSurface(NativeMixedSide.B) }
            val orientation = media.main { fixture.activity.requestedOrientation }
            reachNative(fixture, "More"); key(KeyEvent.KEYCODE_DPAD_CENTER)
            selectMenu("Floating feed position"); selectMenu("Bottom left")
            val mediumWidth = media.main {
                assertNativeFocus(fixture, "More")
                val floating = surfaceB.parent as View
                assertEquals(0, floating.left); assertTrue(floating.top > 0)
                floating.width
            }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            selectMenu("Floating feed size"); selectMenu("Large")
            media.main { assertTrue((surfaceB.parent as View).width > mediumWidth) }
            key(KeyEvent.KEYCODE_DPAD_CENTER); selectMenu("Swap primary feed")
            media.main {
                assertSame(playerA, fixture.currentMember(NativeMixedSide.A)!!.player)
                assertSame(playerB, fixture.currentMember(NativeMixedSide.B)!!.player)
                assertSame(surfaceA, fixture.playerSurface(NativeMixedSide.A))
                assertSame(surfaceB, fixture.playerSurface(NativeMixedSide.B))
                assertTrue((surfaceB.parent as View).width > (surfaceA.parent as View).width)
                assertEquals(orientation, fixture.activity.requestedOrientation)
                assertEquals(1_000L, fixture.controller!!.pair!!.requestedAdjustmentMs)
            }
            key(KeyEvent.KEYCODE_MEDIA_PLAY)
            awaitPlaying(fixture)
            val destroyed = observeSessionDestruction(fixture)
            key(KeyEvent.KEYCODE_DPAD_CENTER); selectMenu("Stop both")
            assertTrue("Stop releases the actual session", destroyed.await(3, TimeUnit.SECONDS))
            media.main { fixture.assertReleased(); assertEquals("STOP", fixture.stopped.get()) }
            setup.assignment("Local sample A", "A").assertIsSelected()
            setup.assignment("Local sample B", "B").assertIsSelected()
            compose.onNodeWithText("Watch").assertIsNotEnabled()
            media.report(fixture, "TV_DPAD_FLOW_COMPLETE")
        }

    @Test(timeout = 90_000) fun localSlotRetryKeepsItsPartnerAndHostPauseReleasesAllPlayersAndSession() =
        media.withFixture(television = true) { fixture ->
            media.main { fixture.beginObservation(45) }
            media.awaitReady(fixture)
            key(KeyEvent.KEYCODE_MEDIA_PLAY)
            awaitPlaying(fixture)
            val retired = media.main { fixture.currentMember(NativeMixedSide.A)!! }
            val partner = media.main { fixture.currentMember(NativeMixedSide.B)!! }
            media.main { fixture.injectLocalFailure(NativeMixedSide.A) }
            val previous = media.main { partner.sample() }
            media.await("unaffected local partner keeps rendering after fixture failure") {
                media.main { partner.sample().let { it.playing && it.positionMs > previous.positionMs &&
                    it.renderedBuffers > previous.renderedBuffers } }
            }
            reachNative(fixture, "Retry local A")
            media.main { assertNativeFocus(fixture, "Retry local A") }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            media.awaitReady(fixture)
            media.main {
                assertTrue(retired.released)
                assertNotSame(retired, fixture.currentMember(NativeMixedSide.A))
                assertSame(partner, fixture.currentMember(NativeMixedSide.B))
                assertEquals(1, fixture.retries)
            }
            key(KeyEvent.KEYCODE_MEDIA_PLAY)
            awaitPlaying(fixture)
            val destroyed = observeSessionDestruction(fixture)
            media.main { fixture.finishHost() }
            media.await("actual host pause closes the controlled TV presentation") { fixture.stopped.get() != null }
            assertTrue("Host pause releases the actual session", destroyed.await(3, TimeUnit.SECONDS))
            media.main {
                assertEquals("ON_PAUSE", fixture.stopped.get())
                assertEquals(3, fixture.players.size)
                fixture.assertReleased()
            }
            media.report(fixture, "TV_LOCAL_RETRY_LIFECYCLE_COMPLETE")
        }

    private inner class Setup(private val fixture: ControlledMirroringDeviceTest.Fixture) {
        val instance = defaultProviderInstances().single { it.service == PrototypeService.TWITCH }
            .copy(customName = "Controlled local media")
        private val entries = listOf("101" to "Local sample A", "102" to "Local sample B").map { (id, title) ->
            CatalogEntry(CatalogResource(ProviderId("twitch"), "video", id, CatalogIntent.VIDEO), title,
                CatalogAvailability.AVAILABLE)
        }
        var configured by mutableStateOf(emptyList<ConfiguredSource>())
            private set
        var assignments = ConfiguredFeedAssignments(null, null)
        var watches = 0
            private set
        private var page by mutableStateOf("picker")

        fun assignment(title: String, slot: String) = compose.onNodeWithContentDescription(
            "Assign $title using ${providerDisplayNames(instance).inline} to feed $slot")

        fun show() {
            composeMounted.set(true)
            fixture.viewer!!.visibility = View.GONE
            val view = ComposeView(fixture.activity)
            fixture.root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
            fixture.onReturnToPicker = { page = "picker"; show() }
            view.setContent {
                val input = LocalInputModeManager.current
                SideEffect { input.requestInputMode(InputMode.Keyboard) }
                BackHandler {
                    page = when (page) { "discovery" -> "providers"; else -> "picker" }
                }
                TachiaiPrototypeTheme {
                    when (page) {
                        "providers" -> ProviderInstancesScreen(listOf(instance), emptyList(), false, null,
                            onRoutes = { error("The local fixture does not import routes") },
                            onSave = { _, _, _ -> error("The local fixture does not persist settings") },
                            onBack = { page = "picker" }, onOpenProvider = { page = "discovery" },
                            onRemove = { error("The local fixture does not delete its provider") })
                        "discovery" -> StreamManagementScreen(StreamManagementState(instance, configured = configured,
                            capabilities = CatalogCapabilities(browse = CatalogAccess.AVAILABLE,
                                playback = CatalogAccess.AVAILABLE, browseTitle = "Local videos"),
                            entries = entries, selectedTabId = DISCOVERY_ALL_TAB),
                            onSearch = {}, onAll = {}, onCollection = {}, onChildren = {}, onMore = {},
                            onAdd = { entry ->
                                if (configured.none { it.entry.resource == entry.resource }) configured = configured +
                                    ConfiguredSource("00000000-0000-4000-8000-${entry.resource.identity.padStart(12, '0')}", instance.id, entry)
                            }, onRemove = { id -> configured = configured.filterNot { it.id == id } },
                            onMove = { _, _ -> }, onRetry = {}, onBack = { page = "providers" })
                        else -> PrototypeSourcePicker("CONTROLLED LOCAL MEDIA · no provider account or network requests",
                            providerInstances = listOf(instance), configuredSources = configured,
                            initialAssignments = assignments, onAssignmentsChanged = { assignments = it },
                            onProviders = { page = "providers" }, playbackAvailable = fixture.stopped.get() == null,
                            onWatch = { selected ->
                                check(selected.a == configured[0].choice && selected.b == configured[1].choice)
                                assignments = selected; watches++
                                view.post {
                                    view.disposeComposition(); fixture.root.removeView(view)
                                    composeMounted.set(false)
                                    fixture.viewer!!.open()
                                    check(fixture.controller!!.play())
                                    fixture.viewer!!.refresh()
                                }
                            })
                    }
                }
            }
        }
    }

    private fun awaitPlaying(fixture: ControlledMirroringDeviceTest.Fixture) = media.await("both local players render while playing") {
        media.main { fixture.samples().all { it.playing && it.renderedBuffers >= 3 } }
    }

    private fun observeSessionDestruction(fixture: ControlledMirroringDeviceTest.Fixture): CountDownLatch {
        val destroyed = CountDownLatch(1)
        media.main { fixture.mediaController!!.registerCallback(object : MediaController.Callback() {
            override fun onSessionDestroyed() { destroyed.countDown() }
        }, Handler(Looper.getMainLooper())) }
        return destroyed
    }

    private fun assertNativeFocus(fixture: ControlledMirroringDeviceTest.Fixture, label: String) {
        assertEquals(label, (fixture.viewer!!.findFocus() as? android.widget.Button)?.text?.toString())
    }

    // Query the existing native focus graph without requesting focus. Each edge
    // is then exercised through the focused window's real D-pad dispatch. This
    // avoids assuming that panel sizing puts a slider before/after a button.
    private fun reachNative(fixture: ControlledMirroringDeviceTest.Fixture, label: String? = null,
        description: String? = null) {
        val path = media.main {
            val root = checkNotNull(fixture.viewer)
            fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
                (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            val allowed = descendants(root).toSet()
            val target = allowed.single { it.isShown && it.isEnabled && it.isFocusable &&
                if (description != null) it.contentDescription?.toString() == description
                else (it as? android.widget.Button)?.text?.toString() == label }
            val initial = checkNotNull(root.findFocus()) { "The viewer must enter remote focus before navigation" }
            val pending = ArrayDeque<Pair<View, List<Pair<Int, View>>>>()
            pending.add(initial to emptyList())
            val seen = mutableSetOf(initial)
            var found: List<Pair<Int, View>>? = null
            val directions = listOf(View.FOCUS_UP to KeyEvent.KEYCODE_DPAD_UP,
                View.FOCUS_DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
                View.FOCUS_LEFT to KeyEvent.KEYCODE_DPAD_LEFT,
                View.FOCUS_RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT)
            while (pending.isNotEmpty() && seen.size <= 64) {
                val (current, steps) = pending.removeFirst()
                if (current === target) { found = steps; break }
                if (steps.size >= 16) continue
                for ((direction, code) in directions) {
                    // Horizontal keys adjust a native slider rather than leave it.
                    if (current is SeekBar && direction in listOf(View.FOCUS_LEFT, View.FOCUS_RIGHT)) continue
                    val next = current.focusSearch(direction) ?: continue
                    if (next !in allowed || !next.isShown || !next.isEnabled || !next.isFocusable || !seen.add(next)) continue
                    pending.add(next to (steps + (code to next)))
                }
            }
            checkNotNull(found) { "No bounded D-pad path reaches ${description ?: label}" }
        }
        for ((code, expected) in path) {
            key(code)
            media.main { assertSame("D-pad edge reaches its native focus candidate", expected, fixture.viewer!!.findFocus()) }
        }
    }

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync()
        if (composeMounted.get()) compose.waitForIdle()
    }

    // Native popup rows are ListView selections, not independently focused text
    // views. Read the public adapter and move its selection only with D-pad keys.
    private fun selectMenu(title: String) {
        onView(isAssignableFrom(ListView::class.java)).inRoot(object : TypeSafeMatcher<Root>() {
            override fun matchesSafely(item: Root) = item.decorView.hasWindowFocus()
            override fun describeTo(description: Description) { description.appendText("focused local menu window") }
        }).perform(object : ViewAction {
            override fun getDescription() = "Select controlled TV menu item with D-pad: $title"
            override fun getConstraints(): Matcher<View> = isAssignableFrom(ListView::class.java)
            override fun perform(ui: UiController, view: View) {
                val list = view as ListView
                val target = (0 until list.count).single { (list.getItemAtPosition(it) as? MenuItem)?.title?.toString() == title }
                var attempts = 0
                while (list.selectedItemPosition != target && attempts++ <= list.count * 2) {
                    val direction = if (list.selectedItemPosition < target) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                    inject(ui, direction)
                }
                assertEquals("Menu selection reached $title", target, list.selectedItemPosition)
                inject(ui, KeyEvent.KEYCODE_DPAD_CENTER)
            }
            private fun inject(ui: UiController, code: Int) {
                val now = SystemClock.uptimeMillis()
                assertTrue(ui.injectKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0)))
                assertTrue(ui.injectKeyEvent(KeyEvent(now, now + 1, KeyEvent.ACTION_UP, code, 0)))
                ui.loopMainThreadUntilIdle()
            }
        })
        instrumentation.waitForIdleSync()
    }
}
