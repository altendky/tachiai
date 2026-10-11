package net.fstab.tachiai.provider.abema

import java.net.URI

internal enum class AbemaGuestBootstrapOperation { GUEST_ONLY, VERIFY_PROFILE }

internal object AbemaGuestBootstrapPolicy {
    const val PROFILE_SUFFIX = "abema-guest-bootstrap"
    const val PAGE = "https://abema.tv/_tachiai/guest/index.html"
    const val GUEST = "https://abema.tv/api/auth/login/guest"
    const val PROFILE_ORIGIN = "https://api.p-c3-e.abema-tv.com"
    const val CONTENT_SECURITY_POLICY = "default-src 'none'; script-src 'self'; " +
        "connect-src https://abema.tv/api/auth/login/guest; " +
        "img-src 'none'; media-src 'none'; frame-src 'none'; worker-src 'none'; " +
        "object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"

    fun contentSecurityPolicy(operation: AbemaGuestBootstrapOperation) = if (operation == AbemaGuestBootstrapOperation.GUEST_ONLY)
        CONTENT_SECURITY_POLICY else CONTENT_SECURITY_POLICY.replace("connect-src $GUEST;", "connect-src $GUEST $PROFILE_ORIGIN/v1/users/;")

    fun isProfile(uri: URI): Boolean = uri.scheme == "https" && uri.host == "api.p-c3-e.abema-tv.com" &&
        uri.port == -1 && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        uri.rawPath == uri.path && uri.normalize() == uri && uri.path.matches(Regex("/v1/users/[A-Za-z0-9_-]{1,256}"))

    // GET headers can contain the bearer in Android's request object. Never
    // inspect/copy them; admission needs only these fixed preflight fields.
    fun corsHeaders(method: String, read: () -> Map<String, String>): Map<String, String> =
        if (method != "OPTIONS") emptyMap() else read().filterKeys { key ->
            listOf("Origin", "Access-Control-Request-Method", "Access-Control-Request-Headers").any { it.equals(key, true) }
        }

    fun allows(uri: URI, mainFrame: Boolean, method: String,
        operation: AbemaGuestBootstrapOperation = AbemaGuestBootstrapOperation.GUEST_ONLY): Boolean {
        if (operation == AbemaGuestBootstrapOperation.VERIFY_PROFILE && !mainFrame && isProfile(uri))
            return method in setOf("GET", "OPTIONS")
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

// Attempt-owned policy. The profile URI carries a private guest ID transiently;
// never expose this object/URI through diagnostics, state, Intents or storage.
internal class AbemaGuestRequestAdmission(private val operation: AbemaGuestBootstrapOperation) {
    private var guest = false
    private var profile = false
    private var options = false
    private var profileUri: URI? = null

    @Synchronized fun admit(uri: URI, mainFrame: Boolean, method: String, headers: Map<String, String> = emptyMap()): Boolean {
        if (!AbemaGuestBootstrapPolicy.allows(uri, mainFrame, method, operation)) return false
        if (uri.toString() == AbemaGuestBootstrapPolicy.GUEST) {
            if (guest) return false
            guest = true
        } else if (AbemaGuestBootstrapPolicy.isProfile(uri)) {
            if (!guest || (profileUri != null && profileUri != uri)) return false
            if (method == "OPTIONS") {
                fun header(name: String) = headers.entries.singleOrNull { it.key.equals(name, true) }?.value
                if (options || profile || header("Origin") != "https://abema.tv" ||
                    header("Access-Control-Request-Method") != "GET" ||
                    header("Access-Control-Request-Headers")?.trim()?.lowercase() != "authorization") return false
                options = true
            } else {
                if (profile) return false
                profile = true
            }
            profileUri = uri
        }
        return true
    }

    override fun toString() = "AbemaGuestRequestAdmission(redacted)"
}
