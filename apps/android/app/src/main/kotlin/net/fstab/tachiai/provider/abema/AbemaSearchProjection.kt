package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.provider.catalog.CatalogEntry
import org.json.JSONArray
import org.json.JSONObject

// Normalized public metadata only. Raw provider JSON is checked inside the
// isolated guest runtime; duplicate keys/Unicode are checked again at IPC.
internal fun decodeAbemaSearchProjection(text: String, request: AbemaSearchRequest): AbemaSearchReply? = runCatching {
    check(text.length <= ABEMA_SEARCH_PROJECTION_BYTES && text.toByteArray(Charsets.UTF_8).size <= ABEMA_SEARCH_PROJECTION_BYTES)
    checkAbemaCollectionJson(text)
    fun fields(value: JSONObject, expected: Set<String>) { check(value.keys().asSequence().toSet() == expected) }
    fun string(value: JSONObject, key: String) = value.opt(key) as? String ?: error("Search projection refused")
    val root = JSONObject(text); fields(root, setOf("modules"))
    val modules = root.opt("modules") as? JSONArray ?: error("Search projection refused")
    check(modules.length() <= ABEMA_SEARCH_MODULE_IDS.size)
    AbemaSearchReply(request, (0 until modules.length()).map { index ->
        val module = modules.opt(index) as? JSONObject ?: error("Search projection refused")
        fields(module, setOf("id", "title", "total", "entries"))
        val total = module.opt("total") as? Int ?: error("Search projection refused")
        val rows = module.opt("entries") as? JSONArray ?: error("Search projection refused")
        check(rows.length() <= if (request.moduleId == null) 8 else 20)
        AbemaSearchModule(string(module, "id"), string(module, "title"), total, (0 until rows.length()).map { row ->
            val item = rows.opt(row) as? JSONObject ?: error("Search projection refused")
            fields(item, setOf("kind", "id", "title"))
            CatalogEntry(checkNotNull(abemaSearchResource(string(item, "kind"), string(item, "id"))), string(item, "title"))
        })
    })
}.getOrNull()
