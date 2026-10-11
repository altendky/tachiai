package net.fstab.tachiai.provider.abema

import java.net.URI
import org.json.JSONObject

// Canonical app-owned JSON only. Provider responses and the fresh token are
// checked in the owning JS runtime; this seam cannot establish token freshness.
internal fun parseAbemaSlotSourceBinding(text: String?, expectedChannelId: String, expectedSlotId: String): URI? = runCatching {
    if (!validAbemaSlotSourceOwner(expectedChannelId, expectedSlotId) || text == null || text.length !in 1..4096) return null
    checkAbemaCollectionJson(text)
    val jsonString = """"(?:[^"\\\x00-\x1f]|\\(?:["\\/bfnrt]|u[0-9a-fA-F]{4}))*""""
    if (!Regex("""\{"channelId":$jsonString,"slotId":$jsonString,"uri":$jsonString\}""").matches(text)) return null
    val value = JSONObject(text)
    if (value.length() != 3 || value.opt("channelId") != expectedChannelId || value.opt("slotId") != expectedSlotId) return null
    val selected = value.opt("uri") as? String ?: return null
    val uri = URI(selected).takeIf { allowsAbemaSlotSource(it, expectedChannelId, expectedSlotId) } ?: return null
    // Admitted IDs and URI use ASCII without JSON escapes. Android's quote()
    // escapes solidus, unlike JSON.stringify; do not use it as the wire format.
    val canonical = "{\"channelId\":\"$expectedChannelId\",\"slotId\":\"$expectedSlotId\",\"uri\":\"$selected\"}"
    if (text != canonical) return null
    uri
}.getOrNull()
