package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*
import org.json.JSONTokener

// Strict normalized projection, not a raw provider DTO. JS validates each full
// contentlist document before discarding all unrelated metadata/source fields.
internal fun decodeAbemaSeriesProjection(text: String, request: AbemaSeriesPageRequest): AbemaSeriesPage? = try {
    if (text.length > 32768 || text.toByteArray(Charsets.UTF_8).size > 32768) null else SeriesProjectionReader(text).read(request)
} catch (_: Throwable) { null }

private class SeriesProjectionReader(private val text: String) {
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
        error("Series projection refused")
    }
    private fun objectFields(allowed: Set<String>, optional: Set<String> = emptySet(), value: (String) -> Unit) {
        need('{'); val seen = mutableSetOf<String>()
        if (!take('}')) {
            do { val key = string(); check((key in allowed || key in optional) && seen.add(key)); need(':'); value(key) } while (take(','))
            need('}')
        }
        check(seen - optional == allowed)
    }
    private fun digits(maximum: Int): Long {
        space()
        val start = at
        while (at < text.length && text[at] in '0'..'9') at++
        val digits = text.substring(start, at)
        check(digits.length in 1..maximum && digits[0] != '0')
        return digits.toLong()
    }
    private fun seconds() = digits(6).also { check(it in 1..MAX_CATALOG_DURATION_SECONDS) }
    private fun bool(): Boolean {
        space()
        for ((word, value) in listOf("true" to true, "false" to false)) if (text.startsWith(word, at)) { at += word.length; return value }
        error("Series projection refused")
    }
    private fun array(read: () -> Unit) {
        need('[')
        if (!take(']')) { do read() while (take(',')); need(']') }
    }
    fun read(request: AbemaSeriesPageRequest): AbemaSeriesPage {
        var parent: String? = null; var season: String? = null; var seasonName: String? = null; var group: String? = null
        var groups: MutableList<AbemaSeriesGroup>? = null; var types: MutableList<Int>? = null; var more: Boolean? = null
        val entries = mutableListOf<CatalogEntry>()
        objectFields(setOf("seriesId", "viewingTypes", "items", "more"), setOf("seasonId", "seasonName", "groups", "groupId")) { key -> when (key) {
            "seriesId" -> parent = string()
            "seasonId" -> season = string()
            "seasonName" -> seasonName = string()
            "groupId" -> group = string()
            "more" -> more = bool()
            "viewingTypes" -> { val values = mutableListOf<Int>(); types = values
                array { check(values.size < 4); values += digits(2).toInt() } }
            "groups" -> { val values = mutableListOf<AbemaSeriesGroup>(); groups = values
                array {
                    check(values.size < ABEMA_SERIES_MAX_GROUPS)
                    var id: String? = null; var name: String? = null
                    objectFields(setOf("id", "name")) { field -> if (field == "id") id = string() else name = string() }
                    values += AbemaSeriesGroup(checkNotNull(id), checkNotNull(name))
                } }
            else -> array {
                check(entries.size < ABEMA_SERIES_PAGE_LIMIT)
                var kind: String? = null; var id: String? = null; var channel: String? = null; var title: String? = null; var duration: Long? = null
                objectFields(setOf("kind", "id", "title"), setOf("channelId", "durationSeconds")) { field -> when (field) {
                    "kind" -> kind = string(); "id" -> id = string(); "channelId" -> channel = string()
                    "title" -> title = string(); else -> duration = seconds()
                } }
                check(safeAbemaSeriesText(checkNotNull(title), 160))
                val resource = when (kind) {
                    "episode" -> { check(channel == null && validAbemaSeriesId(checkNotNull(id)))
                        CatalogResource(ProviderId("abema"), "episode", id!!, CatalogIntent.VIDEO) }
                    "slot" -> AbemaSlotRequest(checkNotNull(channel), checkNotNull(id)).resource
                    else -> error("Series projection refused")
                }
                entries += CatalogEntry(resource, title!!, durationSeconds = duration)
            }
        } }
        space(); check(at == text.length)
        return AbemaSeriesPage(checkNotNull(parent), season, seasonName, groups, group, checkNotNull(types), entries, checkNotNull(more))
            .also { check(it.answers(request)) }
    }
}
