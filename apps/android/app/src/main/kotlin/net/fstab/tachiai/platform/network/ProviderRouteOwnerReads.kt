package net.fstab.tachiai.platform.network

import net.fstab.tachiai.feature.connections.ProviderInstanceRegistrySnapshot
import net.fstab.tachiai.feature.connections.ProviderInstanceStore
import net.fstab.tachiai.platform.diagnostics.OperationStats
import net.fstab.tachiai.platform.storage.PrivateSecretRevision
import net.fstab.tachiai.platform.storage.StoreLockStats
import net.fstab.tachiai.presentation.PrototypeService
import net.fstab.tachiai.presentation.ProviderInstance
import net.fstab.tachiai.presentation.ProviderSetup

// Repeated route-owner checks of the provider-instance and connection-profile
// stores (#745). A full read decrypts and decodes both stores, and a non-null
// verdict records the revision of each store record it decoded. A later check
// for the same key re-reads only those revisions, under the same store locks
// but without a key or decryption. Any changed or unnamed revision, a read
// failure, another key or maxAgeMs forces a full read, so a committed store
// change by any process takes effect for the next check.
//
// read must be a pure function of the records it is given. The verdict holds
// only what read returned, never store plaintext.
internal class ProviderRouteOwnerReads(
    private val instances: ProviderInstanceStore,
    private val profiles: ConnectionProfileStore,
    private val legacy: () -> Map<PrototypeService, ProviderSetup>,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val maxAgeMs: Long = VERDICT_MAX_AGE_MS,
) {
    companion object {
        // Bounds how long a verdict stands without decrypting, for a store
        // whose key became unusable while its record stayed byte-identical.
        const val VERDICT_MAX_AGE_MS = 30_000L
        // Process-wide hit/full counts, drained with the store lock costs.
        private val stats = OperationStats("routeOwner=VERDICT_STATS")
        private fun statsNow() = System.nanoTime() / 1_000_000
        fun drainStats(): List<String> = StoreLockStats.drain() + listOfNotNull(stats.drain(statsNow()))
    }
    private class Verdict(val key: Any, val value: Any, val instances: PrivateSecretRevision,
        val profiles: PrivateSecretRevision?, val atMs: Long)
    // The revision every read of one store returned, or unusable when any
    // read named none or they differed.
    private class Revisions {
        var revision: PrivateSecretRevision? = null; var reads = 0; var stable = true
        fun record(observed: PrivateSecretRevision?) {
            if (observed == null || (reads > 0 && observed != revision)) stable = false
            if (reads++ == 0) revision = observed
        }
        val usable get() = stable && reads > 0 && revision != null
    }
    private val lock = Any()
    private var verdict: Verdict? = null

    private fun clear(held: Verdict?) = synchronized(lock) { if (held == null || verdict === held) verdict = null }

    fun <T : Any> current(key: Any, read: (readInstances: () -> List<ProviderInstance>,
        readProfiles: (Set<String>) -> Map<String, ConnectionProfile>) -> T?): T? =
        currentSnapshot(key) { registry, profiles -> read({ registry().instances }, profiles) }

    // current, where read also sees each registry read's committed/legacy
    // provenance (#752). A legacy view never names a revision, so it is never kept.
    fun <T : Any> currentSnapshot(key: Any, read: (readRegistry: () -> ProviderInstanceRegistrySnapshot,
        readProfiles: (Set<String>) -> Map<String, ConnectionProfile>) -> T?): T? {
        val now = nowMs()
        val held = synchronized(lock) { verdict }?.takeIf { it.key == key && now >= it.atMs && now - it.atMs < maxAgeMs }
        if (held != null) {
            val unchanged = try {
                instances.revision() == held.instances && (held.profiles == null || profiles.revision() == held.profiles)
            } catch (error: Throwable) { clear(held); throw error }
            if (unchanged) {
                stats.count("hit", statsNow())
                @Suppress("UNCHECKED_CAST") return held.value as T
            }
        }
        val instanceReads = Revisions()
        val profileReads = Revisions()
        val value = try {
            read({ instances.readSnapshotRevisioned(legacy).also { instanceReads.record(it.second) }.first },
                { ids -> profiles.snapshotRevisioned(ids).also { profileReads.record(it.second) }.first })
        } catch (error: Throwable) { clear(null); throw error }
        val instanceRevision = instanceReads.revision
        val usable = value != null && instanceReads.usable && instanceRevision != null &&
            (profileReads.reads == 0 || profileReads.usable)
        synchronized(lock) {
            verdict = if (usable) Verdict(key, checkNotNull(value), checkNotNull(instanceRevision),
                profileReads.revision.takeIf { profileReads.reads > 0 }, now) else null
        }
        stats.count(if (usable) "full" else "uncached", statsNow())
        return value
    }

    override fun toString() = "ProviderRouteOwnerReads(redacted)"
}

// Whether the stores still resolve to exactly this captured pool (#752). Only
// a matching read is kept as a verdict; a mismatch or failed read is not.
internal fun providerRoutePoolCurrent(reads: ProviderRouteOwnerReads, pool: ProviderRoutePoolSnapshot): Boolean {
    val instance = pool.configuredInstance
    return reads.current(pool.proof) { instances, profiles ->
        captureProviderRoutePool(instance.id, instance.service, instances, profiles).proof.takeIf { it == pool.proof }
    } != null
}
