package net.fstab.tachiai.provider.abema

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import net.fstab.tachiai.platform.media.BoundedMediaRequests

internal enum class AbemaSlotUriPolicy { ALLOWED, SIZE, AUTHORITY, USER_INFO, FRAGMENT, PATH, QUERY, SLOT_OWNER, SLOT_SOURCE }

internal fun validAbemaSlotSourceOwner(channelId: String, slotId: String) =
    validAbemaChannelIdentity(channelId) && Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}").matches(slotId)

// The existing approved CDN family, not arbitrary Akamai hosts. MPD-declared
// files may have a different path: no guessed /slot/ root or neighbour wildcard.
internal fun abemaSlotCdnUriPolicy(uri: URI): AbemaSlotUriPolicy = when {
    uri.toString().length > 2048 -> AbemaSlotUriPolicy.SIZE
    uri.scheme != "https" || !AbemaCdnApprovalPolicy.approvedHost(uri.host) || uri.port != -1 -> AbemaSlotUriPolicy.AUTHORITY
    uri.rawUserInfo != null -> AbemaSlotUriPolicy.USER_INFO
    uri.rawFragment != null -> AbemaSlotUriPolicy.FRAGMENT
    uri.toString().any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() || it == '\\' } ||
        uri.rawPath == null || uri.rawPath != uri.path || !Regex("[A-Za-z0-9_./~$-]+").matches(uri.rawPath) ||
        uri.rawPath.split('/').any { it == "." || it == ".." } || uri.normalize() != uri -> AbemaSlotUriPolicy.PATH
    else -> AbemaSlotUriPolicy.ALLOWED
}

internal fun allowsAbemaSlotDeclaredMedia(uri: URI) = abemaSlotCdnUriPolicy(uri) == AbemaSlotUriPolicy.ALLOWED

// Closed initial legacy query. The owning JS binds t to its fresh media-token
// response; native syntax checks never claim freshness or provider entitlement.
private fun selectedSlotQuery(uri: URI): Boolean = runCatching {
    val pairs = uri.rawQuery?.takeIf { it.isNotEmpty() }?.split('&') ?: return false
    if (pairs.size !in 5..6 || pairs.any { it.count { char -> char == '=' } != 1 }) return false
    val fields = pairs.map { it.substringBefore('=') to it.substringAfter('=') }
    if (fields.map { it.first }.distinct().size != fields.size) return false
    val query = fields.toMap()
    if (query.keys != setOf("t", "enc", "dt", "ut", "ccf") && query.keys != setOf("t", "enc", "dt", "ut", "ccf", "sw")) return false
    if (query["enc"] != "clear" || query["dt"] !in setOf("pc_chrome", "pc_unknown") || query["ut"] != "1" ||
        query["ccf"] != "0" || query["sw"] != null && query["sw"] != "0") return false
    val raw = checkNotNull(query["t"])
    val token = URLDecoder.decode(raw, "UTF-8")
    safeAbemaSeriesText(token, 2048) && URLEncoder.encode(token, "UTF-8") == raw
}.getOrDefault(false)

internal fun abemaSlotSelectedSourcePolicy(uri: URI, channelId: String, slotId: String): AbemaSlotUriPolicy {
    if (!validAbemaSlotSourceOwner(channelId, slotId)) return AbemaSlotUriPolicy.SLOT_OWNER
    val common = abemaSlotCdnUriPolicy(uri)
    if (common != AbemaSlotUriPolicy.ALLOWED) return common
    if (!uri.rawPath.endsWith(".mpd") || slotId !in uri.rawPath.split('/')) return AbemaSlotUriPolicy.SLOT_SOURCE
    return if (selectedSlotQuery(uri)) AbemaSlotUriPolicy.ALLOWED else AbemaSlotUriPolicy.QUERY
}

internal fun allowsAbemaSlotSource(uri: URI, channelId: String, slotId: String) =
    abemaSlotSelectedSourcePolicy(uri, channelId, slotId) == AbemaSlotUriPolicy.ALLOWED

// Protected standard initialization only. No provider keys or opaque PSSH are
// decoded here; no Widevine source, clear initialization or fallback is added.
internal class AbemaSlotNativeSource(override val uri: URI, kids: List<UUID>, val channelId: String, val slotId: String) : AbemaNativeDashSource {
    override val kids: List<UUID> = Collections.unmodifiableList(ArrayList(kids))
    init {
        require(allowsAbemaSlotSource(uri, channelId, slotId))
        require(kids.size in 1..16 && kids.toSet().size == kids.size && UUID(0, 0) !in kids)
    }
    override fun toString() = "AbemaSlotNativeSource(redacted)"
}

// Failed Source.close retains its actual stream/request owner until process
// disposal. The existing requests/session cleanup admission remains authoritative.
private val retainedSlotManifestSources = ConcurrentLinkedQueue<Any>()

internal enum class AbemaSlotSourcePhase { OPEN, READ, BYTE_LIMIT, UTF8, INITIALIZATION, READY, CLEANUP }
// Structural observation only: no manifest, source URI, IDs or error objects.
internal data class AbemaSlotSourceDiagnostic(val phase: AbemaSlotSourcePhase, val bytes: Int,
    val initialization: AbemaInitializationDiagnostic? = null) {
    fun closedSummary() = "phase=${phase.name} bytes=$bytes " +
        (initialization?.closedSummary() ?: "initialization=UNOBSERVED")
}

@UnstableApi
internal fun resolveAbemaSlotNativeSource(uri: URI, requests: BoundedMediaRequests, channelId: String, slotId: String,
    onSourceDiagnostic: ((AbemaSlotSourceDiagnostic) -> Unit)? = null): AbemaSlotNativeSource {
    if (!allowsAbemaSlotSource(uri, channelId, slotId)) throw IOException("Slot source refused")
    val source = requests.create(C.DATA_TYPE_MANIFEST)
    val buffer = ByteArray(4096)
    var phase = AbemaSlotSourcePhase.OPEN
    var bytes = 0
    var initializationDiagnostic: AbemaInitializationDiagnostic? = null
    try {
        source.openUri(uri)
        phase = AbemaSlotSourcePhase.READ
        val body = ByteArrayOutputStream()
        while (true) {
            val count = source.read(buffer, 0, buffer.size)
            if (count < 0) break
            bytes = (body.size() + count).coerceAtMost(256 * 1024 + 1)
            if (body.size() + count > 256 * 1024) {
                phase = AbemaSlotSourcePhase.BYTE_LIMIT
                throw IOException("Slot manifest refused")
            }
            body.write(buffer, 0, count)
        }
        val raw = body.toByteArray()
        phase = AbemaSlotSourcePhase.UTF8
        val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString() }
        finally { raw.fill(0) }
        phase = AbemaSlotSourcePhase.INITIALIZATION
        val initialization = parseAbemaRequestInitialization(text, onSourceDiagnostic?.let {
            { value -> initializationDiagnostic = value }
        }) ?: throw IOException("Slot initialization refused")
        val result = AbemaSlotNativeSource(uri, initialization.kids, channelId, slotId)
        phase = AbemaSlotSourcePhase.READY
        return result
    } catch (_: Exception) { throw IOException("Slot source unavailable") }
    finally {
        buffer.fill(0)
        try { source.close() } catch (_: Throwable) {
            phase = AbemaSlotSourcePhase.CLEANUP
            retainedSlotManifestSources.add(source)
            throw IOException("Slot manifest cleanup unconfirmed")
        } finally {
            // Exactly one immutable snapshot follows the original close attempt.
            // Observer failures cannot change source acceptance or refusal.
            try { onSourceDiagnostic?.invoke(AbemaSlotSourceDiagnostic(phase, bytes, initializationDiagnostic)) }
            catch (_: Throwable) { }
        }
    }
}
