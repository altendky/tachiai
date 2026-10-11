package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*
import org.json.JSONArray
import org.json.JSONObject

// The retained first-party 248/7876 page contract joins references by both type
// and exact ID. A missing join is a failed page, never a guessed title, alias or
// an apparently successful empty account collection.
internal fun parseAbemaMyListResponse(body: String): AbemaMyListPage {
    if (body.length > ABEMA_MY_LIST_RESPONSE_LIMIT || body.toByteArray(Charsets.UTF_8).size > ABEMA_MY_LIST_RESPONSE_LIMIT)
        throw AbemaCollectionException(AbemaCollectionFailure.RESPONSE_LIMIT)
    try {
        MyListJsonPreflight(body).check()
        val root = JSONObject(body)
        val references = root.opt("mylist") as? JSONArray ?: invalidMyList()
        if (references.length() > ABEMA_MY_LIST_PAGE_LIMIT) limitedMyList()
        val rawDataset = root.opt("dataSet")
        val dataset = when (rawDataset) {
            null, JSONObject.NULL -> if (references.length() == 0) JSONObject() else invalidMyList()
            is JSONObject -> rawDataset
            else -> invalidMyList()
        }
        val types = listOf(
            MyListType("CONTENT_TYPE_PROGRAM", "programs", "episode", CatalogIntent.VIDEO, 128),
            MyListType("CONTENT_TYPE_SERIES", "series", "series", CatalogIntent.COLLECTION, 128),
            MyListType("CONTENT_TYPE_SLOT", "slots", "slot", CatalogIntent.BROADCAST, 64),
            MyListType("CONTENT_TYPE_SLOT_GROUP", "slotGroups", "slot_group", CatalogIntent.COLLECTION, 128),
            MyListType("CONTENT_TYPE_LIVE_EVENT", "liveEvents", "live_event", CatalogIntent.BROADCAST, 128),
        )
        val joins = HashMap<String, Map<String, CatalogEntry>>()
        var count = 0
        for (type in types) {
            val array = if (!dataset.has(type.array)) JSONArray() else dataset.opt(type.array) as? JSONArray ?: invalidMyList()
            count += array.length()
            if (count > 500) limitedMyList()
            val items = LinkedHashMap<String, CatalogEntry>()
            for (index in 0 until array.length()) {
                val item = array.opt(index) as? JSONObject ?: invalidMyList()
                val id = myListId(item.opt("id"), type.maxId)
                val title = item.opt("title") as? String ?: invalidMyList()
                if (!safeMyListTitle(title)) invalidMyList()
                val identity = if (type.kind == "slot") "${myListId(item.opt("channelId"), 64)}/$id" else id
                val entry = CatalogEntry(CatalogResource(ProviderId("abema"), type.kind, identity, type.intent),
                    title.trim(), CatalogAvailability.UNKNOWN)
                if (items.put(id, entry) != null) invalidMyList()
            }
            joins[type.wire] = items
        }
        val entries = LinkedHashMap<CatalogResource, CatalogEntry>()
        for (index in 0 until references.length()) {
            val reference = references.opt(index) as? JSONObject ?: invalidMyList()
            val wire = reference.opt("contentType") as? String ?: invalidMyList()
            val type = types.singleOrNull { it.wire == wire } ?: invalidMyList()
            val id = myListId(reference.opt("contentId"), type.maxId)
            if (reference.has("slotGroupId")) {
                val group = reference.opt("slotGroupId") as? String ?: invalidMyList()
                if (group.isNotEmpty()) myListId(group, 128)
            }
            if (reference.has("slotIds")) {
                val ids = reference.opt("slotIds") as? JSONArray ?: invalidMyList()
                if (ids.length() > 500) limitedMyList()
                for (slotIndex in 0 until ids.length()) myListId(ids.opt(slotIndex), 64)
            }
            val entry = joins[wire]?.get(id) ?: invalidMyList()
            entries.putIfAbsent(entry.resource, entry)
        }
        val paging = root.opt("paging")
        val next = when (paging) {
            null, JSONObject.NULL -> null
            is JSONObject -> if (!paging.has("next")) null else {
                val value = paging.opt("next") as? String ?: invalidMyList()
                if (value.isEmpty()) null else value.also { if (!validAbemaCollectionContinuation(it)) invalidMyList() }
            }
            else -> invalidMyList()
        }
        return AbemaMyListPage(entries.values.toList(), next)
    } catch (error: AbemaCollectionException) { throw error }
    catch (_: Exception) { invalidMyList() }
}

private data class MyListType(val wire: String, val array: String, val kind: String, val intent: CatalogIntent, val maxId: Int)
private fun myListId(value: Any?, maximum: Int): String = (value as? String)?.takeIf {
    it.length in 1..maximum && it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*"))
} ?: invalidMyList()

internal fun safeMyListTitle(value: String): Boolean {
    if (value.length !in 1..160 || value.trim().isEmpty()) return false
    var index = 0
    while (index < value.length) {
        val first = value[index++]
        val point = if (Character.isHighSurrogate(first)) {
            if (index == value.length || !Character.isLowSurrogate(value[index])) return false
            Character.toCodePoint(first, value[index++])
        } else {
            if (Character.isLowSurrogate(first)) return false
            first.code
        }
        if (Character.isISOControl(point) || Character.getType(point) == Character.FORMAT.toInt()) return false
    }
    return true
}

private fun invalidMyList(): Nothing = throw AbemaCollectionException(AbemaCollectionFailure.INVALID_RESPONSE)
private fun limitedMyList(): Nothing = throw AbemaCollectionException(AbemaCollectionFailure.RESPONSE_LIMIT)

// The platform JSON decoder is preceded by strict syntax/duplicate-key and
// Unicode validation, including ignored fields. Bound work before its recursion.
private class MyListJsonPreflight(private val text: String) {
    private var offset = 0
    private var values = 0
    fun check() {
        value(0); whitespace()
        if (offset != text.length) invalidMyList()
    }
    private fun value(depth: Int) {
        if (depth > 32 || ++values > 16_000) limitedMyList()
        whitespace()
        when (text.getOrNull(offset)) {
            '{' -> objectValue(depth)
            '[' -> arrayValue(depth)
            '"' -> stringValue()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            '-', in '0'..'9' -> numberValue()
            else -> invalidMyList()
        }
    }
    private fun objectValue(depth: Int) {
        offset++; whitespace()
        if (take('}')) return
        val keys = HashSet<String>()
        while (true) {
            whitespace()
            if (text.getOrNull(offset) != '"' || !keys.add(stringValue())) invalidMyList()
            whitespace(); if (!take(':')) invalidMyList()
            value(depth + 1); whitespace()
            if (take('}')) return
            if (!take(',')) invalidMyList()
        }
    }
    private fun arrayValue(depth: Int) {
        offset++; whitespace()
        if (take(']')) return
        while (true) {
            value(depth + 1); whitespace()
            if (take(']')) return
            if (!take(',')) invalidMyList()
        }
    }
    private fun stringValue(): String {
        if (!take('"')) invalidMyList()
        val decoded = StringBuilder()
        while (offset < text.length) {
            val char = text[offset++]
            when {
                char == '"' -> {
                    var index = 0
                    while (index < decoded.length) {
                        val current = decoded[index++]
                        if (Character.isHighSurrogate(current)) {
                            if (index == decoded.length || !Character.isLowSurrogate(decoded[index++])) invalidMyList()
                        } else if (Character.isLowSurrogate(current)) invalidMyList()
                    }
                    return decoded.toString()
                }
                char.code < 32 -> invalidMyList()
                char == '\\' -> when (val escaped = text.getOrNull(offset++)) {
                    '"', '\\', '/' -> decoded.append(escaped)
                    'b' -> decoded.append('\b')
                    'f' -> decoded.append('\u000c')
                    'n' -> decoded.append('\n')
                    'r' -> decoded.append('\r')
                    't' -> decoded.append('\t')
                    'u' -> {
                        if (offset + 4 > text.length) invalidMyList()
                        val hex = text.substring(offset, offset + 4)
                        if (hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) invalidMyList()
                        decoded.append(hex.toInt(16).toChar()); offset += 4
                    }
                    else -> invalidMyList()
                }
                else -> decoded.append(char)
            }
        }
        invalidMyList()
    }
    private fun literal(value: String) {
        if (!text.startsWith(value, offset)) invalidMyList()
        offset += value.length
    }
    private fun numberValue() {
        take('-')
        if (!take('0')) {
            val first = text.getOrNull(offset)
            if (first == null || first !in '1'..'9') invalidMyList()
            while (digit()) offset++
        }
        if (take('.')) digits()
        if (take('e') || take('E')) { if (!take('+')) take('-'); digits() }
    }
    private fun digits() { if (!digit()) invalidMyList(); while (digit()) offset++ }
    private fun digit() = text.getOrNull(offset)?.let { it in '0'..'9' } == true
    private fun take(char: Char): Boolean = if (text.getOrNull(offset) == char) { offset++; true } else false
    private fun whitespace() { while (offset < text.length && text[offset] in " \t\r\n") offset++ }
}
