package net.fstab.tachiai.platform.network

import java.io.IOException

// Closed reasons the route bridge reports when it refuses a connection.
// AUTH_CHALLENGE is the 407 to a CONNECT without credentials, which WebView
// answers as an ordinary proxy challenge; it is logged at info level only.
internal enum class RouteRefusalReason { NOT_ALLOWED, BAD_PORT, MALFORMED, AUTH, CAPACITY, AUTH_CHALLENGE, UNKNOWN }

private val refusalHost = Regex("[a-z0-9.:-]{1,253}")

private fun routeRefusalReason(reason: String) =
    RouteRefusalReason.entries.firstOrNull { it.name == reason } ?: RouteRefusalReason.UNKNOWN

// One warning per route refusal, or per periodic summary of identical repeats.
// The bridge passes only a validated host name or IP literal and port; this
// check again so no path, query, header or credential can reach the log.
internal fun routeRefusalLine(process: String, purpose: RoutePurpose?, reason: String, host: String,
    port: Long, count: Long, collapsed: Boolean): String {
    val safeReason = routeRefusalReason(reason)
    val safeHost = host.takeIf { refusalHost.matches(it) } ?: "-"
    val safePort = port.takeIf { it in 1..65535 }?.toString() ?: "-"
    val event = if (safeReason == RouteRefusalReason.AUTH_CHALLENGE) "ROUTE_CHALLENGED" else "ROUTE_REFUSED"
    return "$event process=$process purpose=${purpose?.name ?: "UNSPECIFIED"} reason=${safeReason.name}" +
        " host=$safeHost port=$safePort count=${count.coerceAtLeast(1)}" + if (collapsed) " collapsed=true" else ""
}

// Receives the Go bridge's refusal callbacks for one route. A logging failure
// must never propagate back into the bridge.
internal class RouteRefusalLog(private val purpose: RoutePurpose?,
    private val warn: (String) -> Unit = { line -> android.util.Log.w(TAG, line) },
    private val info: (String) -> Unit = { line -> android.util.Log.i(TAG, line) },
    private val process: () -> String = { RouteEngineOwner.role },
) : routebridge.RefusalListener {
    override fun refused(reason: String, host: String, port: Long, count: Long, collapsed: Boolean) {
        try {
            val line = routeRefusalLine(process(), purpose, reason, host, port, count, collapsed)
            if (routeRefusalReason(reason) == RouteRefusalReason.AUTH_CHALLENGE) info(line) else warn(line)
        } catch (_: Throwable) { }
    }
    override fun toString() = "RouteRefusalLog(secrets hidden)"

    companion object {
        const val TAG = "TachiaiRouteEngine"
    }
}

// OkHttp's only signal that our own loopback route answered CONNECT with
// 400 (malformed) or 403 (host or port refused). The bridge logs the cause.
private val routeRefusalMessages = setOf(
    "Unexpected response code for CONNECT: 400",
    "Unexpected response code for CONNECT: 403",
)

internal fun isRouteConnectRefusal(error: Throwable): Boolean =
    error.javaClass == IOException::class.java && error.message in routeRefusalMessages
