package net.fstab.tachiai.provider.abema

import java.util.concurrent.atomic.AtomicBoolean

// Closed phase names for one isolated ABEMA runtime operation. Each marker is a
// fixed phase, an optional closed public-bundle slot and elapsed milliseconds
// since the operation's preparation began. No identifier, URL, account, route,
// request or response detail is ever part of a marker.
internal enum class AbemaPreparationPhase {
    OWNER_READ, ACCOUNT_ACQUIRED, ROUTE_LEASED,
    BUNDLE_CACHE_HIT, BUNDLE_CACHE_MISS, BUNDLE_DOWNLOADED, BUNDLE_VERIFIED, BUNDLE_READY, BUNDLES_READY,
    FILES_PREPARED, ROUTE_INSTALLED, WEBVIEW_CREATED, PAGE_STARTED, PAGE_LOADED, FIRST_PROVIDER_REQUEST,
}

internal class AbemaPreparationTiming(private val clockMs: () -> Long, private val sink: (String) -> Unit) {
    private val startedMs = clockMs()
    private val firstProviderRequest = AtomicBoolean()

    fun mark(phase: AbemaPreparationPhase, asset: AbemaPublicBundleAsset? = null) {
        val elapsed = (clockMs() - startedMs).coerceAtLeast(0)
        runCatching { sink(if (asset == null) "${phase.name} $elapsed" else "${phase.name} ${asset.name} $elapsed") }
    }

    fun providerRequest() {
        if (firstProviderRequest.compareAndSet(false, true)) mark(AbemaPreparationPhase.FIRST_PROVIDER_REQUEST)
    }

    override fun toString() = "AbemaPreparationTiming(elapsed only)"

    companion object {
        const val TAG = "TachiaiAbemaPreparation"
        val NONE = AbemaPreparationTiming({ 0L }) {}
    }
}
