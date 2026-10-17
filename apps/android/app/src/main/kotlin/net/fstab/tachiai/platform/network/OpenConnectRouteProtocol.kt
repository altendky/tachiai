package net.fstab.tachiai.platform.network

import net.fstab.tachiai.platform.diagnostics.FailureStage

import java.io.IOException
import java.util.Locale
import routebridge.Route
import routebridge.Routebridge

// A conservative subset of the native certificate-only endpoint policy. Parsing
// PEM grammar does not validate certificates, CA authority or private-key matches;
// native GnuTLS remains authoritative before any gateway handshake.
internal object OpenConnectRouteProtocol : RouteProtocol {
    override val kind = ConnectionKind("openconnect", "OpenConnect")
    override val formatLabel = "certificate-only OpenConnect configuration"
    override val importHint = "an [OpenConnect] certificate-only configuration with an HTTPS Server, numeric IPv4 Bootstrap and inline CA, certificate and key"
    private val blockNames = listOf("ca", "cert", "key")
    private class Fields(val server: String, val bootstrap: String, val pem: Map<String, String>, val canonical: String, val endpoint: String)

    override fun recognizes(text: String) = text.lineSequence().any { it.trim(' ', '\t', '\r') == "[OpenConnect]" }
    override fun configurationIdentity(profile: ConnectionProfile) = profile.configuration
    override fun parse(text: String): ConnectionProfile {
        val fields = fields(text)
        return ConnectionProfile(this, fields.endpoint, fields.canonical)
    }

    private fun fields(text: String): Fields {
        if (text.toByteArray(Charsets.UTF_8).size > CONNECTION_IMPORT_LIMIT) failConnectionImport(ConnectionImportFailure.Category.SIZE)
        if (text.any { it.isISOControl() && it !in "\r\n\t" }) failConnectionImport(ConnectionImportFailure.Category.TEXT)
        var header = false
        var block: String? = null
        val options = mutableMapOf<String, String>()
        val blocks = mutableMapOf<String, MutableList<String>>()
        for (raw in text.split('\n')) {
            val line = raw.trim(' ', '\t', '\r')
            if (line.isEmpty()) continue
            val current = block
            if (current != null) {
                if (line == "</$current>") block = null else checkNotNull(blocks[current]).add(line)
                continue
            }
            if (line.startsWith('#') || line.startsWith(';')) continue
            if (line == "[OpenConnect]") {
                validateConnectionValue(!header && options.isEmpty() && blocks.isEmpty())
                header = true
                continue
            }
            validateConnectionValue(header)
            if (line in blockNames.map { "<$it>" }) {
                val name = line.substring(1, line.length - 1)
                validateConnectionValue(name !in blocks)
                blocks[name] = mutableListOf()
                block = name
                continue
            }
            val separator = line.indexOf('=')
            validateConnectionValue(separator > 0)
            val name = line.substring(0, separator).trim(' ', '\t')
            if (name !in listOf("Server", "Bootstrap")) failConnectionImport(ConnectionImportFailure.Category.OPTION)
            validateConnectionValue(name !in options)
            options[name] = line.substring(separator + 1).trim(' ', '\t')
        }
        validateConnectionValue(header && block == null && options.keys == setOf("Server", "Bootstrap") && blocks.keys == blockNames.toSet())
        val server = server(checkNotNull(options["Server"]))
        val bootstrap = ipv4(checkNotNull(options["Bootstrap"])) ?: failConnectionImport(ConnectionImportFailure.Category.VALUE)
        validateConnectionValue(bootstrap[0] != 0 && bootstrap[0] !in 224..239 && bootstrap.any { it != 255 })
        val bootstrapText = bootstrap.joinToString(".")
        val pem = blockNames.associateWith { name -> pem(checkNotNull(blocks[name]), name == "key") }
        val canonical = (listOf("[OpenConnect]", "Server=$server", "Bootstrap=$bootstrapText") +
            blockNames.map { "<$it>\n${checkNotNull(pem[it]).trimEnd('\n')}\n</$it>" }).joinToString("\n")
        if (canonical.toByteArray(Charsets.UTF_8).size > CONNECTION_IMPORT_LIMIT) failConnectionImport(ConnectionImportFailure.Category.SIZE)
        return Fields(server, bootstrapText, pem, canonical, server.removePrefix("https://").substringBefore('/'))
    }

    private fun ipv4(value: String): List<Int>? {
        val parts = value.split('.')
        if (parts.size != 4 || parts.any { !Regex("0|[1-9][0-9]{0,2}").matches(it) }) return null
        return parts.map { it.toInt() }.takeIf { it.all { octet -> octet in 0..255 } }
    }

    private fun server(value: String): String {
        validateConnectionValue(value.length in 10..512 && value.all { it.code in 32..126 } && value.startsWith("https://"))
        val authority = value.removePrefix("https://").substringBefore('/')
        val host = authority.substringBefore(':')
        validateConnectionValue(host.length in 1..253)
        if (host.all { it in '0'..'9' || it == '.' }) validateConnectionValue(ipv4(host) != null)
        else validateConnectionValue(host.split('.').all { Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?").matches(it) })
        val portText = if (':' in authority) authority.substringAfter(':') else "443"
        validateConnectionValue(Regex("[0-9]{1,5}").matches(portText))
        val port = portText.toInt()
        validateConnectionValue(port in 1..65535)
        val path = value.removePrefix("https://").let { if ('/' in it) "/" + it.substringAfter('/') else "/" }
        validateConnectionValue(".." !in path && Regex("/[A-Za-z0-9/_.-]*").matches(path))
        val canonical = "https://${host.lowercase(Locale.ROOT)}:$port$path"
        validateConnectionValue(canonical.length <= 512)
        return canonical
    }

    private fun pem(lines: List<String>, key: Boolean): String {
        validateConnectionValue(lines.isNotEmpty())
        val types = if (key) listOf("PRIVATE KEY", "RSA PRIVATE KEY", "EC PRIVATE KEY", "DSA PRIVATE KEY") else listOf("CERTIFICATE")
        var position = 0
        var count = 0
        while (position < lines.size) {
            val type = types.singleOrNull { lines[position] == "-----BEGIN $it-----" }
                ?: failConnectionImport(ConnectionImportFailure.Category.VALUE)
            position++
            val first = position
            while (position < lines.size && Regex("[A-Za-z0-9+/]+={0,2}").matches(lines[position])) position++
            validateConnectionValue(position > first && position < lines.size && lines[position] == "-----END $type-----")
            position++
            count++
        }
        validateConnectionValue(!key || count == 1)
        return lines.joinToString("\n", postfix = "\n")
    }

    override fun createBackend(profile: ConnectionProfile, security: RouteProxySecurity, preparation: RoutePreparation): RouteBackend {
        val native = Routebridge.newRoutePreparation()
        var registration: AutoCloseable? = null
        var route: Route? = null
        try {
            preparation.checkActive()
            val fields = fields(profile.configuration)
            registration = preparation.onCancel(native::cancel)
            val result = Routebridge.newOpenConnectPrepared(fields.server, fields.bootstrap,
                checkNotNull(fields.pem["ca"]), checkNotNull(fields.pem["cert"]), checkNotNull(fields.pem["key"]),
                security.username, security.password, security.realm, security.allowedHosts, native)
            route = result.route
            registration.close()
            registration = null
            if (result.code.toInt() == 6) preparation.recordCleanupFailure()
            if (result.code.toInt() != 0 || route == null) throw IOException("OpenConnect route preparation failed")
            preparation.checkActive()
            return NativeRouteBackend(checkNotNull(route)).also { route = null }
        } catch (error: Exception) {
            if (!preparation.isCancelled) preparation.diagnostics.report(FailureStage.OPENCONNECT_PREPARE, error)
            registration?.close()
            registration = null
            native.cancel()
            runCatching { route?.close() }.onFailure {
                preparation.recordCleanupFailure(FailureStage.ROUTE_CREATE_ROLLBACK, it)
            }
            throw IOException("OpenConnect route could not be initialized")
        } finally { registration?.close() }
    }
}
