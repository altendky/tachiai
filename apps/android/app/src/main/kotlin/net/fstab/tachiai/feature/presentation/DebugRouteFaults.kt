package net.fstab.tachiai.feature.presentation

import net.fstab.tachiai.BuildConfig

internal enum class DebugRouteFaultResult { QUEUED, CLEARED, INVALID_REQUEST, UNAVAILABLE }

// Debug builds only (#271): a shell-triggered route fault for checking playback
// route recovery on a device. The debug-manifest provider queues a request in
// the viewer's own process; the open viewer applies it to the route that feed
// currently uses. That route then refuses new requests like an unreachable
// connection, here and on any later viewer route of the same configured route,
// until cleared or the process ends. Memory only; never a host, key or name.
// Release builds return UNAVAILABLE and never fault a route.
internal object DebugRouteFaults {
    private val lock = Any()
    private val requestedSlots = linkedSetOf<Int>()
    private var clearRequested = false
    private val faultedRoutes = mutableSetOf<String>()

    fun request(slot: String?): DebugRouteFaultResult {
        if (!BuildConfig.DEBUG) return DebugRouteFaultResult.UNAVAILABLE
        val index = when (slot) { "A" -> 0; "B" -> 1; else -> return DebugRouteFaultResult.INVALID_REQUEST }
        synchronized(lock) { requestedSlots += index }
        return DebugRouteFaultResult.QUEUED
    }

    fun clear(): DebugRouteFaultResult {
        if (!BuildConfig.DEBUG) return DebugRouteFaultResult.UNAVAILABLE
        synchronized(lock) { clearRequested = true; requestedSlots.clear(); faultedRoutes.clear() }
        return DebugRouteFaultResult.CLEARED
    }

    // Viewer main thread: whether a clear arrived, and the requested slots.
    fun take(): Pair<Boolean, Set<Int>> = synchronized(lock) {
        (clearRequested to requestedSlots.toSet()).also { clearRequested = false; requestedSlots.clear() }
    }

    fun fault(routeIdentity: String) { if (BuildConfig.DEBUG) synchronized(lock) { faultedRoutes += routeIdentity } }

    fun faulted(routeIdentity: String?): Boolean =
        BuildConfig.DEBUG && routeIdentity != null && synchronized(lock) { routeIdentity in faultedRoutes }
}
