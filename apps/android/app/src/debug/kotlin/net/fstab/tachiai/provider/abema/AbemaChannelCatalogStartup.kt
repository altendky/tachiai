package net.fstab.tachiai.provider.abema

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*

// Worker-owned startup evidence, captured before the generic catalog converts
// exceptions to TEMPORARY. No exception text, provider body or URL is retained.
internal class AbemaChannelStartupObservation {
    private val current = AtomicReference<(() -> Boolean)?>(null)
    private val cleanup = AtomicReference<() -> Boolean>({ true })
    private val status = AtomicReference<Int?>(null)
    private val transport = AtomicReference<ProviderTransportEvidence?>(null)
    fun bindCurrent(admit: () -> Boolean) { check(current.compareAndSet(null, admit)) }
    fun own(checkCleanup: () -> Boolean) { cleanup.set(checkCleanup) }
    fun response(code: Int) { status.set(code) }
    fun failure(evidence: ProviderTransportEvidence?) { transport.set(evidence) }
    fun isCurrent() = runCatching { current.get()?.invoke() == true }.getOrDefault(false)
    fun cleanupConfirmed() = runCatching { cleanup.get().invoke() }.getOrDefault(false)
    val successfulGet get() = status.get() == 200
    fun rejection(result: CatalogResult.Failure): ProviderStartupRejection {
        val evidence = transport.get()
        return when {
            evidence != null -> ProviderStartupRejection(
                if (evidence.failure == ProviderTransportFailure.TLS) ProviderStartupFailure.TLS
                else ProviderStartupFailure.TRANSPORT, evidence)
            result.reason == CatalogFailure.ACCESS_REQUIRED -> ProviderStartupRejection(ProviderStartupFailure.AUTHENTICATION)
            result.reason == CatalogFailure.RATE_LIMITED -> ProviderStartupRejection(ProviderStartupFailure.RATE_LIMITED)
            result.reason == CatalogFailure.INVALID_INPUT -> ProviderStartupRejection(ProviderStartupFailure.MALFORMED)
            status.get() != null -> ProviderStartupRejection(ProviderStartupFailure.HTTP)
            else -> ProviderStartupRejection(ProviderStartupFailure.UNKNOWN)
        }
    }
}

internal class AbemaChannelStartupCandidate(val catalog: ProviderCatalog, val observation: AbemaChannelStartupObservation)

// The first real Channels response is also the controller's first page. Its
// adapter cursor stays in the retained catalog; another operation discards this
// one-shot page so a stale continuation can never be resurrected.
internal class PreparedAbemaChannelCatalog(private val delegate: ProviderCatalog,
    private val observation: AbemaChannelStartupObservation) : ProviderCatalog by delegate, ProviderStartupLease {
    private val first = AtomicReference<CatalogPage?>(null)
    private val closed = AtomicBoolean()
    private val closeCompleted = AtomicBoolean()
    private val failedClose = AtomicBoolean()
    fun retain(page: CatalogPage) { check(first.compareAndSet(null, page)) }
    override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> {
        if (closed.get()) return CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED)
        val page = first.getAndSet(null)
        if (page != null && query == CatalogQuery()) return if (observation.isCurrent()) CatalogResult.Value(page)
            else CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED)
        return delegate.browse(query)
    }
    override fun lookup(input: String): CatalogResult<CatalogEntry> { first.set(null); return delegate.lookup(input) }
    override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> { first.set(null); return delegate.refresh(resource) }
    override fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource> { first.set(null); return delegate.resolve(resource) }
    // Production catalog close only revokes admission and signals its workers.
    override fun cancel() { runCatching { close() } }
    override fun close() {
        first.set(null)
        if (closed.compareAndSet(false, true)) try { delegate.close() } catch (_: Throwable) {
            failedClose.set(true); throw java.io.IOException("Catalog cleanup remains unconfirmed")
        } finally { closeCompleted.set(true) }
    }
    override fun closeAndConfirm(): Boolean { runCatching { close() }; return closeCompleted.get() && !failedClose.get() && observation.cleanupConfirmed() }
    override fun toString() = "PreparedAbemaChannelCatalog(redacted)"
}

internal fun prepareAbemaChannelCatalogStartup(startup: ProviderRouteStartup,
    create: (ProviderEffectiveRoute, ProviderStartupAttemptContext) -> AbemaChannelStartupCandidate): ProviderStartupResult<ProviderCatalog> =
    startup.runReadOnly { owner, context ->
        val candidate = create(owner, context)
        val prepared = context.own(PreparedAbemaChannelCatalog(candidate.catalog, candidate.observation))
        val result = prepared.browse(CatalogQuery())
        when {
            !context.canContinue() -> ProviderStartupAttempt.Rejected(ProviderStartupRejection(ProviderStartupFailure.CANCELLED))
            !candidate.observation.isCurrent() -> ProviderStartupAttempt.Rejected(ProviderStartupRejection(ProviderStartupFailure.REVOKED))
            !candidate.observation.cleanupConfirmed() -> ProviderStartupAttempt.Rejected(ProviderStartupRejection(ProviderStartupFailure.CLEANUP_UNCONFIRMED))
            result is CatalogResult.Value && candidate.observation.successfulGet -> {
                prepared.retain(result.value)
                ProviderStartupAttempt.Admitted<ProviderCatalog>(prepared, prepared)
            }
            result is CatalogResult.Failure -> ProviderStartupAttempt.Rejected(candidate.observation.rejection(result))
            else -> ProviderStartupAttempt.Rejected(ProviderStartupRejection(ProviderStartupFailure.UNKNOWN))
        }
    }

internal fun prepareAndroidAbemaChannelCatalogStartup(context: Context, instanceId: String,
    setups: Map<PrototypeSource, SourceSetup>, startup: ProviderRouteStartup,
    canUse: () -> Boolean = { true }): ProviderStartupResult<ProviderCatalog> =
    prepareAbemaChannelCatalogStartup(startup) { owner, attempt ->
        check(owner.configuredInstance.id == instanceId && owner.configuredInstance.service == PrototypeService.ABEMA)
        val observation = AbemaChannelStartupObservation()
        val catalog = androidAbemaChannelCatalog(context, instanceId, setups,
            canUse = { canUse() && attempt.canContinue() }, expectedOwnerProof = owner.proof, startupObservation = observation)
        AbemaChannelStartupCandidate(catalog, observation)
    }
