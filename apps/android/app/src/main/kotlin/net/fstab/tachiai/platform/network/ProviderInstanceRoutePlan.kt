package net.fstab.tachiai.platform.network

import net.fstab.tachiai.presentation.*

internal class ProviderInstanceRoutePlan(val profiles: Map<String, Result<ConnectionProfile?>>, val abemaCompatible: Boolean,
    val effective: Map<String, Result<ProviderEffectiveRoute>> = emptyMap())

internal fun captureProviderInstanceRoutePools(selection: ConfiguredPlaybackSelection,
    readInstances: () -> List<ProviderInstance>,
    readProfiles: (Set<String>) -> Map<String, ConnectionProfile>): Map<String, Result<ProviderRoutePoolSnapshot>> =
    selection.feeds.distinctBy { it.instanceId }.associate { feed -> feed.instanceId to runCatching {
        captureProviderRoutePool(feed.instanceId, feed.service, readInstances, readProfiles).also { pool ->
            check(selection.feeds.filter { it.instanceId == feed.instanceId }
                .all { it.resolve(listOf(pool.configuredInstance)) != null })
        }
    } }

// A viewer captures one pool per distinct provider instance before account
// readiness. Duplicate feeds share that choice; no backend is created here.
internal fun captureProviderInstanceRoutes(selection: ConfiguredPlaybackSelection,
    readInstances: () -> List<ProviderInstance>,
    readProfiles: (Set<String>) -> Map<String, ConnectionProfile>): ProviderInstanceRoutePlan {
    val effective = selection.feeds.distinctBy { it.instanceId }.associate { feed -> feed.instanceId to runCatching {
        val captured = captureProviderRoutePool(feed.instanceId, feed.service, readInstances, readProfiles).resolve()
        check(selection.feeds.filter { it.instanceId == feed.instanceId }
            .all { it.resolve(listOf(captured.configuredInstance)) != null })
        captured
    } }
    return effectiveRoutePlan(selection, effective)
}

// Account handoff never authorizes a newer pool. Reconcile only exact captured
// choices, and retain a failed capture until the user starts a new viewer run.
internal fun revalidateProviderInstanceRoutes(selection: ConfiguredPlaybackSelection, captured: ProviderInstanceRoutePlan,
    readInstances: () -> List<ProviderInstance>,
    readProfiles: (Set<String>) -> Map<String, ConnectionProfile>): ProviderInstanceRoutePlan {
    val ids = selection.feeds.map { it.instanceId }.toSet()
    if (captured.effective.keys != ids) return effectiveRoutePlan(selection, ids.associateWith {
        Result.failure(ProviderRoutePoolException(ProviderRoutePoolFailure.OWNER_MISMATCH))
    })
    return effectiveRoutePlan(selection, captured.effective.mapValues { (id, result) -> result.mapCatching { previous ->
        check(selection.feeds.filter { it.instanceId == id }.all { it.resolve(listOf(previous.configuredInstance)) != null })
        captureProviderRoutePool(previous.configuredInstance.id, previous.configuredInstance.service,
            readInstances, readProfiles).resolve(previous.proof)
    } })
}

internal fun effectiveRoutePlan(selection: ConfiguredPlaybackSelection,
    effective: Map<String, Result<ProviderEffectiveRoute>>): ProviderInstanceRoutePlan {
    val profiles = effective.mapValues { (_, owner) -> owner.map { it.profile } }
    val abema = selection.feeds.filter { it.service == PrototypeService.ABEMA }.map { it.instanceId }.distinct()
    val keys = abema.map { id -> effective[id]?.getOrNull()?.let {
        it.profile?.let(::routeConfigurationKey) ?: "SYSTEM"
    } }
    // The WebView proxy applies to every ABEMA source, including configured
    // channels and broadcasts that have no historical prototype source.
    val compatible = abema.size <= 1 || keys.all { it != null } && keys.distinct().size == 1
    return ProviderInstanceRoutePlan(profiles, compatible, effective)
}

internal fun planProviderInstanceRoutes(selection: ConfiguredPlaybackSelection, instances: List<ProviderInstance>,
    readProfile: (String) -> ConnectionProfile): ProviderInstanceRoutePlan {
    val profiles = selection.feeds.distinctBy { it.instanceId }.associate { feed -> feed.instanceId to runCatching {
        check(selection.feeds.filter { it.instanceId == feed.instanceId }.all { it.resolve(instances) != null })
        val instance = checkNotNull(feed.resolve(instances))
        val choice = checkNotNull(instance.setup.route)
        when (choice.mode) {
            SourceRouteMode.SYSTEM -> null
            SourceRouteMode.SAVED_CONNECTION -> readProfile(checkNotNull(choice.connectionId))
            SourceRouteMode.SOURCE_DEFAULT -> error("Provider instance cannot inherit a stream route")
        }
    } }
    val keys = profiles.filterValues { it.isSuccess }.mapValues { (_, profile) ->
        profile.getOrNull()?.let(::routeConfigurationKey) ?: "SYSTEM"
    }
    val historicalFeeds = selection.feeds.mapNotNull { feed ->
        feed.historicalSource?.let { PrototypeFeedChoice(it, feed.instanceId) }
    }
    return ProviderInstanceRoutePlan(profiles, compatibleAbemaInstanceRoutes(historicalFeeds, keys))
}

// Worker-only snapshot/preflight. No RouteSession, socket or provider request is
// created until the ABEMA process-wide proxy constraint has been checked.
internal fun planProviderInstanceRoutes(selection: PrototypeSelection, instances: List<ProviderInstance>,
    readProfile: (String) -> ConnectionProfile): ProviderInstanceRoutePlan {
    val profiles = selection.feeds.distinctBy { it.instanceId }.associate { feed -> feed.instanceId to runCatching {
        check(selection.feeds.filter { it.instanceId == feed.instanceId }.all { it.resolve(instances) != null })
        val instance = checkNotNull(feed.resolve(instances))
        val choice = checkNotNull(instance.setup.route)
        val profile = when (choice.mode) {
            SourceRouteMode.SYSTEM -> null
            SourceRouteMode.SAVED_CONNECTION -> readProfile(checkNotNull(choice.connectionId))
            SourceRouteMode.SOURCE_DEFAULT -> error("Provider instance cannot inherit a stream route")
        }
        profile
    } }
    val keys = profiles.filterValues { it.isSuccess }.mapValues { (_, profile) ->
        profile.getOrNull()?.let(::routeConfigurationKey) ?: "SYSTEM"
    }
    return ProviderInstanceRoutePlan(profiles, compatibleAbemaInstanceRoutes(selection.feeds, keys))
}
