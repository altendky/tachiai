package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.provider.catalog.*
import org.json.JSONTokener

// Only the normalized, bounded public projection crosses the private IPC.
internal fun decodeAbemaScheduleProjection(text: String): AbemaScheduleSnapshot? = try {
    if (text.length > ABEMA_SCHEDULE_PROJECTION_BYTES ||
        text.toByteArray(Charsets.UTF_8).size > ABEMA_SCHEDULE_PROJECTION_BYTES) null
    else ScheduleProjectionReader(text).read()
} catch (_: Throwable) { null }

private class ScheduleProjectionReader(private val text: String) {
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
    private fun fields(allowed: Set<String>, read: (String) -> Unit) {
        need('{'); val seen = mutableSetOf<String>()
        if (!take('}')) {
            do { val key = string(); check(key in allowed && seen.add(key)); need(':'); read(key) } while (take(','))
            need('}')
        }
        check(seen == allowed)
    }
    fun read(): AbemaScheduleSnapshot {
        val entries = mutableListOf<CatalogEntry>(); var truncated = false
        fields(setOf("slots", "truncated")) { key -> when (key) {
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
                        fields(setOf("id", "channelId", "title")) { field -> when (field) {
                            "id" -> id = string(); "channelId" -> channel = string(); "title" -> title = string()
                        } }
                        val request = AbemaSlotRequest(checkNotNull(channel), checkNotNull(id))
                        check(safeAbemaSeriesText(checkNotNull(title), 160))
                        entries += CatalogEntry(request.resource, title!!)
                    } while (take(','))
                    need(']')
                }
            }
        } }
        space(); check(at == text.length)
        return AbemaScheduleSnapshot(entries, truncated)
    }
}
