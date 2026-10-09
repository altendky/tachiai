package net.fstab.tachiai.feature.connections

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.*

// Missing registry is a read-only legacy view. The first explicit mutation commits
// the whole view atomically; no source/provider/route/grant slot is rewritten.
internal class ProviderInstanceStore(private val store: PrivateSecretStore, private val lockFile: File? = null) {
    companion object { private val lock = Any() }
    private fun <T> locked(action: () -> T): T = synchronized(lock) {
        val file = lockFile
        if (file == null) action() else RandomAccessFile(file, "rw").use { it.channel.lock().use { action() } }
    }
    fun read(legacy: () -> Map<PrototypeService, ProviderSetup>): List<ProviderInstance> = locked { readUnlocked(legacy) }

    private fun readUnlocked(legacy: () -> Map<PrototypeService, ProviderSetup>): List<ProviderInstance> {
        val bytes = store.read() ?: return validate(defaultProviderInstances(legacy()))
        check(bytes.size <= PRIVATE_SECRET_LIMIT)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            check(input.readInt() == 1)
            val count = input.readInt(); check(count in PrototypeService.entries.size..MAX_PROVIDER_INSTANCES)
            fun choice(): SourceRouteChoice {
                val mode = SourceRouteMode.valueOf(input.readUTF())
                return if (mode == SourceRouteMode.SAVED_CONNECTION) SourceRouteChoice(mode, input.readUTF(), input.readUTF())
                    else SourceRouteChoice(mode)
            }
            val entries = List(count) {
                val id = input.readUTF(); val service = PrototypeService.valueOf(input.readUTF())
                val defaultName = input.readUTF(); val customName = if (input.readBoolean()) input.readUTF() else null
                val route = if (input.readBoolean()) choice() else null
                val previousCount = input.readInt(); check(previousCount in 0..32)
                ProviderInstance(id, service, defaultName, customName, ProviderSetup(route, List(previousCount) { choice() }))
            }
            check(input.read() == -1)
            validate(entries)
        }
    }

    private fun validate(entries: List<ProviderInstance>): List<ProviderInstance> {
        check(entries.size in PrototypeService.entries.size..MAX_PROVIDER_INSTANCES)
        check(entries.map { it.id }.distinct().size == entries.size)
        PrototypeService.entries.forEach { service ->
            check(entries.singleOrNull { it.id == defaultProviderInstanceId(service) }?.service == service)
            check(entries.filter { it.service == service }.map { it.name.lowercase(java.util.Locale.ROOT) }.distinct().size ==
                entries.count { it.service == service })
        }
        return entries
    }

    fun create(service: PrototypeService, legacy: () -> Map<PrototypeService, ProviderSetup>): List<ProviderInstance> = mutate(legacy) { entries ->
        check(entries.size < MAX_PROVIDER_INSTANCES)
        var ordinal = 2
        while (entries.any { it.service == service && (it.defaultName == "${service.title} $ordinal" ||
                sameProviderInstanceName(it.name, "${service.title} $ordinal")) }) ordinal++
        entries + ProviderInstance(UUID.randomUUID().toString(), service, "${service.title} $ordinal")
    }

    fun save(id: String, name: String?, route: SourceRouteChoice,
        legacy: () -> Map<PrototypeService, ProviderSetup>): List<ProviderInstance> = mutate(legacy) { entries ->
        check(entries.any { it.id == id }) // Stale IDs are never recreated or redirected.
        entries.map { if (it.id == id) it.copy(customName = name, setup = it.setup.copy(route = route)) else it }
    }

    private fun mutate(legacy: () -> Map<PrototypeService, ProviderSetup>, update: (List<ProviderInstance>) -> List<ProviderInstance>): List<ProviderInstance> = locked {
        val entries = validate(update(readUnlocked(legacy)))
        val bytes = ByteArrayOutputStream().also { output -> DataOutputStream(output).use { data ->
            data.writeInt(1); data.writeInt(entries.size)
            fun choice(value: SourceRouteChoice) {
                data.writeUTF(value.mode.name)
                if (value.mode == SourceRouteMode.SAVED_CONNECTION) {
                    data.writeUTF(checkNotNull(value.connectionId)); data.writeUTF(checkNotNull(value.connectionName))
                }
            }
            entries.forEach {
                data.writeUTF(it.id); data.writeUTF(it.service.name); data.writeUTF(it.defaultName)
                data.writeBoolean(it.customName != null); it.customName?.let(data::writeUTF)
                data.writeBoolean(it.setup.route != null); it.setup.route?.let(::choice)
                data.writeInt(it.setup.previousRoutes.size); it.setup.previousRoutes.forEach(::choice)
            }
        } }.toByteArray()
        check(bytes.size <= PRIVATE_SECRET_LIMIT)
        store.write(bytes)
        entries
    }
}
