package net.fstab.tachiai.feature.presentation

import androidx.media3.common.util.UnstableApi
import net.fstab.tachiai.platform.diagnostics.FailureReporter
import net.fstab.tachiai.platform.media.PrototypeFeedEvent
import net.fstab.tachiai.platform.media.PrototypeFeedSession
import net.fstab.tachiai.presentation.ConfiguredSource
import net.fstab.tachiai.presentation.PrototypeSource
import net.fstab.tachiai.presentation.ProviderInstance
import net.fstab.tachiai.presentation.configuredLegacyId
import net.fstab.tachiai.presentation.prototypeCatalogResource
import net.fstab.tachiai.provider.abema.PrototypeAbemaSession
import net.fstab.tachiai.provider.catalog.CatalogEntry
import net.fstab.tachiai.provider.catalog.CatalogResource

/** Retained full-provider-page comparison, reachable only in developer builds. */
@UnstableApi
class FullPagePrototypeActivity : PrototypeActivity() {
    override val useCachedAbema = false

    // The page-backed session plays only these fixed public samples, so this
    // debug comparison lists them instead of configured ABEMA items.
    internal override fun comparisonAbemaSources(instance: ProviderInstance): List<ConfiguredSource> =
        listOf(PrototypeSource.ABEMA_LIVE, PrototypeSource.ABEMA_REPLAY).map {
            ConfiguredSource(configuredLegacyId(instance.id, it), instance.id, CatalogEntry(prototypeCatalogResource(it), it.title))
        }

    internal override fun comparisonAbemaSession(resource: CatalogResource, replay: Boolean, active: () -> Boolean,
        events: (PrototypeFeedEvent) -> Unit, diagnostics: FailureReporter): PrototypeFeedSession {
        check(resource == prototypeCatalogResource(if (replay) PrototypeSource.ABEMA_REPLAY else PrototypeSource.ABEMA_LIVE))
        return PrototypeAbemaSession(this, replay, active, events, diagnostics = diagnostics)
    }
}
