package net.fstab.tachiai.platform.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.io.File
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.platform.storage.processFileLocked
import net.fstab.tachiai.presentation.MAX_PROVIDER_ROUTES

// publicKey: a non-secret key to match against the provider's list (WireGuard), or null.
internal data class ConnectionSummary(val id: String, val name: String, val kind: ConnectionKind, val endpoint: String,
    val publicKey: String? = null)

// Default-process mutations and playback-process snapshots share the same fixed file lock.
internal class ConnectionProfileStore(private val secrets: PrivateSecretStore, private val lockFile: File? = null,
    private val protocols: RouteProtocols = routeProtocols, private val mutated: () -> Unit = {}) {
    companion object { private val lock = Any() } // Also serializes stores across Activity recreation.
    private class Entry(val id: String, val name: String, val profile: ConnectionProfile)
    private fun <T> locked(action: () -> T): T = synchronized(lock) {
        val file = lockFile
        if (file == null) action() else processFileLocked(file, action)
    }
    private fun read(): List<Entry> {
        val bytes = secrets.read() ?: return emptyList()
        check(bytes.size <= PRIVATE_SECRET_LIMIT)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version = input.readInt()
            check(version in 1..2)
            val count = input.readInt()
            check(count in 0..8)
            val entries = (0 until count).map {
                val id = input.readUTF()
                check(UUID.fromString(id).toString() == id)
                val name = input.readUTF()
                check(validConnectionName(name))
                // v1 inferred its format from text. Reads preserve the exact
                // encrypted record; only an explicit mutation writes v2.
                val protocolId = if (version == 2) input.readUTF() else null
                val configuration = input.readUTF().toByteArray(Charsets.UTF_8)
                val profile = if (protocolId == null) protocols.parse(configuration) else protocols.restore(protocolId, configuration)
                Entry(id, name, profile)
            }
            check(entries.map { it.id }.distinct().size == count && input.read() == -1)
            return entries
        }
    }

    private fun write(entries: List<Entry>) {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(2); data.writeInt(entries.size)
                entries.forEach {
                    data.writeUTF(it.id); data.writeUTF(it.name)
                    data.writeUTF(it.profile.kind.id); data.writeUTF(it.profile.configuration)
                }
            }
        }.toByteArray()
        check(bytes.size <= PRIVATE_SECRET_LIMIT) // No partial save, plaintext fallback or silent eviction.
        secrets.write(bytes)
    }

    fun summaries(): List<ConnectionSummary> = locked {
        summaries(read())
    }

    private fun summaries(entries: List<Entry>) = entries.map { entry ->
        ConnectionSummary(entry.id, entry.name, entry.profile.kind, entry.profile.endpoint,
            protocols.protocols.firstOrNull { it.kind == entry.profile.kind }?.publicKey(entry.profile))
    }

    // Worker-only secret snapshot; never sent through intents, UI state or diagnostics.
    fun selected(id: String): ConnectionProfile = locked { checkNotNull(read().find { it.id == id }).profile }

    // Route-owner lookup: a lease client names a profile only by its route
    // configuration digest, so configuration never crosses process boundaries.
    fun configured(configurationKey: String): ConnectionProfile? = locked {
        read().map { it.profile }.firstOrNull { routeConfigurationKey(it) == configurationKey }
    }

    // One protected read captures all requested profiles. An absent ID is a
    // missing candidate; an unreadable/corrupt record fails the whole snapshot.
    fun snapshot(ids: Set<String>): Map<String, ConnectionProfile> {
        require(ids.size <= MAX_PROVIDER_ROUTES && ids.all { UUID.fromString(it).toString() == it })
        if (ids.isEmpty()) return emptyMap()
        return locked { read().filter { it.id in ids }.associate { it.id to it.profile } }
    }

    fun add(name: String, profile: ConnectionProfile) = locked {
        check(validConnectionName(name))
        val validated = protocols.validate(profile)
        val entries = read()
        check(entries.size < 8)
        val updated = entries + Entry(UUID.randomUUID().toString(), name, validated)
        write(updated)
        summaries(updated)
    }.also { runCatching(mutated) }

    fun remove(id: String) = locked {
        val entries = read()
        check(entries.any { it.id == id })
        val updated = entries.filterNot { it.id == id }
        write(updated)
        summaries(updated)
    }.also { runCatching(mutated) }
}
