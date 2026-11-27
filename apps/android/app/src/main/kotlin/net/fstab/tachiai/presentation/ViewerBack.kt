package net.fstab.tachiai.presentation

internal enum class ViewerBackAction { HANDLED, CONFIRM_STOP, STOP, FINISH }

// System Back from Watch until playback stops (#617, #772). The open viewer
// unwinds its own menu, panel and overlay first. Then Back asks
// "Stop playback?", also while Watch is still checking provider connections
// and no viewer exists yet, so Back never closes the app task during startup.
// The preparation page the viewer's Status opens stops without asking, as before.
// Only the idle picker leaves the app.
internal fun viewerBackAction(viewerHandled: Boolean, viewerOpen: Boolean, viewerShown: Boolean,
    starting: Boolean): ViewerBackAction = when {
    viewerHandled -> ViewerBackAction.HANDLED
    viewerOpen && !viewerShown -> ViewerBackAction.STOP
    viewerOpen || starting -> ViewerBackAction.CONFIRM_STOP
    else -> ViewerBackAction.FINISH
}
