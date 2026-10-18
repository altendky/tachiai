package net.fstab.tachiai.provider.twitch.catalog

import net.fstab.tachiai.feature.connections.ProviderInstanceStore
import net.fstab.tachiai.platform.network.ConnectionProfile
import net.fstab.tachiai.platform.network.providerRoutePoolSnapshot
import net.fstab.tachiai.presentation.ProviderInstance
import net.fstab.tachiai.presentation.ProviderSetup
import net.fstab.tachiai.presentation.PrototypeService
import net.fstab.tachiai.presentation.SourceRouteMode
import net.fstab.tachiai.presentation.validProviderInstanceId

// Explicit Android Twitch opt-in. Every invocation reads the actual locked
// registry afresh. A committed entirely SYSTEM pool has no profile file to
// reconcile, so this one authenticated record is its linearization point.
// This does not promise detection of every intervening change or ABA mutation.
internal fun captureTwitchCatalogSystemRegistryOwner(instanceId: String, registry: ProviderInstanceStore,
    legacy: () -> Map<PrototypeService, ProviderSetup>, readProfiles: (Set<String>) -> Map<String, ConnectionProfile>,
    expectedProof: String? = null, onInstanceObserved: (ProviderInstance) -> Unit = {}): TwitchCatalogRouteOwner {
    require(validProviderInstanceId(instanceId))
    val first = registry.readSnapshot(legacy)
    val instance = first.instances.singleOrNull { it.id == instanceId && it.service == PrototypeService.TWITCH }
    if (first.committed && instance != null && instance.setup.routes.isNotEmpty() &&
        instance.setup.routes.all { it.mode == SourceRouteMode.SYSTEM && it.connectionId == null && it.connectionName == null }) {
        onInstanceObserved(instance)
        return TwitchCatalogRouteOwner(providerRoutePoolSnapshot(instance, emptyMap(), emptyMap()).resolve(expectedProof))
    }
    // The first sample belongs only to this invocation. Feed it to the existing
    // double reconciliation, retaining its second registry and both profile
    // reads rather than adding a third registry read to imported/legacy paths.
    var consumed = false
    return captureTwitchCatalogRouteOwner(instanceId, {
        if (!consumed) { consumed = true; first.instances } else registry.readSnapshot(legacy).instances
    }, readProfiles, expectedProof, onInstanceObserved)
}
