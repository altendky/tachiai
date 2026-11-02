package net.fstab.tachiai.platform.network

import routebridge.Route
import routebridge.Routebridge
import routebridge.NetworkBinding
import java.util.concurrent.ConcurrentLinkedQueue

// The concrete Go binding stays behind the backend contract. Long native ports
// are validated before narrowing; RouteSession validates any backend's port too.
internal class NativeRouteBackend(private val route: Route) : RouteBackend {
    companion object {
        private val retainedBindings = ConcurrentLinkedQueue<NetworkBinding>()
        fun createSystem(security: RouteProxySecurity): RouteBackend = NativeRouteBackend(
            Routebridge.newSystem(security.username, security.password, security.realm, security.allowedHosts),
        )

        // Explicit opt-in only; ordinary route factories retain their existing
        // behavior. Unsupported transports must not fall back to System.
        fun createBound(profile: ConnectionProfile?, security: RouteProxySecurity,
            preparation: RoutePreparation, binding: NetworkBinding): RouteBackend {
            try {
                preparation.checkActive()
                check(binding.isActive())
                val route = when (profile?.protocol) {
                    null -> Routebridge.newSystemBound(security.username, security.password, security.realm, security.allowedHosts, binding)
                    WireGuardRouteProtocol -> Routebridge.newWireGuardBound(profile.configuration, security.username, security.password,
                        security.realm, security.allowedHosts, binding)
                    HttpProxyRouteProtocol -> Routebridge.newHttpProxyBound(profile.configuration, security.username, security.password,
                        security.realm, security.allowedHosts, binding)
                    Socks5RouteProtocol -> Routebridge.newSocks5Bound(profile.configuration, security.username, security.password,
                        security.realm, security.allowedHosts, binding)
                    else -> throw java.io.IOException("Bound route unsupported")
                }
                return NativeRouteBackend(route)
            } catch (error: Throwable) {
                val confirmed = runCatching { binding.cancel(); binding.isCleanupConfirmed() }.getOrDefault(false)
                if (!confirmed || error is Error) {
                    runCatching(binding::reportCleanupUnconfirmed)
                    retainedBindings.add(binding)
                    preparation.recordCleanupFailure()
                }
                throw error
            }
        }
    }
    override val proxyPort: Int get() {
        check(route.port in 1L..65535L)
        return route.port.toInt()
    }

    override fun close() = route.close()
    override fun toString() = "NativeRouteBackend(secrets hidden)"
}
