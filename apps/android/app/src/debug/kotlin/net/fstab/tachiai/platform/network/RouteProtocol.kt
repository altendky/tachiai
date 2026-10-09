package net.fstab.tachiai.platform.network

// Only safe metadata belongs here. Configuration and runtime credentials stay
// with the profile/backend, never in summaries or generated diagnostics.
internal class ConnectionKind(val id: String, val title: String) {
    override fun toString() = id

    companion object {
        val WIREGUARD get() = WireGuardRouteProtocol.kind
        val HTTP_PROXY get() = HttpProxyRouteProtocol.kind
        val SOCKS5 get() = Socks5RouteProtocol.kind
    }
}

internal interface RouteBackend : AutoCloseable {
    val proxyPort: Int
}

internal class RouteProxySecurity(val username: String, val password: String, val realm: String, val allowedHosts: String) {
    override fun toString() = "RouteProxySecurity(secrets hidden)"
}

internal interface RouteProtocol {
    val kind: ConnectionKind
    val formatLabel: String
    val importHint: String
    fun recognizes(text: String): Boolean
    fun parse(text: String): ConnectionProfile
    fun configurationIdentity(profile: ConnectionProfile): String
    fun conflictIdentity(profile: ConnectionProfile): String? = null
    fun createBackend(profile: ConnectionProfile, security: RouteProxySecurity, preparation: RoutePreparation): RouteBackend
}

internal class RouteProtocols(protocols: List<RouteProtocol>) {
    val protocols = protocols.toList()
    private val byId = this.protocols.associateBy { it.kind.id }

    init {
        check(this.protocols.isNotEmpty() && byId.size == this.protocols.size)
        check(byId.keys.all { Regex("[a-z][a-z0-9-]{0,47}").matches(it) })
    }

    val formatLabel get() = protocols.joinToString(" or ") { it.formatLabel }
    val importHint get() = protocols.joinToString(" or ") { it.importHint }
    val titles get() = protocols.joinToString(" or ") { it.kind.title }

    fun parse(bytes: ByteArray): ConnectionProfile {
        val text = decodeConnectionText(bytes)
        // An ambiguous recognizer must fail closed instead of selecting by order.
        val protocol = protocols.filter { it.recognizes(text) }.singleOrNull()
            ?: failConnectionImport(ConnectionImportFailure.Category.FORMAT)
        return protocol.parse(text)
    }

    fun restore(id: String, bytes: ByteArray): ConnectionProfile {
        val protocol = checkNotNull(byId[id])
        val text = decodeConnectionText(bytes)
        check(protocol.recognizes(text))
        return protocol.parse(text).also { check(it.protocol === protocol) }
    }

    fun validate(profile: ConnectionProfile): ConnectionProfile {
        // A same-ID handler from another registry cannot define this store's
        // format. Reparse with the registered owner before persisting anything.
        check(byId[profile.kind.id] === profile.protocol)
        return restore(profile.kind.id, profile.configuration.toByteArray(Charsets.UTF_8))
    }
}

internal val routeProtocols = RouteProtocols(listOf(WireGuardRouteProtocol, HttpProxyRouteProtocol, Socks5RouteProtocol))

// Sharing/conflict keys are ephemeral digests, never persisted or logged.
internal fun routeConfigurationKey(profile: ConnectionProfile): String = routeDigest(
    profile.kind.id + ":" + profile.protocol.configurationIdentity(profile),
)

internal fun routeConflictKey(profile: ConnectionProfile): String? = profile.protocol.conflictIdentity(profile)?.let {
    routeDigest(profile.kind.id + ":" + it)
}

// One registry per playback run. Failed initialization is shared too, and a
// conflicting identity cannot create a second competing backend.
internal class RouteSessionRegistry(private val create: (ConnectionProfile?) -> RouteSession = { RouteSession.create(it) }) {
    private val shared = mutableMapOf<String, Result<RouteSession>>()
    private val conflicts = mutableMapOf<String, String>()

    fun acquire(profile: ConnectionProfile?): RouteSession {
        val key = profile?.let(::routeConfigurationKey) ?: "SYSTEM"
        profile?.let(::routeConflictKey)?.let { conflict ->
            val previous = conflicts.putIfAbsent(conflict, key)
            check(previous == null || previous == key)
        }
        return shared.getOrPut(key) { runCatching { create(profile) } }.getOrThrow()
    }
}
