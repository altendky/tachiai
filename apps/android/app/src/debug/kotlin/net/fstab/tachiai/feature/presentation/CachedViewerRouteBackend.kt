package net.fstab.tachiai.feature.presentation

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import net.fstab.tachiai.platform.network.RouteBackend

// Retained before RouteSession construction/rollback. Successful ownership
// transfers to that session; failed rollback keeps this wrapper in the run.
internal class CachedViewerRouteBackend(private val backend: RouteBackend) : RouteBackend {
    private val attempted = AtomicBoolean()
    @Volatile var cleanupConfirmed = false
        private set
    override val proxyPort get() = backend.proxyPort
    override fun close() {
        if (!attempted.compareAndSet(false, true)) {
            if (!cleanupConfirmed) throw IOException("Route cleanup remains unconfirmed")
            return
        }
        backend.close()
        cleanupConfirmed = true
    }
    override fun toString() = "CachedViewerRouteBackend(status only)"
}
