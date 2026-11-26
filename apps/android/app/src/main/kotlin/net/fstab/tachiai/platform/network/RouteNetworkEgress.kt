package net.fstab.tachiai.platform.network

import android.net.Network
import java.net.InetAddress
import javax.net.SocketFactory
import routebridge.NetworkBinding

// One explicitly supplied Network capability, kept only in its operation owner.
// Direct HTTPS call ownership also covers work inside its Network socket factory.
internal interface RouteNetworkEgress : NetworkBinding {
    val socketFactory: SocketFactory
    // The capability this scope was made from, for a separately owned
    // job-bound route engine (#275 stage 5). Fixtures have none.
    val routeJob: RouteJobNetwork? get() = null
    fun checkActive()
    fun resolveAddresses(host: String, timeoutMillis: Long): List<InetAddress>
    fun closeOwnedSockets()
}

// A renewal job's captured Network and original deadline, RAM only. The route
// owner uses it only to start a job-bound engine when none is held for the
// identity; that engine gets its own scope from it.
internal class RouteJobNetwork(val network: Network, val deadlineElapsedMs: Long,
    private val create: (Network, Long) -> RouteNetworkEgress = { value, deadlineNanos -> AndroidNetworkEgress(value, deadlineNanos) },
) {
    init { require(deadlineElapsedMs > 0) }
    fun egress(): RouteNetworkEgress = create(network, Math.multiplyExact(deadlineElapsedMs, 1_000_000L))
    override fun toString() = "RouteJobNetwork(private capability hidden)"
}
