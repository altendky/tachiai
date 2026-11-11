package net.fstab.tachiai.platform.diagnostics

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Collections
import net.fstab.tachiai.platform.media.NativeFailureStage
import net.fstab.tachiai.platform.media.NativePlaybackLivenessState
import net.fstab.tachiai.platform.network.ProviderTransportEvidence
import net.fstab.tachiai.platform.network.ProviderTransportExceptionKind
import net.fstab.tachiai.platform.network.ProviderTransportFailure
import net.fstab.tachiai.platform.network.ProviderTransportOperation
import net.fstab.tachiai.platform.network.ProviderTransportPhase

internal enum class PlaybackHealthSlot { A, B }
internal enum class PlaybackHealthReason { SAMPLE_STATE_CHANGED, FEED_FAILURE, RETRY_REQUESTED, FEED_PREPARED, RETRY_REJOIN }
internal enum class PlaybackHealthIntent {
    STARTUP, USER_PLAY, USER_PAUSE, FOCUS_LOSS, TIMING, BACK_TO_LIVE, OFFSET, RETRY_REJOIN,
    PLAY_REFUSED, OFFSET_UNCONFIRMED, BACKGROUND, STOP,
}
internal enum class PlaybackHealthJournalFailure { INVALID_RECORD, STORAGE }
internal class PlaybackHealthJournalException(val failure: PlaybackHealthJournalFailure) : Exception(failure.name)

// Window-relative positions may legitimately be negative. Ages and decoder
// counters are scalar observations, never provider/native clock timestamps.
internal data class PlaybackHealthPlayer(
    val sampleAgeMs: Long,
    val released: Boolean,
    val playWhenReady: Boolean,
    val playing: Boolean,
    val state: Int,
    val suppressionReason: Int,
    val errorCode: Int? = null,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val bufferedMs: Long? = null,
    val clockProgressAgeMs: Long? = null,
    val renderProgressAgeMs: Long? = null,
    val renderedFrames: Long? = null,
    val droppedFrames: Long? = null,
    val decoderGeneration: Long? = null,
) {
    init {
        require(sampleAgeMs >= 0 && state in 0..4 && suppressionReason in -1..4)
        require(errorCode == null || errorCode >= 0)
        require(durationMs == null || durationMs >= 0)
        require(listOf(clockProgressAgeMs, renderProgressAgeMs, renderedFrames, droppedFrames, decoderGeneration)
            .all { it == null || it >= 0 })
    }
}

internal data class PlaybackHealthTransport(val ageMs: Long, val evidence: ProviderTransportEvidence,
    val stage: NativeFailureStage? = null) {
    init { require(ageMs >= 0) }
}

// Closed immutable groups only: no URI, host, provider/account identity,
// exception, format metadata, absolute content clock or freeform text field.
internal data class PlaybackHealthRecord(
    val timestampMs: Long,
    val slot: PlaybackHealthSlot,
    val reason: PlaybackHealthReason,
    val health: NativePlaybackLivenessState,
    val intent: PlaybackHealthIntent,
    val intentRevision: Long,
    val appDesiredPlaying: Boolean,
    val prepared: Boolean,
    val retryRequested: Boolean,
    val retrying: Boolean,
    val timingBusy: Boolean,
    val offsetBusy: Boolean,
    val barrierPending: Boolean,
    val player: PlaybackHealthPlayer? = null,
    val transport: PlaybackHealthTransport? = null,
) {
    init { require(timestampMs >= 0 && intentRevision >= 0) }
}

internal object PlaybackHealthJournalCodec {
    const val HEADER = "tachiai-playback-health-v1"
    const val MAX_RECORDS = 24
    const val MAX_BYTES = 32 * 1024
    private val decimal = Regex("0|-?[1-9][0-9]{0,18}")
    private fun number(value: String): Long { check(decimal.matches(value)); return value.toLong() }
    private fun optional(value: String) = if (value == "-") null else number(value)
    private fun integer(value: String): Int = number(value).also { check(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) }.toInt()
    private fun boolean(value: String): Boolean = when (value) { "0" -> false; "1" -> true; else -> error("Invalid boolean") }
    private fun field(value: Any?): String = when (value) { null -> "-"; true -> "1"; false -> "0"; else -> value.toString() }

    fun encode(records: List<PlaybackHealthRecord>): ByteArray {
        require(records.size <= MAX_RECORDS)
        val bytes = buildString {
            append(HEADER).append('\n')
            records.forEach { record ->
                val fields = mutableListOf(record.timestampMs.toString(), record.slot.name, record.reason.name,
                    record.health.name, record.intent.name, record.intentRevision.toString(),
                    field(record.appDesiredPlaying), field(record.prepared), field(record.retryRequested),
                    field(record.retrying), field(record.timingBusy), field(record.offsetBusy), field(record.barrierPending),
                    field(record.player != null))
                record.player?.let { value -> fields += listOf(value.sampleAgeMs.toString(), field(value.released),
                    field(value.playWhenReady), field(value.playing), value.state.toString(), value.suppressionReason.toString(),
                    field(value.errorCode), field(value.positionMs), field(value.durationMs), field(value.bufferedMs),
                    field(value.clockProgressAgeMs), field(value.renderProgressAgeMs), field(value.renderedFrames),
                    field(value.droppedFrames), field(value.decoderGeneration)) }
                fields += field(record.transport != null)
                record.transport?.let { value -> fields += listOf(value.ageMs.toString(), field(value.stage),
                    value.evidence.failure.name, value.evidence.phase.name, field(value.evidence.localCancellationRequested),
                    value.evidence.exceptionKind.name, value.evidence.operation.name, field(value.evidence.phaseAvailable),
                    field(value.evidence.proxyPresent)) }
                append(fields.joinToString("\t")).append('\n')
            }
        }.toByteArray(Charsets.US_ASCII)
        check(bytes.size <= MAX_BYTES && decode(bytes) == records)
        return bytes
    }

    fun decode(bytes: ByteArray): List<PlaybackHealthRecord> = try {
        check(bytes.size <= MAX_BYTES && bytes.all { it.toInt() == 9 || it.toInt() == 10 || it.toInt() in 32..126 })
        val lines = bytes.toString(Charsets.US_ASCII).split('\n')
        check(lines.size in 2..MAX_RECORDS + 2 && lines.first() == HEADER && lines.last() == "")
        val records = lines.drop(1).dropLast(1).map { line ->
            val fields = line.split('\t')
            check(fields.size >= 15)
            var at = 0
            fun next(): String = fields[at++]
            val timestamp = number(next())
            val slot = PlaybackHealthSlot.valueOf(next())
            val reason = PlaybackHealthReason.valueOf(next())
            val health = NativePlaybackLivenessState.valueOf(next())
            val intent = PlaybackHealthIntent.valueOf(next())
            val revision = number(next())
            val desired = boolean(next()); val prepared = boolean(next()); val requested = boolean(next())
            val retrying = boolean(next()); val timing = boolean(next()); val offset = boolean(next()); val barrier = boolean(next())
            val player = if (!boolean(next())) null else {
                val age = number(next()); val released = boolean(next()); val pwr = boolean(next()); val playing = boolean(next())
                val state = integer(next()); val suppression = integer(next())
                val error = next().let { if (it == "-") null else integer(it) }
                PlaybackHealthPlayer(age, released, pwr, playing, state, suppression, error,
                    optional(next()), optional(next()), optional(next()), optional(next()), optional(next()),
                    optional(next()), optional(next()), optional(next()))
            }
            val transport = if (!boolean(next())) null else {
                val age = number(next()); val stage = next().let { if (it == "-") null else NativeFailureStage.valueOf(it) }
                PlaybackHealthTransport(age, ProviderTransportEvidence(ProviderTransportFailure.valueOf(next()),
                    ProviderTransportPhase.valueOf(next()), boolean(next()), ProviderTransportExceptionKind.valueOf(next()),
                    ProviderTransportOperation.valueOf(next()), boolean(next()), boolean(next())), stage)
            }
            check(at == fields.size)
            PlaybackHealthRecord(timestamp, slot, reason, health, intent, revision, desired, prepared, requested,
                retrying, timing, offset, barrier, player, transport)
        }
        Collections.unmodifiableList(records)
    } catch (_: Exception) { throw PlaybackHealthJournalException(PlaybackHealthJournalFailure.INVALID_RECORD) }
}

// Worker-only bounded append/read. A stable sidecar owns the entire read/trim/
// atomic-replace transaction. Corrupt or unsafe paths are never reset.
internal class PlaybackHealthJournal(private val file: File,
    private val replace: (File, File) -> Unit = { from, to ->
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    },
) {
    companion object { private val lock = Any() }
    fun append(value: PlaybackHealthRecord) = locked {
        val bytes = PlaybackHealthJournalCodec.encode((readUnlocked() + value).takeLast(PlaybackHealthJournalCodec.MAX_RECORDS))
        val temporary = File.createTempFile(".playback-health-", ".tmp", checkNotNull(file.parentFile))
        try {
            FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            checkSafe(file)
            replace(temporary, file)
        } finally { check(!temporary.exists() || temporary.delete()) }
    }

    fun read(): List<PlaybackHealthRecord> = locked { readUnlocked() }
    private fun readUnlocked(): List<PlaybackHealthRecord> {
        checkSafe(file)
        if (!file.exists()) return emptyList()
        check(file.isFile)
        if (file.length() > PlaybackHealthJournalCodec.MAX_BYTES)
            throw PlaybackHealthJournalException(PlaybackHealthJournalFailure.INVALID_RECORD)
        return file.inputStream().use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (bytes.size() + count > PlaybackHealthJournalCodec.MAX_BYTES)
                    throw PlaybackHealthJournalException(PlaybackHealthJournalFailure.INVALID_RECORD)
                bytes.write(buffer, 0, count)
            }
            PlaybackHealthJournalCodec.decode(bytes.toByteArray())
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
    } catch (error: PlaybackHealthJournalException) { throw error }
    catch (_: Exception) { throw PlaybackHealthJournalException(PlaybackHealthJournalFailure.STORAGE) }
}
