package net.fstab.tachiai.feature.presentation

import java.util.concurrent.atomic.AtomicBoolean
import net.fstab.tachiai.BuildConfig

internal enum class DebugViewerCleanupFaultResult { ARMED, CLEARED, INVALID_REQUEST, UNAVAILABLE }

// Debug builds only (#784): make the next viewer that closes in this process
// report its cleanup as unconfirmed, so "Playback recovery required" and the
// viewer's restart can be checked on a device. The real cleanup still runs.
// Release builds return UNAVAILABLE and never fault a cleanup.
internal object DebugViewerCleanupFaults {
    private val armed = AtomicBoolean()
    fun arm(): DebugViewerCleanupFaultResult {
        if (!BuildConfig.DEBUG) return DebugViewerCleanupFaultResult.UNAVAILABLE
        armed.set(true); return DebugViewerCleanupFaultResult.ARMED
    }
    // Disarms only; a cleanup already reported unconfirmed stays blocked until restart.
    fun clear(): DebugViewerCleanupFaultResult {
        if (!BuildConfig.DEBUG) return DebugViewerCleanupFaultResult.UNAVAILABLE
        armed.set(false); return DebugViewerCleanupFaultResult.CLEARED
    }
    fun take(): Boolean = BuildConfig.DEBUG && armed.getAndSet(false)
}
