package net.fstab.tachiai.provider.abema

import android.os.Bundle
import net.fstab.tachiai.presentation.validProviderInstanceId
import org.json.JSONArray
import org.json.JSONObject

internal object AbemaSeriesMetadataIpc {
    const val START = 1; const val CANCEL = 2; const val PULSE = 3; const val RESULT = 4
    const val OPERATION_MS = 45_000L; const val CLEANUP_MS = 20_000L; const val PULSE_MS = 200L
    class Start(val nonce: String, val instanceId: String, val page: AbemaSeriesPageRequest,
        val ownerProof: String, val deadline: Long) { override fun toString() = "AbemaSeriesStart(redacted)" }
    class Result(val nonce: String, val outcome: String, val page: AbemaSeriesPage? = null) {
        override fun toString() = "AbemaSeriesResult(redacted)"
    }
    fun decodeStart(fields: Map<String, Any?>, now: Long): Start? = runCatching {
        if (fields.keys != setOf("nonce", "instance", "parent", "offset", "version", "proof", "deadline")) return null
        val nonce = fields["nonce"] as? String ?: return null
        val instance = fields["instance"] as? String ?: return null
        val parent = fields["parent"] as? String ?: return null
        val offset = fields["offset"] as? Int ?: return null
        val version = fields["version"] as? String
        if (fields["version"] != null && version == null) return null
        val proof = fields["proof"] as? String ?: return null
        val deadline = fields["deadline"] as? Long ?: return null
        if (!AbemaEpisodeMetadataIpc.validNonce(nonce) || !validProviderInstanceId(instance) ||
            !proof.matches(Regex("[a-f0-9]{64}")) || now < 0 || deadline <= now || deadline - now > OPERATION_MS) return null
        Start(nonce, instance, AbemaSeriesPageRequest(parent, offset, version), proof, deadline)
    }.getOrNull()
    fun decodeResult(fields: Map<String, Any?>, request: AbemaSeriesPageRequest): Result? {
        val nonce = fields["nonce"] as? String ?: return null
        val outcome = fields["outcome"] as? String ?: return null
        if (!AbemaEpisodeMetadataIpc.validNonce(nonce) || outcome !in setOf("OK", "TEMPORARY", "NOT_FOUND", "RATE_LIMITED", "NOT_VERIFIED")) return null
        if (outcome != "OK") return if (fields.keys == setOf("nonce", "outcome")) Result(nonce, outcome) else null
        if (fields.keys != setOf("nonce", "outcome", "page")) return null
        val page = decodeAbemaSeriesProjection(fields["page"] as? String ?: return null, request) ?: return null
        return Result(nonce, outcome, page)
    }
    @Suppress("DEPRECATION")
    fun fields(bundle: Bundle): Map<String, Any?>? = runCatching {
        if (bundle.size() > 7) return null
        bundle.keySet().associateWith { bundle.get(it) }.takeIf { values ->
            values.values.all { it == null || it is String && it.length <= 32768 || it is Int || it is Long }
        }
    }.getOrNull()
    fun start(value: Start) = Bundle().apply {
        putString("nonce", value.nonce); putString("instance", value.instanceId); putString("parent", value.page.seriesId)
        putInt("offset", value.page.offset); putString("version", value.page.version)
        putString("proof", value.ownerProof); putLong("deadline", value.deadline)
    }
    fun nonce(value: String) = AbemaEpisodeMetadataIpc.nonce(value)
    fun result(value: Result, parent: String) = Bundle().apply {
        putString("nonce", value.nonce); putString("outcome", value.outcome)
        value.page?.let { page -> check(page.seriesId == parent); putString("page", JSONObject().put("seriesId", page.seriesId).put("version", page.version)
            .put("programs", JSONArray().apply { page.entries.forEach { put(JSONObject().put("id", it.resource.identity).put("title", it.title)) } }).toString()) }
    }
}
