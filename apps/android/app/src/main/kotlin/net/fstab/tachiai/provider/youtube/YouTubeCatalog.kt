package net.fstab.tachiai.provider.youtube

import java.net.URI
import java.util.Locale
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.presentation.validProviderInstanceId
import net.fstab.tachiai.provider.catalog.*

internal fun validYouTubeVideoId(value: String) = value.matches(Regex("[A-Za-z0-9_-]{11}"))

internal fun supportedYouTubeResource(resource: CatalogResource) = resource.providerId == ProviderId("youtube") &&
    resource.kind == "video" && resource.intent == CatalogIntent.VIDEO && validYouTubeVideoId(resource.identity)

internal fun parseYouTubeVideoId(input: String): String? {
    if (input.length !in 1..2048 || input.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) return null
    val value = input.trim()
    if (validYouTubeVideoId(value)) return value
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", true) || uri.rawUserInfo != null || uri.port !in listOf(-1, 443) ||
        uri.rawFragment != null || uri.rawPath != uri.path) return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    val id = when (host) {
        "youtu.be" -> Regex("/([A-Za-z0-9_-]{11})/?").matchEntire(uri.path)?.groupValues?.get(1)
        "youtube.com", "www.youtube.com", "m.youtube.com" -> when {
            uri.path == "/watch" -> {
                val ids = uri.rawQuery.orEmpty().split('&').filter { it.substringBefore('=') == "v" }
                ids.singleOrNull()?.substringAfter('=', "")
            }
            else -> Regex("/(?:embed|shorts|live)/([A-Za-z0-9_-]{11})/?").matchEntire(uri.path)?.groupValues?.get(1)
        }
        else -> null
    }
    return id?.takeIf(::validYouTubeVideoId)
}

// Local public identity import only. No request, account grant, metadata or
// availability is inferred from a syntactically valid YouTube URL.
internal class YouTubeCatalog(override val instanceId: String,
    private val canUse: () -> Boolean = { true }) : ProviderCatalog {
    override val providerId = ProviderId("youtube")
    @Volatile private var closed = false
    init { require(validProviderInstanceId(instanceId)) }
    private fun current() = !closed && canUse()
    private fun entry(resource: CatalogResource) = CatalogEntry(resource,
        "YouTube video ${resource.identity} (metadata not checked)", CatalogAvailability.UNKNOWN)
    override fun capabilities() = if (!current()) CatalogCapabilities() else CatalogCapabilities(
        lookup = CatalogAccess.AVAILABLE, playback = CatalogAccess.AVAILABLE,
        collections = listOf(CatalogCollection("subscriptions", "Subscriptions", CatalogAccess.AUTHORIZATION_REQUIRED)))
    override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> =
        CatalogResult.Failure(if (!current()) CatalogFailure.TEMPORARY else if (query.collectionId == "subscriptions")
            CatalogFailure.ACCESS_REQUIRED else CatalogFailure.UNSUPPORTED)
    override fun lookup(input: String): CatalogResult<CatalogEntry> {
        if (!current()) return CatalogResult.Failure(CatalogFailure.TEMPORARY)
        val id = parseYouTubeVideoId(input) ?: return CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
        return CatalogResult.Value(entry(CatalogResource(providerId, "video", id, CatalogIntent.VIDEO)))
    }
    override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> =
        CatalogResult.Failure(if (!current()) CatalogFailure.TEMPORARY else if (!supportedYouTubeResource(resource))
            CatalogFailure.INVALID_INPUT else CatalogFailure.NOT_VERIFIED)
    override fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource> =
        if (!current()) CatalogResult.Failure(CatalogFailure.TEMPORARY)
        else if (!supportedYouTubeResource(resource)) CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
        else CatalogResult.Value(CatalogPlaybackResource(resource))
    override fun close() { closed = true }
    override fun toString() = "YouTubeCatalog(redacted)"
}
