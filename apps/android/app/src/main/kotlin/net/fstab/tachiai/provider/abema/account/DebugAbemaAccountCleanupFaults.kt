package net.fstab.tachiai.provider.abema.account

import java.util.concurrent.atomic.AtomicBoolean
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.platform.network.DebugStartupCleanupFaultResult

// Debug builds only (#783): report the next ABEMA lease session end in the main
// process as unconfirmed, so that instance is poisoned as a slow cancelled
// validation would leave it. The real cleanup still runs normally. Armed from
// the shell through the startup-cleanup fault trigger.
internal object DebugAbemaAccountCleanupFaults {
    private val armed = AtomicBoolean()
    fun arm(): DebugStartupCleanupFaultResult {
        if (!BuildConfig.DEBUG) return DebugStartupCleanupFaultResult.UNAVAILABLE
        armed.set(true); return DebugStartupCleanupFaultResult.ARMED
    }
    // Disarms only; it never clears an instance already poisoned.
    fun clear() { armed.set(false) }
    fun take(): Boolean = BuildConfig.DEBUG && armed.getAndSet(false)
}
