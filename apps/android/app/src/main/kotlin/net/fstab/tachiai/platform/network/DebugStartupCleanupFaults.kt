package net.fstab.tachiai.platform.network

import java.util.concurrent.atomic.AtomicBoolean
import net.fstab.tachiai.BuildConfig

internal enum class DebugStartupCleanupFaultResult { ARMED, CLEARED, INVALID_REQUEST, UNAVAILABLE }

// Debug builds only (#764): make the next retired startup scope in this process
// poison its instance as if its cleanup were unconfirmed, so the provider
// details restart recovery can be checked on a device. The real scope still
// closes normally. Only a process restart clears the resulting poison.
internal object DebugStartupCleanupFaults {
    private val armed = AtomicBoolean()
    fun arm(): DebugStartupCleanupFaultResult {
        if (!BuildConfig.DEBUG) return DebugStartupCleanupFaultResult.UNAVAILABLE
        armed.set(true); return DebugStartupCleanupFaultResult.ARMED
    }
    // Disarms only; it never clears an instance already poisoned.
    fun clear(): DebugStartupCleanupFaultResult {
        if (!BuildConfig.DEBUG) return DebugStartupCleanupFaultResult.UNAVAILABLE
        armed.set(false); return DebugStartupCleanupFaultResult.CLEARED
    }
    fun take(): Boolean = BuildConfig.DEBUG && armed.getAndSet(false)
}
