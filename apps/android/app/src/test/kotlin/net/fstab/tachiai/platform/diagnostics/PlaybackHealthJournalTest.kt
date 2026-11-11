package net.fstab.tachiai.platform.diagnostics

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import net.fstab.tachiai.platform.media.NativeFailureStage
import net.fstab.tachiai.platform.media.NativePlaybackLivenessState
import net.fstab.tachiai.platform.network.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackHealthJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun file() = File(temporary.newFolder(), "playback-health.tsv")
    private fun player() = PlaybackHealthPlayer(250, false, true, false, 3, -1, 2000,
        -20_000, 60_000, -2_000, 5_000, 7_000, 100, 2, 3)
    private fun transport() = PlaybackHealthTransport(500, ProviderTransportEvidence(ProviderTransportFailure.CANCELLED,
        ProviderTransportPhase.RESPONSE, true, ProviderTransportExceptionKind.INTERRUPTED_IO,
        ProviderTransportOperation.RESPONSE_BODY, true, true), NativeFailureStage.MEDIA_REQUEST_READ)
    private fun record(timestamp: Long = 1_000, player: PlaybackHealthPlayer? = player(),
        transport: PlaybackHealthTransport? = transport()) = PlaybackHealthRecord(timestamp, PlaybackHealthSlot.A,
        PlaybackHealthReason.SAMPLE_STATE_CHANGED, NativePlaybackLivenessState.HELD, PlaybackHealthIntent.TIMING,
        4, false, true, false, true, true, false, false, player, transport)

    @Test fun closedScalarGroupsRoundTripIncludingSignedWindowCoordinatesAndEmptyOptionals() {
        val records = listOf(record(), record(2_000, player = null), record(3_000, transport = null),
            record(4_000, player = null, transport = null), record(Long.MAX_VALUE,
                player().copy(sampleAgeMs = Long.MAX_VALUE, positionMs = Long.MIN_VALUE,
                    bufferedMs = Long.MAX_VALUE, errorCode = Int.MAX_VALUE, renderedFrames = Long.MAX_VALUE,
                    droppedFrames = null, clockProgressAgeMs = null, renderProgressAgeMs = null, decoderGeneration = null),
                transport().copy(stage = null)))
        val bytes = PlaybackHealthJournalCodec.encode(records)
        assertEquals(records, PlaybackHealthJournalCodec.decode(bytes))
        assertTrue(bytes.size <= PlaybackHealthJournalCodec.MAX_BYTES)
        assertTrue(bytes.toString(Charsets.US_ASCII).startsWith("tachiai-playback-health-v1\n"))
        val disk = file(); val journal = PlaybackHealthJournal(disk)
        assertTrue(journal.read().isEmpty()); assertFalse(disk.exists())
        records.forEach(journal::append)
        assertEquals(records, journal.read())
        assertFalse(disk.readText().contains("http")); assertFalse(disk.readText().contains("clockMs"))
        assertThrows(UnsupportedOperationException::class.java) {
            (journal.read() as MutableList<PlaybackHealthRecord>).clear()
        }
    }

    @Test fun allClosedHealthIntentReasonAndTransportVocabulariesRoundTrip() {
        val original = record()
        NativePlaybackLivenessState.entries.forEach { value ->
            val changed = original.copy(health = value)
            assertEquals(listOf(changed), PlaybackHealthJournalCodec.decode(PlaybackHealthJournalCodec.encode(listOf(changed))))
        }
        PlaybackHealthIntent.entries.forEach { value ->
            val changed = original.copy(intent = value, slot = PlaybackHealthSlot.B)
            assertEquals(listOf(changed), PlaybackHealthJournalCodec.decode(PlaybackHealthJournalCodec.encode(listOf(changed))))
        }
        PlaybackHealthReason.entries.forEach { value ->
            val changed = original.copy(reason = value)
            assertEquals(listOf(changed), PlaybackHealthJournalCodec.decode(PlaybackHealthJournalCodec.encode(listOf(changed))))
        }
        ProviderTransportExceptionKind.entries.forEach { value ->
            val changed = original.copy(transport = transport().copy(evidence = transport().evidence.copy(exceptionKind = value)))
            assertEquals(listOf(changed), PlaybackHealthJournalCodec.decode(PlaybackHealthJournalCodec.encode(listOf(changed))))
        }
    }

    @Test fun retentionKeepsNewestTwentyFourRecordsAcrossInstancesWithoutGrowingPastByteLimit() {
        val file = file()
        for (timestamp in 1L..35) PlaybackHealthJournal(file).append(record(timestamp))
        assertEquals((12L..35).toList(), PlaybackHealthJournal(file).read().map { it.timestampMs })
        assertTrue(file.length() <= PlaybackHealthJournalCodec.MAX_BYTES)
        assertEquals(24, PlaybackHealthJournal(file).read().size)
    }

    @Test fun malformedVocabularyNumbersFlagsAndGroupShapeNeverReplaceExistingEvidence() {
        val valid = PlaybackHealthJournalCodec.encode(listOf(record())).toString(Charsets.US_ASCII)
        val fields = valid.substringAfter('\n').trimEnd('\n').split('\t')
        fun changed(index: Int, value: String) = PlaybackHealthJournalCodec.HEADER + "\n" +
            fields.toMutableList().also { it[index] = value }.joinToString("\t") + "\n"
        val invalid = listOf(
            changed(0, "-1"), changed(0, "01000"), changed(0, "9223372036854775808"), changed(0, "-0"),
            changed(1, "C"), changed(2, "https://private.example/token"), changed(3, "UNKNOWN_HEALTH"),
            changed(4, "private-format"), changed(5, "-1"), changed(6, "2"), changed(7, "-"),
            changed(13, "0"), changed(14, "-1"), changed(18, "5"), changed(19, "-2"), changed(19, "5"),
            changed(20, "2147483648"), changed(20, "-1"), changed(22, "-1"), changed(24, "-1"),
            changed(25, "-1"), changed(26, "-1"), changed(27, "-1"), changed(28, "-1"),
            changed(29, "0"), changed(30, "-1"), changed(31, "PRIVATE_STAGE"), changed(32, "PRIVATE_FAILURE"),
            changed(33, "PRIVATE_PHASE"), changed(34, "2"), changed(35, "PRIVATE_EXCEPTION"),
            changed(36, "PRIVATE_OPERATION"), changed(37, "2"), changed(38, "2"),
            valid.dropLast(1), valid + "\n", valid.replace("\n", "\r\n"), valid.replace("1000", "1 000"),
        ).map { it.toByteArray(Charsets.UTF_8) } +
            listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(-1), ByteArray(PlaybackHealthJournalCodec.MAX_BYTES + 1))
        for (bytes in invalid) {
            val file = file().also { it.writeBytes(bytes) }; val journal = PlaybackHealthJournal(file)
            val error = assertThrows(PlaybackHealthJournalException::class.java) { journal.read() }
            assertEquals(PlaybackHealthJournalFailure.INVALID_RECORD, error.failure)
            assertEquals("INVALID_RECORD", error.message); assertNull(error.cause)
            assertThrows(PlaybackHealthJournalException::class.java) { journal.append(record()) }
            assertArrayEquals(bytes, file.readBytes())
        }
        assertTrue(PlaybackHealthJournalCodec.decode((PlaybackHealthJournalCodec.HEADER + "\n").toByteArray()).isEmpty())
    }

    @Test fun modelBoundsAndRecordCountAreCheckedBeforePublication() {
        assertThrows(IllegalArgumentException::class.java) { record(-1) }
        assertThrows(IllegalArgumentException::class.java) { record().copy(intentRevision = -1) }
        assertThrows(IllegalArgumentException::class.java) { player().copy(sampleAgeMs = -1) }
        assertThrows(IllegalArgumentException::class.java) { player().copy(state = -1) }
        assertThrows(IllegalArgumentException::class.java) { player().copy(suppressionReason = 5) }
        assertThrows(IllegalArgumentException::class.java) { player().copy(errorCode = -1) }
        assertThrows(IllegalArgumentException::class.java) { player().copy(durationMs = -1) }
        assertThrows(IllegalArgumentException::class.java) { player().copy(renderedFrames = -1) }
        assertThrows(IllegalArgumentException::class.java) { player().copy(droppedFrames = -1) }
        assertThrows(IllegalArgumentException::class.java) { player().copy(decoderGeneration = -1) }
        assertThrows(IllegalArgumentException::class.java) { transport().copy(ageMs = -1) }
        assertThrows(IllegalArgumentException::class.java) { PlaybackHealthJournalCodec.encode(List(25) { record() }) }
        val line = PlaybackHealthJournalCodec.encode(listOf(record())).toString(Charsets.US_ASCII).substringAfter('\n')
        assertThrows(PlaybackHealthJournalException::class.java) {
            PlaybackHealthJournalCodec.decode((PlaybackHealthJournalCodec.HEADER + "\n" + line.repeat(25)).toByteArray())
        }
    }

    @Test fun failedAtomicReplacementPreservesPriorFileAndRemovesTemporaryOutput() {
        val file = file(); PlaybackHealthJournal(file).append(record(1))
        val before = file.readBytes()
        val error = assertThrows(PlaybackHealthJournalException::class.java) {
            PlaybackHealthJournal(file) { _, _ -> throw IOException("Synthetic private failure") }.append(record(2))
        }
        assertEquals(PlaybackHealthJournalFailure.STORAGE, error.failure)
        assertEquals("STORAGE", error.message); assertNull(error.cause)
        assertArrayEquals(before, file.readBytes())
        assertFalse(checkNotNull(file.parentFile.listFiles()).any { it.name.startsWith(".playback-health-") })
        PlaybackHealthJournal(file).append(record(3))
        assertEquals(listOf(1L, 3L), PlaybackHealthJournal(file).read().map { it.timestampMs })
    }

    @Test fun sidecarLockCoversReplacementAndConcurrentInstancesKeepAllRecordsUnderTheCap() {
        val file = file()
        PlaybackHealthJournal(file) { from, to ->
            RandomAccessFile(File(to.parentFile, "${to.name}.lock"), "rw").use { handle ->
                assertThrows(OverlappingFileLockException::class.java) { handle.channel.tryLock() }
            }
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.append(record(0))
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (1L..8L).map { timestamp -> executor.submit { PlaybackHealthJournal(file).append(record(timestamp)) } }
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
        assertEquals((0L..8L).toSet(), PlaybackHealthJournal(file).read().map { it.timestampMs }.toSet())
    }

    @Test fun symbolicJournalSidecarAndParentPathsAreRejectedWithoutTouchingTheirTargets() {
        val outside = temporary.newFile().also { it.writeText("untouched") }
        val journalLink = file()
        Files.createSymbolicLink(journalLink.toPath(), outside.toPath())
        val lockLink = file()
        Files.createSymbolicLink(File(lockLink.parentFile, "${lockLink.name}.lock").toPath(), outside.toPath())
        val realParent = temporary.newFolder()
        val parentLink = File(temporary.newFolder(), "linked")
        Files.createSymbolicLink(parentLink.toPath(), realParent.toPath())
        for (path in listOf(journalLink, lockLink, File(parentLink, "playback-health.tsv"))) {
            assertEquals(PlaybackHealthJournalFailure.STORAGE,
                assertThrows(PlaybackHealthJournalException::class.java) { PlaybackHealthJournal(path).read() }.failure)
            assertThrows(PlaybackHealthJournalException::class.java) { PlaybackHealthJournal(path).append(record()) }
        }
        assertEquals("untouched", outside.readText()); assertFalse(File(realParent, "playback-health.tsv").exists())
    }
}
