package net.fstab.tachiai.presentation

import org.junit.Assert.*
import org.junit.Test

class ViewerOverlayLayoutTest {
    // Density 1 keeps pixel vectors equal to dp; 16 is the sheet's side padding.
    private fun plan(width: Int, height: Int, landscape: Boolean = true, controls: Boolean = true,
        panel: ViewerPanelSpace? = ViewerPanelSpace.RESERVE, fit: Boolean = true, sheetHeight: Int = 400): ViewerOverlayPlan {
        val open = panel != null
        val side = viewerSideSheet(width, landscape, open, 1f)
        val sheetW = viewerSheetWidth(width, landscape, open, 1f, 16)
        val cap = viewerSheetCap(height, 64, 64)
        return viewerOverlayPlan(width, height, landscape, controls, panel, fit, 64, 64, sheetW, minOf(sheetHeight, cap), side)
    }

    @Test fun basicOverlayNeverResizesTheVideo() {
        for (landscape in listOf(true, false)) for (controls in listOf(true, false)) {
            val result = plan(850, 420, landscape, controls, panel = null)
            assertEquals(ViewerRect(0, 0, 850, 420), result.stage)
            assertNull(result.sheet)
            assertEquals(ViewerRect(0, 0, 850, 64), result.topBar)
            assertEquals(ViewerRect(0, 356, 850, 64), result.bottomBar)
        }
        // Hidden controls leave no reserve in portrait either (formerly 56 dp).
        assertEquals(ViewerRect(0, 0, 400, 800), plan(400, 800, false, false, panel = null).stage)
    }

    @Test fun sideSheetThresholdAndMinimumWidth() {
        assertFalse(viewerSideSheet(639, true, true, 1f))
        assertTrue(viewerSideSheet(640, true, true, 1f))
        assertFalse(viewerSideSheet(850, false, true, 1f))
        assertFalse(viewerSideSheet(850, true, false, 1f))
        assertEquals(639, viewerSheetWidth(639, true, true, 1f, 16))
        // Six 48 dp targets plus padding take precedence over 45% at 640/641.
        assertEquals(304, viewerSheetWidth(640, true, true, 1f, 16))
        assertEquals(304, viewerSheetWidth(641, true, true, 1f, 16))
        assertEquals(360, viewerSheetWidth(850, true, true, 1f, 16))
        assertEquals(850, viewerSheetWidth(850, true, true, 2f, 32)) // Below 640 dp at density 2.
        assertEquals(720, viewerSheetWidth(1700, true, true, 2f, 32))
    }

    @Test fun reservingSideSheetResizesOnlyWithFit() {
        val fit = plan(850, 420)
        assertEquals(ViewerRect(490, 64, 360, 252), fit.sheet)
        assertEquals(ViewerRect(0, 0, 490, 420), fit.stage)
        assertEquals(490, fit.floatingRight)
        val under = plan(850, 420, fit = false)
        assertEquals(ViewerRect(0, 0, 850, 420), under.stage)
        assertEquals(490, under.floatingRight)
        assertEquals(64, under.floatingTop); assertEquals(356, under.floatingBottom)
    }

    @Test fun overlaySheetsFloatOverTheVideoButTheFloatingFeedAvoidsThem() {
        val side = plan(850, 420, panel = ViewerPanelSpace.OVERLAY)
        assertEquals(ViewerRect(0, 0, 850, 420), side.stage)
        assertEquals(490, side.floatingRight)
        val bottom = plan(560, 320, panel = ViewerPanelSpace.OVERLAY, sheetHeight = 100)
        assertEquals(ViewerRect(0, 0, 560, 320), bottom.stage)
        assertEquals(ViewerRect(0, 156, 560, 100), bottom.sheet)
        assertEquals(156, bottom.floatingBottom)
        val portrait = plan(400, 800, false, panel = ViewerPanelSpace.OVERLAY, sheetHeight = 200)
        assertEquals(ViewerRect(0, 0, 400, 800), portrait.stage)
    }

    @Test fun reservingBottomSheetTakesSheetAndTransportSpace() {
        val narrow = plan(560, 320, sheetHeight = 100)
        assertEquals(ViewerRect(0, 156, 560, 100), narrow.sheet)
        assertEquals(ViewerRect(0, 0, 560, 156), narrow.stage)
        assertEquals(156, narrow.floatingBottom)
        // Landscape Fit off lets the video underneath; the floating feed still avoids it.
        val under = plan(560, 320, fit = false, sheetHeight = 100)
        assertEquals(ViewerRect(0, 0, 560, 320), under.stage)
        assertEquals(156, under.floatingBottom)
        // Portrait always reserves, as before.
        val portrait = plan(400, 800, false, fit = false, sheetHeight = 300)
        assertEquals(ViewerRect(0, 0, 400, 436), portrait.stage)
        assertEquals(ViewerRect(0, 436, 400, 300), portrait.sheet)
    }

    @Test fun sheetCapUsesSixtyPercentAndSpaceBetweenBars() {
        assertEquals(480, viewerSheetCap(800, 64, 64))
        assertEquals(192, viewerSheetCap(320, 64, 64))
        assertEquals(0, viewerSheetCap(100, 64, 64))
        // A sheet never extends over either bar.
        val tall = viewerOverlayPlan(560, 320, true, true, ViewerPanelSpace.RESERVE, true, 64, 64, 560, 1_000, false)
        assertEquals(ViewerRect(0, 64, 560, 192), tall.sheet)
    }

    @Test fun hiddenControlsClearFloatingBoundsAndIgnoreThePanel() {
        val hidden = plan(850, 420, controls = false)
        assertEquals(ViewerRect(0, 0, 850, 420), hidden.stage)
        assertNull(hidden.sheet)
        assertEquals(0, hidden.floatingTop); assertEquals(850, hidden.floatingRight); assertEquals(420, hidden.floatingBottom)
    }
}

class ViewerPaneInsetsTest {
    @Test fun noticeContentAvoidsVisibleBarsAndPanelsOnly() {
        // Full landscape pane under a 64 px top bar and transport.
        assertEquals(ViewerInsets(64, 0, 64), viewerPaneInsets(ViewerRect(0, 0, 850, 420), 64, 850, 356))
        // A floating pane already inside the clear range needs nothing.
        assertEquals(ViewerInsets(), viewerPaneInsets(ViewerRect(578, 64, 272, 153), 64, 850, 356))
        // A side panel covers the pane's right edge.
        assertEquals(ViewerInsets(64, 360, 64), viewerPaneInsets(ViewerRect(0, 0, 850, 420), 64, 490, 356))
        // Portrait: feed A under the top bar, feed B clear of a short transport.
        assertEquals(ViewerInsets(64, 0, 0), viewerPaneInsets(ViewerRect(0, 0, 400, 225), 64, 400, 736))
        assertEquals(ViewerInsets(), viewerPaneInsets(ViewerRect(0, 225, 400, 225), 64, 400, 736))
        // Hidden controls clear everything; insets never exceed the pane.
        assertEquals(ViewerInsets(), viewerPaneInsets(ViewerRect(0, 0, 850, 420), 0, 850, 420))
        assertEquals(ViewerInsets(100, 0, 0), viewerPaneInsets(ViewerRect(0, 0, 200, 100), 300, 200, 0))
    }
}

class ViewerStatusNoticesTest {
    @Test fun openingStateIsNotANotice() {
        val notices = ViewerStatusNotices()
        assertNull(notices.observe("No playable feeds.", mapOf("A" to false, "B" to false)))
        assertNull(notices.observe("No playable feeds.", mapOf("A" to false, "B" to false)))
    }

    @Test fun statusChangesAndFeedTransitionsAreNoticedOnce() {
        val notices = ViewerStatusNotices()
        notices.observe("No playable feeds.", mapOf("A" to false, "B" to false))
        assertEquals("A ready\nOne feed prepared paused; press Play.",
            notices.observe("One feed prepared paused; press Play.", mapOf("A" to true, "B" to false)))
        assertNull(notices.observe("One feed prepared paused; press Play.", mapOf("A" to true, "B" to false)))
        assertEquals("B ready", notices.observe("One feed prepared paused; press Play.", mapOf("A" to true, "B" to true)))
        assertEquals("B unavailable", notices.observe("One feed prepared paused; press Play.", mapOf("A" to true, "B" to false)))
        // Surrounding whitespace is not a change; a returning earlier status is.
        assertNull(notices.observe("One feed prepared paused; press Play.\n", mapOf("A" to true, "B" to false)))
        assertEquals("No playable feeds.", notices.observe("No playable feeds.", mapOf("A" to true, "B" to false)))
    }

    // #271 (PR #732): "Now using <route>" stays first when the moved feed's
    // ready transition arrives in the same refresh.
    @Test fun heldAnnouncementLeadsGenericChangesWhileItIsShown() {
        val notices = ViewerStatusNotices()
        notices.observe("One feed prepared paused; press Play.", mapOf("A" to true, "B" to false), 0)
        assertEquals("Now using Tokyo 2", notices.hold("Now using Tokyo 2", 1_000))
        assertEquals("Now using Tokyo 2\nB ready\nPrepared paused; press Play.",
            notices.observe("Prepared paused; press Play.", mapOf("A" to true, "B" to true), 1_000))
        // No new generic change keeps the shown notice as is.
        assertNull(notices.observe("Prepared paused; press Play.", mapOf("A" to true, "B" to true), 2_000))
        assertEquals("Now using Tokyo 2\nPlaying.", notices.observe("Playing.", mapOf("A" to true, "B" to true), 3_999))
        // Once its display time has passed, generic changes show alone.
        assertEquals("Paused.", notices.observe("Paused.", mapOf("A" to true, "B" to true), 4_000))
        notices.hold("Now using Tokyo 2", 5_000); notices.reset()
        notices.observe("Paused.", mapOf("A" to true), 5_000)
        assertEquals("Playing.", notices.observe("Playing.", mapOf("A" to true), 5_001))
    }

    @Test fun volumeWarningIsNoticedWhenItAppears() {
        val notices = ViewerStatusNotices()
        notices.observe("Paused.", emptyMap())
        assertEquals("Volume request failed; shown mix is requested, not confirmed.\nPaused.",
            notices.observe("Volume request failed; shown mix is requested, not confirmed.\nPaused.", emptyMap()))
    }

    @Test fun blankStatusIsIgnoredAndResetStartsAgain() {
        val notices = ViewerStatusNotices()
        assertNull(notices.observe(" ", mapOf("A" to true)))
        assertNull(notices.observe("Paused.", mapOf("A" to true)))
        assertEquals("A unavailable", notices.observe(null, mapOf("A" to false)))
        notices.reset()
        assertNull(notices.observe("Stopped.", mapOf("A" to true)))
        assertTrue(ViewerStatusNotices.SHOWN_MS in 2_000L..4_000L)
    }
}
