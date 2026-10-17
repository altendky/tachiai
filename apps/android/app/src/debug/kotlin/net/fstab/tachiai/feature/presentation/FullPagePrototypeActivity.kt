package net.fstab.tachiai.feature.presentation

import androidx.media3.common.util.UnstableApi
import net.fstab.tachiai.platform.diagnostics.FailureReporter
import net.fstab.tachiai.platform.media.PrototypeFeedEvent
import net.fstab.tachiai.platform.media.PrototypeFeedSession
import net.fstab.tachiai.provider.abema.PrototypeAbemaSession

/** Retained full-provider-page comparison, reachable only in developer builds. */
@UnstableApi
class FullPagePrototypeActivity : PrototypeActivity() {
    override val useCachedAbema = false

    internal override fun historicalAbemaSession(replay: Boolean, active: () -> Boolean,
        events: (PrototypeFeedEvent) -> Unit, diagnostics: FailureReporter): PrototypeFeedSession =
        PrototypeAbemaSession(this, replay, active, events, diagnostics = diagnostics)
}
