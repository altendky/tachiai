package net.fstab.tachiai.provider.abema

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

// Only reviewed anonymous bootstrap APIs and the one initial license endpoint.
// Account/password routes and provider navigation remain refused. Exact-slot
// preparation may read one fixed public page as data without loading its code.
internal object AbemaNativeBootstrapPolicy {
    const val ORIGIN = "https://abema.tv"
    const val PAGE = "$ORIGIN/_tachiai/native/index.html"
    const val CONTENT_SECURITY_POLICY = "default-src 'none'; script-src 'self'; " +
        "connect-src https://abema.tv/api/auth/login/guest https://api.p-c3-e.abema-tv.com/v1/media/token " +
        "https://api.abema.io/v1/channels https://api.abema.io/v1/video/programs/394-72_s10_p8529 " +
        "https://streaming-api-cf.p-c2-x.abema-tv.com/v1/playbackResources/ https://license.p-c3-e.abema-tv.com/abematv-dash; " +
        "img-src 'none'; media-src 'none'; frame-src 'none'; worker-src 'none'; " +
        "object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

    const val SLOT_REGION_PAGE = "$ORIGIN/now-on-air/abema-news"
    const val SLOT_REGION_URI = "https://ds-glb-linear-abematv.akamaized.net/region"
    val SLOT_REGION_URIS = setOf(SLOT_REGION_URI, "https://ds-linear-abematv.akamaized.net/region")
    fun contentSecurityPolicy(episodeId: String? = null, channelId: String? = null, slotIdentity: String? = null,
        modernChannelContext: Boolean = false, continuousSlotPlayback: Boolean = false): String {
        require(listOfNotNull(episodeId, channelId, slotIdentity).size <= 1)
        require(!continuousSlotPlayback || slotIdentity != null)
        require(!modernChannelContext || channelId != null && episodeId == null && slotIdentity == null ||
            continuousSlotPlayback && slotIdentity != null)
        if (slotIdentity != null) {
            require(validAbemaSlotIdentity(slotIdentity))
            val policy = CONTENT_SECURITY_POLICY.replace("https://api.abema.io/v1/channels ",
                if (!continuousSlotPlayback) "" else if (modernChannelContext)
                    "https://api.p-c3-e.abema-tv.com/v1/channels https://api.p-c3-e.abema-tv.com/v1/media/deviceTypeId "
                else "https://api.abema.io/v1/channels ")
                .replace("https://api.abema.io/v1/video/programs/394-72_s10_p8529", slotUrl(slotIdentity.substringAfter('/')))
                .replace("https://streaming-api-cf.p-c2-x.abema-tv.com/v1/playbackResources/ ", "$SLOT_REGION_PAGE ${SLOT_REGION_URIS.joinToString(" ")} ")
            return if (modernChannelContext) policy.replace("https://abema.tv/api/auth/login/guest ", "") else policy
        }
        if (channelId != null) {
            require(validAbemaChannelIdentity(channelId))
            val policy = CONTENT_SECURITY_POLICY.replace("https://api.abema.io/v1/video/programs/394-72_s10_p8529 ", "")
                .replace("https://streaming-api-cf.p-c2-x.abema-tv.com/v1/playbackResources/ ",
                    if (modernChannelContext) "$SLOT_REGION_PAGE ${SLOT_REGION_URIS.joinToString(" ")} " else "")
            return if (modernChannelContext) policy.replace("https://api.abema.io/v1/channels",
                "https://api.p-c3-e.abema-tv.com/v1/channels https://api.p-c3-e.abema-tv.com/v1/media/deviceTypeId")
                .replace("https://abema.tv/api/auth/login/guest ", "")
                .replace(" https://license.p-c3-e.abema-tv.com/abematv-dash", "") else policy
        }
        if (episodeId == null) return CONTENT_SECURITY_POLICY
        require(validAbemaEpisodeIdentity(episodeId))
        return CONTENT_SECURITY_POLICY.replace("https://api.abema.io/v1/channels ", "")
            .replace("https://api.abema.io/v1/video/programs/394-72_s10_p8529", episodeUrl(episodeId))
    }
    private fun episodeUrl(id: String) = "https://api.p-c3-e.abema-tv.com/v1/video/programs/$id"
    private fun slotUrl(id: String) = "https://api.p-c3-e.abema-tv.com/v1/media/slots/$id"

    // Closed shape categories only: never return a host, path, query or ARIN.
    fun refusalCategory(uri: URI?, mainFrame: Boolean, method: String): String = when {
        uri == null -> "INVALID_URI"
        uri.scheme != "https" || uri.port != -1 || uri.rawUserInfo != null || uri.rawFragment != null -> "AUTHORITY"
        uri.toString().length > 4096 -> "TOO_LONG"
        uri.rawPath != uri.path -> "ENCODED_PATH"
        uri.normalize() != uri -> "PATH_NORMALIZATION"
        mainFrame -> "MAIN_FRAME"
        uri.host == "streaming-api-cf.p-c2-x.abema-tv.com" && uri.path.startsWith("/v1/playbackResources/") -> when {
            method == "OPTIONS" -> "GATEWAY_OPTIONS"
            method != "GET" -> "GATEWAY_METHOD"
            uri.rawQuery != null -> "GATEWAY_QUERY"
            uri.path.substringAfter("/v1/playbackResources/").length > 256 -> "GATEWAY_TOO_LONG"
            '/' in uri.path.substringAfter("/v1/playbackResources/") -> "GATEWAY_SLASH"
            uri.path.any { it.code > 127 } -> "GATEWAY_NON_ASCII"
            uri.path.substringAfter("/v1/playbackResources/").any { it == '.' || it == '~' } -> "GATEWAY_DOT_TILDE"
            else -> "GATEWAY_OTHER_SHAPE"
        }
        else -> "OTHER_POLICY"
    }

    // Preserve parameters emitted by the unchanged legacy source selector.
    // Only this selected manifest expands the historical query-free policy.
    fun allowsNewsSource(uri: URI, approvedPrototype: Boolean = false, channelId: String = "abema-news",
        modernChannelContext: Boolean = false): Boolean {
        val names = queryNames(uri) ?: return false
        if (!names.containsAll(setOf("t", "enc", "dt", "ut")) ||
            names.any { it !in setOf("t", "enc", "dt", "ut", "sw") && !(modernChannelContext && it == "dtid") }) return false
        val parameters = uri.rawQuery.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        if ("dtid" in names && canonicalQueryValue(parameters.getValue("dtid"))?.let(::validDeviceTypeId) != true) return false
        return parameters["enc"] == "clear" && parameters["ut"] == "1" &&
            parameters["dt"] in setOf("pc_chrome", "pc_unknown") &&
            (parameters["sw"] == null || parameters["sw"] == "0") &&
            abemaNewsMediaUriPolicy(URI(uri.toString().substringBefore('?')), approvedPrototype, channelId) == AbemaNewsMediaUriPolicy.ALLOWED &&
            uri.rawPath.endsWith(".mpd") && uri.rawFragment == null
    }

    fun allowsNewsCdn(uri: URI, approvedPrototype: Boolean = false, channelId: String = "abema-news",
        modernChannelContext: Boolean = false): Boolean = allowsNewsSource(uri, approvedPrototype, channelId, modernChannelContext) ||
        abemaNewsCdnUriPolicy(uri, approvedPrototype) == AbemaNewsMediaUriPolicy.ALLOWED

    fun allows(uri: URI, mainFrame: Boolean, method: String, episodeId: String? = null, channelId: String? = null,
        slotIdentity: String? = null, modernChannelContext: Boolean = false,
        continuousSlotPlayback: Boolean = false): Boolean {
        if (continuousSlotPlayback && slotIdentity == null) return false
        if (modernChannelContext && (episodeId != null ||
                if (continuousSlotPlayback) channelId != null else channelId == null || slotIdentity != null)) return false
        if (listOfNotNull(episodeId, channelId, slotIdentity).size > 1 ||
            slotIdentity != null && !validAbemaSlotIdentity(slotIdentity)) return false
        if (channelId != null && (!validAbemaChannelIdentity(channelId) || episodeId != null)) return false
        if (episodeId != null && !validAbemaEpisodeIdentity(episodeId)) return false
        if (uri.scheme != "https" || uri.port != -1 || uri.rawUserInfo != null || uri.rawFragment != null ||
            uri.toString().length > 4096 || uri.rawPath != uri.path || uri.normalize() != uri) return false
        if (mainFrame) return method == "GET" && uri.toString() == PAGE
        if (uri.host == "abema.tv" && uri.path.startsWith("/_tachiai/native/")) {
            return method == "GET" && uri.rawQuery == null && uri.toString() == "$ORIGIN${uri.path}" &&
                uri.path in setOf("/_tachiai/native/control.js", "/_tachiai/native/support.js",
                    "/_tachiai/native/bundle.js", "/_tachiai/native/legacy.js", "/_tachiai/native/utilities.js",
                    "/_tachiai/native/initialize.js")
        }
        return when (uri.host) {
            "abema.tv" -> !modernChannelContext && uri.toString() == "$ORIGIN/api/auth/login/guest" && method == "POST" ||
                (slotIdentity != null || modernChannelContext) && uri.toString() == SLOT_REGION_PAGE && method == "GET"
            "api.p-c3-e.abema-tv.com" -> if (uri.path == "/v1/media/token") method in setOf("GET", "OPTIONS") &&
                queryNames(uri) == setOf("osName", "osVersion", "osLang", "osTimezone", "appVersion")
                else if (modernChannelContext && uri.path == "/v1/media/deviceTypeId") method in setOf("GET", "OPTIONS") &&
                    modernQuery(uri)?.let { it.keys == setOf("os", "osVersion", "drm") &&
                        it["os"] in setOf("MacOS", "Windows", "unknown") &&
                        it["drm"] in setOf("Widevine", "FairPlay", "PlayReady", "unknown") &&
                        it.getValue("osVersion").let { value -> value.length <= 64 && value.all { it.code in 0x20..0x7e } } } == true
                else if (modernChannelContext && uri.path == "/v1/channels") method in setOf("GET", "OPTIONS") &&
                    modernQuery(uri)?.let { it.keys.all { name -> name in setOf("division", "dtid") } &&
                        (it["division"] == null || it["division"] == "1") &&
                        (it["dtid"] == null || validDeviceTypeId(it.getValue("dtid"))) } == true
                else if (slotIdentity != null) uri.toString() == slotUrl(slotIdentity.substringAfter('/')) + "?include=payperview" && method in setOf("GET", "OPTIONS")
                else episodeId != null && uri.toString() == episodeUrl(episodeId) && method in setOf("GET", "OPTIONS")
            "api.abema.io" -> !modernChannelContext && episodeId == null &&
                (slotIdentity == null && (uri.path == "/v1/channels" || channelId == null && uri.path == "/v1/video/programs/394-72_s10_p8529") ||
                    continuousSlotPlayback && uri.path == "/v1/channels") && uri.rawQuery == null &&
                method in setOf("GET", "OPTIONS")
            "streaming-api-cf.p-c2-x.abema-tv.com" -> slotIdentity == null && channelId == null && method == "GET" && uri.rawQuery == null &&
                Regex("/v1/playbackResources/[A-Za-z0-9_:.~-]{1,256}").matches(uri.path)
            "ds-glb-linear-abematv.akamaized.net", "ds-linear-abematv.akamaized.net" ->
                (slotIdentity != null || modernChannelContext) && method == "GET" && uri.toString() in SLOT_REGION_URIS
            "license.p-c3-e.abema-tv.com" -> (!modernChannelContext || continuousSlotPlayback) && uri.path == "/abematv-dash" && method in setOf("POST", "OPTIONS") &&
                queryNames(uri)?.let { "t" in it && it.all { name -> name in setOf("t", "ct", "cid", "tt", "tk", "dt") } } == true &&
                uri.rawQuery?.split('&')?.filter { it.startsWith("dt=") }?.all { it == "dt=web_android" } == true &&
                (episodeId == null || uri.rawQuery?.split('&')?.associate { it.substringBefore('=') to it.substringAfter('=') }
                    ?.let { it["ct"] == "program" && it["cid"] == episodeId } == true) &&
                (channelId == null || uri.rawQuery?.split('&')?.associate { it.substringBefore('=') to it.substringAfter('=') }
                    ?.let { it.keys == setOf("t", "ct", "cid") && it["ct"] == "channel" && it["cid"] == channelId } == true) &&
                (slotIdentity == null || uri.rawQuery?.split('&')?.associate { it.substringBefore('=') to it.substringAfter('=') }
                    ?.let { it.keys == setOf("t", "ct", "cid") &&
                        (it["ct"] == "slot" && it["cid"] == slotIdentity.substringAfter('/') ||
                            continuousSlotPlayback && it["ct"] == "channel" && it["cid"] == slotIdentity.substringBefore('/')) } == true)
            else -> false
        }
    }

    private fun validDeviceTypeId(value: String): Boolean = value.length in 1..256 && value.all { it.code in 0x21..0x7e }

    private fun canonicalQueryValue(raw: String): String? = try {
        URLDecoder.decode(raw, StandardCharsets.UTF_8.name()).takeIf {
            URLEncoder.encode(it, StandardCharsets.UTF_8.name()) == raw
        }
    } catch (_: IllegalArgumentException) { null }

    // Canonical URLSearchParams values, literal unique keys. Kept separate from
    // legacy admission so its existing query acceptance remains unchanged.
    private fun modernQuery(uri: URI): Map<String, String>? {
        val raw = uri.rawQuery ?: return emptyMap()
        if (raw.length !in 1..3072) return null
        val result = mutableMapOf<String, String>()
        for (pair in raw.split('&')) {
            if (pair.count { it == '=' } != 1) return null
            val key = pair.substringBefore('=')
            if (!Regex("[A-Za-z][A-Za-z0-9]{0,32}").matches(key) || key in result) return null
            result[key] = canonicalQueryValue(pair.substringAfter('=')) ?: return null
        }
        return result
    }

    private fun queryNames(uri: URI): Set<String>? {
        return try {
        val pairs = uri.rawQuery?.takeIf { it.length in 1..3072 }?.split('&') ?: return null
        val names = pairs.map { pair ->
            if (!pair.contains('=') || pair.substringAfter('=').isEmpty()) return null
            val raw = pair.substringBefore('=')
            val decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
            if (raw != decoded || !Regex("[A-Za-z][A-Za-z0-9]{0,32}").matches(decoded)) return null
            decoded
        }
        names.toSet().takeIf { it.size == names.size }
        } catch (_: Exception) { null }
    }
}
