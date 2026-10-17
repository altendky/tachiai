package net.fstab.tachiai.platform.network

import java.net.URI
import routebridge.Routebridge

internal object HttpProxyRouteProtocol : RouteProtocol {
    override val kind = ConnectionKind("http-connect", "HTTP CONNECT proxy")
    override val formatLabel = "http:// proxy URL"
    override val importHint = "an http:// proxy URL with an explicit port"
    override fun recognizes(text: String) = text.startsWith("http://")
    override fun configurationIdentity(profile: ConnectionProfile) = profile.configuration
    override fun createBackend(profile: ConnectionProfile, security: RouteProxySecurity, preparation: RoutePreparation): RouteBackend = NativeRouteBackend(
        Routebridge.newHttpProxy(profile.configuration, security.username, security.password, security.realm, security.allowedHosts),
    )

    override fun parse(text: String): ConnectionProfile {
        val uri = try { URI(text) } catch (_: Exception) { failConnectionImport(ConnectionImportFailure.Category.VALUE) }
        validateConnectionValue(uri.scheme == "http" && uri.host != null && uri.port in 1..65535 && uri.rawQuery == null &&
            uri.rawFragment == null && uri.rawPath in listOf("", "/"))
        val host = uri.host.removePrefix("[").removeSuffix("]")
        validateConnectionValue(endpointHost(host))
        uri.rawUserInfo?.let { validateConnectionValue(it.isNotEmpty() && ':' in it && it.substringBefore(':').isNotEmpty()) }
        val endpoint = if (':' in host) "[$host]:${uri.port}" else "$host:${uri.port}"
        // URI retains credentials only in the encrypted configuration, not in endpoint/status.
        return ConnectionProfile(this, endpoint, uri.toASCIIString())
    }
}
