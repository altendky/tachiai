package net.fstab.tachiai.platform.diagnostics

import java.io.File
import java.io.IOException
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TimingFailureJournalTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun file() = File(temporary.newFolder(), "timing-failures.tsv")
    private fun target(side: TimingFailureSide = TimingFailureSide.A) = TimingFailureTarget(side,
        -125, 8_000, 3, -2_000, 30_000, 10_000, false, false, false)
    private fun evidence(delta: Long = 26_400, targets: List<TimingFailureTarget> = listOf(target())) =
        TimingFailureEvidence(TimingFailureKind.MARK_ALIGNMENT, delta, targets)

    @Test fun `closed scalar evidence round trips without absolute clock or freeform fields`() {
        val file = file(); val journal = TimingFailureJournal(file)
        val first = evidence(targets = listOf(target(), TimingFailureTarget(TimingFailureSide.B,
            null, 0, null, null, null, null, null, null, null)))
        val last = TimingFailureEvidence(TimingFailureKind.SAVED_OFFSET, Long.MIN_VALUE,
            listOf(target().copy(ownClockErrorMs = Long.MAX_VALUE, positionMs = Long.MIN_VALUE, bufferedMs = -2_000)))
        journal.append(first); journal.append(last)
        assertEquals(listOf(first, last), journal.read())
        assertEquals(journal.read(), TimingFailureJournalCodec.decode(file.readBytes()))
        assertTrue(file.length() <= TimingFailureJournalCodec.MAX_BYTES)
        assertEquals("tachiai-timing-failures-v1", file.readLines().first())
        assertFalse(file.readText().contains("clockMs"))
        assertFalse(file.readText().contains("http"))
    }

    @Test fun `evidence captures list immutably before worker publication`() {
        val values = mutableListOf(target())
        val captured = evidence(targets = values)
        values.clear()
        assertEquals(1, captured.targets.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (captured.targets as MutableList<TimingFailureTarget>).clear()
        }
        val decoded = TimingFailureJournalCodec.decode(TimingFailureJournalCodec.encode(listOf(captured)))
        assertThrows(UnsupportedOperationException::class.java) { (decoded as MutableList<TimingFailureEvidence>).clear() }
    }

    @Test fun `retention keeps exactly the newest eight records across instances`() {
        val file = file()
        assertTrue(TimingFailureJournal(file).read().isEmpty())
        for (delta in 1L..20L) TimingFailureJournal(file).append(evidence(delta))
        val records = TimingFailureJournal(file).read()
        assertEquals((13L..20L).toList(), records.map { it.requestedDeltaMs })
        assertTrue(file.length() <= TimingFailureJournalCodec.MAX_BYTES)
    }

    @Test fun `malformed records refuse append without replacing the original evidence`() {
        val valid = TimingFailureJournalCodec.encode(listOf(evidence())).toString(Charsets.US_ASCII)
        val malformed = listOf(
            valid.replace("MARK_ALIGNMENT", "https://private.example/token"),
            valid.replace("UNCONFIRMED", "secret-result"),
            valid.replace("\tA\t", "\tPRIVATE\t"),
            valid.replace("\t26400\t", "\t026400\t"),
            valid.replace("\t26400\t", "\t9223372036854775808\t"),
            valid.replace("\t8000\t", "\t60001\t"),
            valid.replace("\t3\t-2000", "\t5\t-2000"),
            valid.replace("\t30000\t", "\t-1\t"),
            valid.replace("\t0\t0\t0\n", "\t2\t0\t0\n"),
            valid.dropLast(1), valid + "\n", valid.replace("\n", "\r\n"),
        ).map { it.toByteArray(Charsets.UTF_8) } + listOf(byteArrayOf(0), ByteArray(TimingFailureJournalCodec.MAX_BYTES + 1))
        for (bytes in malformed) {
            val file = file().also { it.writeBytes(bytes) }; val journal = TimingFailureJournal(file)
            val read = assertThrows(TimingFailureJournalException::class.java) { journal.read() }
            assertEquals(TimingFailureJournalFailure.INVALID_RECORD, read.failure)
            assertEquals("INVALID_RECORD", read.message); assertNull(read.cause)
            assertThrows(TimingFailureJournalException::class.java) { journal.append(evidence()) }
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun `record count target identities and numeric capabilities are strictly bounded`() {
        val a = target()
        assertThrows(IllegalArgumentException::class.java) { evidence(targets = listOf(a, a)) }
        assertThrows(IllegalArgumentException::class.java) { evidence(targets = listOf(a, a, a)) }
        for (invalid in listOf(-1L, 60_001L)) assertThrows(IllegalArgumentException::class.java) { a.copy(elapsedMs = invalid) }
        assertThrows(IllegalArgumentException::class.java) { a.copy(state = -1) }
        assertEquals(-1L, a.copy(bufferedMs = -1).bufferedMs)
        val nine = List(9) { evidence(it.toLong()) }
        assertThrows(IllegalArgumentException::class.java) { TimingFailureJournalCodec.encode(nine) }
        val two = evidence(targets = listOf(a, target(TimingFailureSide.B)))
        val duplicate = TimingFailureJournalCodec.encode(listOf(two)).toString(Charsets.US_ASCII).replace("\tB\t", "\tA\t")
        assertThrows(TimingFailureJournalException::class.java) { TimingFailureJournalCodec.decode(duplicate.toByteArray()) }
        val tooMany = TimingFailureJournalCodec.HEADER + "\n" +
            TimingFailureJournalCodec.encode(listOf(evidence())).toString(Charsets.US_ASCII).substringAfter('\n').repeat(9)
        assertThrows(TimingFailureJournalException::class.java) { TimingFailureJournalCodec.decode(tooMany.toByteArray()) }
    }

    @Test fun `failed atomic replacement keeps the prior file and exposes only a closed failure`() {
        val file = file(); TimingFailureJournal(file).append(evidence(1))
        val before = file.readBytes()
        val failure = assertThrows(TimingFailureJournalException::class.java) {
            TimingFailureJournal(file) { _, _ -> throw IOException("synthetic-private-cause") }.append(evidence(2))
        }
        assertEquals(TimingFailureJournalFailure.STORAGE, failure.failure)
        assertEquals("STORAGE", failure.message); assertNull(failure.cause)
        assertArrayEquals(before, file.readBytes())
        assertFalse(checkNotNull(file.parentFile.listFiles()).any { it.name.startsWith(".timing-failure-") })
        TimingFailureJournal(file).append(evidence(3))
        assertEquals(listOf(1L, 3L), TimingFailureJournal(file).read().map { it.requestedDeltaMs })
    }

    @Test fun `sidecar lock covers atomic replacement and concurrent instances lose no accepted records`() {
        val file = file()
        TimingFailureJournal(file) { from, to ->
            RandomAccessFile(File(to.parentFile, "${to.name}.lock"), "rw").use { handle ->
                assertThrows(OverlappingFileLockException::class.java) { handle.channel.tryLock() }
            }
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.append(evidence(0))
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val tasks = (1L..2L).map { group -> executor.submit {
                check(start.await(5, TimeUnit.SECONDS))
                repeat(3) { TimingFailureJournal(file).append(evidence(group * 10 + it)) }
            } }
            start.countDown(); tasks.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally { executor.shutdownNow() }
        assertEquals(setOf(0L, 10L, 11L, 12L, 20L, 21L, 22L), TimingFailureJournal(file).read().map { it.requestedDeltaMs }.toSet())
    }

    @Test fun `symlink target and lock are refused without touching the linked file`() {
        for (lock in listOf(false, true)) {
            val file = file()
            val linked = File(file.parentFile, "untouched").also { it.writeText("unchanged") }
            Files.createSymbolicLink((if (lock) File(file.parentFile, "${file.name}.lock") else file).toPath(), linked.toPath())
            assertThrows(TimingFailureJournalException::class.java) { TimingFailureJournal(file).append(evidence()) }
            assertEquals("unchanged", linked.readText())
        }
    }
}
