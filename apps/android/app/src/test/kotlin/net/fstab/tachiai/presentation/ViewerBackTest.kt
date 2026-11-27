package net.fstab.tachiai.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerBackTest {
    @Test fun backWhileWatchStartsBeforeTheViewerExistsAsksToStop() {
        // Watch is checking provider connections: no viewer and no Back handler of its own yet.
        assertEquals(ViewerBackAction.CONFIRM_STOP,
            viewerBackAction(viewerHandled = false, viewerOpen = false, viewerShown = false, starting = true))
    }

    @Test fun backInTheOpenViewerUnwindsItsControlsThenAsksToStop() {
        assertEquals(ViewerBackAction.HANDLED,
            viewerBackAction(viewerHandled = true, viewerOpen = true, viewerShown = true, starting = false))
        assertEquals(ViewerBackAction.CONFIRM_STOP,
            viewerBackAction(viewerHandled = false, viewerOpen = true, viewerShown = true, starting = false))
        // The viewer is open before its window attaches; it handles nothing then and Back still asks.
        assertEquals(ViewerBackAction.CONFIRM_STOP,
            viewerBackAction(viewerHandled = false, viewerOpen = true, viewerShown = true, starting = true))
    }

    @Test fun backOnThePreparationPageStopsWithoutAsking() {
        assertEquals(ViewerBackAction.STOP,
            viewerBackAction(viewerHandled = false, viewerOpen = true, viewerShown = false, starting = false))
    }

    @Test fun onlyTheIdlePickerLeavesTheApp() {
        assertEquals(ViewerBackAction.FINISH,
            viewerBackAction(viewerHandled = false, viewerOpen = false, viewerShown = false, starting = false))
    }
}
