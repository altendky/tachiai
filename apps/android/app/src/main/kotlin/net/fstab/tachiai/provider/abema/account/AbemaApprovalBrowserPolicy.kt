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
    val emailNonce: String? = null,
    val resetBrowserSession: Boolean = false,
) {
    init {
        require(validProviderInstanceId(instanceId))
        require(ownerProof.matches(Regex("[0-9a-f]{64}")))
        require(validAbemaVerificationUri(verificationUri))
        require(deadlineElapsedMs >= 0)
        require(emailNonce == null || emailNonce.matches(Regex("[0-9a-f]{32}")) && verificationUri == "https://abema.tv/account")
        require(!resetBrowserSession || emailNonce != null)
    }

    fun current(nowElapsedMs: Long) = nowElapsedMs >= 0 && nowElapsedMs < deadlineElapsedMs
    override fun toString() = "AbemaApprovalBrowserRequest(redacted)"
}

// Separate from playback and guest policies. First-party account code uses
// same-origin /api/auth requests and declares these auth/modern API origins;
// Provider-owned *.abema-tv.com resources use the instance route. Top-level
// login navigation remains on the provider's account origins. Its separate
// region check selects one of two reviewed GET resources.
internal object AbemaApprovalBrowserPolicy {
    const val PROFILE_SUFFIX = "abema-provider-approval"
    const val REGION_HOST = "ds-glb-linear-abematv.akamaized.net"
    const val GATEWAY_HOST = "gateway.p-c2-x.abema-tv.com"
    val REGION_HOSTS = setOf(REGION_HOST, "ds-linear-abematv.akamaized.net")
    val REGION_URIS = REGION_HOSTS.map { "https://$it/region" }.toSet()
    const val HOSTS = "abema.tv,auth.abema.tv,*.abema-tv.com,$REGION_HOST,ds-linear-abematv.akamaized.net"
    private val navigationHosts = setOf("abema.tv", "auth.abema.tv")
    private val resourceHosts = HOSTS.split(',').toSet()

    fun allowedNavigation(text: String) = allowed(text, navigationHosts)
    fun allowedResource(text: String, method: String = "GET"): Boolean {
        if (!allowed(text, resourceHosts, providerResources = true)) return false
        val uri = URI(text)
        return uri.host !in REGION_HOSTS || (method == "GET" && text in REGION_URIS)
    }

    private val dnsLabel = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
    private fun providerResourceHost(host: String): Boolean {
        val suffix = ".abema-tv.com"
        return host.length <= 253 && host.endsWith(suffix) && host.removeSuffix(suffix).split('.')
            .all { it.isNotEmpty() && dnsLabel.matches(it) }
    }

    private fun allowed(text: String, hosts: Set<String>, providerResources: Boolean = false): Boolean = runCatching {
        if (text.length !in 1..8192 || text.any { it.code !in 33..126 } || '\\' in text) return false
        val decoded = URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")
        if (decoded.codePoints().anyMatch {
            Character.isISOControl(it) || Character.getType(it) == Character.FORMAT.toInt() ||
                it == '\\'.code || it == 0xfffd
        }) return false
        val uri = URI(text)
        val host = uri.host ?: return false
        uri.scheme == "https" && (host in hosts || providerResources && providerResourceHost(host)) &&
            uri.port in listOf(-1, 443) && uri.rawUserInfo == null &&
            uri.rawAuthority == host + (if (uri.port == 443) ":443" else "") &&
            uri.normalize() == uri && uri.toASCIIString() == text &&
            uri.path.orEmpty().split('/').none { it == "." || it == ".." }
    }.getOrDefault(false)
}
