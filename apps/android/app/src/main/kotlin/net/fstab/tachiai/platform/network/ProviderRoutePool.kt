package net.fstab.tachiai.platform.network

import java.util.Collections
import net.fstab.tachiai.presentation.*

internal enum class ProviderRoutePoolFailure { CHOICE_REQUIRED, PROFILE_MISSING, SNAPSHOT_CHANGED, OWNER_MISMATCH }

internal class ProviderRoutePoolException(val reason: ProviderRoutePoolFailure) :
    IllegalStateException("Provider route is unavailable")

private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))

private fun frozenInstance(instance: ProviderInstance) = instance.copy(setup = instance.setup.copy(
    previousRoutes = frozen(instance.setup.previousRoutes), fallbackRoutes = frozen(instance.setup.fallbackRoutes)))

private fun choiceIdentity(choice: SourceRouteChoice) = "${choice.mode.name}|${choice.connectionId.orEmpty()}"

internal class ProviderRouteCandidate internal constructor(
    val choice: SourceRouteChoice,
    val profile: ConnectionProfile?,
    val available: Boolean,
    val proof: String,
    val routeIdentity: String,
) {
    override fun toString() = "ProviderRouteCandidate(redacted)"
}

// Profile objects contain immutable strings and remain worker-owned. Neither
// this snapshot nor its proofs belong in saved state, diagnostics or preferences.
internal class ProviderEffectiveRoute internal constructor(
    configuredInstance: ProviderInstance,
    val candidate: ProviderRouteCandidate,
    val poolProof: String,
) {
    val configuredInstance: ProviderInstance = frozenInstance(configuredInstance)
    val choice get() = candidate.choice
    val profile get() = candidate.profile
    val proof get() = candidate.proof
    // Cleanup identity excludes pool order, aliases and runtime scope identity.
    val routeIdentity get() = candidate.routeIdentity
    val effectiveInstance get() = configuredInstance.copy(setup = ProviderSetup(choice,
        previousRoutes = configuredInstance.setup.previousRoutes))
    override fun toString() = "ProviderEffectiveRoute(redacted)"
}

internal class ProviderRoutePoolSnapshot internal constructor(
    configuredInstance: ProviderInstance,
    candidates: List<ProviderRouteCandidate>,
    val proof: String,
) {
    val configuredInstance: ProviderInstance = frozenInstance(configuredInstance)
    val candidates: List<ProviderRouteCandidate> = frozen(candidates)

    // A missing first choice never selects a later candidate implicitly. Only
    // an exact proof issued for this snapshot can select another saved choice.
    fun resolve(expectedProof: String? = null): ProviderEffectiveRoute {
        val candidate = if (expectedProof == null) candidates.firstOrNull()
            ?: throw ProviderRoutePoolException(ProviderRoutePoolFailure.CHOICE_REQUIRED)
        else candidates.singleOrNull { it.proof == expectedProof }
            ?: throw ProviderRoutePoolException(ProviderRoutePoolFailure.OWNER_MISMATCH)
        if (!candidate.available) throw ProviderRoutePoolException(ProviderRoutePoolFailure.PROFILE_MISSING)
        return ProviderEffectiveRoute(configuredInstance, candidate, proof)
    }

    override fun toString() = "ProviderRoutePoolSnapshot(redacted)"
}

// Worker-only bounded reconciliation, not a cross-store transaction. Double
// reads detect observed registry/profile changes; they cannot detect an ABA
// mutation between reads. Existing operation gates must recheck ownership at
// request/publication boundaries and retain observed revocation locally.
internal fun captureProviderRoutePool(
    instanceId: String,
    service: PrototypeService,
    readInstances: () -> List<ProviderInstance>,
    readProfiles: (Set<String>) -> Map<String, ConnectionProfile>,
): ProviderRoutePoolSnapshot {
    require(validProviderInstanceId(instanceId))
    fun instance(): ProviderInstance {
        val current = readInstances().singleOrNull { it.id == instanceId }
            ?.takeIf { it.service == service }
            ?: throw ProviderRoutePoolException(ProviderRoutePoolFailure.OWNER_MISMATCH)
        return frozenInstance(current)
    }
    fun identities(value: ProviderInstance) = value.setup.routes.map(::choiceIdentity)
    val first = instance()
    val ids = first.setup.routes.mapNotNull { it.connectionId }.toSet()
    fun profiles(): Map<String, ConnectionProfile> {
        if (ids.isEmpty()) return emptyMap()
        return readProfiles(ids).also { check(it.keys.all(ids::contains)) }.toMap()
    }
    fun keys(values: Map<String, ConnectionProfile>) = ids.associateWith { id ->
        values[id]?.let(::routeConfigurationKey) ?: "MISSING"
    }
    val initialProfiles = profiles()
    val initialKeys = keys(initialProfiles)
    val latest = instance()
    if (identities(first) != identities(latest))
        throw ProviderRoutePoolException(ProviderRoutePoolFailure.SNAPSHOT_CHANGED)
    val latestProfiles = profiles()
    val latestKeys = keys(latestProfiles)
    if (initialKeys != latestKeys) throw ProviderRoutePoolException(ProviderRoutePoolFailure.SNAPSHOT_CHANGED)
    return providerRoutePoolSnapshot(latest, latestProfiles, latestKeys)
}

// Only proof construction is shared with the explicitly opted-in single-file
// SYSTEM snapshot. Callers own validation/reconciliation, not this pure builder.
internal fun providerRoutePoolSnapshot(instance: ProviderInstance, profiles: Map<String, ConnectionProfile>,
    profileKeys: Map<String, String>): ProviderRoutePoolSnapshot {
    val prefix = "${instance.id}|${instance.service.name}"
    val poolProof = routeDigest("provider-route-pool-v1|$prefix|" + instance.setup.routes.joinToString(";") { choice ->
        "${choiceIdentity(choice)}|${choice.connectionId?.let(profileKeys::getValue) ?: "SYSTEM"}"
    })
    val candidates = instance.setup.routes.map { choice ->
        val profile = choice.connectionId?.let(profiles::get)
        val configuration = choice.connectionId?.let(profileKeys::getValue) ?: "SYSTEM"
        ProviderRouteCandidate(choice, profile,
            available = choice.mode == SourceRouteMode.SYSTEM || profile != null,
            proof = routeDigest("provider-route-candidate-v1|$poolProof|${choiceIdentity(choice)}|$configuration"),
            routeIdentity = routeDigest("provider-route-cleanup-v1|$prefix|$configuration"))
    }
    return ProviderRoutePoolSnapshot(instance, candidates, poolProof)
}
