package net.fstab.tachiai.platform.network

import java.net.InetAddress
import javax.net.SocketFactory
import routebridge.NetworkBinding

// One explicitly supplied Network capability, kept only in its operation owner.
// Direct HTTPS call ownership also covers work inside its Network socket factory.
internal interface RouteNetworkEgress : NetworkBinding {
    val socketFactory: SocketFactory
    fun checkActive()
    fun resolveAddresses(host: String, timeoutMillis: Long): List<InetAddress>
    fun closeOwnedSockets()
}
