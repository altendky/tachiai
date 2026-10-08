package net.fstab.tachiai.platform.network

import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.security.MessageDigest

internal const val CONNECTION_IMPORT_LIMIT = 8192

internal enum class ConnectionKind(val title: String) { WIREGUARD("WireGuard"), HTTP_PROXY("HTTP CONNECT proxy") }

// Not a data class: generated toString/copy/component methods must not expose secrets.
internal class ConnectionProfile(val kind: ConnectionKind, val endpoint: String, internal val configuration: String) {
    override fun toString() = "ConnectionProfile(${kind.name}, secrets hidden)"
}

// Ephemeral registry keys, never diagnostic identifiers or persisted secrets.
private fun routeDigest(value: String) = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private fun wireGuardFields(profile: ConnectionProfile): Map<String, String> {
    var section = ""
    return buildMap {
        profile.configuration.lineSequence().forEach { line ->
            if (line.startsWith('[')) section = line
            else if ('=' in line) {
                val name = line.substringBefore('=').trim()
                var value = line.substringAfter('=').trim()
                if (name in listOf("Address", "DNS", "AllowedIPs")) value = value.split(',').joinToString(",") { it.trim() }
                if (name == "Endpoint") value = value.lowercase()
                put("$section.$name", value)
            }
        }
    }
}

internal fun routeConfigurationKey(profile: ConnectionProfile): String = routeDigest(
    profile.kind.name + ":" + if (profile.kind == ConnectionKind.WIREGUARD)
        wireGuardFields(profile).toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }
    else profile.configuration,
)

internal fun wireGuardPeerKey(profile: ConnectionProfile): String? = if (profile.kind != ConnectionKind.WIREGUARD) null else
    wireGuardFields(profile).let { fields -> routeDigest(listOf("[Interface].PrivateKey", "[Peer].PublicKey")
        .joinToString("\n") { checkNotNull(fields[it]) }) }

internal class ConnectionImportFailure(val category: Category) : Exception(category.message) {
    enum class Category(val message: String) {
        SIZE("Configuration is too large. Maximum size is 8 KiB."),
        TEXT("Use a UTF-8 text configuration, not an archive or image."),
        FORMAT("Use a WireGuard configuration or an http:// proxy URL with an explicit port."),
        OPTION("This configuration contains unsupported options. No commands or scripts are executed."),
        PEERS("This first importer supports exactly one WireGuard peer."),
        VALUE("A required field is missing, duplicated or invalid. Nothing was saved."),
        SOURCE("This file source needs Android 11 or newer. Use a local downloaded file or paste the configuration."),
    }
}

private fun fail(category: ConnectionImportFailure.Category): Nothing = throw ConnectionImportFailure(category)
private fun valid(value: Boolean) { if (!value) fail(ConnectionImportFailure.Category.VALUE) }

internal fun readConnectionImport(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return output.toByteArray()
        if (output.size() + count > CONNECTION_IMPORT_LIMIT) fail(ConnectionImportFailure.Category.SIZE)
        output.write(buffer, 0, count)
    }
}

internal fun parseConnectionProfile(bytes: ByteArray): ConnectionProfile {
    if (bytes.size > CONNECTION_IMPORT_LIMIT) fail(ConnectionImportFailure.Category.SIZE)
    val text = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF").trim()
    } catch (_: Exception) { fail(ConnectionImportFailure.Category.TEXT) }
    if (text.any { it.isISOControl() && it !in "\r\n\t" }) fail(ConnectionImportFailure.Category.TEXT)
    return if (text.startsWith("http://")) parseHttpProxy(text)
        else if (text.lineSequence().any { it.substringBefore('#').trim() == "[Interface]" }) parseWireGuard(text)
        else fail(ConnectionImportFailure.Category.FORMAT)
}

private fun parseHttpProxy(text: String): ConnectionProfile {
    val uri = try { URI(text) } catch (_: Exception) { fail(ConnectionImportFailure.Category.VALUE) }
    valid(uri.scheme == "http" && uri.host != null && uri.port in 1..65535 && uri.rawQuery == null &&
        uri.rawFragment == null && uri.rawPath in listOf("", "/"))
    val host = uri.host.removePrefix("[").removeSuffix("]")
    valid(endpointHost(host))
    uri.rawUserInfo?.let { valid(it.isNotEmpty() && ':' in it && it.substringBefore(':').isNotEmpty()) }
    val endpoint = if (':' in host) "[$host]:${uri.port}" else "$host:${uri.port}"
    // URI retains credentials only in the encrypted configuration, not in endpoint/status.
    return ConnectionProfile(ConnectionKind.HTTP_PROXY, endpoint, uri.toASCIIString())
}

private fun parseWireGuard(text: String): ConnectionProfile {
    val fields = linkedMapOf<String, String>()
    var section = ""
    var interfaces = 0
    var peers = 0
    val interfaceFields = setOf("PrivateKey", "Address", "DNS", "MTU")
    val peerFields = setOf("PublicKey", "PresharedKey", "AllowedIPs", "Endpoint", "PersistentKeepalive")
    text.lineSequence().forEach { raw ->
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) return@forEach
        if (line.startsWith('[')) {
            section = when (line) {
                "[Interface]" -> { interfaces++; valid(interfaces == 1 && peers == 0); "Interface" }
                "[Peer]" -> { peers++; if (peers != 1) fail(ConnectionImportFailure.Category.PEERS); valid(interfaces == 1); "Peer" }
                else -> fail(ConnectionImportFailure.Category.OPTION)
            }
        } else {
            valid('=' in line && section.isNotEmpty())
            val name = line.substringBefore('=').trim()
            val value = line.substringAfter('=').trim()
            if (name !in if (section == "Interface") interfaceFields else peerFields) fail(ConnectionImportFailure.Category.OPTION)
            val key = "$section.$name"
            valid(value.isNotEmpty() && key !in fields)
            fields[key] = value
        }
    }
    valid(interfaces == 1)
    if (peers != 1) fail(ConnectionImportFailure.Category.PEERS)
    fun required(name: String) = fields[name] ?: fail(ConnectionImportFailure.Category.VALUE)
    valid(keyBytes(required("Interface.PrivateKey")) && keyBytes(required("Peer.PublicKey")))
    fields["Peer.PresharedKey"]?.let { valid(keyBytes(it)) }
    valid(listValues(required("Interface.Address")).all(::cidr))
    valid(listValues(required("Peer.AllowedIPs")).all(::cidr))
    fields["Interface.DNS"]?.let { valid(listValues(it).all(::literalIp)) }
    fields["Interface.MTU"]?.let { valid(it.toIntOrNull() in 576..9000) }
    fields["Peer.PersistentKeepalive"]?.let { valid(it == "off" || it.toIntOrNull() in 0..65535) }
    val endpoint = required("Peer.Endpoint")
    val host = if (endpoint.startsWith('[')) endpoint.substringBefore(']').removePrefix("[") else endpoint.substringBeforeLast(':')
    val port = endpoint.substringAfterLast(':').toIntOrNull()
    valid(port in 1..65535 && endpointHost(host) &&
        (if (':' in host) endpoint == "[$host]:$port" else endpoint == "$host:$port"))
    val canonical = listOf("Interface", "Peer").joinToString("\n\n") { group ->
        "[$group]\n" + fields.filterKeys { it.startsWith("$group.") }.entries.joinToString("\n") {
            "${it.key.substringAfter('.')} = ${it.value}"
        }
    }
    return ConnectionProfile(ConnectionKind.WIREGUARD, endpoint, canonical)
}

private fun keyBytes(value: String): Boolean = try {
    val bytes = Base64.getDecoder().decode(value)
    bytes.size == 32 && bytes.any { it != 0.toByte() } && Base64.getEncoder().encodeToString(bytes) == value
} catch (_: Exception) { false }

private fun listValues(value: String): List<String> {
    val values = value.split(',').map(String::trim)
    valid(values.size in 1..16 && values.all { it.isNotEmpty() })
    return values
}

// Only numeric IPv6 candidates reach InetAddress, never a hostname/DNS lookup.
private fun literalIp(value: String): Boolean = if (':' in value) {
    value.length <= 45 && Regex("[0-9A-Fa-f:.]+").matches(value) && try {
        InetAddress.getByName(value) is Inet6Address
    } catch (_: Exception) { false }
} else {
    val parts = value.split('.')
    parts.size == 4 && parts.all { part -> Regex("0|[1-9][0-9]{0,2}").matches(part) && part.toInt() in 0..255 }
}

private fun cidr(value: String): Boolean {
    val address = value.substringBefore('/')
    val prefix = value.substringAfter('/', "").toIntOrNull() ?: return false
    return literalIp(address) && prefix in 0..(if (':' in address) 128 else 32)
}

private fun endpointHost(value: String): Boolean = literalIp(value) ||
    (':' !in value && value.length in 1..253 && !value.all { it.isDigit() || it == '.' } &&
        value.split('.').all { Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?").matches(it) })

internal fun validConnectionName(value: String): Boolean = value == value.trim() && value.length in 1..48 &&
    value.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
