package net.fstab.tachiai.feature.connections

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Collections
import java.util.UUID
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.*

// Provenance describes this one locked read, never a cached existence check.
// Legacy-derived views may span other files and are not single-file snapshots.
internal class ProviderInstanceRegistrySnapshot internal constructor(instances: List<ProviderInstance>, val committed: Boolean) {
    val instances: List<ProviderInstance> = Collections.unmodifiableList(instances.map { instance ->
        instance.copy(setup = instance.setup.copy(
            previousRoutes = Collections.unmodifiableList(ArrayList(instance.setup.previousRoutes)),
            fallbackRoutes = Collections.unmodifiableList(ArrayList(instance.setup.fallbackRoutes))))
    })
    override fun toString() = "ProviderInstanceRegistrySnapshot(redacted)"
}

// Missing registry is a read-only legacy view. The first explicit mutation commits
// the whole view atomically; no source/provider/route/grant slot is rewritten.
internal class ProviderInstanceStore(private val store: PrivateSecretStore, private val lockFile: File? = null) {
    companion object { private val lock = Any() }
    private fun <T> locked(action: () -> T): T = synchronized(lock) {
        val file = lockFile
        if (file == null) action() else RandomAccessFile(file, "rw").use { it.channel.lock().use { action() } }
    }
    fun read(legacy: () -> Map<PrototypeService, ProviderSetup>): List<ProviderInstance> = readSnapshot(legacy).instances
    fun readSnapshot(legacy: () -> Map<PrototypeService, ProviderSetup>): ProviderInstanceRegistrySnapshot =
        locked { readSnapshotUnlocked(legacy) }

    private fun readUnlocked(legacy: () -> Map<PrototypeService, ProviderSetup>): List<ProviderInstance> =
        readSnapshotUnlocked(legacy).instances

    private fun readSnapshotUnlocked(legacy: () -> Map<PrototypeService, ProviderSetup>): ProviderInstanceRegistrySnapshot {
        val bytes = store.read() ?: return ProviderInstanceRegistrySnapshot(validate(defaultProviderInstances(legacy())), committed = false)
        check(bytes.size <= PRIVATE_SECRET_LIMIT)
        val entries = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version = input.readInt(); check(version in 1..3)
            val count = input.readInt()
            check(count in (if (version < 3) PrototypeService.entries.size else 0)..MAX_PROVIDER_INSTANCES)
            fun choice(): SourceRouteChoice {
                val mode = SourceRouteMode.valueOf(input.readUTF())
                return if (mode == SourceRouteMode.SAVED_CONNECTION) SourceRouteChoice(mode, input.readUTF(), input.readUTF())
                    else SourceRouteChoice(mode)
            }
            val entries = List(count) {
                val id = input.readUTF(); val service = PrototypeService.valueOf(input.readUTF())
                val defaultName = input.readUTF(); val customName = if (input.readBoolean()) input.readUTF() else null
                val routes = if (version == 1) {
                    if (input.readBoolean()) listOf(choice()) else emptyList()
                } else {
                    val routeCount = input.readInt(); check(routeCount in 0..MAX_PROVIDER_ROUTES)
                    List(routeCount) { choice() }
                }
                val previousCount = input.readInt(); check(previousCount in 0..32)
                ProviderInstance(id, service, defaultName, customName,
                    ProviderSetup(routes.firstOrNull(), List(previousCount) { choice() }, routes.drop(1)))
            }
            check(input.read() == -1)
            if (version < 3) PrototypeService.entries.forEach { service ->
                check(entries.singleOrNull { it.id == defaultProviderInstanceId(service) }?.service == service)
            }
            validate(entries)
        }
        return ProviderInstanceRegistrySnapshot(entries, committed = true)
    }

    private fun validate(entries: List<ProviderInstance>): List<ProviderInstance> {
        check(entries.size <= MAX_PROVIDER_INSTANCES)
        check(entries.map { it.id }.distinct().size == entries.size)
        PrototypeService.entries.forEach { service ->
            check(entries.filter { it.service == service }.map { it.name.lowercase(java.util.Locale.ROOT) }.distinct().size ==
                entries.count { it.service == service })
        }
        return entries
    }

    fun create(service: PrototypeService, legacy: () -> Map<PrototypeService, ProviderSetup>): List<ProviderInstance> = mutate(legacy) { entries ->
        check(entries.size < MAX_PROVIDER_INSTANCES)
        var ordinal = 1
        fun name() = if (ordinal == 1) service.title else "${service.title} $ordinal"
        while (entries.any { it.service == service && (it.defaultName == name() ||
                sameProviderInstanceName(it.name, name())) }) ordinal++
        entries + ProviderInstance(UUID.randomUUID().toString(), service, name())
    }

    fun remove(id: String, legacy: () -> Map<PrototypeService, ProviderSetup>): List<ProviderInstance> = mutate(legacy) { entries ->
        check(entries.any { it.id == id })
        entries.filterNot { it.id == id }
    }

    fun reorder(ids: List<String>, legacy: () -> Map<PrototypeService, ProviderSetup>): List<ProviderInstance> = mutate(legacy) { entries ->
        check(ids.size == entries.size && ids.toSet() == entries.map { it.id }.toSet())
        ids.map { id -> entries.single { it.id == id } }
    }

    fun save(id: String, name: String?, route: SourceRouteChoice,
        legacy: () -> Map<PrototypeService, ProviderSetup>): List<ProviderInstance> =
        saveRoutes(id, name, listOf(route), legacy)

    // Replace the complete ordered pool in one write; an empty pool requires an
    // explicit future choice and never implies System network.
    fun saveRoutes(id: String, name: String?, routes: List<SourceRouteChoice>,
        legacy: () -> Map<PrototypeService, ProviderSetup>): List<ProviderInstance> = mutate(legacy) { entries ->
        check(entries.any { it.id == id }) // Stale IDs are never recreated or redirected.
        entries.map { if (it.id == id) it.copy(customName = name,
            setup = it.setup.copy(route = routes.firstOrNull(), fallbackRoutes = routes.drop(1))) else it }
    }

    private fun mutate(legacy: () -> Map<PrototypeService, ProviderSetup>, update: (List<ProviderInstance>) -> List<ProviderInstance>): List<ProviderInstance> = locked {
        val entries = validate(update(readUnlocked(legacy)))
        val bytes = ByteArrayOutputStream().also { output -> DataOutputStream(output).use { data ->
            data.writeInt(3); data.writeInt(entries.size)
            fun choice(value: SourceRouteChoice) {
                data.writeUTF(value.mode.name)
                if (value.mode == SourceRouteMode.SAVED_CONNECTION) {
                    data.writeUTF(checkNotNull(value.connectionId)); data.writeUTF(checkNotNull(value.connectionName))
                }
            }
            entries.forEach {
                data.writeUTF(it.id); data.writeUTF(it.service.name); data.writeUTF(it.defaultName)
                data.writeBoolean(it.customName != null); it.customName?.let(data::writeUTF)
                data.writeInt(it.setup.routes.size); it.setup.routes.forEach(::choice)
                data.writeInt(it.setup.previousRoutes.size); it.setup.previousRoutes.forEach(::choice)
            }
        } }.toByteArray()
        check(bytes.size <= PRIVATE_SECRET_LIMIT)
        store.write(bytes)
        entries
    }
}
