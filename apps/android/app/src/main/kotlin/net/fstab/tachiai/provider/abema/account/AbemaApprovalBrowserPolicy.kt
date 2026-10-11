package net.fstab.tachiai.provider.abema.account

import java.net.URI
import java.net.URLDecoder
import net.fstab.tachiai.presentation.validProviderInstanceId

// Private handoff only: never saved UI state, diagnostics or a catalog resource.
internal class AbemaApprovalBrowserRequest(
    val instanceId: String,
    val ownerProof: String,
    val verificationUri: String,
    val deadlineElapsedMs: Long,
) {
    init {
        require(validProviderInstanceId(instanceId))
        require(ownerProof.matches(Regex("[0-9a-f]{64}")))
        require(validAbemaVerificationUri(verificationUri))
        require(deadlineElapsedMs >= 0)
    }

    fun current(nowElapsedMs: Long) = nowElapsedMs >= 0 && nowElapsedMs < deadlineElapsedMs
    override fun toString() = "AbemaApprovalBrowserRequest(redacted)"
}

// Separate from playback and guest policies. First-party account code uses
// same-origin /api/auth requests and declares these auth/modern API origins;
// media, license, advertising and analytics destinations are not admitted.
internal object AbemaApprovalBrowserPolicy {
    const val PROFILE_SUFFIX = "abema-provider-approval"
    const val HOSTS = "abema.tv,auth.abema.tv,api.p-c3-e.abema-tv.com"
    private val navigationHosts = setOf("abema.tv", "auth.abema.tv")
    private val resourceHosts = HOSTS.split(',').toSet()

    fun allowedNavigation(text: String) = allowed(text, navigationHosts)
    fun allowedResource(text: String) = allowed(text, resourceHosts)

    private fun allowed(text: String, hosts: Set<String>): Boolean = runCatching {
        if (text.length !in 1..8192 || text.any { it.code !in 33..126 } || '\\' in text) return false
        val decoded = URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")
        if (decoded.codePoints().anyMatch {
            Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() ||
                it == '\\'.code || it == 0xfffd
        }) return false
        val uri = URI(text)
        val host = uri.host ?: return false
        uri.scheme == "https" && host in hosts && uri.port in listOf(-1, 443) && uri.rawUserInfo == null &&
            uri.rawAuthority == host + (if (uri.port == 443) ":443" else "") &&
            uri.normalize() == uri && uri.toASCIIString() == text &&
            uri.path.orEmpty().split('/').none { it == "." || it == ".." }
    }.getOrDefault(false)
}
