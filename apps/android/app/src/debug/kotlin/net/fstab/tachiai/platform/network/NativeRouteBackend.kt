package net.fstab.tachiai.platform.network

import routebridge.Route

// The concrete Go binding stays behind the backend contract. Long native ports
// are validated before narrowing; RouteSession validates any backend's port too.
internal class NativeRouteBackend(private val route: Route) : RouteBackend {
    override val proxyPort: Int get() {
        check(route.port in 1L..65535L)
        return route.port.toInt()
    }

    override fun close() = route.close()
    override fun toString() = "NativeRouteBackend(secrets hidden)"
}
