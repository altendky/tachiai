package net.fstab.tachiai.provider.abema

import java.net.URI
import org.json.JSONObject

// App-owned canonical projection only; provider metadata and the fresh token
// stay in the owning JS runtime. The signed URI is transient native input.
internal fun parseAbemaChannelSourceBinding(text: String?, expectedId: String, modernChannelContext: Boolean = false): URI? = runCatching {
    if (!validAbemaChannelIdentity(expectedId) || text == null || text.length !in 1..4096) return null
    val jsonString = """"(?:[^"\\\x00-\x1f]|\\(?:["\\/bfnrt]|u[0-9a-fA-F]{4}))*""""
    if (!Regex("""\{"channelId":$jsonString,"uri":$jsonString\}""").matches(text)) return null
    val value = JSONObject(text)
    if (value.length() != 2 || value.opt("channelId") != expectedId) return null
    val uri = URI(value.opt("uri") as? String ?: return null)
    uri.takeIf { AbemaNativeBootstrapPolicy.allowsNewsSource(it, approvedPrototype = true, channelId = expectedId,
        modernChannelContext = modernChannelContext) }
}.getOrNull()
