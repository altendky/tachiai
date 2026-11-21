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
import net.fstab.tachiai.presentation.ConfiguredEpisodeChoice
import net.fstab.tachiai.presentation.ConfiguredFeedAssignments
import net.fstab.tachiai.presentation.ConfiguredFeedChoice
import net.fstab.tachiai.presentation.configuredChoice
import net.fstab.tachiai.presentation.decodeConfiguredFeedChoice
import net.fstab.tachiai.presentation.decodePrototypeFeedChoice
import net.fstab.tachiai.presentation.encodeConfiguredFeedChoice
import net.fstab.tachiai.presentation.encodePrototypeFeedChoice

// Foundation for the configured picker: local item/instance identities and, for
// a collection item, its chosen episode's public identity and title (#550) are
// checkpointed. No account state, quality or other provider metadata is saved.
internal class ConfiguredRecoveryCheckpoint(private val directory: File, private val cached: Boolean) {
    companion object {
        const val MAX_BYTES = 4096
        private const val HEADER = "tachiai-configured-recovery-v3"
        private const val V2_HEADER = "tachiai-configured-recovery-v2"
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
        fun row(slot: String, choice: ConfiguredFeedChoice?, episode: ConfiguredEpisodeChoice?): String =
            encodeConfiguredFeedChoice(choice)?.let { "$slot\tCHOICE\t$it" + (episode?.let { "\tEPISODE\t${it.encode()}" } ?: "") }
                ?: "$slot\tNONE"
        // Without an episode the previous v2 form is written unchanged.
        val header = if (assignments.aEpisode != null || assignments.bEpisode != null) HEADER else V2_HEADER
        val text = listOf(header, "PRESENT\t${if (cached) "CACHED" else "HISTORICAL"}",
            row("A", assignments.a, assignments.aEpisode), row("B", assignments.b, assignments.bEpisode)).joinToString("\n", postfix = "\n")
        val bytes = text.toByteArray(Charsets.US_ASCII)
        check(bytes.size <= MAX_BYTES && decode(bytes) == assignments)
        return bytes
    }

    private fun decode(bytes: ByteArray): ConfiguredFeedAssignments {
        check(bytes.size <= MAX_BYTES && bytes.all { it.toInt() in 32..126 || it.toInt() in listOf(9, 10) })
        val lines = bytes.toString(Charsets.US_ASCII).split('\n')
        check(lines.size == 5 && lines[0] in listOf(HEADER, V2_HEADER, LEGACY_HEADER) &&
            lines[1] == "PRESENT\t${if (cached) "CACHED" else "HISTORICAL"}" && lines[4].isEmpty())
        fun choice(row: String, slot: String): Pair<ConfiguredFeedChoice?, ConfiguredEpisodeChoice?> {
            val fields = row.split('\t')
            check(fields.first() == slot)
            if (fields == listOf(slot, "NONE")) return null to null
            check(fields.size == 3 || lines[0] == HEADER && fields.size == 5 && fields[3] == "EPISODE")
            check(fields[1] == "CHOICE")
            val episode = if (fields.size == 5) checkNotNull(ConfiguredEpisodeChoice.decode(fields[4])) else null
            return if (lines[0] == LEGACY_HEADER) {
                val value = checkNotNull(decodePrototypeFeedChoice(fields[2]))
                check(encodePrototypeFeedChoice(value) == fields[2])
                configuredChoice(value) to null
            } else {
                check(fields[2].startsWith("v2|"))
                val value = checkNotNull(decodeConfiguredFeedChoice(fields[2]))
                check(encodeConfiguredFeedChoice(value) == fields[2])
                value to episode
            }
        }
        val (a, aEpisode) = choice(lines[2], "A"); val (b, bEpisode) = choice(lines[3], "B")
        // Canonical: v3 is written only when an episode is present.
        check(lines[0] != HEADER || aEpisode != null || bEpisode != null)
        return ConfiguredFeedAssignments(a, b, aEpisode, bEpisode)
    }
}
