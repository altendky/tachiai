package net.fstab.tachiai.presentation

import java.util.UUID

internal const val MAX_PROVIDER_INSTANCES = 16

internal fun sameProviderInstanceName(first: String, second: String): Boolean =
    first.lowercase(java.util.Locale.ROOT) == second.lowercase(java.util.Locale.ROOT)

// Reserved stable identities retain the original provider settings and LOCAL grant.
internal fun defaultProviderInstanceId(service: PrototypeService): String = when (service) {
    PrototypeService.ABEMA -> "00000000-0000-0000-0000-000000000001"
    PrototypeService.TWITCH -> "00000000-0000-0000-0000-000000000002"
}

internal fun validProviderInstanceId(id: String): Boolean = runCatching {
    UUID.fromString(id).toString() == id
}.getOrDefault(false)

internal data class ProviderInstance(
    val id: String,
    val service: PrototypeService,
    val defaultName: String,
    val customName: String? = null,
    val setup: ProviderSetup = ProviderSetup(SourceRouteChoice.system),
) {
    init {
        require(validProviderInstanceId(id) && validSourceSetupName(defaultName))
        require(customName == null || validSourceSetupName(customName))
        PrototypeService.entries.forEach { if (id == defaultProviderInstanceId(it)) require(service == it) }
        require(setup.previousRoutes.size <= 32 && setup.previousRoutes.none { it.mode == SourceRouteMode.SOURCE_DEFAULT })
    }
    val name get() = customName ?: defaultName
}

internal fun defaultProviderInstances(setups: Map<PrototypeService, ProviderSetup> =
    legacyProviderSetups(defaultSourceSetups())): List<ProviderInstance> = PrototypeService.entries.map {
    ProviderInstance(defaultProviderInstanceId(it), it, it.title, setup = checkNotNull(setups[it]))
}

internal data class PrototypeFeedChoice(val source: PrototypeSource, val instanceId: String) {
    init { require(validProviderInstanceId(instanceId)) }
    fun resolve(instances: List<ProviderInstance>): ProviderInstance? =
        instances.singleOrNull { it.id == instanceId && it.service == source.service }
}

// Source and instance move together. A stale/mismatched ID never becomes the default instance.
internal data class PrototypeFeedAssignments(val a: PrototypeFeedChoice?, val b: PrototypeFeedChoice?) {
    fun assign(slot: PrototypeSlot, choice: PrototypeFeedChoice, checked: Boolean): PrototypeFeedAssignments {
        val previous = if (slot == PrototypeSlot.A) a else b
        val next = if (checked) choice else previous?.takeUnless { it == choice }
        return if (slot == PrototypeSlot.A) copy(a = next) else copy(b = next)
    }
    fun selectionOrNull(instances: List<ProviderInstance>): PrototypeSelection? {
        val first = a ?: return null
        val second = b ?: return null
        return if (first.resolve(instances) != null && second.resolve(instances) != null)
            PrototypeSelection(first.source, second.source, first.instanceId, second.instanceId) else null
    }
}

// WebView's ABEMA proxy override is process-wide, even for independent provider instances.
internal fun compatibleAbemaInstanceRoutes(feeds: List<PrototypeFeedChoice>, canonicalRoutes: Map<String, String>): Boolean {
    val ids = feeds.filter { it.source.service == PrototypeService.ABEMA }.map { it.instanceId }.distinct()
    // A single instance's missing route remains an ordinary per-feed route failure.
    if (ids.size <= 1) return true
    val keys = ids.map { canonicalRoutes[it] }
    return keys.none { it == null } && keys.distinct().size == 1
}
