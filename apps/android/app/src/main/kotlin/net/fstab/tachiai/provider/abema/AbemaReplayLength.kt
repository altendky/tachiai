package net.fstab.tachiai.provider.abema

import android.os.Bundle
import java.util.Collections
import net.fstab.tachiai.presentation.validProviderInstanceId
import net.fstab.tachiai.provider.catalog.*
import org.json.JSONTokener

// Replay lengths of listed broadcasts (#672). A finished broadcast's listed
// length is its reserved slot span; the real length exists only in its replay
// manifest. The metadata runtime reads each manifest through playback's own
// source path and returns whole seconds only: no URL, token or manifest text.
internal const val ABEMA_REPLAY_LENGTH_LIMIT = REPLAY_LENGTH_LIMIT
// Runtime runs per listing page: each reads what its time limit allows.
internal const val ABEMA_REPLAY_LENGTH_RUNS = 3

internal class AbemaReplayLengthRequest(slots: List<AbemaSlotRequest>) {
    val slots: List<AbemaSlotRequest> = Collections.unmodifiableList(slots.toList())
    init {
        require(this.slots.size in 1..ABEMA_REPLAY_LENGTH_LIMIT)
        require(this.slots.map { it.resource }.distinct().size == this.slots.size)
    }
    fun slot(channelId: String, slotId: String) = slots.singleOrNull { it.channelId == channelId && it.slotId == slotId }
    // Private binding input only; never logged or exported.
    fun key() = slots.joinToString(",") { "${it.channelId}/${it.slotId}" }
    override fun toString() = "AbemaReplayLengthRequest(redacted)"
}

// Seconds for the requested broadcasts whose manifest was read; any other
// requested broadcast is omitted and keeps its listed span. unread is the
// request's suffix that the run's time limit left unstarted, for another run.
internal class AbemaReplayLengths(val request: AbemaReplayLengthRequest, seconds: Map<CatalogResource, Long>, unread: Int = 0) {
    val seconds: Map<CatalogResource, Long> = Collections.unmodifiableMap(LinkedHashMap(seconds))
    val unread: List<AbemaSlotRequest> = request.slots.takeLast(unread)
    init {
        require(unread in 0..request.slots.size)
        require(this.seconds.keys.all { key -> request.slots.dropLast(unread).any { it.resource == key } })
        require(this.seconds.values.all { it in 1..MAX_CATALOG_DURATION_SECONDS })
    }
    override fun toString() = "AbemaReplayLengths(redacted)"
}

internal interface AbemaReplayLengthTransport : AutoCloseable {
    fun fetch(request: AbemaReplayLengthRequest): CatalogResult<AbemaReplayLengths>
    fun cancelActiveRequest()
}

internal const val ABEMA_REPLAY_LENGTH_PROJECTION_BYTES = 8192

// Strict normalized projection: {"lengths":[{"channelId","slotId","durationSeconds"}],"unread":n}
// in request order, each requested broadcast at most once.
internal fun decodeAbemaReplayLengthProjection(text: String, request: AbemaReplayLengthRequest): AbemaReplayLengths? = try {
    if (text.length > ABEMA_REPLAY_LENGTH_PROJECTION_BYTES ||
        text.toByteArray(Charsets.UTF_8).size > ABEMA_REPLAY_LENGTH_PROJECTION_BYTES) null
    else ReplayLengthReader(text).read(request)
} catch (_: Throwable) { null }

private class ReplayLengthReader(private val text: String) {
    private var at = 0
    private fun space() { while (at < text.length && text[at] in " \t\r\n") at++ }
    private fun take(char: Char): Boolean { space(); if (text.getOrNull(at) != char) return false; at++; return true }
    private fun need(char: Char) { check(take(char)) }
    private fun string(): String {
        space()
        val start = at
        need('"')
        while (at < text.length) {
            val character = text[at++]
            if (character == '"') return JSONTokener(text.substring(start, at)).nextValue() as String
            check(character.code >= 32)
            if (character == '\\') {
                val escaped = text.getOrNull(at++)
                if (escaped == 'u') {
                    check(at + 4 <= text.length && text.substring(at, at + 4).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                    at += 4
                } else check(escaped != null && escaped in "\"\\/bfnrt")
            }
        }
        error("Replay length projection refused")
    }
    private fun seconds(): Long {
        space()
        val start = at
        while (at < text.length && text[at] in '0'..'9') at++
        val digits = text.substring(start, at)
        check(digits.length in 1..6 && digits[0] != '0')
        return digits.toLong().also { check(it in 1..MAX_CATALOG_DURATION_SECONDS) }
    }
    private fun fields(names: List<String>, value: (String) -> Unit) {
        need('{')
        names.forEachIndexed { index, name -> if (index > 0) need(','); check(string() == name); need(':'); value(name) }
        need('}')
    }
    fun read(request: AbemaReplayLengthRequest): AbemaReplayLengths {
        val seconds = linkedMapOf<CatalogResource, Long>()
        var last = -1
        var unread = 0
        fields(listOf("lengths", "unread")) { field ->
            if (field == "unread") { space(); unread = if (text.getOrNull(at) == '0') { at++; 0 } else seconds().toInt()
                check(unread <= request.slots.size); return@fields }
            need('[')
            if (!take(']')) {
                do {
                    var channel: String? = null; var slot: String? = null; var duration: Long? = null
                    fields(listOf("channelId", "slotId", "durationSeconds")) { name -> when (name) {
                        "channelId" -> channel = string(); "slotId" -> slot = string(); else -> duration = seconds()
                    } }
                    val requested = checkNotNull(request.slot(checkNotNull(channel), checkNotNull(slot)))
                    val position = request.slots.indexOf(requested)
                    check(position > last); last = position
                    seconds[requested.resource] = checkNotNull(duration)
                } while (take(','))
                need(']')
            }
        }
        space(); check(at == text.length)
        return AbemaReplayLengths(request, seconds, unread)
    }
}

// Written by hand: the reader requires this field order, which org.json does not keep.
internal fun encodeAbemaReplayLengthProjection(lengths: AbemaReplayLengths): String =
    lengths.request.slots.mapNotNull { slot -> lengths.seconds[slot.resource]?.let { value ->
        """{"channelId":${org.json.JSONObject.quote(slot.channelId)},"slotId":${org.json.JSONObject.quote(slot.slotId)},"durationSeconds":$value}"""
    } }.joinToString(",", """{"lengths":[""", "],\"unread\":${lengths.unread.size}}")

// A fourth closed operation on the list service. Only exact broadcast IDs go
// out and whole seconds come back; no URL, token, manifest or body crosses IPC.
internal object AbemaReplayLengthIpc {
    const val START = 31; const val CANCEL = 32; const val PULSE = 33; const val RESULT = 34
    class Start(val nonce: String, val instanceId: String, val request: AbemaReplayLengthRequest, val ownerProof: String, val deadline: Long) {
        override fun toString() = "AbemaReplayLengthStart(redacted)"
    }
    class Result(val nonce: String, val outcome: String, val lengths: AbemaReplayLengths? = null) {
        override fun toString() = "AbemaReplayLengthResult(redacted)"
    }
    fun decodeStart(fields: Map<String, Any?>, now: Long): Start? = runCatching {
        if (fields.keys != setOf("nonce", "instance", "slots", "proof", "deadline")) return null
        val nonce = fields["nonce"] as? String ?: return null
        val instance = fields["instance"] as? String ?: return null
        val slots = (fields["slots"] as? String ?: return null).split(',').map { pair ->
            val parts = pair.split('/')
            if (parts.size != 2) return null
            AbemaSlotRequest(parts[0], parts[1])
        }
        val proof = fields["proof"] as? String ?: return null
        val deadline = fields["deadline"] as? Long ?: return null
        if (!AbemaEpisodeMetadataIpc.validNonce(nonce) || !validProviderInstanceId(instance) || !proof.matches(Regex("[a-f0-9]{64}")) ||
            now < 0 || deadline <= now || deadline - now > AbemaSeriesMetadataIpc.OPERATION_MS) return null
        Start(nonce, instance, AbemaReplayLengthRequest(slots), proof, deadline)
    }.getOrNull()
    fun decodeResult(fields: Map<String, Any?>, request: AbemaReplayLengthRequest): Result? {
        val nonce = fields["nonce"] as? String ?: return null
        val outcome = fields["outcome"] as? String ?: return null
        if (!AbemaEpisodeMetadataIpc.validNonce(nonce) || outcome !in setOf("OK", "TEMPORARY", "NOT_FOUND", "RATE_LIMITED", "NOT_VERIFIED")) return null
        if (outcome != "OK") return if (fields.keys == setOf("nonce", "outcome")) Result(nonce, outcome) else null
        if (fields.keys != setOf("nonce", "outcome", "lengths")) return null
        val lengths = decodeAbemaReplayLengthProjection(fields["lengths"] as? String ?: return null, request) ?: return null
        return Result(nonce, outcome, lengths)
    }
    @Suppress("DEPRECATION")
    fun fields(bundle: Bundle): Map<String, Any?>? = runCatching {
        if (bundle.size() > 5) return null
        bundle.keySet().associateWith { bundle.get(it) }.takeIf { values ->
            values.values.all { it == null || it is String && it.length <= ABEMA_REPLAY_LENGTH_PROJECTION_BYTES || it is Int || it is Long }
        }
    }.getOrNull()
    fun start(value: Start) = Bundle().apply {
        putString("nonce", value.nonce); putString("instance", value.instanceId); putString("slots", value.request.key())
        putString("proof", value.ownerProof); putLong("deadline", value.deadline)
    }
    fun result(value: Result) = Bundle().apply {
        putString("nonce", value.nonce); putString("outcome", value.outcome)
        value.lengths?.let { putString("lengths", encodeAbemaReplayLengthProjection(it)) }
    }
}
