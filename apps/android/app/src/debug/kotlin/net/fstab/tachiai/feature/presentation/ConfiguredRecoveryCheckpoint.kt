package net.fstab.tachiai.feature.presentation

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import net.fstab.tachiai.presentation.ConfiguredFeedAssignments
import net.fstab.tachiai.presentation.ConfiguredFeedChoice
import net.fstab.tachiai.presentation.configuredChoice
import net.fstab.tachiai.presentation.decodeConfiguredFeedChoice
import net.fstab.tachiai.presentation.decodePrototypeFeedChoice
import net.fstab.tachiai.presentation.encodeConfiguredFeedChoice
import net.fstab.tachiai.presentation.encodePrototypeFeedChoice

// Foundation for the configured picker: only local item/instance identities are
// checkpointed. No provider resource, metadata, account state or quality is saved.
internal class ConfiguredRecoveryCheckpoint(private val directory: File, private val cached: Boolean) {
    companion object {
        const val MAX_BYTES = 512
        private const val HEADER = "tachiai-configured-recovery-v2"
        private const val LEGACY_HEADER = "tachiai-prototype-recovery-v1"
        private val lock = Any()
        fun fileName(cached: Boolean): String = PrototypeRecoveryCheckpoint.fileName(cached)
    }

    sealed interface Result {
        data object Absent : Result
        data class Restored(val assignments: ConfiguredFeedAssignments) : Result
        data object Invalid : Result
    }

    private val file get() = File(directory, fileName(cached))

    fun write(assignments: ConfiguredFeedAssignments) = synchronized(lock) {
        try {
            checkSafe()
            val bytes = encode(assignments)
            val temporary = File.createTempFile(".configured-recovery-", ".tmp", directory)
            try {
                FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
                checkSafe()
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { check(!temporary.exists() || temporary.delete()) }
            Unit
        } catch (_: Exception) {
            throw IOException("Picker recovery checkpoint could not be written")
        }
    }

    fun consume(): Result = synchronized(lock) {
        try {
            checkSafe()
            if (!file.exists()) return@synchronized Result.Absent
            check(file.isFile && file.length() <= MAX_BYTES)
            val bytes = Files.newByteChannel(file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val input = Channels.newInputStream(channel)
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
            // Invalid or newer drafts remain available for explicit recovery.
            Result.Invalid
        }
    }

    private fun checkSafe() {
        check(directory.isDirectory && directory.canRead() && directory.canExecute() &&
            !Files.isSymbolicLink(directory.toPath()) && !Files.isSymbolicLink(file.toPath()))
        check(!file.exists() || file.isFile)
    }

    private fun encode(assignments: ConfiguredFeedAssignments): ByteArray {
        fun row(slot: String, choice: ConfiguredFeedChoice?): String =
            encodeConfiguredFeedChoice(choice)?.let { "$slot\tCHOICE\t$it" } ?: "$slot\tNONE"
        val text = listOf(HEADER, "PRESENT\t${if (cached) "CACHED" else "HISTORICAL"}",
            row("A", assignments.a), row("B", assignments.b)).joinToString("\n", postfix = "\n")
        val bytes = text.toByteArray(Charsets.US_ASCII)
        check(bytes.size <= MAX_BYTES && decode(bytes) == assignments)
        return bytes
    }

    private fun decode(bytes: ByteArray): ConfiguredFeedAssignments {
        check(bytes.size <= MAX_BYTES && bytes.all { it.toInt() in 32..126 || it.toInt() in listOf(9, 10) })
        val lines = bytes.toString(Charsets.US_ASCII).split('\n')
        check(lines.size == 5 && lines[0] in listOf(HEADER, LEGACY_HEADER) &&
            lines[1] == "PRESENT\t${if (cached) "CACHED" else "HISTORICAL"}" && lines[4].isEmpty())
        fun choice(row: String, slot: String): ConfiguredFeedChoice? {
            val fields = row.split('\t')
            check(fields.first() == slot)
            if (fields == listOf(slot, "NONE")) return null
            check(fields.size == 3 && fields[1] == "CHOICE")
            return if (lines[0] == LEGACY_HEADER) {
                val value = checkNotNull(decodePrototypeFeedChoice(fields[2]))
                check(encodePrototypeFeedChoice(value) == fields[2])
                configuredChoice(value)
            } else {
                check(fields[2].startsWith("v2|"))
                val value = checkNotNull(decodeConfiguredFeedChoice(fields[2]))
                check(encodeConfiguredFeedChoice(value) == fields[2])
                value
            }
        }
        return ConfiguredFeedAssignments(choice(lines[2], "A"), choice(lines[3], "B"))
    }
}
