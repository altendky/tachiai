package net.fstab.tachiai.platform.network

import java.io.IOException
import routebridge.Route
import routebridge.Routebridge

// Matches the native adapter's bounded policy grammar. This parser does not
// establish cryptographic validity; native Core must validate the inline
// certificates and unencrypted key before starting a handshake.
internal object OpenVpnRouteProtocol : RouteProtocol {
    override val kind = ConnectionKind("openvpn", "OpenVPN")
    override val formatLabel = "certificate-only OpenVPN configuration"
    override val importHint = "a certificate-only OpenVPN configuration with one numeric remote and inline CA, certificate and key"
    private val required = listOf("client", "dev", "proto", "remote", "remote-cert-tls", "verify-x509-name", "tls-version-min")
    private val blockNames = listOf("ca", "cert", "key")

    override fun recognizes(text: String) = text.split('\n').any { it.trim(' ', '\t', '\r') == "client" }
    override fun configurationIdentity(profile: ConnectionProfile) = profile.configuration

    override fun parse(text: String): ConnectionProfile {
        if (text.toByteArray(Charsets.UTF_8).size > CONNECTION_IMPORT_LIMIT) failConnectionImport(ConnectionImportFailure.Category.SIZE)
        validateConnectionValue(text.isNotEmpty())
        if (text.any { it.code < 32 && it !in "\r\n\t" }) failConnectionImport(ConnectionImportFailure.Category.TEXT)
        val seen = mutableSetOf<String>()
        val options = mutableMapOf<String, String>()
        val blocks = mutableMapOf<String, MutableList<String>>()
        var block: String? = null
        for (raw in text.split('\n')) {
            val line = raw.trim(' ', '\t', '\r')
            if (line.isEmpty()) continue
            val current = block
            if (current != null) {
                if (line == "</$current>") block = null
                else {
                    validateConnectionValue('<' !in line && "ENCRYPTED" !in line)
                    checkNotNull(blocks[current]).add(line)
                }
                continue
            }
            if (line.startsWith('#') || line.startsWith(';')) continue
            if (line in blockNames.map { "<$it>" }) {
                val name = line.substring(1, line.length - 1)
                validateConnectionValue(seen.add(name))
                blocks[name] = mutableListOf()
                block = name
                continue
            }
            val args = line.split(Regex("[ \t\r]+"))
            val name = args.first()
            validateConnectionValue(seen.add(name))
            when (name) {
                "client", "nobind" -> validateConnectionValue(args.size == 1)
                "dev" -> validateConnectionValue(args.size == 2 && args[1] == "tun")
                "proto" -> validateConnectionValue(args.size == 2 && args[1] in listOf("udp", "tcp-client"))
                "remote" -> {
                    validateConnectionValue(args.size == 3)
                    val address = numericRemote(args[1]) ?: failConnectionImport(ConnectionImportFailure.Category.VALUE)
                    validateConnectionValue(Regex("[0-9]{1,5}").matches(args[2]))
                    val port = args[2].toInt()
                    validateConnectionValue(port in 1..65535)
                    options[name] = "remote $address $port"
                    continue
                }
                "remote-cert-tls" -> validateConnectionValue(args.size == 2 && args[1] == "server")
                "verify-x509-name" -> validateConnectionValue(args.size == 3 &&
                    Regex("[A-Za-z0-9._:-]{1,253}").matches(args[1]) && args[2] == "name")
                "tls-version-min" -> validateConnectionValue(args.size == 2 && args[1] in listOf("1.2", "1.3"))
                else -> failConnectionImport(ConnectionImportFailure.Category.OPTION)
            }
            options[name] = args.joinToString(" ")
        }
        validateConnectionValue(block == null && required.all { it in options } && blockNames.all { it in blocks })
        val canonical = (required.map { checkNotNull(options[it]) } + listOfNotNull(options["nobind"]) +
            blockNames.map { name -> "<$name>\n" + checkNotNull(blocks[name]).joinToString("\n") + "\n</$name>" }).joinToString("\n")
        if (canonical.toByteArray(Charsets.UTF_8).size > CONNECTION_IMPORT_LIMIT) failConnectionImport(ConnectionImportFailure.Category.SIZE)
        val remote = checkNotNull(options["remote"]).split(' ')
        val host = remote[1]
        val endpoint = if (':' in host) "[$host]:${remote[2]}" else "$host:${remote[2]}"
        return ConnectionProfile(this, endpoint, canonical)
    }

    override fun createBackend(profile: ConnectionProfile, security: RouteProxySecurity, preparation: RoutePreparation): RouteBackend {
        val native = Routebridge.newRoutePreparation()
        var registration: AutoCloseable? = null
        var route: Route? = null
        try {
            preparation.checkActive()
            registration = preparation.onCancel(native::cancel)
            val result = Routebridge.newOpenVPNPrepared(profile.configuration, security.username, security.password,
                security.realm, security.allowedHosts, native)
            route = result.route
            // Cancel only signals a Go context; native cleanup belongs to its
            // worker. Remove the hook before handing off or closing a route.
            registration.close()
            registration = null
            if (result.code.toInt() == 6) preparation.recordCleanupFailure()
            if (result.code.toInt() != 0 || route == null) throw IOException("OpenVPN route preparation failed")
            preparation.checkActive()
            return NativeRouteBackend(checkNotNull(route)).also { route = null }
        } catch (_: Exception) {
            registration?.close()
            registration = null
            native.cancel()
            if (runCatching { route?.close() }.isFailure) preparation.recordCleanupFailure()
            throw IOException("OpenVPN route could not be initialized")
        } finally {
            registration?.close()
        }
    }

    // Parse bytes locally; no InetAddress hostname parser or DNS API is used.
    private fun ipv4(value: String): List<Int>? {
        val parts = value.split('.')
        if (parts.size != 4 || parts.any { !Regex("0|[1-9][0-9]{0,2}").matches(it) }) return null
        return parts.map { it.toInt() }.takeIf { it.all { octet -> octet in 0..255 } }
    }

    private fun numericRemote(value: String): String? {
        if (':' !in value) {
            val bytes = ipv4(value) ?: return null
            if (bytes[0] == 0 || bytes[0] in 224..239 || bytes.all { it == 255 }) return null
            return bytes.joinToString(".")
        }
        if (value.length > 45 || !Regex("[0-9A-Fa-f:.]+").matches(value)) return null
        val halves = value.split("::")
        if (halves.size > 2) return null
        fun words(part: String, allowDottedTail: Boolean): List<Int>? {
            if (part.isEmpty()) return emptyList()
            val tokens = part.split(':')
            val result = mutableListOf<Int>()
            tokens.forEachIndexed { index, token ->
                if ('.' in token) {
                    if (!allowDottedTail || index != tokens.lastIndex) return null
                    val bytes = ipv4(token) ?: return null
                    result += bytes[0] * 256 + bytes[1]
                    result += bytes[2] * 256 + bytes[3]
                } else {
                    if (!Regex("[0-9A-Fa-f]{1,4}").matches(token)) return null
                    result += token.toInt(16)
                }
            }
            return result
        }
        val left = words(halves[0], halves.size == 1) ?: return null
        val address = if (halves.size == 1) left.takeIf { it.size == 8 } ?: return null else {
            val right = words(halves[1], true) ?: return null
            val missing = 8 - left.size - right.size
            if (missing < 1) return null
            left + List(missing) { 0 } + right
        }
        if (address.all { it == 0 } || (address[0] and 0xff00) == 0xff00 || (address[0] and 0xffc0) == 0xfe80 ||
            (address.take(5).all { it == 0 } && address[5] == 0xffff)) return null
        var bestStart = -1
        var bestLength = 1
        var index = 0
        while (index < address.size) {
            if (address[index] != 0) { index++; continue }
            val start = index
            while (index < address.size && address[index] == 0) index++
            if (index - start > bestLength) { bestStart = start; bestLength = index - start }
        }
        if (bestStart < 0) return address.joinToString(":") { it.toString(16) }
        return address.take(bestStart).joinToString(":") { it.toString(16) } + "::" +
            address.drop(bestStart + bestLength).joinToString(":") { it.toString(16) }
    }
}
