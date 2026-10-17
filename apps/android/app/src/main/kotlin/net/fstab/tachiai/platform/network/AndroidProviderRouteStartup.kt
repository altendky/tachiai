package net.fstab.tachiai.platform.network

import android.content.Context
import android.os.SystemClock
import net.fstab.tachiai.feature.connections.legacyProviderSettings
import net.fstab.tachiai.feature.connections.providerInstanceStore

// Construction is memory-only; isCurrent is invoked only by startup workers.
// Foreground cancellation is separate from configuration reconciliation.
internal fun androidProviderRouteStartup(context: Context, pool: ProviderRoutePoolSnapshot,
    onProgress: (ProviderStartupProgress) -> Unit = {}): ProviderRouteStartup {
    val application = context.applicationContext
    val captured = pool.configuredInstance
    val deadline = Math.addExact(SystemClock.elapsedRealtime(), 45_000L)
    return ProviderRouteStartup(pool, deadline, SystemClock::elapsedRealtime, isCurrent = {
        captureProviderRoutePool(captured.id, captured.service,
            { providerInstanceStore(application).read { legacyProviderSettings(application) } },
            connectionProfileStore(application)::snapshot).proof == pool.proof
    }, onProgress = onProgress, canPublishLocally = { !ProviderStartupScopeCleanup.isPoisoned(captured.id) })
}

internal fun providerStartupStatus(progress: ProviderStartupProgress): String = when (progress.phase) {
    ProviderStartupPhase.IDLE -> "Waiting for route"
    ProviderStartupPhase.TRYING -> "Trying ${progress.ordinal} of ${progress.count} · ${progress.label.orEmpty()}"
    ProviderStartupPhase.CLEANING -> "Closing route ${progress.ordinal}"
    ProviderStartupPhase.SELECTED -> "Using ${progress.label.orEmpty()}"
    ProviderStartupPhase.HELD_FOR_MUTATION -> "Using ${progress.label.orEmpty()} · sign-in"
    ProviderStartupPhase.CANCELLED -> "Connection cancelled"
    ProviderStartupPhase.EXPIRED -> "Connection timed out"
    ProviderStartupPhase.REVOKED -> "Routes changed · retry"
    ProviderStartupPhase.CLEANUP_UNCONFIRMED -> "Connection cleanup unconfirmed"
    ProviderStartupPhase.FAILED -> if (progress.exhausted) "All configured routes failed" else "Connection stopped"
}
