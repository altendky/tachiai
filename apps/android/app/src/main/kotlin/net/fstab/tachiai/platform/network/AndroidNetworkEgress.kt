package net.fstab.tachiai.platform.network

import android.net.Network
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.IOException
import java.net.InetAddress
import javax.net.SocketFactory

/** One exact supplied Network. This adapter never selects a default or binds the process. */
internal class AndroidNetworkEgress(
    private val network: Network,
    deadlineNanos: Long,
    nowNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) : RouteNetworkEgress {
    val scope = NetworkEgressScope(
        deadlineNanos = deadlineNanos,
        nowNanos = nowNanos,
        lookup = { host -> network.getAllByName(host).toList() },
        bind = ::bindDescriptor,
    )
    private val trackedFactory by lazy { scope.trackedSocketFactory(network.socketFactory) }

    override val socketFactory: SocketFactory
        get() {
            scope.checkActive()
            return trackedFactory
        }

    override fun checkActive() = scope.checkActive()

    override fun resolveAddresses(host: String, timeoutMillis: Long): List<InetAddress> =
        scope.resolveAddresses(host, timeoutMillis)

    override fun resolve(host: String?, timeoutMillis: Long): String =
        scope.resolve(host ?: throw IOException("Network egress refused"), timeoutMillis)

    override fun bindSocket(fd: Int) = scope.bindSocket(fd)

    override fun cancel() = scope.cancel()

    override fun isCleanupConfirmed(): Boolean = scope.isCleanupConfirmed()

    override fun isActive(): Boolean = scope.isActive()

    override fun reportCleanupUnconfirmed() = scope.reportCleanupUnconfirmed()

    override fun closeOwnedSockets() = scope.closeOwnedSockets()

    private fun bindDescriptor(fd: Int) {
        // fromFd duplicates ownership; the Go caller keeps the original alive throughout this call.
        val duplicate = ParcelFileDescriptor.fromFd(fd)
        try {
            network.bindSocket(duplicate.fileDescriptor)
        } finally {
            try {
                duplicate.close()
            } catch (_: Throwable) {
                scope.retainCleanupUnconfirmed(duplicate)
                throw IOException("Network egress refused")
            }
        }
    }

    override fun toString() = "AndroidNetworkEgress(private state hidden)"
}
