package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*
import org.json.JSONArray
import org.json.JSONObject

// Coherent first-party 9298/458 modules45468/95697: History references join
// timeshifts/vods/liveEvents by type and sourceId. Progress, viewing time,
// completion, status and entitlement are deliberately outside this projection.
internal fun parseAbemaHistoryResponse(body: String): AbemaMyListPage {
    if (body.length > ABEMA_MY_LIST_RESPONSE_LIMIT || body.toByteArray(Charsets.UTF_8).size > ABEMA_MY_LIST_RESPONSE_LIMIT)
        throw AbemaCollectionException(AbemaCollectionFailure.RESPONSE_LIMIT)
    try {
        checkAbemaCollectionJson(body)
        val root = JSONObject(body)
        val references = root.opt("histories") as? JSONArray ?: invalidHistory()
        if (references.length() > ABEMA_MY_LIST_PAGE_LIMIT) limitedHistory()
        val dataset = when (val raw = root.opt("dataSet")) {
            null, JSONObject.NULL -> if (references.length() == 0) JSONObject() else invalidHistory()
            is JSONObject -> raw
            else -> invalidHistory()
        }
        val types = listOf(
            HistoryType(1, "TIMESHIFT", "timeshifts", "slot", CatalogIntent.BROADCAST, 64),
            HistoryType(2, "VOD", "vods", "episode", CatalogIntent.VIDEO, 128),
            HistoryType(3, "LIVE_EVENT", "liveEvents", "live_event", CatalogIntent.BROADCAST, 128),
        )
        var count = 0
        val joins = HashMap<HistoryType, Map<String, CatalogEntry>>()
        for (type in types) {
            val items = if (!dataset.has(type.array)) JSONArray() else dataset.opt(type.array) as? JSONArray ?: invalidHistory()
            count += items.length()
            if (count > 500) limitedHistory()
            val entries = LinkedHashMap<String, CatalogEntry>()
            for (index in 0 until items.length()) {
                val item = items.opt(index) as? JSONObject ?: invalidHistory()
                val id = historyId(item.opt("id"), type.maximum)
                val title = item.opt("title") as? String ?: invalidHistory()
                if (!safeMyListTitle(title)) invalidHistory()
                val identity = if (type.kind == "slot") "${historyId(item.opt("channelId"), 64)}/$id" else id
                val entry = CatalogEntry(CatalogResource(ProviderId("abema"), type.kind, identity, type.intent), title.trim())
                if (entries.put(id, entry) != null) invalidHistory()
            }
            joins[type] = entries
        }
        val entries = LinkedHashMap<CatalogResource, CatalogEntry>()
        for (index in 0 until references.length()) {
            val reference = references.opt(index) as? JSONObject ?: invalidHistory()
            val wire = reference.opt("type")
            val type = types.singleOrNull {
                wire == it.symbol || wire is Number && wire.toDouble() == it.number.toDouble()
            } ?: invalidHistory()
            val id = historyId(reference.opt("sourceId"), type.maximum)
            val entry = joins[type]?.get(id) ?: invalidHistory()
            entries.putIfAbsent(entry.resource, entry)
        }
        val next = when (val paging = root.opt("paging")) {
            null, JSONObject.NULL -> null
            is JSONObject -> if (!paging.has("next")) null else {
                val value = paging.opt("next") as? String ?: invalidHistory()
                if (value.isEmpty()) null else value.also { if (!validAbemaCollectionContinuation(it)) invalidHistory() }
            }
            else -> invalidHistory()
        }
        return AbemaMyListPage(entries.values.toList(), next, AbemaCollectionKind.HISTORY)
    } catch (error: AbemaCollectionException) { throw error }
    catch (_: Exception) { invalidHistory() }
}

private data class HistoryType(val number: Int, val symbol: String, val array: String, val kind: String,
    val intent: CatalogIntent, val maximum: Int)
private fun historyId(value: Any?, maximum: Int): String = (value as? String)?.takeIf {
    it.length in 1..maximum && it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*"))
} ?: invalidHistory()
private fun invalidHistory(): Nothing = throw AbemaCollectionException(AbemaCollectionFailure.INVALID_RESPONSE)
private fun limitedHistory(): Nothing = throw AbemaCollectionException(AbemaCollectionFailure.RESPONSE_LIMIT)
