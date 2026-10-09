package net.fstab.tachiai.feature.presentation

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import net.fstab.tachiai.presentation.PrototypeFeedAssignments
import net.fstab.tachiai.presentation.PrototypeFeedChoice
import net.fstab.tachiai.presentation.decodePrototypeFeedChoice
import net.fstab.tachiai.presentation.encodePrototypeFeedChoice

// Each exact prototype process owns its own app-private no-backup file. It
// contains catalogue identities and local UUIDs only, never provider resources.
internal class PrototypeRecoveryCheckpoint(private val directory: File, private val cached: Boolean) {
    companion object {
        const val MAX_BYTES = 512
        private const val HEADER = "tachiai-prototype-recovery-v1"
        private val lock = Any()
        fun fileName(cached: Boolean): String = if (cached) "prototype-cached-recovery.tsv" else "prototype-player-recovery.tsv"
    }
    sealed interface Result {
        data object Absent : Result
        data class Restored(val assignments: PrototypeFeedAssignments) : Result
        data object Invalid : Result
    }
    private val file get() = File(directory, fileName(cached))

    fun write(assignments: PrototypeFeedAssignments) = synchronized(lock) {
        try {
            checkSafe()
            val bytes = encode(assignments)
            val temporary = File.createTempFile(".prototype-recovery-", ".tmp", directory)
            try {
                FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
                checkSafe()
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { check(!temporary.exists() || temporary.delete()) }
            Unit
        } catch (_: Exception) {
            // Raw filesystem/decode exception text never crosses this boundary.
            throw IOException("Picker recovery checkpoint could not be written")
        }
    }

    fun consume(): Result = synchronized(lock) {
        try {
            checkSafe()
            if (!file.exists()) return@synchronized Result.Absent
            check(file.isFile && file.length() <= MAX_BYTES)
            val bytes = file.inputStream().use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(256)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= MAX_BYTES)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val assignments = decode(bytes)
            checkSafe()
            Files.delete(file.toPath())
            Result.Restored(assignments)
        } catch (_: Exception) {
            // Preserve malformed/unreadable data until an explicit restart
            // writes canonical choices. A second fresh startup cannot silently
            // replace an invalid draft with default provider identities.
            Result.Invalid
        }
    }

    private fun checkSafe() {
        check(directory.isDirectory && directory.canRead() && directory.canExecute() &&
            !Files.isSymbolicLink(directory.toPath()) && !Files.isSymbolicLink(file.toPath()))
        check(!file.exists() || file.isFile)
    }

    private fun encode(assignments: PrototypeFeedAssignments): ByteArray {
        fun row(slot: String, choice: PrototypeFeedChoice?): String =
            encodePrototypeFeedChoice(choice)?.let { "$slot\tCHOICE\t$it" } ?: "$slot\tNONE"
        val text = listOf(HEADER, "PRESENT\t${if (cached) "CACHED" else "HISTORICAL"}",
            row("A", assignments.a), row("B", assignments.b)).joinToString("\n", postfix = "\n")
        val bytes = text.toByteArray(Charsets.US_ASCII)
        check(bytes.size <= MAX_BYTES && decode(bytes) == assignments)
        return bytes
    }

    private fun decode(bytes: ByteArray): PrototypeFeedAssignments {
        check(bytes.size <= MAX_BYTES && bytes.all { it.toInt() in 32..126 || it.toInt() in listOf(9, 10) })
        val lines = bytes.toString(Charsets.US_ASCII).split('\n')
        check(lines.size == 5 && lines[0] == HEADER && lines[1] == "PRESENT\t${if (cached) "CACHED" else "HISTORICAL"}" && lines[4].isEmpty())
        fun choice(row: String, slot: String): PrototypeFeedChoice? {
            val fields = row.split('\t')
            check(fields.first() == slot)
            if (fields == listOf(slot, "NONE")) return null
            check(fields.size == 3 && fields[1] == "CHOICE")
            val value = checkNotNull(decodePrototypeFeedChoice(fields[2]))
            check(encodePrototypeFeedChoice(value) == fields[2])
            return value
        }
        return PrototypeFeedAssignments(choice(lines[2], "A"), choice(lines[3], "B"))
    }
}
