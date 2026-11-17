package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.network.ProviderStartupProgress

// UI publication has a shorter lifetime than the provider scope it describes.
// Route admission keeps its original gate after the viewer opens. Only this
// queued selection-page update is retired, including work queued before entry.
internal class ViewerStartupStatus(
    private val post: (() -> Unit) -> Unit,
    private val current: () -> Boolean,
    private val checking: () -> Boolean,
    private val viewerOpen: () -> Boolean,
    private val show: (ProviderStartupProgress) -> Unit,
) {
    var onDiscard: (ProviderStartupProgress) -> Unit = {}

    fun publish(progress: ProviderStartupProgress) {
        post {
            if (current() && checking() && !viewerOpen()) show(progress)
            else onDiscard(progress)
        }
    }
}
