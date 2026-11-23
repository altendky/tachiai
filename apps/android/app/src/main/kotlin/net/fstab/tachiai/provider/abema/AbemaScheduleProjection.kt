package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.provider.catalog.*
import org.json.JSONTokener

// Only the normalized, bounded public projection crosses the private IPC.
internal fun decodeAbemaScheduleProjection(text: String, expected: AbemaScheduleRequest = AbemaScheduleRequest()): AbemaScheduleSnapshot? = try {
    if (text.length > ABEMA_SCHEDULE_PROJECTION_BYTES ||
        text.toByteArray(Charsets.UTF_8).size > ABEMA_SCHEDULE_PROJECTION_BYTES) null
    else ScheduleProjectionReader(text, expected).read()
} catch (_: Throwable) { null }

private class ScheduleProjectionReader(private val text: String, private val expected: AbemaScheduleRequest) {
    private var at = 0
    private fun space() { while (at < text.length && text[at] in " \t\r\n") at++ }
    private fun take(char: Char): Boolean { space(); if (text.getOrNull(at) != char) return false; at++; return true }
    private fun need(char: Char) { check(take(char)) }
    private fun string(): String {
        space(); val start = at; need('"')
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
        error("Schedule projection refused")
    }
    private fun fields(allowed: Set<String>, optional: Set<String> = emptySet(), read: (String) -> Unit) {
        need('{'); val seen = mutableSetOf<String>()
        if (!take('}')) {
            do { val key = string(); check((key in allowed || key in optional) && seen.add(key)); need(':'); read(key) } while (take(','))
            need('}')
        }
        check(seen - optional == allowed)
    }
    private fun seconds(): Long {
        space()
        val start = at
        while (at < text.length && text[at] in '0'..'9') at++
        val digits = text.substring(start, at)
        check(digits.length in 1..6 && digits[0] != '0')
        return digits.toLong().also { check(it in 1..MAX_CATALOG_DURATION_SECONDS) }
    }
    // Epoch seconds or null (#560), only beside a channel-filtered snapshot's slots.
    private fun epochSeconds(): Long? {
        space()
        if (text.startsWith("null", at)) { at += 4; return null }
        val start = at
        while (at < text.length && text[at] in '0'..'9') at++
        val digits = text.substring(start, at)
        check(digits.length in 1..11 && digits[0] != '0')
        return digits.toLong().also { check(it in 1..ABEMA_SCHEDULE_MAX_EPOCH_SECONDS) }
    }
    fun read(): AbemaScheduleSnapshot {
        val entries = mutableListOf<CatalogEntry>(); var truncated = false
        val filtered = expected.channelId != null
        val times = mutableListOf<AbemaSlotTimes?>()
        fields(setOf("slots", "truncated") + if (expected.channelId != null) setOf("channelId") else emptySet()) { key -> when (key) {
            "channelId" -> check(string() == expected.channelId)
            "truncated" -> {
                space()
                if (text.startsWith("true", at)) { truncated = true; at += 4 }
                else { check(text.startsWith("false", at)); at += 5 }
            }
            "slots" -> {
                need('[')
                if (!take(']')) {
                    do {
                        check(entries.size < ABEMA_SCHEDULE_PREFIX_LIMIT)
                        var id: String? = null; var channel: String? = null; var title: String? = null
                        var duration: Long? = null; var startAt: Long? = null; var endAt: Long? = null
                        fields(setOf("id", "channelId", "title") + if (filtered) setOf("startAt", "endAt") else emptySet(),
                            setOf("durationSeconds")) { field -> when (field) {
                            "id" -> id = string(); "channelId" -> channel = string(); "title" -> title = string()
                            "startAt" -> startAt = epochSeconds(); "endAt" -> endAt = epochSeconds()
                            else -> duration = seconds()
                        } }
                        val request = AbemaSlotRequest(checkNotNull(channel), checkNotNull(id))
                        check(safeAbemaSeriesText(checkNotNull(title), 160))
                        entries += CatalogEntry(request.resource, title!!, durationSeconds = duration)
                        // Both times or neither; known times agree with the projected length.
                        check((startAt == null) == (endAt == null))
                        val slotTimes = startAt?.let { AbemaSlotTimes(it, checkNotNull(endAt)) }
                        check(slotTimes == null || duration == slotTimes.endEpochSeconds - slotTimes.startEpochSeconds)
                        if (filtered) times += slotTimes
                    } while (take(','))
                    need(']')
                }
            }
        } }
        space(); check(at == text.length)
        return AbemaScheduleSnapshot(entries, truncated, expected, times.takeIf { filtered })
    }
}
