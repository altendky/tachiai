package net.fstab.tachiai.feature.connections

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Collections
import net.fstab.tachiai.platform.storage.AndroidPrivateSecretStore
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.platform.storage.PrivateAuthorizationSlot
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.platform.storage.processFileLocked
import net.fstab.tachiai.presentation.MAX_REPLAY_POSITION_MS
import net.fstab.tachiai.presentation.MAX_REPLAY_POSITION_RECORDS
import net.fstab.tachiai.presentation.validReplayPositionKey

private const val REPLAY_POSITION_KEY_BYTES = 32

internal fun replayPositionStore(context: Context) = ReplayPositionStore(
    AndroidPrivateSecretStore(context.applicationContext, PrivateAuthorizationSlot.REPLAY_POSITIONS),
    File(context.applicationContext.noBackupFilesDir, "replay-positions.lock"),
)

// Worker-only, encrypted, app-private record of remembered replay positions
// (#655 part 2): hashed item keys and positions, most recently saved first, at
// most MAX_REPLAY_POSITION_RECORDS. Playback reads and writes from separate
// processes, so the file lock covers each whole read-modify-write. These are
// conveniences, not settings: an unreadable or invalid record reads as empty
// and the next save replaces it rather than blocking playback.
internal class ReplayPositionStore(private val store: PrivateSecretStore, private val lockFile: File? = null) {
    companion object { private val lock = Any() }
    private fun <T> locked(action: () -> T): T = synchronized(lock) {
        val file = lockFile
        if (file == null) action() else processFileLocked(file, action)
    }

    private fun readUnlocked(): Map<String, Long> {
        val bytes = try { store.read() } catch (_: Exception) { null } ?: return emptyMap()
        return try {
            check(bytes.size in 8..PRIVATE_SECRET_LIMIT)
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                check(input.readInt() == 1)
                val count = input.readInt(); check(count in 0..MAX_REPLAY_POSITION_RECORDS)
                val result = linkedMapOf<String, Long>()
                repeat(count) {
                    val key = ByteArray(REPLAY_POSITION_KEY_BYTES).also(input::readFully)
                        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
                    val position = input.readLong()
                    check(key !in result && position in 0..MAX_REPLAY_POSITION_MS)
                    result[key] = position
                }
                check(input.read() == -1)
                result
            }
        } catch (_: Exception) { emptyMap() }
    }

    private fun writeUnlocked(entries: Map<String, Long>) {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(1); data.writeInt(entries.size)
                entries.forEach { (key, position) ->
                    data.write(ByteArray(REPLAY_POSITION_KEY_BYTES) { index ->
                        key.substring(index * 2, index * 2 + 2).toInt(16).toByte()
                    })
                    data.writeLong(position)
                }
            }
        }.toByteArray()
        check(bytes.size <= PRIVATE_SECRET_LIMIT)
        store.write(bytes)
    }

    // Most recently saved first.
    fun read(): Map<String, Long> = locked { Collections.unmodifiableMap(readUnlocked()) }

    // The given items become the most recent; the least recent beyond the cap are dropped.
    fun record(positions: Map<String, Long>): Map<String, Long> {
        require(positions.all { (key, position) -> validReplayPositionKey(key) && position in 0..MAX_REPLAY_POSITION_MS })
        return locked {
            val previous = readUnlocked()
            if (positions.isEmpty()) return@locked Collections.unmodifiableMap(previous)
            val updated = LinkedHashMap(positions)
            previous.forEach { (key, position) -> if (key !in updated && updated.size < MAX_REPLAY_POSITION_RECORDS) updated[key] = position }
            writeUnlocked(updated)
            Collections.unmodifiableMap(updated)
        }
    }

    fun forget(keys: Set<String>): Map<String, Long> {
        require(keys.all(::validReplayPositionKey))
        return locked {
            val previous = readUnlocked()
            if (keys.none(previous::containsKey)) return@locked Collections.unmodifiableMap(previous)
            val updated = LinkedHashMap(previous).apply { keys.forEach(::remove) }
            writeUnlocked(updated)
            Collections.unmodifiableMap(updated)
        }
    }
}
