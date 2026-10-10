package net.fstab.tachiai.presentation

import java.util.Collections
import java.util.Locale
import net.fstab.tachiai.provider.catalog.CatalogAvailability
import net.fstab.tachiai.provider.catalog.CatalogIntent

internal enum class ConfiguredPrototypeSelectionFailure {
    MISSING_CHOICE, STALE_ITEM, STALE_INSTANCE, PROVIDER_MISMATCH, ROUTE_REQUIRED,
    UNAVAILABLE, COLLECTION, UNSUPPORTED,
}

internal sealed interface ConfiguredPrototypeSelectionResult {
    data class Ready(val selection: PrototypeSelection, val sources: List<ConfiguredSource>) : ConfiguredPrototypeSelectionResult
    data class Failure(val reason: ConfiguredPrototypeSelectionFailure) : ConfiguredPrototypeSelectionResult
}

// Resolve local ownership before the exact public-resource playback bridge.
// This validation never opens a route/session or replaces a missing selection.
internal fun resolveConfiguredPrototypeSelection(
    assignments: ConfiguredFeedAssignments,
    sources: List<ConfiguredSource>,
    instances: List<ProviderInstance>,
): ConfiguredPrototypeSelectionResult {
    fun failure(reason: ConfiguredPrototypeSelectionFailure) = ConfiguredPrototypeSelectionResult.Failure(reason)
    val choices = listOf(assignments.a, assignments.b)
    if (choices.any { it == null }) return failure(ConfiguredPrototypeSelectionFailure.MISSING_CHOICE)
    val selected = mutableListOf<ConfiguredSource>()
    val legacy = mutableListOf<PrototypeSource>()
    for (choice in choices.filterNotNull()) {
        val source = choice.resolve(sources) ?: return failure(ConfiguredPrototypeSelectionFailure.STALE_ITEM)
        val instance = instances.singleOrNull { it.id == choice.instanceId }
            ?: return failure(ConfiguredPrototypeSelectionFailure.STALE_INSTANCE)
        if (source.entry.resource.providerId.value != instance.service.name.lowercase(Locale.ROOT))
            return failure(ConfiguredPrototypeSelectionFailure.PROVIDER_MISMATCH)
        if (source.entry.resource.intent == CatalogIntent.COLLECTION)
            return failure(ConfiguredPrototypeSelectionFailure.COLLECTION)
        if (source.entry.availability in setOf(CatalogAvailability.UPCOMING, CatalogAvailability.OFFLINE,
                CatalogAvailability.EXPIRED, CatalogAvailability.UNAVAILABLE))
            return failure(ConfiguredPrototypeSelectionFailure.UNAVAILABLE)
        if (instance.setup.route == null) return failure(ConfiguredPrototypeSelectionFailure.ROUTE_REQUIRED)
        val supported = legacyPrototypeSource(source)
            ?: return failure(ConfiguredPrototypeSelectionFailure.UNSUPPORTED)
        if (supported.service != instance.service) return failure(ConfiguredPrototypeSelectionFailure.PROVIDER_MISMATCH)
        selected += source
        legacy += supported
    }
    return ConfiguredPrototypeSelectionResult.Ready(
        PrototypeSelection(legacy[0], legacy[1], selected[0].instanceId, selected[1].instanceId),
        Collections.unmodifiableList(selected.toList()),
    )
}
