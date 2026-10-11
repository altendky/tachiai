package net.fstab.tachiai.provider.abema

import android.os.Bundle
import net.fstab.tachiai.presentation.validProviderInstanceId
import org.json.JSONArray
import org.json.JSONObject

// A third closed operation on the list service. The query is transient; URLs,
// provider bodies, credentials and arbitrary operation names cannot cross IPC.
internal object AbemaSearchMetadataIpc {
    const val START = 21; const val CANCEL = 22; const val PULSE = 23; const val RESULT = 24
    class Start(val nonce: String, val instanceId: String, val search: AbemaSearchRequest, val ownerProof: String, val deadline: Long) {
        override fun toString() = "AbemaSearchStart(redacted)"
    }
    class Result(val nonce: String, val outcome: String, val reply: AbemaSearchReply? = null) {
        override fun toString() = "AbemaSearchResult(redacted)"
    }
    fun decodeStart(fields: Map<String, Any?>, now: Long): Start? = runCatching {
        if (fields.keys != setOf("nonce", "instance", "query", "module", "offset", "proof", "deadline")) return null
        val nonce = fields["nonce"] as? String ?: return null
        val instance = fields["instance"] as? String ?: return null
        val query = fields["query"] as? String ?: return null
        val module = fields["module"] as? String
        if (fields["module"] != null && module == null) return null
        val offset = fields["offset"] as? Int ?: return null
        val proof = fields["proof"] as? String ?: return null
        val deadline = fields["deadline"] as? Long ?: return null
        if (!AbemaEpisodeMetadataIpc.validNonce(nonce) || !validProviderInstanceId(instance) || !proof.matches(Regex("[a-f0-9]{64}")) ||
            now < 0 || deadline <= now || deadline - now > AbemaSeriesMetadataIpc.OPERATION_MS) return null
        Start(nonce, instance, AbemaSearchRequest(query, module, offset), proof, deadline)
    }.getOrNull()
    fun decodeResult(fields: Map<String, Any?>, request: AbemaSearchRequest): Result? {
        val nonce = fields["nonce"] as? String ?: return null
        val outcome = fields["outcome"] as? String ?: return null
        if (!AbemaEpisodeMetadataIpc.validNonce(nonce) || outcome !in setOf("OK", "TEMPORARY", "NOT_FOUND", "RATE_LIMITED", "NOT_VERIFIED")) return null
        if (outcome != "OK") return if (fields.keys == setOf("nonce", "outcome")) Result(nonce, outcome) else null
        if (fields.keys != setOf("nonce", "outcome", "reply")) return null
        val reply = decodeAbemaSearchProjection(fields["reply"] as? String ?: return null, request) ?: return null
        return Result(nonce, outcome, reply)
    }
    @Suppress("DEPRECATION")
    fun fields(bundle: Bundle): Map<String, Any?>? = runCatching {
        if (bundle.size() > 7) return null
        bundle.keySet().associateWith { bundle.get(it) }.takeIf { values ->
            values.values.all { it == null || it is String && it.length <= ABEMA_SEARCH_PROJECTION_BYTES || it is Int || it is Long }
        }
    }.getOrNull()
    fun start(value: Start) = Bundle().apply {
        putString("nonce", value.nonce); putString("instance", value.instanceId); putString("query", value.search.query)
        putString("module", value.search.moduleId); putInt("offset", value.search.offset)
        putString("proof", value.ownerProof); putLong("deadline", value.deadline)
    }
    fun result(value: Result) = Bundle().apply {
        putString("nonce", value.nonce); putString("outcome", value.outcome)
        value.reply?.let { reply ->
            val text = JSONObject().put("modules", JSONArray().apply { reply.modules.forEach { module ->
                put(JSONObject().put("id", module.id).put("title", module.title).put("total", module.total)
                    .put("entries", JSONArray().apply { module.entries.forEach { entry ->
                        put(JSONObject().put("kind", entry.resource.kind).put("id", entry.resource.identity).put("title", entry.title))
                    } }))
            } }).toString()
            check(text.toByteArray(Charsets.UTF_8).size <= ABEMA_SEARCH_PROJECTION_BYTES)
            putString("reply", text)
        }
    }
}
