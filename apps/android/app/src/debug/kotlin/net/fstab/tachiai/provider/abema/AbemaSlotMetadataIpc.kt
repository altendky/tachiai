package net.fstab.tachiai.provider.abema

import android.os.Bundle
import net.fstab.tachiai.presentation.validProviderInstanceId

// Closed extension of the private episode service protocol. Distinct message
// codes and field sets prevent an episode reply becoming a slot reply.
internal object AbemaSlotMetadataIpc {
    const val START = 11
    const val CANCEL = 12
    const val PULSE = 13
    const val RESULT = 14
    class Start(val nonce: String, val instanceId: String, val slot: AbemaSlotRequest,
        val ownerProof: String, val deadline: Long) {
        override fun toString() = "AbemaSlotStart(redacted)"
    }
    class Result(val nonce: String, val outcome: String, val channelId: String? = null,
        val slotId: String? = null, val title: String? = null) {
        override fun toString() = "AbemaSlotResult(redacted)"
    }
    fun decodeStart(fields: Map<String, Any?>, now: Long): Start? {
        if (fields.keys != setOf("nonce", "instance", "channel", "slot", "proof", "deadline")) return null
        val nonce = fields["nonce"] as? String ?: return null
        val instance = fields["instance"] as? String ?: return null
        val channel = fields["channel"] as? String ?: return null
        val slot = fields["slot"] as? String ?: return null
        val proof = fields["proof"] as? String ?: return null
        val deadline = fields["deadline"] as? Long ?: return null
        if (!AbemaEpisodeMetadataIpc.validNonce(nonce) || !validProviderInstanceId(instance) ||
            !AbemaSlotRequest.validId(channel) || !AbemaSlotRequest.validId(slot) || !proof.matches(Regex("[a-f0-9]{64}")) ||
            now < 0 || deadline <= now || deadline - now > AbemaEpisodeMetadataIpc.OPERATION_MS) return null
        return Start(nonce, instance, AbemaSlotRequest(channel, slot), proof, deadline)
    }
    fun decodeResult(fields: Map<String, Any?>, expected: AbemaSlotRequest): Result? {
        val nonce = fields["nonce"] as? String ?: return null
        val outcome = fields["outcome"] as? String ?: return null
        if (!AbemaEpisodeMetadataIpc.validNonce(nonce) || outcome !in setOf("OK", "TEMPORARY", "NOT_FOUND", "RATE_LIMITED", "NOT_VERIFIED")) return null
        if (outcome != "OK") return if (fields.keys == setOf("nonce", "outcome")) Result(nonce, outcome) else null
        if (fields.keys != setOf("nonce", "outcome", "channel", "slot", "title")) return null
        val channel = fields["channel"] as? String ?: return null
        val slot = fields["slot"] as? String ?: return null
        val title = fields["title"] as? String ?: return null
        if (channel != expected.channelId || slot != expected.slotId || !safeAbemaSeriesText(title, 160)) return null
        return Result(nonce, outcome, channel, slot, title)
    }
    @Suppress("DEPRECATION")
    fun fields(bundle: Bundle): Map<String, Any?>? = runCatching {
        if (bundle.size() > 6) return null
        bundle.keySet().associateWith { bundle.get(it) }.takeIf { values ->
            values.values.all { it is String && it.length <= 256 || it is Long }
        }
    }.getOrNull()
    fun start(value: Start) = Bundle().apply {
        putString("nonce", value.nonce); putString("instance", value.instanceId)
        putString("channel", value.slot.channelId); putString("slot", value.slot.slotId)
        putString("proof", value.ownerProof); putLong("deadline", value.deadline)
    }
    fun result(value: Result) = Bundle().apply {
        putString("nonce", value.nonce); putString("outcome", value.outcome)
        value.channelId?.let { putString("channel", it) }; value.slotId?.let { putString("slot", it) }
        value.title?.let { putString("title", it) }
    }
}
