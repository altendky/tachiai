package net.fstab.tachiai.provider.twitch.catalog

import android.content.Context
import net.fstab.tachiai.feature.connections.*
import net.fstab.tachiai.platform.diagnostics.*
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.provider.catalog.*
import net.fstab.tachiai.presentation.*

// UI-owner scoped, transient rate hints only. No grants, aliases or provider
// responses survive here, and an explicit retry never waits automatically.
internal class TwitchBroadcasterRetryGate(private val wallMs: () -> Long = System::currentTimeMillis) {
    private val deadlines = mutableMapOf<String, Long>()
    @Synchronized fun retryAt(instanceId: String): Long? {
        val deadline = deadlines[instanceId] ?: return null
        if (wallMs() >= deadline) { deadlines.remove(instanceId); return null }
        return deadline
    }
    fun isBlocked(instanceId: String): Boolean = retryAt(instanceId) != null
    @Synchronized internal fun observe(instanceId: String, result: CatalogResult<*>) {
        if (result !is CatalogResult.Failure || result.reason != CatalogFailure.RATE_LIMITED) return
        val now = wallMs().coerceAtLeast(0)
        val maximum = if (now > Long.MAX_VALUE - 86_400_000L) Long.MAX_VALUE else now + 86_400_000L
        val deadline = (result.retryAtEpochMs ?: minOf(maximum,
            if (now > Long.MAX_VALUE - 60_000L) Long.MAX_VALUE else now + 60_000L)).coerceIn(now, maximum)
        if (deadlines.size >= 32 && instanceId !in deadlines) deadlines.entries.removeAll { it.value <= now }
        // A viewer can have at most two instances; refuse excess rather than
        // forgetting an unexpired rate hint owned by another active instance.
        check(deadlines.size < 32 || instanceId in deadlines)
        deadlines[instanceId] = maxOf(deadlines[instanceId] ?: 0, deadline)
    }
}

// Construction and every identity exchange run on the preparation worker.
// The expected native route was frozen by the viewer; metadata creates its own
// closed-purpose route and cannot silently use a newly selected connection.
internal fun androidTwitchLiveIdentityResolver(context: Context, instanceId: String,
    expectedProfile: ConnectionProfile?, canUse: () -> Boolean,
    retryGate: TwitchBroadcasterRetryGate? = null,
    expectedRoute: SourceRouteChoice? = null): TwitchLiveIdentityResolver {
    val application = context.applicationContext
    val diagnostics = FailureDiagnostics.create(application)
    val route = readTwitchCatalogRouteOwner(instanceId,
        { providerInstanceStore(application).read { legacyProviderSettings(application) } },
        { connectionProfileStore(application).selected(it) })
    requireExpectedTwitchRoute(route, expectedRoute)
    val binding = androidTwitchCatalogConnectionBinding(application, instanceId, canUse)
    return try {
        ownedTwitchLiveIdentityResolver(instanceId, expectedProfile, route, binding, canUse,
            { profile, gate, cleanup -> OwnedTwitchHelixTransport(profile, gate,
                onCleanupFailure = cleanup, diagnostics = diagnostics) }, retryGate, diagnostics, expectedRoute)
    } catch (error: Throwable) {
        runCatching { binding.close() }.onFailure { diagnostics.report(FailureStage.CATALOG_CLOSE, it) }
        throw error
    }
}

// Narrow binding seam also exercised by provider-free Android fixtures. Both
// owner rereads and session checks remain worker calls; publication uses only
// the separate memory admission callback and core's local lease check.
internal fun ownedTwitchLiveIdentityResolver(instanceId: String, expectedProfile: ConnectionProfile?,
    route: TwitchCatalogRouteOwner, binding: TwitchCatalogConnectionBinding, canUse: () -> Boolean,
    transportFactory: (ConnectionProfile?, () -> Boolean, () -> Unit) -> TwitchHelixTransport,
    retryGate: TwitchBroadcasterRetryGate? = null,
    diagnostics: FailureReporter = FailureReporter.NONE,
    expectedRoute: SourceRouteChoice? = null,
    wallMs: () -> Long = System::currentTimeMillis): TwitchLiveIdentityResolver {
    require(route.instance.id == instanceId && route.instance.service == PrototypeService.TWITCH)
    requireExpectedTwitchRoute(route, expectedRoute)
    check((route.profile?.let(::routeConfigurationKey) ?: "SYSTEM") ==
        (expectedProfile?.let(::routeConfigurationKey) ?: "SYSTEM"))
    val captured = binding.owner()
    check(captured.routeUsable && captured.sameOwnership(route.presentation))
    val lock = Any()
    var closed = false
    var cleanupFailed = false
    fun local(): Boolean = canUse() && !synchronized(lock) { closed || cleanupFailed } &&
        !TwitchCatalogConnectionWrites.isPending(instanceId) && !TwitchCatalogConnectionWrites.isFailed(instanceId) &&
        binding.canPublishLocally()
    fun current(): Boolean = local() && runCatching { captured.sameOwnership(binding.owner()) }
        .onFailure { diagnostics.report(FailureStage.CATALOG_LOAD, it) }.getOrDefault(false)
    val catalog = TwitchProviderCatalog(instanceId, binding.session(),
        transportFactory = { gate -> transportFactory(route.profile, { gate() && current() },
            { synchronized(lock) { cleanupFailed = true } }) }, canPublish = ::current, wallMs = wallMs, canPublishLocally = ::local)
    return object : TwitchLiveIdentityResolver {
        override fun begin(resource: CatalogResource): CatalogResult<TwitchLiveIdentity> {
            if (!local()) return CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED)
            retryGate?.retryAt(instanceId)?.let { return CatalogResult.Failure(CatalogFailure.RATE_LIMITED, it) }
            return catalog.begin(resource).also { retryGate?.observe(instanceId, it) }
        }
        override fun confirm(identity: TwitchLiveIdentity): CatalogResult<Unit> {
            if (!local()) return CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED)
            retryGate?.retryAt(instanceId)?.let { return CatalogResult.Failure(CatalogFailure.RATE_LIMITED, it) }
            return catalog.confirm(identity).also { retryGate?.observe(instanceId, it) }
        }
        override fun canPublish(identity: TwitchLiveIdentity): Boolean = local() && catalog.canPublish(identity)
        // Completed exchanges already released routes/HTTP on their workers.
        // Close only invalidates memory and signals/schedules cancellation;
        // neither binding nor core close rereads protected storage or joins IO.
        override fun close() {
            if (!synchronized(lock) { if (closed) false else { closed = true; true } }) return
            try { catalog.close() } finally { binding.close() }
        }
        override fun toString() = "AndroidTwitchLiveIdentityResolver(redacted)"
    }
}

private fun requireExpectedTwitchRoute(route: TwitchCatalogRouteOwner, expected: SourceRouteChoice?) {
    if (expected == null) return
    val actual = checkNotNull(route.instance.setup.route)
    check(actual.mode == expected.mode && actual.connectionId == expected.connectionId)
}
