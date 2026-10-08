package net.fstab.tachiai.feature.connections

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.presentation.*

// New slot: never rewrite imported route secrets or the historical per-stream record.
internal class ProviderSetupStore(private val store: PrivateSecretStore, private val lockFile: File? = null) {
    companion object { private val lock = Any() }
    private fun <T> locked(action: () -> T): T = synchronized(lock) {
        val file = lockFile
        if (file == null) action() else RandomAccessFile(file, "rw").use { handle -> handle.channel.lock().use { action() } }
    }
    private fun readExplicit(): Map<PrototypeService, ProviderSetup> {
        val bytes = store.read() ?: return emptyMap()
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            check(input.readInt() == 1)
            val count = input.readInt(); check(count in 0..PrototypeService.entries.size)
            val entries = List(count) {
                val provider = PrototypeService.valueOf(input.readUTF())
                val mode = SourceRouteMode.valueOf(input.readUTF())
                val route = if (mode == SourceRouteMode.SAVED_CONNECTION) SourceRouteChoice(mode, input.readUTF(), input.readUTF()) else SourceRouteChoice(mode)
                provider to ProviderSetup(route)
            }
            check(entries.map { it.first }.distinct().size == count && input.read() == -1)
            entries.toMap()
        }
    }
    fun read(legacy: Map<PrototypeSource, SourceSetup>): Map<PrototypeService, ProviderSetup> = locked {
        legacyProviderSetups(legacy) + readExplicit()
    }
    fun save(provider: PrototypeService, route: SourceRouteChoice, legacy: Map<PrototypeSource, SourceSetup>): Map<PrototypeService, ProviderSetup> = locked {
        val value = ProviderSetup(route)
        val updated = readExplicit() + (provider to value)
        val bytes = ByteArrayOutputStream().also { output -> DataOutputStream(output).use { data ->
            data.writeInt(1); data.writeInt(updated.size)
            PrototypeService.entries.filter { it in updated }.forEach { key ->
                val choice = checkNotNull(updated[key]?.route)
                data.writeUTF(key.name); data.writeUTF(choice.mode.name)
                if (choice.mode == SourceRouteMode.SAVED_CONNECTION) {
                    data.writeUTF(checkNotNull(choice.connectionId)); data.writeUTF(checkNotNull(choice.connectionName))
                }
            }
        } }.toByteArray()
        check(bytes.size <= PRIVATE_SECRET_LIMIT)
        // Validate the legacy view before committing; return committed data without another read.
        val result = legacyProviderSetups(legacy) + updated
        store.write(bytes)
        result
    }
}
