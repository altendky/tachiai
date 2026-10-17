package net.fstab.tachiai.provider.abema

import java.net.URI
import org.json.JSONObject

// Read only an app-owned stringify projection. Provider metadata was already
// bounded/duplicate-checked in JS; no bearer/license fields cross this seam.
internal fun parseAbemaEpisodeSourceBinding(text: String?, expectedId: String): URI? = runCatching {
    if (!validAbemaEpisodeIdentity(expectedId) || text == null || text.length !in 1..4096) return null
    val jsonString = """"(?:[^"\\\x00-\x1f]|\\(?:["\\/bfnrt]|u[0-9a-fA-F]{4}))*""""
    if (!Regex("""\{"episodeId":$jsonString,"uri":$jsonString\}""").matches(text)) return null
    val value = JSONObject(text)
    if (value.length() != 2 || value.opt("episodeId") != expectedId) return null
    val uri = URI(value.opt("uri") as? String ?: return null)
    uri.takeIf { abemaReplaySelectedSourcePolicy(it, AbemaReplayCdnScope.APPROVED_PROTOTYPE, expectedId) == AbemaReplayUriPolicy.ALLOWED }
}.getOrNull()
