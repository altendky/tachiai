package net.fstab.tachiai.provider.abema

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

// Only reviewed anonymous bootstrap APIs and the one initial license endpoint.
// Neither account/password routes nor provider pages are admitted.
internal object AbemaNativeBootstrapPolicy {
    const val ORIGIN = "https://abema.tv"
    const val PAGE = "$ORIGIN/_tachiai/native/index.html"
    const val CONTENT_SECURITY_POLICY = "default-src 'none'; script-src 'self'; " +
        "connect-src https://abema.tv/api/auth/login/guest https://api.p-c3-e.abema-tv.com/v1/media/token " +
        "https://api.abema.io/v1/channels https://api.abema.io/v1/video/programs/394-72_s10_p8529 " +
        "https://streaming-api-cf.p-c2-x.abema-tv.com/v1/playbackResources/ https://license.p-c3-e.abema-tv.com/abematv-dash; " +
        "img-src 'none'; media-src 'none'; frame-src 'none'; worker-src 'none'; " +
        "object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

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
    fun allowsNewsSource(uri: URI, approvedPrototype: Boolean = false): Boolean {
        val names = queryNames(uri) ?: return false
        if (!names.containsAll(setOf("t", "enc", "dt", "ut")) ||
            names.any { it !in setOf("t", "enc", "dt", "ut", "sw") }) return false
        val parameters = uri.rawQuery.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        return parameters["enc"] == "clear" && parameters["ut"] == "1" &&
            parameters["dt"] in setOf("pc_chrome", "pc_unknown") &&
            (parameters["sw"] == null || parameters["sw"] == "0") &&
            abemaNewsMediaUriPolicy(URI(uri.toString().substringBefore('?')), approvedPrototype) == AbemaNewsMediaUriPolicy.ALLOWED &&
            uri.rawPath.endsWith(".mpd") && uri.rawFragment == null
    }

    fun allowsNewsCdn(uri: URI, approvedPrototype: Boolean = false): Boolean = allowsNewsSource(uri, approvedPrototype) ||
        abemaNewsCdnUriPolicy(uri, approvedPrototype) == AbemaNewsMediaUriPolicy.ALLOWED

    fun allows(uri: URI, mainFrame: Boolean, method: String): Boolean {
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
            "abema.tv" -> uri.toString() == "$ORIGIN/api/auth/login/guest" && method == "POST"
            "api.p-c3-e.abema-tv.com" -> uri.path == "/v1/media/token" && method in setOf("GET", "OPTIONS") &&
                queryNames(uri) == setOf("osName", "osVersion", "osLang", "osTimezone", "appVersion")
            "api.abema.io" -> uri.path in setOf("/v1/channels", "/v1/video/programs/394-72_s10_p8529") && uri.rawQuery == null &&
                method in setOf("GET", "OPTIONS")
            "streaming-api-cf.p-c2-x.abema-tv.com" -> method == "GET" && uri.rawQuery == null &&
                Regex("/v1/playbackResources/[A-Za-z0-9_:.~-]{1,256}").matches(uri.path)
            "license.p-c3-e.abema-tv.com" -> uri.path == "/abematv-dash" && method in setOf("POST", "OPTIONS") &&
                queryNames(uri)?.let { "t" in it && it.all { name -> name in setOf("t", "ct", "cid", "tt", "tk", "dt") } } == true &&
                uri.rawQuery?.split('&')?.filter { it.startsWith("dt=") }?.all { it == "dt=web_android" } == true
            else -> false
        }
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
