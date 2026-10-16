package net.fstab.tachiai.presentation

internal const val MAX_PROVIDER_ROUTES = 8

// Saved alternatives are explicit configuration, not evidence of an active route.
// Historical previousRoutes require review and never participate in this pool.
internal data class ProviderSetup(
    val route: SourceRouteChoice?,
    val previousRoutes: List<SourceRouteChoice> = emptyList(),
    val fallbackRoutes: List<SourceRouteChoice> = emptyList(),
) {
    val routes get() = listOfNotNull(route) + fallbackRoutes
    init {
        require(route != null || fallbackRoutes.isEmpty())
        require(routes.size <= MAX_PROVIDER_ROUTES)
        require(routes.none { it.mode == SourceRouteMode.SOURCE_DEFAULT })
        require(routes.distinctBy { it.mode to it.connectionId }.size == routes.size)
    }
    val title get() = route?.title ?: "Route choice required"
}

internal fun legacyProviderSetups(streams: Map<PrototypeSource, SourceSetup>): Map<PrototypeService, ProviderSetup> =
    PrototypeService.entries.associateWith { provider ->
        val settings = PrototypeSource.entries.filter { it.service == provider }.map { checkNotNull(streams[it]) }
        val routes = settings.flatMap { setup -> listOf(setup.defaultRoute) + PrototypeSlot.entries.map(setup::route) }
            .distinctBy { it.mode to it.connectionId }
        // Explicit old feed overrides need review even when their current effective routes agree.
        val overrides = settings.any { it.feedA.mode != SourceRouteMode.SOURCE_DEFAULT || it.feedB.mode != SourceRouteMode.SOURCE_DEFAULT }
        ProviderSetup(if (routes.size == 1 && !overrides) routes.single() else null, routes)
    }

internal fun unsupportedProviderRoutes(selection: PrototypeSelection, providers: Map<PrototypeService, ProviderSetup>): List<PrototypeSlot> =
    PrototypeSlot.entries.filter { slot ->
        val stream = if (slot == PrototypeSlot.A) selection.a else selection.b
        checkNotNull(providers[stream.service]).route?.mode != SourceRouteMode.SYSTEM
    }
