package net.fstab.tachiai.provider.abema

import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.platform.net.boundedHttpsGet
import net.fstab.tachiai.presentation.ScrubPreviewSource
import net.fstab.tachiai.presentation.ScrubSpriteSheets

// ABEMA timeline thumbnails (#673). Slots and video programs carry a public
// `timelineThumbComponent` whose prefix names sprites `{prefix}/{600·k}.png`,
// one per 10 minutes: 1920×648 (the last one shorter), 10×6 tiles of 192×108,
// one tile per 10 s. Verified by fetching sprites on 2026-11-24; the host
// returns them without cookies or tokens (JPEG bytes despite the extension).
internal const val ABEMA_TIMELINE_HOST = "image.p-c2-x.abema-tv.com"
private const val ABEMA_TIMELINE_SHEET_SECONDS = 600L

internal enum class AbemaTimelineKind(val path: String) { SLOT("slots"), PROGRAM("programs") }

// The bootstrap's prefix, bound to the selected slot or program, or null.
internal fun abemaTimelineThumbnailPrefix(raw: String?, kind: AbemaTimelineKind, id: String): String? {
    if (raw == null || raw.length > 512 || !Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}").matches(id)) return null
    val expected = "https://$ABEMA_TIMELINE_HOST/media/${kind.path}/$id/"
    if (!raw.startsWith(expected) || !Regex("[A-Za-z0-9_-]{1,64}/timeline").matches(raw.substring(expected.length))) return null
    return raw
}

// The host's resizer, as ABEMA's web player uses it, returns a 1280-wide
// sheet (tiles of 128×72) of about 80 KB instead of about 300 KB, so a sheet
// arrives within a second through a distant route.
private const val ABEMA_TIMELINE_RESIZE = "?width=1280&quality=50"

internal fun abemaTimelineSheets(prefix: String): ScrubSpriteSheets =
    ScrubSpriteSheets(intervalMs = 10_000, columns = 10, rows = 6, tileWidth = 192, tileHeight = 108, tileCount = null) { index ->
        "$prefix/${ABEMA_TIMELINE_SHEET_SECONDS * index}.png$ABEMA_TIMELINE_RESIZE"
    }

internal fun allowedAbemaTimelineSprite(prefix: String, url: String): Boolean {
    if (!url.startsWith("$prefix/") || !url.endsWith(".png$ABEMA_TIMELINE_RESIZE")) return false
    val seconds = url.substring(prefix.length + 1).removeSuffix(".png$ABEMA_TIMELINE_RESIZE")
    return Regex("0|[1-9][0-9]{0,8}").matches(seconds) && seconds.toLong() % ABEMA_TIMELINE_SHEET_SECONDS == 0L
}

// Sprites go through the feed's own route, like its other provider traffic.
internal fun abemaScrubPreviewSource(prefix: String, open: (URL) -> HttpsURLConnection): ScrubPreviewSource {
    val sheets = abemaTimelineSheets(prefix)
    return ScrubPreviewSource("abema:$prefix", resolve = { sheets }) { url ->
        require(allowedAbemaTimelineSprite(prefix, url))
        boundedHttpsGet(open, URI(url), 2 * 1024 * 1024, "image/*")
    }
}
