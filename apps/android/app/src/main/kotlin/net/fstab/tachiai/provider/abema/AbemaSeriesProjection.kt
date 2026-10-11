package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*
import org.json.JSONTokener

// Strict normalized projection, not a raw provider DTO. JS validates the full
// provider document before discarding all unrelated metadata/source fields.
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
    private fun objectFields(allowed: Set<String>, value: (String) -> Unit) {
        need('{'); val seen = mutableSetOf<String>()
        if (!take('}')) {
            do { val key = string(); check(key in allowed && seen.add(key)); need(':'); value(key) } while (take(','))
            need('}')
        }
        check(seen == allowed)
    }
    fun read(request: AbemaSeriesPageRequest): AbemaSeriesPage {
        var parent: String? = null; var version: String? = null
        val entries = mutableListOf<CatalogEntry>()
        objectFields(setOf("seriesId", "version", "programs")) { key -> when (key) {
            "seriesId" -> parent = string()
            "version" -> version = string()
            "programs" -> {
                need('[')
                if (!take(']')) {
                    do {
                        check(entries.size < 20)
                        var id: String? = null; var title: String? = null
                        objectFields(setOf("id", "title")) { field -> if (field == "id") id = string() else title = string() }
                        check(validAbemaSeriesId(checkNotNull(id)) && safeAbemaSeriesText(checkNotNull(title), 160))
                        entries += CatalogEntry(CatalogResource(ProviderId("abema"), "episode", id!!, CatalogIntent.VIDEO), title!!)
                    } while (take(','))
                    need(']')
                }
            }
        } }
        space(); check(at == text.length && parent == request.seriesId)
        check(request.version == null || version == request.version)
        return AbemaSeriesPage(request.seriesId, entries, checkNotNull(version))
    }
}
