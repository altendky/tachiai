package net.fstab.tachiai.feature.presentation

import java.util.concurrent.atomic.AtomicLong

// Closed user-facing categories: no provider exception, source, grant or
// diagnostic-journal identity is required to offer recovery.
internal enum class PrototypeRecoveryKind(val message: String) {
    PLAYBACK_CLEANUP_UNCONFIRMED("Playback could not be safely stopped. Restart Tachiai before opening the viewer again. Saved app data will be kept."),
    ROUTE_CLEANUP_UNCONFIRMED("Route cleanup could not be confirmed. Restart Tachiai before opening the viewer again. Saved app data will be kept."),
    PICKER_RESTORE_FAILED("The saved picker choices could not be restored. Choose the feeds again, then restart Tachiai. Saved routes and authorization will be kept."),
    ;
    val code: String get() = name
}

internal data class PrototypeRecoveryIncident(
    val id: Long,
    val kind: PrototypeRecoveryKind,
    val acknowledged: Boolean = false,
    val restarting: Boolean = false,
)

// One instance belongs to the dedicated prototype process. Callers mutate and
// observe it on the main thread. Ignore never clears the blocking incident;
// process restart creates a fresh owner rather than resetting unsafe flags.
internal class PrototypeRecoveryState {
    companion object { private val nextId = AtomicLong() }
    var incident: PrototypeRecoveryIncident? = null
        private set
    private val observers = linkedSetOf<() -> Unit>()

    fun fail(kind: PrototypeRecoveryKind): PrototypeRecoveryIncident {
        incident?.let { return it }
        val value = PrototypeRecoveryIncident(nextId.incrementAndGet(), kind)
        incident = value
        notifyChanged()
        return value
    }

    fun ignore(id: Long) {
        val current = incident ?: return
        if (current.id != id || current.acknowledged || current.restarting) return
        incident = current.copy(acknowledged = true)
        notifyChanged()
    }

    fun beginRestart(id: Long): Boolean {
        val current = incident ?: return false
        if (current.id != id || current.restarting) return false
        incident = current.copy(restarting = true)
        notifyChanged()
        return true
    }

    fun restartFailed(id: Long) {
        val current = incident ?: return
        if (current.id != id || !current.restarting) return
        incident = current.copy(restarting = false)
        notifyChanged()
    }

    fun addObserver(observer: () -> Unit) { observers.add(observer) }
    fun removeObserver(observer: () -> Unit) { observers.remove(observer) }

    // Route admission may change without a new incident. A recreated Activity
    // must receive old-Activity completion through this process-scoped owner.
    fun notifyChanged() {
        observers.toList().forEach { observer ->
            try { observer() } catch (_: Throwable) { /* A stale observer must not strand other observers. */ }
        }
    }
}
