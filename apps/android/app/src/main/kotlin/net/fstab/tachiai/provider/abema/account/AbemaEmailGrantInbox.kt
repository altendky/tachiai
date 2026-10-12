package net.fstab.tachiai.provider.abema.account

import net.fstab.tachiai.presentation.validProviderInstanceId

internal fun validAbemaEmailHandoff(nonce: String, instanceId: String, ownerProof: String,
    deadlineElapsedMs: Long) = nonce.matches(Regex("[0-9a-f]{32}")) && validProviderInstanceId(instanceId) &&
    ownerProof.matches(Regex("[0-9a-f]{64}")) && deadlineElapsedMs >= 0

// Ordinary-process memory only. Taking an entry and invoking its receiver are
// serialized with cancellation; no pending credential survives process death.
internal class AbemaEmailGrantInbox(private val elapsedMs: () -> Long, private val wallMs: () -> Long) {
    private class Pending(val instanceId: String, val proof: String, val deadline: Long,
        val receiver: (AbemaAccountGrant) -> Unit)
    private val lock = Any()
    private val pending = mutableMapOf<String, Pending>()
    fun register(nonce: String, instanceId: String, ownerProof: String, deadlineElapsedMs: Long,
        receiver: (AbemaAccountGrant) -> Unit) = synchronized(lock) {
        require(validAbemaEmailHandoff(nonce, instanceId, ownerProof, deadlineElapsedMs))
        val now = elapsedMs()
        require(now >= 0 && now < deadlineElapsedMs && !pending.containsKey(nonce))
        pending[nonce] = Pending(instanceId, ownerProof, deadlineElapsedMs, receiver)
    }
    fun unregister(nonce: String) { synchronized(lock) { pending.remove(nonce) } }
    fun deliver(nonce: String, instanceId: String, ownerProof: String, deadlineElapsedMs: Long,
        grant: AbemaAccountGrant): Boolean {
        if (!validAbemaEmailHandoff(nonce, instanceId, ownerProof, deadlineElapsedMs)) return false
        val valid = runCatching {
            decodedAbemaGrant(grant.bearer, grant.userId, wallMs()).expiresAtMs == grant.expiresAtMs
        }.getOrDefault(false)
        return synchronized(lock) {
            val entry = pending[nonce] ?: return@synchronized false
            val now = elapsedMs()
            if (now < 0 || now >= entry.deadline) { pending.remove(nonce); return@synchronized false }
            if (!valid || entry.instanceId != instanceId || entry.proof != ownerProof ||
                entry.deadline != deadlineElapsedMs) return@synchronized false
            pending.remove(nonce)
            try { entry.receiver(grant); true } catch (_: Exception) { false }
        }
    }
    override fun toString() = "AbemaEmailGrantInbox(redacted)"
}

// Closed same-UID envelope shared by the Android transport and provider-free
// tests. Provider credentials never become a general application message.
internal class AbemaEmailGrantEnvelope(val nonce: String, val instanceId: String, val ownerProof: String,
    val deadlineElapsedMs: Long, val grant: AbemaAccountGrant) {
    override fun toString() = "AbemaEmailGrantEnvelope(redacted)"
    companion object {
        fun decode(fields: Map<String, Any>, sendingUid: Int, ownUid: Int, wallMs: Long): AbemaEmailGrantEnvelope? = runCatching {
            if (sendingUid < 0 || ownUid < 0 || sendingUid != ownUid ||
                fields.keys != setOf("nonce", "instance", "proof", "deadline", "userId", "bearer", "expiry")) return null
            val nonce = fields["nonce"] as? String ?: return null
            val instance = fields["instance"] as? String ?: return null
            val proof = fields["proof"] as? String ?: return null
            val deadline = fields["deadline"] as? Long ?: return null
            if (!validAbemaEmailHandoff(nonce, instance, proof, deadline)) return null
            val userId = fields["userId"] as? String ?: return null
            val bearer = fields["bearer"] as? String ?: return null
            val expiry = fields["expiry"] as? Long ?: return null
            val grant = decodedAbemaGrant(bearer, userId, wallMs)
            if (grant.expiresAtMs != expiry) return null
            AbemaEmailGrantEnvelope(nonce, instance, proof, deadline, grant)
        }.getOrNull()
    }
}
