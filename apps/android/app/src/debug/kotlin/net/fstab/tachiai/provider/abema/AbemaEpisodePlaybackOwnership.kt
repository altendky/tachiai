package net.fstab.tachiai.provider.abema

import android.content.Context
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import net.fstab.tachiai.platform.network.connectionProfileStore
import net.fstab.tachiai.feature.connections.providerInstanceStore
import net.fstab.tachiai.feature.connections.legacyProviderSettings
import net.fstab.tachiai.platform.network.ConnectionProfile
import net.fstab.tachiai.platform.diagnostics.FailureReporter
import net.fstab.tachiai.platform.diagnostics.FailureStage
import net.fstab.tachiai.presentation.ProviderInstance

// Expected inputs come from the viewer's frozen route plan. Only checkWorker
// reads protected stores; main admission uses the sticky in-memory result.
internal class AbemaEpisodePlaybackOwnership(private val captured: AbemaCatalogRouteOwner,
    private val readOwner: () -> AbemaCatalogRouteOwner, private val canUse: () -> Boolean,
    private val diagnostics: FailureReporter = FailureReporter.NONE) {
    private val refused = AtomicBoolean()
    fun canContinue(): Boolean = !refused.get() && canUse()
    fun checkWorker(): Boolean {
        if (!canContinue()) return false
        val matched = try { captured.sameOwnership(readOwner()) }
        catch (error: Throwable) { diagnostics.report(FailureStage.CACHED_ABEMA_SOURCE_PREPARE, error); false }
        if (!matched) refused.set(true)
        return matched && canContinue()
    }
    override fun toString() = "AbemaEpisodePlaybackOwnership(redacted)"
}

internal fun androidAbemaEpisodePlaybackOwnership(context: Context, instance: ProviderInstance,
    profile: ConnectionProfile?, canUse: () -> Boolean, diagnostics: FailureReporter): AbemaEpisodePlaybackOwnership {
    val application = context.applicationContext
    val captured = AbemaCatalogRouteOwner(instance, profile)
    return AbemaEpisodePlaybackOwnership(captured, readOwner = {
        check(Looper.myLooper() != Looper.getMainLooper())
        readAbemaCatalogRouteOwner(instance.id,
            { providerInstanceStore(application).read { legacyProviderSettings(application) } },
            { connectionProfileStore(application).selected(it) })
    }, canUse = canUse, diagnostics = diagnostics)
}
