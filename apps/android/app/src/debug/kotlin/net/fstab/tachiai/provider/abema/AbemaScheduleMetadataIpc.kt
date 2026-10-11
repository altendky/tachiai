package net.fstab.tachiai.provider.abema

import android.os.Bundle
import net.fstab.tachiai.presentation.validProviderInstanceId
import org.json.JSONArray
import org.json.JSONObject

// A second closed operation on the existing list service. No URL, account
// grant, provider JSON or resource argument can be supplied by the caller.
internal object AbemaScheduleMetadataIpc {
    const val START = 11; const val CANCEL = 12; const val PULSE = 13; const val RESULT = 14
    class Start(val nonce: String, val instanceId: String, val ownerProof: String, val deadline: Long) {
        override fun toString() = "AbemaScheduleStart(redacted)"
    }
    class Result(val nonce: String, val outcome: String, val snapshot: AbemaScheduleSnapshot? = null) {
        override fun toString() = "AbemaScheduleResult(redacted)"
    }
    fun decodeStart(fields: Map<String, Any?>, now: Long): Start? {
        if (fields.keys != setOf("nonce", "instance", "proof", "deadline")) return null
        val nonce = fields["nonce"] as? String ?: return null
        val instance = fields["instance"] as? String ?: return null
        val proof = fields["proof"] as? String ?: return null
        val deadline = fields["deadline"] as? Long ?: return null
        if (!AbemaEpisodeMetadataIpc.validNonce(nonce) || !validProviderInstanceId(instance) ||
            !proof.matches(Regex("[a-f0-9]{64}")) || now < 0 || deadline <= now ||
            deadline - now > AbemaSeriesMetadataIpc.OPERATION_MS) return null
        return Start(nonce, instance, proof, deadline)
    }
    fun decodeResult(fields: Map<String, Any?>): Result? {
        val nonce = fields["nonce"] as? String ?: return null
        val outcome = fields["outcome"] as? String ?: return null
        if (!AbemaEpisodeMetadataIpc.validNonce(nonce) || outcome !in setOf("OK", "TEMPORARY", "NOT_FOUND", "RATE_LIMITED", "NOT_VERIFIED")) return null
        if (outcome != "OK") return if (fields.keys == setOf("nonce", "outcome")) Result(nonce, outcome) else null
        if (fields.keys != setOf("nonce", "outcome", "snapshot")) return null
        val snapshot = decodeAbemaScheduleProjection(fields["snapshot"] as? String ?: return null) ?: return null
        return Result(nonce, outcome, snapshot)
    }
    @Suppress("DEPRECATION")
    fun fields(bundle: Bundle): Map<String, Any?>? = runCatching {
        if (bundle.size() > 4) return null
        bundle.keySet().associateWith { bundle.get(it) }.takeIf { values ->
            values.values.all { it is String && it.length <= ABEMA_SCHEDULE_PROJECTION_BYTES || it is Long }
        }
    }.getOrNull()
    fun start(value: Start) = Bundle().apply {
        putString("nonce", value.nonce); putString("instance", value.instanceId)
        putString("proof", value.ownerProof); putLong("deadline", value.deadline)
    }
    fun nonce(value: String) = AbemaEpisodeMetadataIpc.nonce(value)
    fun result(value: Result) = Bundle().apply {
        putString("nonce", value.nonce); putString("outcome", value.outcome)
        value.snapshot?.let { snapshot ->
            val projection = JSONObject().put("truncated", snapshot.truncated).put("slots", JSONArray().apply {
                snapshot.entries.forEach { entry ->
                    val request = checkNotNull(AbemaSlotRequest.from(entry.resource))
                    put(JSONObject().put("id", request.slotId).put("channelId", request.channelId).put("title", entry.title))
                }
            }).toString()
            check(projection.toByteArray(Charsets.UTF_8).size <= ABEMA_SCHEDULE_PROJECTION_BYTES)
            putString("snapshot", projection)
        }
    }
}
