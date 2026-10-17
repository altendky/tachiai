package net.fstab.tachiai.feature.connections

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.presentation.PrototypeSource
import net.fstab.tachiai.presentation.SourceSetup
import net.fstab.tachiai.presentation.SourceRouteChoice
import net.fstab.tachiai.presentation.SourceRouteMode
import net.fstab.tachiai.presentation.defaultSourceSetups

internal class ObsoleteSourceSetupException : IllegalStateException("Stream settings need an explicit reset")

// Playback reads in separate processes. Hold both locks across the entire AtomicFile operation.
internal class SourceSetupStore(private val store: PrivateSecretStore, private val lockFile: File? = null) {
    companion object {
        private val lock = Any()
        private val originalSources = setOf(
            PrototypeSource.ABEMA_LIVE, PrototypeSource.ABEMA_REPLAY,
            PrototypeSource.TWITCH_LIVE, PrototypeSource.TWITCH_REPLAY,
        )
    }
    private fun <T> locked(action: () -> T): T = synchronized(lock) {
        val file = lockFile
        if (file == null) action() else RandomAccessFile(file, "rw").use { handle ->
            handle.channel.lock().use { action() }
        }
    }

    fun read(): Map<PrototypeSource, SourceSetup> = locked { readUnlocked() }

    private fun readUnlocked(): Map<PrototypeSource, SourceSetup> {
        val bytes = store.read() ?: return defaultSourceSetups()
        check(bytes.size <= PRIVATE_SECRET_LIMIT)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            check(input.readInt() == 1)
            val count = input.readInt()
            check(count == originalSources.size || count == PrototypeSource.entries.size)
            fun choice(): SourceRouteChoice {
                val mode = SourceRouteMode.valueOf(input.readUTF())
                return if (mode == SourceRouteMode.SAVED_CONNECTION) SourceRouteChoice(mode, input.readUTF(), input.readUTF())
                    else SourceRouteChoice(mode)
            }
            val entries = List(count) {
                PrototypeSource.valueOf(input.readUTF()) to SourceSetup(input.readUTF(), choice(), choice(), choice())
            }
            val identities = entries.map { it.first }.toSet()
            check(identities.size == count && input.read() == -1)
            if (count != PrototypeSource.entries.size) {
                check(identities == originalSources)
                // Recognize valid obsolete metadata, but do not migrate it or invent route choices.
                throw ObsoleteSourceSetupException()
            }
            check(identities == PrototypeSource.entries.toSet())
            entries.toMap()
        }
    }

    fun resetObsolete(): Unit = locked {
        try {
            readUnlocked()
            error("Only obsolete stream settings can be reset")
        } catch (_: ObsoleteSourceSetupException) {
            writeUnlocked(defaultSourceSetups())
        }
    }

    fun save(source: PrototypeSource, setup: SourceSetup): Map<PrototypeSource, SourceSetup> = locked {
        val entries = readUnlocked() + (source to setup)
        writeUnlocked(entries)
        entries
    }

    private fun writeUnlocked(entries: Map<PrototypeSource, SourceSetup>) {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(1); data.writeInt(entries.size)
                fun choice(value: SourceRouteChoice) {
                    data.writeUTF(value.mode.name)
                    if (value.mode == SourceRouteMode.SAVED_CONNECTION) {
                        data.writeUTF(checkNotNull(value.connectionId)); data.writeUTF(checkNotNull(value.connectionName))
                    }
                }
                PrototypeSource.entries.forEach { key ->
                    val value = checkNotNull(entries[key])
                    data.writeUTF(key.name); data.writeUTF(value.name)
                    choice(value.defaultRoute); choice(value.feedA); choice(value.feedB)
                }
            }
        }.toByteArray()
        check(bytes.size <= PRIVATE_SECRET_LIMIT)
        store.write(bytes)
    }
}
