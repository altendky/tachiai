package net.fstab.tachiai.provider.abema

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.UUID
import net.fstab.tachiai.platform.media.BoundedMediaRequests

internal enum class AbemaReplayUriPolicy { ALLOWED, AUTHORITY, USER_INFO, FRAGMENT, PATH, QUERY_SIZE, SOURCE_ROUTE }
internal enum class AbemaReplayCdnScope { HISTORICAL_EXACT, APPROVED_PROTOTYPE }

// Historical exact CDN by default; the cached prototype opts into its approved
// CDN family. Exact selected MPD. No signed query reconstruction,
// credential headers or browser cookies; the manifest owns segment resolution.
internal fun abemaReplayCdnUriPolicy(uri: URI, scope: AbemaReplayCdnScope = AbemaReplayCdnScope.HISTORICAL_EXACT): AbemaReplayUriPolicy = when {
    uri.toString().length > 2048 || uri.scheme != "https" ||
        !(if (scope == AbemaReplayCdnScope.HISTORICAL_EXACT) uri.host == "ds-vod-abematv.akamaized.net"
            else AbemaCdnApprovalPolicy.approvedHost(uri.host)) ||
        uri.port !in listOf(-1, 443) -> AbemaReplayUriPolicy.AUTHORITY
    uri.rawUserInfo != null -> AbemaReplayUriPolicy.USER_INFO
    uri.rawFragment != null -> AbemaReplayUriPolicy.FRAGMENT
    uri.rawPath == null || !Regex("[A-Za-z0-9_./~$-]+").matches(uri.rawPath) ||
        uri.rawPath.split('/').any { it == "." || it == ".." } || uri.normalize().rawPath != uri.rawPath -> AbemaReplayUriPolicy.PATH
    (uri.rawQuery?.length ?: 0) > 1024 -> AbemaReplayUriPolicy.QUERY_SIZE
    else -> AbemaReplayUriPolicy.ALLOWED
}

internal fun abemaReplaySelectedSourcePolicy(uri: URI, scope: AbemaReplayCdnScope = AbemaReplayCdnScope.HISTORICAL_EXACT): AbemaReplayUriPolicy {
    val common = abemaReplayCdnUriPolicy(uri, scope)
    return if (common == AbemaReplayUriPolicy.ALLOWED && (!uri.rawPath.endsWith(".mpd") ||
        !Regex("(^|/)394-72_s10_p8529([/.]|$)").containsMatchIn(uri.rawPath))) AbemaReplayUriPolicy.SOURCE_ROUTE else common
}

internal class AbemaReplayNativeSource(override val uri: URI, override val kids: List<UUID>) : AbemaNativeDashSource {
    override fun toString() = "AbemaReplayNativeSource(redacted)"
}

@UnstableApi
internal fun resolveAbemaReplayNativeSource(uri: URI, requests: BoundedMediaRequests,
    scope: AbemaReplayCdnScope = AbemaReplayCdnScope.HISTORICAL_EXACT): AbemaReplayNativeSource {
    if (abemaReplaySelectedSourcePolicy(uri, scope) != AbemaReplayUriPolicy.ALLOWED) throw IOException("Replay source refused")
    val source = requests.create(C.DATA_TYPE_MANIFEST)
    try {
        source.openUri(uri)
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = source.read(buffer, 0, buffer.size)
            if (count < 0) break
            if (bytes.size() + count > 256 * 1024) throw IOException("Replay manifest refused")
            bytes.write(buffer, 0, count)
        }
        val initialization = parseAbemaRequestInitialization(bytes.toString("UTF-8"))
            ?: throw IOException("Replay initialization refused")
        return AbemaReplayNativeSource(uri, initialization.kids)
    } catch (_: Exception) { throw IOException("Replay source unavailable") }
    finally { source.close() }
}

internal fun abemaReplayMuteOriginalVideoScript(): String = """
    (() => {
      if (window !== window.top || location.href !== 'https://abema.tv/video/episode/394-72_s10_p8529') return;
      const media = Array.from(document.querySelectorAll('video'))
        .find(candidate => candidate.offsetWidth > 0 && candidate.offsetHeight > 0);
      if (media) media.muted = true;
    })()
""".trimIndent()

// Closed timing readback only; never returns provider metadata or source URLs.
internal fun abemaReplayOriginalTimingScript(pause: Boolean): String = """
    (() => {
      if (window !== window.top || location.href !== 'https://abema.tv/video/episode/394-72_s10_p8529') return null;
      const media = Array.from(document.querySelectorAll('video'))
        .find(candidate => candidate.offsetWidth > 0 && candidate.offsetHeight > 0);
      if (!media) return null;
      if (__PAUSE__) media.pause();
      const position = Math.round(media.currentTime * 1000);
      return JSON.stringify({paused: media.paused, muted: media.muted,
        positionMs: Number.isFinite(position) && position >= 0 && position <= 86400000 ? position : null});
    })()
""".trimIndent().replace("__PAUSE__", pause.toString())
