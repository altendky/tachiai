package net.fstab.tachiai.platform.network

import java.net.InetAddress
import java.util.Base64
import routebridge.Routebridge

internal object WireGuardRouteProtocol : RouteProtocol {
    override val kind = ConnectionKind("wireguard", "WireGuard")
    override val formatLabel = "WireGuard configuration"
    override val importHint = "a WireGuard configuration"
    override fun recognizes(text: String) = text.lineSequence().any { it.substringBefore('#').trim() == "[Interface]" }

    override fun configurationIdentity(profile: ConnectionProfile) = wireGuardFields(profile).toSortedMap()
        .entries.joinToString("\n") { "${it.key}=${it.value}" }

    override fun publicKey(profile: ConnectionProfile): String? = runCatching {
        val private = java.util.Base64.getDecoder().decode(checkNotNull(wireGuardFields(profile)["[Interface].PrivateKey"]))
        java.util.Base64.getEncoder().encodeToString(x25519PublicKey(private)).also { private.fill(0) }
    }.getOrNull()

    override fun conflictIdentity(profile: ConnectionProfile) = wireGuardFields(profile).let { fields ->
        listOf("[Interface].PrivateKey", "[Peer].PublicKey").joinToString("\n") { checkNotNull(fields[it]) }
    }

    // Where this process's leases come from. The ordinary process owns the
    // devices; the playback process installs a Binder lease source at startup
    // so it never starts a second device with the same key (#275).
    @Volatile internal var leases: (ConnectionProfile, RouteProxySecurity, RoutePreparation) -> RouteBackend =
        { profile, security, _ -> leaseLocally(profile, security) }

    // Every lease of this key pair in the process shares one device.
    internal fun leaseLocally(profile: ConnectionProfile, security: RouteProxySecurity): RouteBackend =
        RouteEngineOwner.shared.lease(routeConfigurationKey(profile), checkNotNull(routeConflictKey(profile)), security) {
            NativeRouteEngine(Routebridge.newWireGuardEngine(profile.configuration))
        }

    // Renewal-job leases (#275 stage 5) come from the same owner. The job's
    // Network is used only if that owner must start a job-bound device.
    @Volatile internal var jobLeases: (ConnectionProfile, RouteProxySecurity, RoutePreparation, RouteJobNetwork) -> RouteBackend =
        { profile, security, _, job -> leaseLocallyForJob(profile, security, job) }

    internal fun leaseLocallyForJob(profile: ConnectionProfile, security: RouteProxySecurity, job: RouteJobNetwork): RouteBackend =
        RouteEngineOwner.shared.leaseForJob(routeConfigurationKey(profile), checkNotNull(routeConflictKey(profile)), security) {
            val egress = job.egress()
            try { NativeRouteEngine(Routebridge.newWireGuardBoundEngine(profile.configuration, egress)) }
            catch (error: Throwable) {
                // The native start cancelled this scope; anything it could not
                // confirm keeps the identity blocked.
                if (!runCatching { egress.cancel(); egress.isCleanupConfirmed() }.getOrDefault(false)) {
                    runCatching(egress::reportCleanupUnconfirmed)
                    retainedJobScopes.add(egress)
                    throw RouteEngineOwner.CleanupUnconfirmed()
                }
                throw error
            }
        }
    private val retainedJobScopes = java.util.concurrent.ConcurrentLinkedQueue<RouteNetworkEgress>()

    // The bound path: a renewal job's lease, never a device of its own.
    internal fun createJobBackend(profile: ConnectionProfile, security: RouteProxySecurity, preparation: RoutePreparation,
        job: RouteJobNetwork): RouteBackend {
        preparation.checkActive()
        return jobLeases(profile, security, preparation, job)
    }

    override fun createBackend(profile: ConnectionProfile, security: RouteProxySecurity, preparation: RoutePreparation): RouteBackend {
        preparation.checkActive()
        return leases(profile, security, preparation)
    }

    // Match the native backend, which never invents host DNS: every DNS server
    // must be routed through the peer in a family the interface has, and IPv6
    // interfaces need MTU 1280+. Otherwise every later route start would fail.
    override fun admitImport(profile: ConnectionProfile) {
        val fields = wireGuardFields(profile)
        val addresses = fields["[Interface].Address"].orEmpty().split(',').map { it.substringBefore('/') }
        val allowed = fields["[Peer].AllowedIPs"].orEmpty().split(',')
        val dns = fields["[Interface].DNS"]?.split(',').orEmpty()
        val mtu = fields["[Interface].MTU"]?.toIntOrNull() ?: 1280
        val routable = dns.isNotEmpty() && dns.all { server ->
            !numeric(server).isAnyLocalAddress && allowed.any { cidrContains(it, server) } &&
                addresses.any { (':' in it) == (':' in server) }
        }
        if (!routable || addresses.any { numeric(it).isAnyLocalAddress } || (addresses.any { ':' in it } && mtu < 1280))
            throw ConnectionImportFailure(ConnectionImportFailure.Category.VALUE,
                "WireGuard needs DNS servers routed through the peer for its interface addresses. Nothing was saved.")
    }

    // Values already passed literalIp/cidr, so no hostname lookup occurs.
    private fun numeric(address: String): InetAddress = InetAddress.getByName(address)

    private fun cidrContains(prefix: String, address: String): Boolean {
        val network = numeric(prefix.substringBefore('/')).address
        val candidate = numeric(address).address
        if (network.size != candidate.size) return false
        val bits = prefix.substringAfter('/').toInt()
        return (0 until bits).all { bit ->
            val mask = 0x80 ushr (bit % 8)
            (network[bit / 8].toInt() and mask) == (candidate[bit / 8].toInt() and mask)
        }
    }

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
