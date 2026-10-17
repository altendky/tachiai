package net.fstab.tachiai.provider.abema

import java.net.URI
import org.json.JSONObject

// The helper's validated provider window survives takeover without keeping its
// page, credentials or URI. A nullable expiry means no finite selected window
// was declared, not an exemption from account, route, DRM or foreground checks.
internal class AbemaPlaybackAvailability(val expiresAtMs: Long?, initialWallMs: Long,
    private val wallClock: () -> Long = System::currentTimeMillis) {
    init { require(initialWallMs > 0 && (expiresAtMs == null ||
        expiresAtMs > initialWallMs && expiresAtMs <= 9_007_199_254_740_991L)) }
    private val lock = Any()
    private var minimumWallMs = initialWallMs
    private var refused = false
    // Sample under the same lock: two callers entering in a different order
    // must not turn normal scheduling delay into a permanent clock rollback.
    fun current(): Boolean = synchronized(lock) { currentLocked(wallClock()) }
    fun current(wallMs: Long): Boolean = synchronized(lock) { currentLocked(wallMs) }
    private fun currentLocked(wallMs: Long): Boolean {
        val okay = !refused && wallMs > 0 && wallMs >= minimumWallMs &&
            (expiresAtMs == null || wallMs < expiresAtMs)
        if (!okay) refused = true
        minimumWallMs = maxOf(minimumWallMs, wallMs)
        return okay
    }
    override fun toString() = "AbemaPlaybackAvailability(redacted)"
}

// Require a separate canonical app-owned projection tied to the exact URI
// already admitted by the source's identity/origin parser. Old identity-binding
// shapes remain strict and cannot silently omit this new lifetime evidence.
internal fun parseAbemaPlaybackAvailability(text: String?, expectedUri: URI,
    requireExpiry: Boolean, wallMs: Long): AbemaPlaybackAvailability? = runCatching {
    if (text == null || text.length !in 1..4096 || wallMs <= 0) return null
    val jsonString = """"(?:[^"\\\x00-\x1f]|\\(?:["\\/bfnrt]|u[0-9a-fA-F]{4}))*""""
    if (!Regex("""\{"uri":$jsonString,"expiresAtMs":(?:null|[1-9][0-9]{0,15})\}""").matches(text)) return null
    val value = JSONObject(text)
    if (value.length() != 2 || value.opt("uri") != expectedUri.toString()) return null
    val raw = value.opt("expiresAtMs")
    val expiry = if (raw === JSONObject.NULL) null else when (raw) {
        is Int -> raw.toLong()
        is Long -> raw
        else -> return null
    }
    if (requireExpiry && expiry == null || expiry != null &&
        (expiry <= wallMs || expiry > 9_007_199_254_740_991L)) return null
    val canonical = "{\"uri\":\"$expectedUri\",\"expiresAtMs\":${expiry ?: "null"}}"
    if (text != canonical) return null
    AbemaPlaybackAvailability(expiry, wallMs)
}.getOrNull()
