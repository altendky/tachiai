package net.fstab.tachiai.platform.web

import android.app.Application
import android.webkit.ValueCallback
import android.webkit.WebView
import java.net.URI
import net.fstab.tachiai.feature.diagnostic.AbemaTwitchSingleWebContentsAdapter
import net.fstab.tachiai.feature.diagnostic.COMPOSITE_ABEMA_PANE
import net.fstab.tachiai.feature.diagnostic.COMPOSITE_TWITCH_PANE
import net.fstab.tachiai.feature.presentation.ABEMA_SUMO_REPLAY_URL
import net.fstab.tachiai.provider.BrowserCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserPaneControllerTest {
    @Test
    fun `older active window cannot overwrite a newer rejected attempt`() {
        val controller = BrowserPaneController(AbemaTwitchSingleWebContentsAdapter("arcajazz"))
        controller.recordPopupEvent(PopupDiagnosticEvent(1, PopupEventKind.OPENED))
        val rejected = PopupDiagnosticEvent(2, PopupEventKind.REQUEST_ALREADY_OPEN)
        controller.recordPopupEvent(rejected)
        controller.recordPopupEvent(PopupDiagnosticEvent(1, PopupEventKind.NETWORK_ERROR, platformCode = -2))
        controller.recordPopupEvent(PopupDiagnosticEvent(1, PopupEventKind.CLOSED))
        assertEquals(rejected, controller.lastPopupFailure)
        assertEquals(rejected, controller.latestPopupEvent)
        controller.recordPopupEvent(PopupDiagnosticEvent(3, PopupEventKind.OPENED))
        assertNull(controller.lastPopupFailure)
    }

    @Test
    fun `popup failure survives page completion and close but a new opened attempt resets it`() {
        val controller = BrowserPaneController(AbemaTwitchSingleWebContentsAdapter("arcajazz"))
        controller.recordPopupEvent(PopupDiagnosticEvent(1, PopupEventKind.OPENED))
        val failed = PopupDiagnosticEvent(1, PopupEventKind.NAVIGATION_BLOCKED, PopupDestinationClass.OTHER)
        controller.recordPopupEvent(failed)
        controller.pageLoading()
        controller.pageReady()
        controller.recordPopupEvent(PopupDiagnosticEvent(1, PopupEventKind.CLOSED))
        assertEquals("Ready for user interaction", controller.status)
        assertEquals(failed, controller.lastPopupFailure)
        assertEquals(PopupEventKind.CLOSED, controller.latestPopupEvent?.kind)
        controller.recordPopupEvent(PopupDiagnosticEvent(2, PopupEventKind.OPENED))
        assertNull(controller.lastPopupFailure)
        controller.recordPopupEvent(failed) // A stale callback cannot replace the new attempt's status.
        controller.recordPopupEvent(PopupDiagnosticEvent(1, PopupEventKind.CLOSED))
        assertNull(controller.lastPopupFailure)
        assertEquals(PopupDiagnosticEvent(2, PopupEventKind.OPENED), controller.latestPopupEvent)
    }

    private class TestWebView : WebView(Application()) {
        val callbacks = mutableListOf<ValueCallback<String>>()
        val delayed = mutableListOf<Runnable>()
        override fun getUrl(): String = ABEMA_SUMO_REPLAY_URL
        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            if (resultCallback != null) callbacks += resultCallback
        }
        override fun postDelayed(action: Runnable, delayMillis: Long): Boolean {
            delayed += action
            return true
        }
    }

    private fun controller(view: TestWebView): BrowserPaneController =
        BrowserPaneController(AbemaTwitchSingleWebContentsAdapter("arcajazz")).apply {
            attach(view, URI(ABEMA_SUMO_REPLAY_URL))
            pageReady()
        }

    @Test
    fun `newer commands suppress old callbacks for the same pane`() {
        val view = TestWebView()
        val controller = controller(view)
        controller.execute(BrowserCommand.PLAY, COMPOSITE_ABEMA_PANE)
        controller.execute(BrowserCommand.PAUSE, COMPOSITE_ABEMA_PANE)
        view.callbacks[1].onReceiveValue("\"paused\"")
        view.callbacks[0].onReceiveValue("\"playing\"")
        assertEquals("Paused", controller.statusFor(COMPOSITE_ABEMA_PANE))
    }

    @Test
    fun `commands on another pane do not cancel Twitch acknowledgment`() {
        val view = TestWebView()
        val controller = controller(view)
        controller.execute(BrowserCommand.PLAY, COMPOSITE_TWITCH_PANE)
        view.callbacks[0].onReceiveValue("\"pending\"")
        controller.execute(BrowserCommand.PAUSE, COMPOSITE_ABEMA_PANE)
        view.callbacks[1].onReceiveValue("\"paused\"")
        view.delayed.removeAt(0).run()
        view.callbacks[2].onReceiveValue("\"play requested\"")
        assertEquals("Play requested", controller.statusFor(COMPOSITE_TWITCH_PANE))
        assertEquals("Paused", controller.statusFor(COMPOSITE_ABEMA_PANE))
    }

    @Test
    fun `navigation and detach invalidate callbacks and pending polling`() {
        val view = TestWebView()
        val controller = controller(view)
        controller.execute(BrowserCommand.PLAY, COMPOSITE_TWITCH_PANE)
        view.callbacks[0].onReceiveValue("\"pending\"")
        controller.pageLoading()
        view.delayed.removeAt(0).run()
        assertEquals(1, view.callbacks.size)
        assertEquals("Loading provider page", controller.statusFor(COMPOSITE_TWITCH_PANE))
        controller.execute(BrowserCommand.PAUSE, COMPOSITE_ABEMA_PANE)
        controller.detach(view)
        view.callbacks[1].onReceiveValue("\"paused\"")
        assertEquals("Loading provider page", controller.statusFor(COMPOSITE_ABEMA_PANE))
    }

    @Test
    fun `missing acknowledgment times out after bounded polling`() {
        val view = TestWebView()
        val controller = controller(view)
        controller.execute(BrowserCommand.PLAY, COMPOSITE_TWITCH_PANE)
        view.callbacks[0].onReceiveValue("\"pending\"")
        repeat(15) {
            view.delayed.removeAt(0).run()
            view.callbacks.last().onReceiveValue("null")
        }
        assertEquals(0, view.delayed.size)
        assertEquals("Player did not acknowledge the command; use its own controls", controller.statusFor(COMPOSITE_TWITCH_PANE))
    }

    @Test
    fun `diagnostic callbacks are invalidated by same URL reload and newer reads`() {
        val view = TestWebView()
        val controller = controller(view)
        var result = "unset"
        controller.evaluatePlaybackDiagnostic({ _, _ -> "read" }) { result = it ?: "null" }
        controller.pageLoading()
        view.callbacks[0].onReceiveValue("old document")
        assertEquals("unset", result)
        controller.evaluatePlaybackDiagnostic({ _, _ -> "read" }) { result = it ?: "null" }
        controller.evaluatePlaybackDiagnostic({ _, _ -> "read" }) { result = it ?: "null" }
        view.callbacks[2].onReceiveValue("new")
        view.callbacks[1].onReceiveValue("old")
        assertEquals("new", result)
    }
}
