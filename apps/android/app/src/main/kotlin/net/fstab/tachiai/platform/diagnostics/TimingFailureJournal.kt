package net.fstab.tachiai.platform.diagnostics

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Collections

internal enum class TimingFailureKind { MARK_ALIGNMENT, RELATIVE, SAVED_OFFSET }
internal enum class TimingFailureOutcome { UNCONFIRMED }
internal enum class TimingFailureSide { A, B }
internal enum class TimingFailureJournalFailure { INVALID_RECORD, STORAGE }
internal class TimingFailureJournalException(val failure: TimingFailureJournalFailure) : Exception(failure.name)

// Numeric positions are relative to their own windows. No absolute source
// clock, provider identity, URI, account, exception text or freeform field exists.
internal data class TimingFailureTarget(
    val side: TimingFailureSide,
    val ownClockErrorMs: Long?,
    val elapsedMs: Long,
    val state: Int?,
    val positionMs: Long?,
    val durationMs: Long?,
    val bufferedMs: Long?,
    val playing: Boolean?,
    val playWhenReady: Boolean?,
    val playingAd: Boolean?,
) {
    init {
        require(elapsedMs in 0..60_000)
        require(state == null || state in 0..4)
        require(durationMs == null || durationMs >= 0)
    }
}

internal class TimingFailureEvidence(
    val kind: TimingFailureKind,
    val requestedDeltaMs: Long,
    targets: List<TimingFailureTarget>,
    val outcome: TimingFailureOutcome = TimingFailureOutcome.UNCONFIRMED,
) {
    val targets: List<TimingFailureTarget> = Collections.unmodifiableList(targets.toList())
    init { require(this.targets.size <= 2 && this.targets.map { it.side }.distinct().size == this.targets.size) }
    override fun equals(other: Any?) = other is TimingFailureEvidence && kind == other.kind &&
        requestedDeltaMs == other.requestedDeltaMs && targets == other.targets && outcome == other.outcome
    override fun hashCode() = listOf(kind, requestedDeltaMs, targets, outcome).hashCode()
    override fun toString() = "TimingFailureEvidence(kind=$kind, requestedDeltaMs=$requestedDeltaMs, targets=$targets, outcome=$outcome)"
}

internal object TimingFailureJournalCodec {
    const val HEADER = "tachiai-timing-failures-v1"
    const val MAX_RECORDS = 8
    const val MAX_BYTES = 16 * 1024
    private val decimal = Regex("0|-?[1-9][0-9]{0,18}")
    private fun number(value: String): Long { check(decimal.matches(value)); return value.toLong() }
    private fun optional(value: String) = if (value == "-") null else number(value)
    private fun boolean(value: String): Boolean? = when (value) { "-" -> null; "0" -> false; "1" -> true; else -> error("Invalid boolean") }
    private fun field(value: Any?): String = when (value) { null -> "-"; true -> "1"; false -> "0"; else -> value.toString() }

    fun encode(records: List<TimingFailureEvidence>): ByteArray {
        require(records.size <= MAX_RECORDS)
        val bytes = buildString {
            append(HEADER).append('\n')
            records.forEach { value ->
                val fields = mutableListOf(value.kind.name, value.outcome.name,
                    value.requestedDeltaMs.toString(), value.targets.size.toString())
                value.targets.forEach { target -> fields += listOf(target.side.name, field(target.ownClockErrorMs),
                    target.elapsedMs.toString(), field(target.state), field(target.positionMs), field(target.durationMs),
                    field(target.bufferedMs), field(target.playing), field(target.playWhenReady), field(target.playingAd)) }
                append(fields.joinToString("\t")).append('\n')
            }
        }.toByteArray(Charsets.US_ASCII)
        check(decode(bytes) == records)
        return bytes
    }

    fun decode(bytes: ByteArray): List<TimingFailureEvidence> = try {
        check(bytes.size <= MAX_BYTES && bytes.all { it.toInt() == 9 || it.toInt() == 10 || it.toInt() in 32..126 })
        val lines = bytes.toString(Charsets.US_ASCII).split('\n')
        check(lines.size in 2..MAX_RECORDS + 2 && lines.first() == HEADER && lines.last() == "")
        val records = lines.drop(1).dropLast(1).map { line ->
            val fields = line.split('\t')
            check(fields.size >= 4)
            val count = number(fields[3]); check(count in 0..2 && fields.size == 4 + count.toInt() * 10)
            val targets = (0 until count.toInt()).map { index ->
                val at = 4 + index * 10
                val state = optional(fields[at + 3]); check(state == null || state in 0..4)
                TimingFailureTarget(TimingFailureSide.valueOf(fields[at]), optional(fields[at + 1]),
                    number(fields[at + 2]), state?.toInt(), optional(fields[at + 4]), optional(fields[at + 5]),
                    optional(fields[at + 6]), boolean(fields[at + 7]), boolean(fields[at + 8]), boolean(fields[at + 9]))
            }
            TimingFailureEvidence(TimingFailureKind.valueOf(fields[0]), number(fields[2]), targets,
                TimingFailureOutcome.valueOf(fields[1]))
        }
        Collections.unmodifiableList(records)
    } catch (_: Exception) { throw TimingFailureJournalException(TimingFailureJournalFailure.INVALID_RECORD) }
}

// Worker-only append/read. A stable sidecar lock protects the bounded read/trim/
// replace transaction across instances and processes; corruption is never reset.
internal class TimingFailureJournal(
    private val file: File,
    private val replace: (File, File) -> Unit = { from, to ->
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    },
) {
    companion object { private val lock = Any() }
    fun append(value: TimingFailureEvidence) = locked {
        val records = (readUnlocked() + value).takeLast(TimingFailureJournalCodec.MAX_RECORDS)
        val bytes = TimingFailureJournalCodec.encode(records)
        val temporary = File.createTempFile(".timing-failure-", ".tmp", checkNotNull(file.parentFile))
        try {
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            checkSafe(file)
            replace(temporary, file)
        } finally { check(!temporary.exists() || temporary.delete()) }
    }

    fun read(): List<TimingFailureEvidence> = locked { readUnlocked() }
    private fun readUnlocked(): List<TimingFailureEvidence> {
        checkSafe(file)
        if (!file.exists()) return emptyList()
        check(file.isFile)
        if (file.length() > TimingFailureJournalCodec.MAX_BYTES)
            throw TimingFailureJournalException(TimingFailureJournalFailure.INVALID_RECORD)
        return file.inputStream().use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (bytes.size() + count > TimingFailureJournalCodec.MAX_BYTES)
                    throw TimingFailureJournalException(TimingFailureJournalFailure.INVALID_RECORD)
                bytes.write(buffer, 0, count)
            }
            TimingFailureJournalCodec.decode(bytes.toByteArray())
        }
    }
    private fun checkSafe(target: File) {
        val parent = checkNotNull(target.parentFile)
        check(parent.isDirectory && !Files.isSymbolicLink(parent.toPath()) && !Files.isSymbolicLink(target.toPath()))
    }
    private fun <T> locked(action: () -> T): T = try {
        synchronized(lock) {
            checkSafe(file)
            val sidecar = File(file.parentFile, "${file.name}.lock")
            checkSafe(sidecar)
            FileChannel.open(sidecar.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use { action() }
            }
        }
    } catch (error: TimingFailureJournalException) { throw error }
    catch (_: Exception) { throw TimingFailureJournalException(TimingFailureJournalFailure.STORAGE) }
}
