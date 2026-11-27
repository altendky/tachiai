package net.fstab.tachiai.feature.connections

import net.fstab.tachiai.platform.network.ProviderStartupCleanupAdmission

internal enum class ProviderCleanupAction { RETRY, RESTART }

internal data class ProviderCleanupNotice(val message: String, val status: String, val action: ProviderCleanupAction)

internal const val PROVIDER_CLEANUP_RESTART_MESSAGE =
    "Connection cleanup could not be confirmed. Restart Tachiai before using this provider again. Saved app data will be kept."
internal const val PROVIDER_CLEANUP_RESTART_FAILED_MESSAGE =
    "Restart could not be completed. This provider stays blocked; saved app data was not cleared."

// What provider details and Manage streams show when the startup cleanup gate
// does not admit a new connection (#764). A deadline can pass with time, so it
// keeps Retry. An unconfirmed cleanup stays blocked until the process restarts,
// so it offers the same restart recovery as the viewer.
internal fun providerCleanupNotice(admission: ProviderStartupCleanupAdmission): ProviderCleanupNotice? = when (admission) {
    ProviderStartupCleanupAdmission.READY, ProviderStartupCleanupAdmission.CANCELLED -> null
    ProviderStartupCleanupAdmission.DEADLINE -> ProviderCleanupNotice(
        "The previous connection has not finished closing. Retry shortly.", "Waiting for connection cleanup",
        ProviderCleanupAction.RETRY)
    ProviderStartupCleanupAdmission.CLEANUP_UNCONFIRMED -> ProviderCleanupNotice(
        PROVIDER_CLEANUP_RESTART_MESSAGE, "Connection cleanup unconfirmed", ProviderCleanupAction.RESTART)
}
