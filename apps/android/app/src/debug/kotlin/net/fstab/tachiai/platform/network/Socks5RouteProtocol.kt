package net.fstab.tachiai.platform.network

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import routebridge.Routebridge

internal object Socks5RouteProtocol : RouteProtocol {
    override val kind = ConnectionKind("socks5", "SOCKS5 proxy")
    override val formatLabel = "socks5:// proxy URL"
    override val importHint = "a socks5:// proxy URL with an explicit port"
    override fun recognizes(text: String) = text.startsWith("socks5://")
    override fun configurationIdentity(profile: ConnectionProfile) = profile.configuration
    override fun createBackend(profile: ConnectionProfile, security: RouteProxySecurity): RouteBackend = NativeRouteBackend(
        Routebridge.newSocks5(profile.configuration, security.username, security.password, security.realm, security.allowedHosts),
    )

    override fun parse(text: String): ConnectionProfile {
        val uri = try { URI(text) } catch (_: Exception) { failConnectionImport(ConnectionImportFailure.Category.VALUE) }
        validateConnectionValue(uri.scheme == "socks5" && uri.host != null && uri.port in 1..65535 &&
            uri.rawQuery == null && uri.rawFragment == null && uri.rawPath in listOf("", "/"))
        val host = uri.host.removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
        validateConnectionValue(endpointHost(host))
        val rawPort = uri.rawAuthority.substringAfterLast(':')
        validateConnectionValue(Regex("[0-9]{1,5}").matches(rawPort))
        val credentials = uri.rawUserInfo?.let { raw ->
            validateConnectionValue(raw.count { it == ':' } == 1)
            encodeCredential(decodeCredential(raw.substringBefore(':'))) + ":" +
                encodeCredential(decodeCredential(raw.substringAfter(':'))) + "@"
        } ?: ""
        val endpoint = if (':' in host) "[$host]:${uri.port}" else "$host:${uri.port}"
        return ConnectionProfile(this, endpoint, "socks5://$credentials$endpoint")
    }

    private fun decodeCredential(raw: String): String {
        val bytes = ByteArrayOutputStream()
        var index = 0
        while (index < raw.length) {
            val character = raw[index]
            if (character == '%') {
                validateConnectionValue(index + 2 < raw.length)
                val value = raw.substring(index + 1, index + 3).toIntOrNull(16)
                validateConnectionValue(value != null)
                bytes.write(checkNotNull(value))
                index += 3
            } else {
                validateConnectionValue(character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character in "-._~")
                bytes.write(character.code)
                index++
            }
        }
        validateConnectionValue(bytes.size() in 1..255)
        val decoded = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: Exception) { failConnectionImport(ConnectionImportFailure.Category.VALUE) }
        validateConnectionValue(decoded.codePoints().allMatch { !Character.isISOControl(it) && Character.getType(it) != Character.FORMAT.toInt() })
        return decoded
    }

    private fun encodeCredential(value: String): String = value.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
        val character = (byte.toInt() and 255).toChar()
        if (character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character in "-._~") character.toString()
        else "%%%02X".format(Locale.ROOT, byte.toInt() and 255)
    }
}
