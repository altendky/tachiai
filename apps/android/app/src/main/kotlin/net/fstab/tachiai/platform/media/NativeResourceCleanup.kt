package net.fstab.tachiai.platform.media

// Cancellation must precede player release, but a failed cancellation must not
// strand the player/DRM or skip the remaining owned resources. Keep the first
// failure so callers still retain their unconfirmed-cleanup admission gate.
internal fun closeNativeResources(vararg actions: () -> Unit) {
    var failure: Throwable? = null
    actions.forEach { action ->
        try { action() } catch (error: Throwable) {
            val first = failure
            if (first == null) failure = error
            else if (first !== error) first.addSuppressed(error)
        }
    }
    failure?.let { throw it }
}
