package net.fstab.tachiai.provider.abema

import java.net.URI
import org.json.JSONObject

// Canonical app-owned JSON only. Provider responses and the fresh token are
// checked in the owning JS runtime; neither URI nor mode establishes authority.
internal class AbemaSlotPlaybackBinding(val uri: URI, val chase: Boolean, val linear: Boolean = false) {
    init { require(!chase || !linear) }
    override fun toString() = "AbemaSlotPlaybackBinding(redacted)"
}

internal fun parseAbemaSlotPlaybackBinding(text: String?, expectedChannelId: String,
    expectedSlotId: String, allowLinear: Boolean = false,
    modernChannelContext: Boolean = false): AbemaSlotPlaybackBinding? = runCatching {
    if (!validAbemaSlotSourceOwner(expectedChannelId, expectedSlotId) || text == null || text.length !in 1..4096) return null
    checkAbemaCollectionJson(text)
    val jsonString = """"(?:[^"\\\x00-\x1f]|\\(?:["\\/bfnrt]|u[0-9a-fA-F]{4}))*""""
    if (!Regex("""\{"channelId":$jsonString,"slotId":$jsonString,"uri":$jsonString(?:,"(?:chase|linear)":true)?\}""").matches(text)) return null
    val value = JSONObject(text)
    val chase = value.has("chase")
    val linear = value.has("linear")
    if (linear && !allowLinear || value.length() != (if (chase || linear) 4 else 3) ||
        chase && value.opt("chase") != true || linear && value.opt("linear") != true ||
        value.opt("channelId") != expectedChannelId || value.opt("slotId") != expectedSlotId) return null
    val selected = value.opt("uri") as? String ?: return null
    val uri = URI(selected).takeIf {
        if (linear) it.port == -1 && AbemaNativeBootstrapPolicy.allowsNewsSource(it, approvedPrototype = true,
            channelId = expectedChannelId, modernChannelContext = modernChannelContext)
        else allowsAbemaSlotSource(it, expectedChannelId, expectedSlotId)
    } ?: return null
    // Admitted IDs and URI use ASCII without JSON escapes. Android's quote()
    // escapes solidus, unlike JSON.stringify; do not use it as the wire format.
    val mode = if (chase) ",\"chase\":true" else if (linear) ",\"linear\":true" else ""
    val canonical = "{\"channelId\":\"$expectedChannelId\",\"slotId\":\"$expectedSlotId\",\"uri\":\"$selected\"$mode}"
    if (text != canonical) return null
    AbemaSlotPlaybackBinding(uri, chase, linear)
}.getOrNull()

// Ordinary-only compatibility: callers must explicitly adopt the mode-bearing
// contract and obtain their own fresh authority before enabling Chase. Linear
// playback additionally requires the caller's explicit continuing-channel intent.
internal fun parseAbemaSlotSourceBinding(text: String?, expectedChannelId: String, expectedSlotId: String): URI? =
    parseAbemaSlotPlaybackBinding(text, expectedChannelId, expectedSlotId)?.takeIf { !it.chase }?.uri
