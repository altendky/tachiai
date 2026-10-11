package net.fstab.tachiai.provider.abema

import android.os.Bundle
import net.fstab.tachiai.presentation.validProviderInstanceId

// Private same-UID Messenger protocol. Only these bounded primitive fields
// cross processes; never a profile, URL, grant, arbitrary Parcelable or body.
internal object AbemaEpisodeMetadataIpc {
    const val START = 1
    const val CANCEL = 2
    const val PULSE = 3
    const val RESULT = 4
    const val OPERATION_MS = 45_000L
    const val CLEANUP_MS = 20_000L
    const val PULSE_MS = 200L
    const val LEASE_MS = 1_000L
    class Start(val nonce: String, val instanceId: String, val episodeId: String,
        val ownerProof: String, val deadline: Long) {
        override fun toString() = "AbemaEpisodeStart(redacted)"
    }
    class Result(val nonce: String, val outcome: String, val id: String? = null, val title: String? = null) {
        override fun toString() = "AbemaEpisodeResult(redacted)"
    }
    fun validNonce(value: String) = value.matches(Regex("[a-f0-9]{32}"))
    fun decodeStart(fields: Map<String, Any?>, now: Long): Start? {
        if (fields.keys != setOf("nonce", "instance", "episode", "proof", "deadline")) return null
        val nonce = fields["nonce"] as? String ?: return null
        val instance = fields["instance"] as? String ?: return null
        val episode = fields["episode"] as? String ?: return null
        val proof = fields["proof"] as? String ?: return null
        val deadline = fields["deadline"] as? Long ?: return null
        if (!validNonce(nonce) || !validProviderInstanceId(instance) || !AbemaGuestBootstrapPolicy.validEpisodeId(episode) ||
            !proof.matches(Regex("[a-f0-9]{64}")) || now < 0 || deadline <= now || deadline - now > OPERATION_MS) return null
        return Start(nonce, instance, episode, proof, deadline)
    }
    fun decodeNonce(fields: Map<String, Any?>): String? =
        (fields["nonce"] as? String)?.takeIf { fields.keys == setOf("nonce") && validNonce(it) }
    fun decodeResult(fields: Map<String, Any?>): Result? {
        val nonce = fields["nonce"] as? String ?: return null
        val outcome = fields["outcome"] as? String ?: return null
        if (!validNonce(nonce) || outcome !in setOf("OK", "TEMPORARY", "NOT_FOUND", "RATE_LIMITED", "NOT_VERIFIED")) return null
        if (outcome != "OK") return if (fields.keys == setOf("nonce", "outcome")) Result(nonce, outcome) else null
        if (fields.keys != setOf("nonce", "outcome", "id", "title")) return null
        val id = fields["id"] as? String ?: return null
        val title = fields["title"] as? String ?: return null
        if (!AbemaGuestBootstrapPolicy.validEpisodeId(id) || !AbemaGuestRuntimeCoordinator.safeEpisodeTitle(title)) return null
        return Result(nonce, outcome, id, title)
    }
    @Suppress("DEPRECATION")
    fun fields(bundle: Bundle): Map<String, Any?>? = runCatching {
        if (bundle.size() > 5) return null
        bundle.keySet().associateWith { bundle.get(it) }.takeIf { values ->
            values.values.all { it is String && it.length <= 256 || it is Long }
        }
    }.getOrNull()
    fun start(value: Start) = Bundle().apply {
        putString("nonce", value.nonce); putString("instance", value.instanceId); putString("episode", value.episodeId)
        putString("proof", value.ownerProof); putLong("deadline", value.deadline)
    }
    fun nonce(value: String) = Bundle().apply { putString("nonce", value) }
    fun result(value: Result) = Bundle().apply {
        putString("nonce", value.nonce); putString("outcome", value.outcome)
        value.id?.let { putString("id", it) }; value.title?.let { putString("title", it) }
    }
}

// Memory-only remote foreground lease. Pulses cannot extend the operation's
// immutable deadline, resurrect cancellation, or act on another nonce.
internal class AbemaEpisodeRequestLease(private val nonce: String, private val deadline: Long,
    private val clockMs: () -> Long) {
    private val lock = Any()
    private var cancelled = false
    private var until = minOf(deadline, clockMs() + AbemaEpisodeMetadataIpc.LEASE_MS)
    fun current(): Boolean = synchronized(lock) { !cancelled && clockMs() < until && clockMs() < deadline }
    fun pulse(candidate: String): Boolean = synchronized(lock) {
        if (candidate != nonce || !current()) false
        else { until = minOf(deadline, clockMs() + AbemaEpisodeMetadataIpc.LEASE_MS); true }
    }
    fun cancel(candidate: String) = synchronized(lock) { if (candidate == nonce) cancelled = true }
    override fun toString() = "AbemaEpisodeRequestLease(redacted)"
}
