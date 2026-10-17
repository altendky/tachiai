package net.fstab.tachiai.platform.network

import java.util.Base64
import routebridge.Routebridge

internal object WireGuardRouteProtocol : RouteProtocol {
    override val kind = ConnectionKind("wireguard", "WireGuard")
    override val formatLabel = "WireGuard configuration"
    override val importHint = "a WireGuard configuration"
    override fun recognizes(text: String) = text.lineSequence().any { it.substringBefore('#').trim() == "[Interface]" }

    override fun configurationIdentity(profile: ConnectionProfile) = wireGuardFields(profile).toSortedMap()
        .entries.joinToString("\n") { "${it.key}=${it.value}" }

    override fun conflictIdentity(profile: ConnectionProfile) = wireGuardFields(profile).let { fields ->
        listOf("[Interface].PrivateKey", "[Peer].PublicKey").joinToString("\n") { checkNotNull(fields[it]) }
    }

    override fun createBackend(profile: ConnectionProfile, security: RouteProxySecurity, preparation: RoutePreparation): RouteBackend = NativeRouteBackend(
        Routebridge.newWireGuard(profile.configuration, security.username, security.password, security.realm, security.allowedHosts),
    )

    private fun invalidPeers(): Nothing = throw ConnectionImportFailure(
        ConnectionImportFailure.Category.PEERS, "This first importer supports exactly one WireGuard peer.",
    )

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

    override fun parse(text: String): ConnectionProfile {
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
                    "[Interface]" -> { interfaces++; validateConnectionValue(interfaces == 1 && peers == 0); "Interface" }
                    "[Peer]" -> { peers++; if (peers != 1) invalidPeers(); validateConnectionValue(interfaces == 1); "Peer" }
                    else -> failConnectionImport(ConnectionImportFailure.Category.OPTION)
                }
            } else {
                validateConnectionValue('=' in line && section.isNotEmpty())
                val name = line.substringBefore('=').trim()
                val value = line.substringAfter('=').trim()
                if (name !in if (section == "Interface") interfaceFields else peerFields) failConnectionImport(ConnectionImportFailure.Category.OPTION)
                val key = "$section.$name"
                validateConnectionValue(value.isNotEmpty() && key !in fields)
                fields[key] = value
            }
        }
        validateConnectionValue(interfaces == 1)
        if (peers != 1) invalidPeers()
        fun required(name: String) = fields[name] ?: failConnectionImport(ConnectionImportFailure.Category.VALUE)
        validateConnectionValue(keyBytes(required("Interface.PrivateKey")) && keyBytes(required("Peer.PublicKey")))
        fields["Peer.PresharedKey"]?.let { validateConnectionValue(keyBytes(it)) }
        validateConnectionValue(listValues(required("Interface.Address")).all(::cidr))
        validateConnectionValue(listValues(required("Peer.AllowedIPs")).all(::cidr))
        fields["Interface.DNS"]?.let { validateConnectionValue(listValues(it).all(::literalIp)) }
        fields["Interface.MTU"]?.let { validateConnectionValue(it.toIntOrNull() in 576..9000) }
        fields["Peer.PersistentKeepalive"]?.let { validateConnectionValue(it == "off" || it.toIntOrNull() in 0..65535) }
        val endpoint = required("Peer.Endpoint")
        val host = if (endpoint.startsWith('[')) endpoint.substringBefore(']').removePrefix("[") else endpoint.substringBeforeLast(':')
        val port = endpoint.substringAfterLast(':').toIntOrNull()
        validateConnectionValue(port in 1..65535 && endpointHost(host) &&
            (if (':' in host) endpoint == "[$host]:$port" else endpoint == "$host:$port"))
        val canonical = listOf("Interface", "Peer").joinToString("\n\n") { group ->
            "[$group]\n" + fields.filterKeys { it.startsWith("$group.") }.entries.joinToString("\n") {
                "${it.key.substringAfter('.')} = ${it.value}"
            }
        }
        return ConnectionProfile(this, endpoint, canonical)
    }

    private fun keyBytes(value: String): Boolean = try {
        val bytes = Base64.getDecoder().decode(value)
        bytes.size == 32 && bytes.any { it != 0.toByte() } && Base64.getEncoder().encodeToString(bytes) == value
    } catch (_: Exception) { false }

    private fun listValues(value: String): List<String> {
        val values = value.split(',').map(String::trim)
        validateConnectionValue(values.size in 1..16 && values.all { it.isNotEmpty() })
        return values
    }

    private fun cidr(value: String): Boolean {
        val address = value.substringBefore('/')
        val prefix = value.substringAfter('/', "").toIntOrNull() ?: return false
        return literalIp(address) && prefix in 0..(if (':' in address) 128 else 32)
    }
}
