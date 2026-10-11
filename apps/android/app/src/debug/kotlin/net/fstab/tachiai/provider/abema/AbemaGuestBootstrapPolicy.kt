package net.fstab.tachiai.provider.abema

import java.net.URI

internal object AbemaGuestBootstrapPolicy {
    const val PROFILE_SUFFIX = "abema-guest-bootstrap"
    const val PAGE = "https://abema.tv/_tachiai/guest/index.html"
    const val GUEST = "https://abema.tv/api/auth/login/guest"
    const val CONTENT_SECURITY_POLICY = "default-src 'none'; script-src 'self'; " +
        "connect-src https://abema.tv/api/auth/login/guest; " +
        "img-src 'none'; media-src 'none'; frame-src 'none'; worker-src 'none'; " +
        "object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

    fun allows(uri: URI, mainFrame: Boolean, method: String): Boolean {
        if (uri.scheme != "https" || uri.host != "abema.tv" || uri.port != -1 ||
            uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            uri.rawPath != uri.path || uri.normalize() != uri) return false
        if (mainFrame) return method == "GET" && uri.toString() == PAGE
        if (uri.toString() == GUEST) return method == "POST"
        return method == "GET" && uri.toString() == "https://abema.tv${uri.path}" &&
            uri.path in setOf("/_tachiai/guest/control.js", "/_tachiai/guest/support.js",
                "/_tachiai/guest/utilities.js", "/_tachiai/guest/bundle.js",
                "/_tachiai/guest/legacy.js", "/_tachiai/guest/initialize.js")
    }
}
