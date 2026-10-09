package net.fstab.tachiai.platform.network

import net.fstab.tachiai.presentation.*

internal class ProviderInstanceRoutePlan(val profiles: Map<String, Result<ConnectionProfile?>>, val abemaCompatible: Boolean)

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
