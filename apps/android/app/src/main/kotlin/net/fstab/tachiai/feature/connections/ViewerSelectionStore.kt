package net.fstab.tachiai.feature.connections

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import net.fstab.tachiai.platform.storage.AndroidPrivateSecretStore
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.platform.storage.PrivateAuthorizationSlot
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.ConfiguredFeedAssignments
import net.fstab.tachiai.presentation.ConfiguredFeedChoice

internal enum class ViewerSelectionStoreFailure { STORAGE, INVALID_RECORD }
internal class ViewerSelectionStoreException(val failure: ViewerSelectionStoreFailure) : Exception(failure.name)

internal fun viewerSelectionStore(context: Context) = ViewerSelectionStore(
    AndroidPrivateSecretStore(context.applicationContext, PrivateAuthorizationSlot.VIEWER_SELECTION),
    File(context.applicationContext.noBackupFilesDir, "viewer-selection.lock"),
)

// Worker-only. This independent encrypted record contains only local choice IDs,
// preserving empty/partial assignments and duplicate feeds exactly as selected.
internal class ViewerSelectionStore(private val store: PrivateSecretStore, private val lockFile: File? = null) {
    private companion object {
        val lock = Any()
        const val MAGIC = 0x5456534c // TVSL
        const val VERSION = 1
    }

    private fun <T> locked(action: () -> T): T = try {
        synchronized(lock) {
            val file = lockFile
            if (file == null) action() else RandomAccessFile(file, "rw").use { handle ->
                handle.channel.lock().use { action() }
            }
        }
    } catch (error: ViewerSelectionStoreException) { throw error }
    catch (_: Exception) { throw ViewerSelectionStoreException(ViewerSelectionStoreFailure.STORAGE) }

    private fun readUnlocked(): ConfiguredFeedAssignments? {
        val bytes = try { store.read() }
            catch (_: Exception) { throw ViewerSelectionStoreException(ViewerSelectionStoreFailure.STORAGE) }
            ?: return null
        try {
            check(bytes.size in 10..PRIVATE_SECRET_LIMIT)
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                check(input.readInt() == MAGIC && input.readInt() == VERSION)
                fun id(): String = String(ByteArray(36).also(input::readFully), Charsets.US_ASCII)
                fun choice(): ConfiguredFeedChoice? = when (input.readUnsignedByte()) {
                    0 -> null
                    1 -> ConfiguredFeedChoice(id(), id())
                    else -> error("Invalid choice flag")
                }
                ConfiguredFeedAssignments(choice(), choice()).also { check(input.read() == -1) }
            }
        } catch (_: Exception) { throw ViewerSelectionStoreException(ViewerSelectionStoreFailure.INVALID_RECORD) }
    }

    fun read(): ConfiguredFeedAssignments? = locked { readUnlocked() }

    fun save(assignments: ConfiguredFeedAssignments): Unit = locked {
        // A corrupt or unreadable record must be surfaced, never silently reset.
        readUnlocked()
        val bytes = ByteArrayOutputStream().also { output -> DataOutputStream(output).use { data ->
            data.writeInt(MAGIC); data.writeInt(VERSION)
            for (choice in listOf(assignments.a, assignments.b)) {
                data.writeByte(if (choice == null) 0 else 1)
                if (choice != null) {
                    data.write(choice.itemId.toByteArray(Charsets.US_ASCII))
                    data.write(choice.instanceId.toByteArray(Charsets.US_ASCII))
                }
            }
        } }.toByteArray()
        check(bytes.size <= PRIVATE_SECRET_LIMIT)
        try { store.write(bytes) }
        catch (_: Exception) { throw ViewerSelectionStoreException(ViewerSelectionStoreFailure.STORAGE) }
    }
}
