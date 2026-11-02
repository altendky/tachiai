package net.fstab.tachiai.platform.network

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/** RAM-only owner of work on one supplied Network; cancellation never means owned work has stopped. */
internal class NetworkEgressScope(
    val deadlineNanos: Long,
    private val nowNanos: () -> Long,
    private val lookup: (String) -> List<InetAddress>,
    private val bind: (Int) -> Unit,
) {
    private val lock = Object()
    private var lastNow = nowNanos()
    private var retired = false
    private var activeLookups = 0
    private val lookupsByHost = mutableMapOf<String, Lookup>()
    private var activeBindings = 0
    private var activeSocketCreations = 0
    private var bindingCleanupFailed = false
    private val retainedResources = mutableListOf<AutoCloseable>()
    private class OwnedSocket(val socket: Socket) {
        var closeConfirmed = false
    }
    private val ownedSockets = mutableListOf<OwnedSocket>()
    private var socketCloseStarted = false
    private var socketCloseFinished = false

    init {
        require(lastNow >= 0 && deadlineNanos > lastNow && deadlineNanos - lastNow <= MAX_LIFETIME_NANOS) {
            "Invalid network egress lifetime"
        }
    }

    private class Lookup(var deadlineNanos: Long) {
        var finished = false
        var addresses: List<InetAddress>? = null
    }

    @Throws(IOException::class)
    fun checkActive() = synchronized(lock) { checkActiveLocked() }

    fun isActive(): Boolean = synchronized(lock) { isActiveLocked() }

    /** Wakes waiters without interrupting or pretending to cancel a platform resolver/binder. */
    fun cancel() = synchronized(lock) { retireLocked() }

    fun isCleanupConfirmed(): Boolean = synchronized(lock) {
        activeLookups == 0 && activeBindings == 0 && activeSocketCreations == 0 && !bindingCleanupFailed &&
            ownedSockets.all { it.closeConfirmed }
    }

    @Throws(IOException::class)
    fun resolve(host: String, timeoutMillis: Long): String =
        resolveAddresses(host, timeoutMillis).joinToString("\n") { it.hostAddress!! }

    @Throws(IOException::class)
    fun resolveAddresses(host: String, timeoutMillis: Long): List<InetAddress> {
        val pending: Lookup
        val startsWorker: Boolean
        val waitDeadline = synchronized(lock) {
            checkActiveLocked()
            if (timeoutMillis <= 0 || !validHost(host)) throw refused()
            // Clamp before conversion/addition, including arbitrarily large native timeout hints.
            val budget = TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtMost(MAX_LIFETIME_MILLIS))
            val end = lastNow + minOf(budget, deadlineNanos - lastNow)
            // Only an actually outstanding same-scope lookup may be shared; never cache a reply.
            val existing = lookupsByHost[host]
            startsWorker = existing == null
            pending = existing ?: run {
                if (activeLookups >= MAX_LOOKUPS) throw refused()
                Lookup(end).also {
                    lookupsByHost[host] = it
                    activeLookups++
                }
            }
            pending.deadlineNanos = minOf(pending.deadlineNanos, end)
            end
        }
        if (startsWorker) {
            val work = Runnable {
                try {
                    val permitted = synchronized(lock) {
                        val active = isActiveLocked()
                        if (lastNow >= pending.deadlineNanos) retireLocked()
                        active && !retired
                    }
                    if (permitted) {
                        val result = lookup(host)
                        val numeric = boundedAddresses(result)
                        synchronized(lock) {
                            if (isActiveLocked()) {
                                if (lastNow >= pending.deadlineNanos) retireLocked()
                                else pending.addresses = numeric
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // No provider/host/platform exception text or cause crosses this boundary.
                } finally {
                    synchronized(lock) {
                        pending.finished = true
                        lookupsByHost.remove(host)
                        activeLookups--
                        lock.notifyAll()
                    }
                }
            }
            try {
                val worker = Thread(work, "tachiai-network-dns").apply { isDaemon = true }
                worker.start()
            } catch (_: Throwable) {
                synchronized(lock) {
                    pending.finished = true
                    lookupsByHost.remove(host)
                    activeLookups--
                    lock.notifyAll()
                }
                throw refused()
            }
        }
        synchronized(lock) {
            while (true) {
                checkActiveLocked()
                if (lastNow >= pending.deadlineNanos) {
                    retireLocked()
                    throw refused()
                }
                if (pending.finished) return pending.addresses ?: throw refused()
                val remaining = minOf(waitDeadline - lastNow, pending.deadlineNanos - lastNow, WAIT_SLICE_NANOS)
                try {
                    lock.wait(remaining / 1_000_000, (remaining % 1_000_000).toInt())
                } catch (_: InterruptedException) {
                    retireLocked()
                    Thread.currentThread().interrupt()
                    throw refused()
                }
            }
        }
    }

    /** The caller retains the borrowed original FD until this synchronous call returns. */
    @Throws(IOException::class)
    fun bindSocket(fd: Int) {
        synchronized(lock) {
            checkActiveLocked()
            if (fd < 0 || activeBindings >= MAX_BINDINGS) throw refused()
            activeBindings++
        }
        try {
            bind(fd)
            synchronized(lock) { checkActiveLocked() }
        } catch (_: Throwable) {
            throw refused()
        } finally {
            synchronized(lock) { activeBindings--; lock.notifyAll() }
        }
    }

    /** Socket identity and TLS stay unchanged; even accepted sockets keep a separate close owner. */
    fun trackedSocketFactory(factory: SocketFactory): SocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket = createOwnedSocket { factory.createSocket() }
        override fun createSocket(host: String, port: Int): Socket =
            createOwnedSocket { factory.createSocket(host, port) }
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
            createOwnedSocket { factory.createSocket(host, port, local, localPort) }
        override fun createSocket(host: InetAddress, port: Int): Socket =
            createOwnedSocket { factory.createSocket(host, port) }
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
            createOwnedSocket { factory.createSocket(host, port, local, localPort) }
        override fun toString() = "NetworkEgressSocketFactory(private state hidden)"
    }

    private fun createOwnedSocket(create: () -> Socket?): Socket {
        synchronized(lock) {
            checkActiveLocked()
            if (activeSocketCreations >= MAX_SOCKET_CREATIONS ||
                ownedSockets.size + activeSocketCreations >= MAX_OWNED_SOCKETS
            ) throw refused()
            activeSocketCreations++
        }
        var created: Socket? = null
        try {
            val result = create() ?: throw refused()
            created = result
            synchronized(lock) {
                checkActiveLocked()
                // Register before dropping creation ownership: cancellation may precede caller assignment.
                if (ownedSockets.none { it.socket === result }) ownedSockets.add(OwnedSocket(result))
            }
            return result
        } catch (_: Throwable) {
            val late = created
            if (late != null) {
                try { late.close() } catch (_: Throwable) { retainCleanupUnconfirmed(late) }
            } else {
                // A throwing factory supplies no socket/closure receipt for any partial allocation.
                reportCleanupUnconfirmed()
            }
            throw refused()
        } finally {
            synchronized(lock) { activeSocketCreations--; lock.notifyAll() }
        }
    }

    /** One bounded explicit close receipt; cancellation alone never acknowledges accepted sockets. */
    @Throws(IOException::class)
    fun closeOwnedSockets() {
        val closeDeadline = System.nanoTime() + SOCKET_CLOSE_WAIT_NANOS
        val start = synchronized(lock) {
            retireLocked()
            if (activeSocketCreations != 0) reportCleanupUnconfirmed()
            if (socketCloseStarted) false else {
                socketCloseStarted = true
                if (ownedSockets.isEmpty()) socketCloseFinished = true
                !socketCloseFinished
            }
        }
        if (start) {
            try {
                Thread({
                    try {
                        // Retirement prevents any further accepted-socket registration.
                        val snapshot = synchronized(lock) { ownedSockets.toList() }
                        snapshot.forEach { owned ->
                            try {
                                owned.socket.close()
                                synchronized(lock) { owned.closeConfirmed = true }
                            } catch (_: Throwable) { retainCleanupUnconfirmed(owned.socket) }
                        }
                    } catch (_: Throwable) { reportCleanupUnconfirmed() }
                    finally { synchronized(lock) { socketCloseFinished = true; lock.notifyAll() } }
                }, "tachiai-network-close").apply { isDaemon = true }.start()
            } catch (_: Throwable) {
                synchronized(lock) { reportCleanupUnconfirmed(); socketCloseFinished = true; lock.notifyAll() }
            }
        }
        synchronized(lock) {
            while (!socketCloseFinished) {
                val left = closeDeadline - System.nanoTime()
                if (left <= 0) {
                    reportCleanupUnconfirmed()
                    throw refused()
                }
                try {
                    lock.wait(left / 1_000_000, (left % 1_000_000).toInt())
                } catch (_: InterruptedException) {
                    reportCleanupUnconfirmed()
                    Thread.currentThread().interrupt()
                    throw refused()
                }
            }
            if (bindingCleanupFailed || ownedSockets.any { !it.closeConfirmed }) throw refused()
        }
    }

    /** Diagnostic-only native callback: never calls back into Go or releases any resource. */
    fun reportCleanupUnconfirmed() = synchronized(lock) {
        if (!bindingCleanupFailed) {
            bindingCleanupFailed = true
            retainedUncertainScopes.add(this)
        }
        retireLocked()
    }

    /** Keep the actual owner after a failed close, rather than only keeping its failure flag. */
    fun retainCleanupUnconfirmed(resource: AutoCloseable) = synchronized(lock) {
        if (retainedResources.none { it === resource }) retainedResources.add(resource)
        reportCleanupUnconfirmed()
    }

    fun markBindingCleanupUnconfirmed() = reportCleanupUnconfirmed()

    private fun checkActiveLocked() {
        if (!isActiveLocked()) throw refused()
    }

    private fun isActiveLocked(): Boolean {
        val now = nowNanos()
        if (now < lastNow || now >= deadlineNanos) retireLocked()
        lastNow = now
        return !retired
    }

    private fun retireLocked() {
        retired = true
        lock.notifyAll()
    }

    override fun toString() = "NetworkEgressScope(private state hidden)"

    companion object {
        private const val MAX_LOOKUPS = 4
        private const val MAX_BINDINGS = 4
        private const val MAX_SOCKET_CREATIONS = 32
        private const val MAX_OWNED_SOCKETS = 64
        private val SOCKET_CLOSE_WAIT_NANOS = TimeUnit.SECONDS.toNanos(1)
        private val retainedUncertainScopes = ConcurrentLinkedQueue<NetworkEgressScope>()
        private const val MAX_LIFETIME_MILLIS = 90_000L
        private val MAX_LIFETIME_NANOS = TimeUnit.MILLISECONDS.toNanos(MAX_LIFETIME_MILLIS)
        private val WAIT_SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(25)
        private fun refused() = IOException("Network egress refused")

        private fun validHost(host: String): Boolean = host.length in 1..253 && host.all {
            it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' || it == ':'
        }

        private fun boundedAddresses(addresses: List<InetAddress>): List<InetAddress> {
            if (addresses.isEmpty() || addresses.size > 32) throw refused()
            val distinct = addresses.distinctBy { it.hostAddress }
            val numeric = distinct.map { it.hostAddress ?: throw refused() }
            // Scope identifiers are not a permitted part of the native numeric-address protocol.
            if (numeric.any { it.length > 64 || it.any { c -> c !in "0123456789abcdefABCDEF:." } } ||
                numeric.sumOf { it.length + 1 } > 4096
            ) throw refused()
            return distinct.toList()
        }
    }
}
