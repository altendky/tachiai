package net.fstab.tachiai.provider.abema

import java.net.URI

internal object AbemaCachedHelperPolicy {
    const val ORIGIN = "https://tachiai-helper.invalid"
    const val PAGE = "$ORIGIN/runtime/index.html"
    const val CONTENT_SECURITY_POLICY = "default-src 'none'; script-src 'self'; connect-src 'none'; " +
        "img-src 'none'; media-src 'none'; frame-src 'none'; worker-src 'none'; " +
        "object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

    fun allows(uri: URI, mainFrame: Boolean, method: String): Boolean {
        if (method != "GET" || uri.scheme != "https" || uri.host != "tachiai-helper.invalid" ||
            uri.port != -1 || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return false
        return if (mainFrame) uri.toString() == PAGE else uri.path in setOf(
            "/runtime/control.js", "/runtime/bundle.js", "/runtime/initialize.js",
        ) && uri.toString() == "$ORIGIN${uri.path}"
    }
}
