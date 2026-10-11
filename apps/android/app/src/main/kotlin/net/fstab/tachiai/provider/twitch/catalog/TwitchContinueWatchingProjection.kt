package net.fstab.tachiai.provider.twitch.catalog

import java.time.Instant
import java.util.Collections
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*
import net.fstab.tachiai.provider.twitch.TwitchAccessCase
import net.fstab.tachiai.provider.twitch.validTwitchPlaybackResource
import org.json.JSONArray
import org.json.JSONObject

internal const val TWITCH_CONTINUE_WATCHING_ID = "continue_watching"
internal const val TWITCH_CONTINUE_WATCHING_LIMIT = 20
internal const val TWITCH_CONTINUE_WATCHING_BYTES = 64 * 1024

// Deliberately no account, progress, viewed timestamp, URLs or provider cursor.
internal class TwitchContinueWatchingPage(val userId: String, entries: List<CatalogEntry>) {
    val entries: List<CatalogEntry> = Collections.unmodifiableList(ArrayList(entries))
    init {
        require(validCatalogUserId(userId))
        require(entries.size <= TWITCH_CONTINUE_WATCHING_LIMIT && entries.map { it.resource }.distinct().size == entries.size)
        require(entries.all { it.resource.providerId == ProviderId("twitch") && it.resource.kind == "video" &&
            it.resource.intent == CatalogIntent.VIDEO && validTwitchCatalogId(it.resource.identity) &&
            validTwitchPlaybackResource(TwitchAccessCase.REPLAY, it.resource.identity) &&
            it.availability == CatalogAvailability.UNKNOWN && it.scheduledStartEpochMs == null })
    }
    override fun toString() = "TwitchContinueWatchingPage(redacted)"
}

// Local scalar/bounds restrictions, pending real Smart TV grant acceptance.
// The first-party shelf uses seconds-ratio arithmetic, Date(updatedAt), an
// inclusive fourteen-day cutoff and <=95% by default. Preserve returned order.
internal fun parseTwitchContinueWatching(body: String, expectedUserId: String, nowEpochMs: Long): TwitchContinueWatchingPage {
    fun invalid(): Nothing = throw TwitchContinueWatchingException(TwitchContinueWatchingFailure.INVALID_RESPONSE)
    try {
        if (!validCatalogUserId(expectedUserId) || nowEpochMs < 0) invalid()
        if (body.length > TWITCH_CONTINUE_WATCHING_BYTES || body.toByteArray(Charsets.UTF_8).size > TWITCH_CONTINUE_WATCHING_BYTES)
            throw TwitchContinueWatchingException(TwitchContinueWatchingFailure.RESPONSE_LIMIT)
        ContinueWatchingJson(body).check()
        val root = JSONObject(body)
        if (listOf("error", "message").any { root.has(it) && !root.isNull(it) }) invalid()
        if (root.has("errors")) {
            val errors = root.opt("errors") as? JSONArray ?: invalid()
            if (errors.length() != 0) invalid()
        }
        val data = root.opt("data") as? JSONObject ?: invalid()
        val user = data.opt("currentUser") as? JSONObject ?: invalid()
        if (user.opt("id") != expectedUserId) throw TwitchContinueWatchingException(TwitchContinueWatchingFailure.USER_MISMATCH)
        val videos = user.opt("viewedVideos") as? JSONObject ?: invalid()
        val edges = videos.opt("edges") as? JSONArray ?: invalid()
        if (edges.length() > TWITCH_CONTINUE_WATCHING_LIMIT)
            throw TwitchContinueWatchingException(TwitchContinueWatchingFailure.RESPONSE_LIMIT)
        val seen = HashSet<String>()
        val entries = ArrayList<CatalogEntry>()
        val cutoff = (nowEpochMs - 14 * 24 * 60 * 60 * 1000L).coerceAtLeast(0)
        fun seconds(value: Any?): Double {
            if (value !is Number) invalid()
            return value.toDouble().also { if (!it.isFinite() || it < 0 || it > 31_536_000) invalid() }
        }
        fun date(value: Any?): Long = when (value) {
            is Int -> value.toLong()
            is Long -> value
            is String -> {
                if (value.length !in 1..64 || value != value.trim()) invalid()
                Instant.parse(value).toEpochMilli()
            }
            else -> invalid()
        }.also { if (it !in 0..253_402_300_799_999L) invalid() }
        for (index in 0 until edges.length()) {
            val edge = edges.opt(index) as? JSONObject ?: invalid()
            val history = edge.opt("history") as? JSONObject ?: invalid()
            val node = edge.opt("node") as? JSONObject ?: invalid()
            val id = node.opt("id") as? String ?: invalid()
            if (!validTwitchCatalogId(id) || !validTwitchPlaybackResource(TwitchAccessCase.REPLAY, id) || !seen.add(id)) invalid()
            val owner = node.opt("owner") as? JSONObject ?: invalid()
            val ownerId = owner.opt("id") as? String ?: invalid()
            val login = owner.opt("login") as? String ?: invalid()
            if (!validTwitchCatalogId(ownerId) || !validCatalogLogin(login)) invalid()
            val title = node.opt("title") as? String ?: invalid()
            if (title.length !in 1..160) invalid()
            val entry = CatalogEntry(CatalogResource(ProviderId("twitch"), "video", id, CatalogIntent.VIDEO), title.trim())
            val position = seconds(history.opt("position"))
            val duration = seconds(node.opt("lengthSeconds"))
            val updated = date(history.opt("updatedAt"))
            if (position > 0 && duration > 0 && updated >= cutoff && position / duration * 100 <= 95.0) entries.add(entry)
        }
        return TwitchContinueWatchingPage(expectedUserId, entries)
    } catch (error: TwitchContinueWatchingException) { throw error }
    catch (_: Throwable) { invalid() }
}

// Strict syntax and duplicate/Unicode checks precede Android's permissive JSON
// decoder. Validate ignored fields too, with bounded depth/count/string work.
private class ContinueWatchingJson(private val text: String) {
    private var at = 0
    private var values = 0
    private fun invalid(): Nothing = throw TwitchContinueWatchingException(TwitchContinueWatchingFailure.INVALID_RESPONSE)
    private fun limited(): Nothing = throw TwitchContinueWatchingException(TwitchContinueWatchingFailure.RESPONSE_LIMIT)
    fun check() { value(0); whitespace(); if (at != text.length) invalid() }
    private fun value(depth: Int) {
        if (depth > 16 || ++values > 2048) limited()
        whitespace()
        when (text.getOrNull(at)) {
            '{' -> {
                at++; whitespace()
                if (take('}')) return
                val keys = HashSet<String>()
                while (true) {
                    whitespace(); if (text.getOrNull(at) != '"' || !keys.add(string())) invalid()
                    whitespace(); if (!take(':')) invalid()
                    value(depth + 1); whitespace(); if (take('}')) return
                    if (!take(',')) invalid()
                }
            }
            '[' -> {
                at++; whitespace(); if (take(']')) return
                while (true) { value(depth + 1); whitespace(); if (take(']')) return; if (!take(',')) invalid() }
            }
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            '-', in '0'..'9' -> number()
            else -> invalid()
        }
    }
    private fun string(): String {
        if (!take('"')) invalid()
        val decoded = StringBuilder()
        while (at < text.length) {
            val char = text[at++]
            when {
                char == '"' -> {
                    var index = 0
                    while (index < decoded.length) {
                        val first = decoded[index++]
                        if (Character.isHighSurrogate(first)) {
                            if (index == decoded.length || !Character.isLowSurrogate(decoded[index++])) invalid()
                        } else if (Character.isLowSurrogate(first)) invalid()
                    }
                    return decoded.toString()
                }
                char.code < 32 -> invalid()
                char == '\\' -> when (val escaped = text.getOrNull(at++)) {
                    '"', '\\', '/' -> decoded.append(escaped)
                    'b' -> decoded.append('\b')
                    'f' -> decoded.append('\u000c')
                    'n' -> decoded.append('\n')
                    'r' -> decoded.append('\r')
                    't' -> decoded.append('\t')
                    'u' -> {
                        if (at + 4 > text.length) invalid()
                        val hex = text.substring(at, at + 4)
                        if (hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) invalid()
                        decoded.append(hex.toInt(16).toChar()); at += 4
                    }
                    else -> invalid()
                }
                else -> decoded.append(char)
            }
            if (decoded.length > 4096) limited()
        }
        invalid()
    }
    private fun number() {
        val start = at
        take('-')
        if (!take('0')) { val first = text.getOrNull(at); if (first == null || first !in '1'..'9') invalid(); digits() }
        if (take('.')) digits()
        if (take('e') || take('E')) { if (!take('+')) take('-'); digits() }
        if (at - start > 64) limited()
        if (text.substring(start, at).toDoubleOrNull()?.isFinite() != true) invalid()
    }
    private fun digits() { if (!digit()) invalid(); while (digit()) at++ }
    private fun digit() = text.getOrNull(at)?.let { it in '0'..'9' } == true
    private fun literal(value: String) { if (!text.startsWith(value, at)) invalid(); at += value.length }
    private fun take(char: Char): Boolean = if (text.getOrNull(at) == char) { at++; true } else false
    private fun whitespace() { while (text.getOrNull(at)?.let { it in " \t\r\n" } == true) at++ }
}
