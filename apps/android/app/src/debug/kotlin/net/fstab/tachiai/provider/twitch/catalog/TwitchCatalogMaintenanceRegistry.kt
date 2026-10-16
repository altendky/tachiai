package net.fstab.tachiai.provider.twitch.catalog

import java.util.concurrent.atomic.AtomicBoolean

// The client/scope are fixed by this adapter. Storage includes the application's
// protected directory and exact encrypted binding, rather than just a UUID.
// Owner equality includes the captured route configuration and credentials;
// names and route titles do not change ownership. Never print these keys.
internal class TwitchCatalogMaintenanceScope(
    private val storage: String,
    private val owner: TwitchCatalogConnectionOwner,
    private val client: String,
    private val scope: String,
) {
    override fun equals(other: Any?): Boolean = other is TwitchCatalogMaintenanceScope &&
        storage == other.storage && client == other.client && scope == other.scope &&
        owner.sameOwnership(other.owner)
    override fun hashCode(): Int = listOf(storage, owner.instanceId, client, scope).hashCode()
    override fun toString() = "TwitchCatalogMaintenanceScope(redacted)"
}

// Bindings hold references before sessions attach, preventing an idle-removal
// race from creating two coordinators for the same owner. Construction, owner
// callbacks, storage, transport cancellation and cleanup stay outside this lock.
internal class TwitchCatalogMaintenanceRegistry {
    private class Entry(val coordinator: TwitchCatalogMaintenanceCoordinator) {
        var references = 0
        val revoked = AtomicBoolean()
    }
    private val lock = Any()
    private val entries = mutableMapOf<TwitchCatalogMaintenanceScope, Entry>()
    private val retired = mutableSetOf<Entry>()

    internal class Handle(val coordinator: TwitchCatalogMaintenanceCoordinator,
        private val scopeCurrent: () -> Boolean, private val revokeScope: () -> Unit,
        private val release: () -> Unit) : AutoCloseable {
        private val lock = Any()
        private var closed = false
        // Closing one binding does not revoke its other shared consumers.
        fun canContinue() = scopeCurrent()
        fun revoke() = revokeScope()
        override fun close() {
            if (synchronized(lock) { if (closed) false else { closed = true; true } }) release()
        }
        override fun toString() = "TwitchCatalogMaintenanceHandle(redacted)"
    }

    fun acquire(scope: TwitchCatalogMaintenanceScope,
        create: (onIdle: () -> Unit) -> TwitchCatalogMaintenanceCoordinator): Handle {
        synchronized(lock) { entries[scope]?.let { it.references++; return handle(scope, it) } }
        lateinit var created: TwitchCatalogMaintenanceCoordinator
        created = create { retire(scope, created) }
        return synchronized(lock) {
            val entry = entries.getOrPut(scope) { Entry(created) }
            entry.references++
            handle(scope, entry)
        }
    }

    private fun handle(scope: TwitchCatalogMaintenanceScope, entry: Entry) = Handle(entry.coordinator,
        { !entry.revoked.get() }, { revoke(scope, entry) }) {
        synchronized(lock) { check(entry.references > 0); entry.references-- }
        retire(scope, entry.coordinator)
    }

    private fun revoke(scope: TwitchCatalogMaintenanceScope, entry: Entry) {
        synchronized(lock) {
            entry.revoked.set(true)
            if (entries[scope] === entry) entries.remove(scope)
            // Retain old workers/timers/attachments until their own cleanup is
            // confirmed. A fresh scope never removes or replaces these owners.
            retired.add(entry)
        }
        retire(scope, entry.coordinator)
    }

    private fun retire(scope: TwitchCatalogMaintenanceScope, coordinator: TwitchCatalogMaintenanceCoordinator) {
        // isIdle is a memory snapshot; coordinator invokes onIdle without its
        // monitor. No participant/owner callbacks run while holding this lock.
        synchronized(lock) {
            val entry = entries[scope]
            if (entry?.coordinator === coordinator && entry.references == 0 && coordinator.isIdle) entries.remove(scope)
            retired.removeAll { it.coordinator === coordinator && it.references == 0 && coordinator.isIdle }
        }
    }
}
