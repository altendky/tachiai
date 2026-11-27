package net.fstab.tachiai.platform.network

import android.content.Context
import android.os.SystemClock
import net.fstab.tachiai.feature.connections.legacyProviderSettings
import net.fstab.tachiai.feature.connections.providerInstanceStore

// Construction is memory-only; isCurrent is invoked only by startup workers.
// Foreground cancellation is separate from configuration reconciliation.
internal fun androidProviderRouteStartup(context: Context, pool: ProviderRoutePoolSnapshot,
    onProgress: (ProviderStartupProgress) -> Unit = {}): ProviderRouteStartup =
    androidProviderRouteSwitchStartup(context, pool, emptySet(), onProgress)

// #271: a playback route switch's scope skips routes that failed in its pass.
internal fun androidProviderRouteSwitchStartup(context: Context, pool: ProviderRoutePoolSnapshot,
    skipRouteIdentities: Set<String>, onProgress: (ProviderStartupProgress) -> Unit = {}): ProviderRouteStartup {
    val application = context.applicationContext
    val captured = pool.configuredInstance
    val deadline = Math.addExact(SystemClock.elapsedRealtime(), 45_000L)
    // Each startup gate re-checks the pool (#752); unchanged store revisions
    // keep its last matching verdict. Built on the first worker check.
    val reads by lazy {
        ProviderRouteOwnerReads(providerInstanceStore(application), connectionProfileStore(application),
            { legacyProviderSettings(application) })
    }
    return ProviderRouteStartup(pool, deadline, SystemClock::elapsedRealtime, isCurrent = {
        providerRoutePoolCurrent(reads, pool)
    }, onProgress = onProgress, canPublishLocally = { !ProviderStartupScopeCleanup.isPoisoned(captured.id) },
        skipRouteIdentities = skipRouteIdentities)
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
