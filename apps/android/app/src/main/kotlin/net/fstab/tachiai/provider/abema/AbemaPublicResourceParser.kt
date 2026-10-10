package net.fstab.tachiai.provider.abema

import java.net.URI
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.CatalogEntry
import net.fstab.tachiai.provider.catalog.CatalogIntent
import net.fstab.tachiai.provider.catalog.CatalogResource

// Local syntax recognition only: no account, metadata, existence or playback
// check. Keep public identifiers, never the original URL or its rejected parts.
internal fun parseAbemaPublicResource(input: String): CatalogEntry? {
    if (input.length !in 1..2048 || input.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) return null
    val uri = runCatching { URI(input.trim(' ')) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || !uri.host.equals("abema.tv", ignoreCase = true) ||
        uri.port !in listOf(-1, 443) || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    val path = uri.rawPath ?: return null
    if ('%' in path) return null
    val parts = path.split('/')
    if (parts.firstOrNull() != "") return null
    fun identifier(value: String, maximum: Int) = value.length in 1..maximum &&
        value.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*"))
    val resource: CatalogResource
    val label: String
    when {
        parts.size == 3 && parts[1] == "channels" && identifier(parts[2], 64) -> {
            resource = CatalogResource(ProviderId("abema"), "channel", parts[2], CatalogIntent.CHANNEL)
            label = "ABEMA channel"
        }
        parts.size == 5 && parts[1] == "channels" && parts[3] == "slots" &&
            identifier(parts[2], 64) && identifier(parts[4], 64) -> {
            resource = CatalogResource(ProviderId("abema"), "slot", "${parts[2]}/${parts[4]}", CatalogIntent.BROADCAST)
            label = "ABEMA broadcast"
        }
        parts.size == 4 && parts[1] == "video" && parts[2] == "episode" && identifier(parts[3], 128) -> {
            resource = CatalogResource(ProviderId("abema"), "episode", parts[3], CatalogIntent.VIDEO)
            label = "ABEMA episode"
        }
        parts.size == 4 && parts[1] == "video" && parts[2] == "title" && identifier(parts[3], 128) -> {
            resource = CatalogResource(ProviderId("abema"), "series", parts[3], CatalogIntent.COLLECTION)
            label = "ABEMA series"
        }
        else -> return null
    }
    return CatalogEntry(resource, "$label · ${resource.identity}")
}
