package net.fstab.tachiai.platform.media

import java.util.concurrent.atomic.AtomicInteger
import net.fstab.tachiai.platform.network.RoutedHttpsCallPhase
import net.fstab.tachiai.platform.network.RoutedHttpsFailureCategory
import net.fstab.tachiai.platform.network.RoutedHttpsFailureDiagnostic

internal enum class NativeMediaRequestType { MANIFEST, MEDIA, OTHER }

// Closed values only: no connection, Throwable, provider identity or request data
// crosses this optional observation boundary. Cancellation observations describe
// this request; they do not diagnose why an owner or route ended.
internal data class NativeMediaTransportDiagnostic(
    val type: NativeMediaRequestType,
    val category: RoutedHttpsFailureCategory,
    val phase: RoutedHttpsCallPhase,
    val proxyPresent: Boolean,
    val localCancellationRequested: Boolean,
    val phaseAvailable: Boolean,
    val routeInactiveObserved: Boolean,
)

internal typealias NativeMediaTransportObserver = (NativeMediaTransportDiagnostic) -> Unit

internal class NativeMediaTransportDiagnostics(private val observer: NativeMediaTransportObserver) {
    private val delivered = AtomicInteger()

    fun record(type: NativeMediaRequestType, diagnostic: RoutedHttpsFailureDiagnostic, current: () -> Boolean) {
        if (!runCatching(current).getOrDefault(false)) return
        // Count attempts, including a throwing consumer, without retaining any
        // diagnostic history or letting a broken sink increase the output cap.
        while (true) {
            val count = delivered.get()
            if (count >= MAXIMUM_OBSERVATIONS) return
            if (delivered.compareAndSet(count, count + 1)) break
        }
        if (!runCatching(current).getOrDefault(false)) return
        val value = NativeMediaTransportDiagnostic(type, diagnostic.category, diagnostic.phase,
            diagnostic.proxyPresent, diagnostic.localCancellationRequested, diagnostic.phaseAvailable,
            diagnostic.routeInactiveObserved)
        try { observer(value) } catch (_: Throwable) { }
    }

    companion object { internal const val MAXIMUM_OBSERVATIONS = 16 }
}
