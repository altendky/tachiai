package net.fstab.tachiai.provider.abema

import android.content.Context
import android.os.Looper
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.FileLockInterruptionException
import java.util.concurrent.atomic.AtomicBoolean
import net.fstab.tachiai.platform.network.connectionProfileStore
import net.fstab.tachiai.feature.connections.providerInstanceStore
import net.fstab.tachiai.feature.connections.legacyProviderSettings
import net.fstab.tachiai.platform.network.ConnectionProfile
import net.fstab.tachiai.platform.network.ProviderRouteOwnerReads
import net.fstab.tachiai.platform.network.ProviderRoutePoolException
import net.fstab.tachiai.platform.diagnostics.FailureReporter
import net.fstab.tachiai.platform.diagnostics.FailureStage
import net.fstab.tachiai.presentation.ProviderInstance

// Expected inputs come from the viewer's frozen route plan. Only checkWorker
// reads protected stores; main admission uses the sticky in-memory result.
internal class AbemaEpisodePlaybackOwnership(private val captured: AbemaCatalogRouteOwner,
    private val readOwner: () -> AbemaCatalogRouteOwner, private val canUse: () -> Boolean,
    private val diagnostics: FailureReporter = FailureReporter.NONE) {
    private val refused = AtomicBoolean()
    val instanceId get() = captured.instance.id
    val ownerProof get() = captured.proof()
    fun canContinue(): Boolean = !refused.get() && canUse()
    fun checkWorker(): Boolean {
        if (Thread.currentThread().isInterrupted || !canContinue()) return false
        val matched = try { captured.sameOwnership(readOwner()) }
        catch (error: Throwable) {
            // Media3 can cancel a loader while its protected owner read waits
            // for a file lock. Refuse that request without revoking the last
            // verified binding; its next request must still reconcile the owner.
            if (cancelledOwnerRead(error)) return false
            diagnostics.report(FailureStage.CACHED_ABEMA_SOURCE_PREPARE, error)
            false
        }
        if (!matched) refused.set(true)
        return matched && !Thread.currentThread().isInterrupted && canContinue()
    }
    override fun toString() = "AbemaEpisodePlaybackOwnership(redacted)"
}

private fun cancelledOwnerRead(error: Throwable): Boolean {
    var cause: Throwable? = error
    var interrupted = Thread.currentThread().isInterrupted
    var routeFailure = false
    repeat(8) {
        val current = cause ?: return interrupted && !routeFailure
        if (current is ProviderRoutePoolException) routeFailure = true
        if (current is InterruptedException || current is ClosedByInterruptException || current is FileLockInterruptionException) {
            Thread.currentThread().interrupt()
            interrupted = true
        }
        val next = current.cause
        if (next === current) return false
        cause = next
    }
    // An incomplete or cyclic chain cannot establish cancellation alone.
    return cause == null && interrupted && !routeFailure
}

internal fun androidAbemaEpisodePlaybackOwnership(context: Context, instance: ProviderInstance,
    profile: ConnectionProfile?, canUse: () -> Boolean, diagnostics: FailureReporter,
    expectedProof: String? = null): AbemaEpisodePlaybackOwnership {
    val application = context.applicationContext
    // Each media request re-checks this owner (#745); unchanged store
    // revisions keep its last verdict. Built on the first worker read.
    val reads by lazy {
        ProviderRouteOwnerReads(providerInstanceStore(application), connectionProfileStore(application),
            { legacyProviderSettings(application) })
    }
    // This factory runs on main. Its inputs are the viewer's worker-built frozen
    // plan; only checkWorker reads protected state. Legacy single-route callers
    // can derive a candidate proof solely from those already-captured inputs.
    check(expectedProof != null || instance.setup.routes.size == 1)
    val captured = if (expectedProof != null) AbemaCatalogRouteOwner(instance, profile, expectedProof)
        else captureAbemaCatalogRouteOwner(instance.id, { listOf(instance) }, { ids ->
            if (profile == null) emptyMap() else ids.associateWith { profile }
        })
    return AbemaEpisodePlaybackOwnership(captured, readOwner = {
        check(Looper.myLooper() != Looper.getMainLooper())
        abemaCatalogRouteOwnerRead(reads, captured, instance.id, captured.proof())
    }, canUse = canUse, diagnostics = diagnostics)
}
