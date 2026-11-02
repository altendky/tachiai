package net.fstab.tachiai.provider.abema.account

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

// RAM-only platform invocation registry. Keys identify scheduled invocations;
// owner identity prevents an old finish/recheck from removing a replacement.
internal class AbemaRenewalJobOwners<O : Any> {
    private class Entry<O>(val key: AbemaRenewalJobKey, val owner: O) {
        val preflight = AtomicReference<AbemaRenewalJobPreflight?>()
    }
    private val entries = ConcurrentHashMap<Int, Entry<O>>()
    fun register(key: AbemaRenewalJobKey, owner: O) = entries.putIfAbsent(key.jobId, Entry(key, owner)) == null
    fun owner(jobId: Int): O? = entries[jobId]?.owner
    fun callback(key: AbemaRenewalJobKey): O? = entries[key.jobId]?.takeIf { it.key == key }?.owner
    fun snapshot(): List<O> = entries.values.map { it.owner }
    fun remove(key: AbemaRenewalJobKey, owner: O): Boolean {
        val entry = entries[key.jobId]?.takeIf { it.key == key && it.owner === owner } ?: return false
        return entries.remove(key.jobId, entry)
    }
    fun attach(key: AbemaRenewalJobKey, owner: O, preflight: AbemaRenewalJobPreflight) {
        require(preflight.target.instanceId == key.instance)
        entries[key.jobId]?.takeIf { it.key == key && it.owner === owner }?.preflight?.set(preflight)
    }
    fun reconcile(instances: Set<String>, inspect: (String) -> AbemaRenewalJobPreflight?, retire: (O) -> Unit) {
        entries.values.toList().forEach { entry ->
            val expected = entry.preflight.get() ?: return@forEach
            val fresh = if (entry.key.instance in instances) runCatching { inspect(entry.key.instance) }.getOrNull() else null
            if (fresh == null || fresh.target != expected.target || fresh.generation != expected.generation) {
                if (entries[entry.key.jobId] === entry) retire(entry.owner)
            }
        }
    }
    override fun toString() = "AbemaRenewalJobOwners(redacted)"
}
