package net.fstab.tachiai.presentation

internal data class ViewerRetryIntentDecision(val restoreOffset: Boolean, val skipOffsetStartup: Boolean,
    val resumePlayback: Boolean)

// Session-owned retry choices. Transport, provider admission and cleanup remain
// independently checked by the Activity and playback controller.
internal class ViewerRetryIntentPolicy {
    private var offsetRestoreSuppressed = false
    fun backToLiveDispatched() { offsetRestoreSuppressed = true }
    fun offsetToggleRequested(enabled: Boolean) { if (!enabled) offsetRestoreSuppressed = true }
    fun settledOffsetPreference(enabled: Boolean) { offsetRestoreSuppressed = !enabled }

    fun plan(savedOffsetEnabled: Boolean, automaticRestoreOffset: Boolean?, incidentRestoreWanted: Boolean?,
        desiredPlaying: Boolean, siblingRequested: Boolean, explicitPlaybackHeld: Boolean): ViewerRetryIntentDecision =
        ViewerRetryIntentDecision(
            restoreOffset = !offsetRestoreSuppressed && if (automaticRestoreOffset == null) savedOffsetEnabled
                else automaticRestoreOffset || incidentRestoreWanted == true,
            skipOffsetStartup = offsetRestoreSuppressed ||
                automaticRestoreOffset != null && !automaticRestoreOffset && incidentRestoreWanted != true,
            resumePlayback = !explicitPlaybackHeld && (automaticRestoreOffset != null || desiredPlaying || siblingRequested),
        )
}
