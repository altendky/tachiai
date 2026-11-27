package net.fstab.tachiai.platform.diagnostics

import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import net.fstab.tachiai.presentation.PrototypeFailureReason
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FailureJournalWriterTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun journalFile() = File(temporary.newFolder(), "failure-diagnostics.tsv")

    private fun observation(stage: FailureStage = FailureStage.ROUTE_CREATE, session: String = "a".repeat(32)) =
        FailureObservation(100_000, session, stage, FailureSlot.NONE, FailureThread.WORKER, false, SafeFailure(FailureCategory.IO))

    private fun elapsedMs(began: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)

    @Test fun `interrupted reporter keeps its record and its interrupt`() {
        val file = journalFile()
        // The synchronous journal refuses an interrupted caller at its file lock.
        var direct = 0
        Thread.currentThread().interrupt()
        FailureReporter(FailureJournal(file)::record, fallback = { direct++ }).report(FailureStage.MEDIA_REQUEST_READ)
        assertTrue(Thread.interrupted())
        assertEquals(1, direct)
        assertFalse(file.exists())

        var fallback = 0
        val losses = mutableListOf<FailureJournalLoss>()
        val writer = FailureJournalWriter(FailureJournal(file)::recordAll, lost = { losses += it })
        val reporter = FailureReporter(writer::submit, fallback = { fallback++ })
        Thread.currentThread().interrupt()
        reporter.report(FailureStage.MEDIA_REQUEST_READ, IOException("synthetic-private"))
        reporter.cleanup(FailureStage.MEDIA_REQUEST_CLOSE) { throw IOException("synthetic-private") }
        assertTrue("A report must not consume the loader's interrupt", Thread.interrupted())
        assertTrue(writer.flush(5_000))
        assertEquals(0, fallback); assertEquals(emptyList<FailureJournalLoss>(), losses)
        val records = FailureJournal(file).read()
        assertEquals(listOf(FailureStage.MEDIA_REQUEST_READ, FailureStage.MEDIA_REQUEST_CLOSE), records.map { it.stage })
        assertTrue(records.all { it.failure.category == FailureCategory.IO })
        assertFalse(file.readText().contains("synthetic-private"))
    }

    @Test fun `report made during load cancellation is durable and cancellation never waits for journal io`() {
        val file = journalFile()
        val journal = FailureJournal(file)
        val writeEntered = CountDownLatch(1)
        val writeRelease = CountDownLatch(1)
        val writer = FailureJournalWriter({ values -> writeEntered.countDown(); writeRelease.await(); journal.recordAll(values) })
        val reporter = FailureReporter(writer::submit).forSlot(FailureSlot.A)
        try {
            // Occupy the writer so any reporter that waited on I/O would stall.
            reporter.report(FailureStage.FEED_FAILURE, reason = PrototypeFailureReason.PLAYER_FAILED)
            assertTrue(writeEntered.await(5, TimeUnit.SECONDS))
            val loading = CountDownLatch(1)
            val interruptedAtReport = AtomicInteger()
            // A Media3-style loader: its load is cancelled by interrupting its thread.
            val loader = Thread {
                try {
                    loading.countDown()
                    CountDownLatch(1).await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    if (Thread.currentThread().isInterrupted) interruptedAtReport.incrementAndGet()
                    reporter.cleanup(FailureStage.MEDIA_DISCONNECT) { throw IOException("https://private.example/?token=synthetic") }
                    reporter.report(FailureStage.MEDIA_REQUEST_READ, IOException("synthetic-private"))
                }
            }
            loader.start()
            assertTrue(loading.await(5, TimeUnit.SECONDS))
            val began = System.nanoTime()
            loader.interrupt() // Loader.cancelLoad
            loader.join(2_000) // Release waits only for the loader thread.
            assertFalse("Cancelled loader waited for journal I/O", loader.isAlive)
            assertTrue(elapsedMs(began) < 1_000)
            assertEquals(1, interruptedAtReport.get())
        } finally { writeRelease.countDown() }
        assertTrue(writer.flush(5_000))
        val records = FailureJournal(file).read()
        assertEquals(listOf(FailureStage.FEED_FAILURE, FailureStage.MEDIA_DISCONNECT, FailureStage.MEDIA_REQUEST_READ),
            records.map { it.stage })
        assertEquals(listOf(FailureRelation.FIRST, FailureRelation.SECONDARY, FailureRelation.SECONDARY), records.map { it.relation })
        assertTrue(records.all { it.slot == FailureSlot.A })
        val text = file.readText()
        assertFalse(text.contains("private.example")); assertFalse(text.contains("synthetic"))
    }

    @Test fun `concurrent main and loader reports return while the writer is blocked`() {
        val file = journalFile()
        val journal = FailureJournal(file)
        val release = CountDownLatch(1)
        val writes = AtomicInteger()
        val writer = FailureJournalWriter({ values -> release.await(); journal.recordAll(values); writes.incrementAndGet() })
        val reporter = FailureReporter(writer::submit)
        val start = CountDownLatch(1)
        val returned = CountDownLatch(8)
        val stages = FailureStage.entries.take(8)
        try {
            val threads = stages.mapIndexed { index, stage ->
                Thread {
                    start.await()
                    if (index % 2 == 0) Thread.currentThread().interrupt()
                    repeat(3) { reporter.report(stage, IOException("synthetic")) }
                    returned.countDown()
                }.also { it.start() }
            }
            val began = System.nanoTime()
            start.countDown()
            assertTrue("A reporter waited for the blocked journal", returned.await(2, TimeUnit.SECONDS))
            assertTrue(elapsedMs(began) < 2_000)
            threads.forEach { it.join(2_000) }
        } finally { release.countDown() }
        assertTrue(writer.flush(5_000))
        val records = FailureJournal(file).read()
        assertEquals(stages.toSet(), records.map { it.stage }.toSet())
        assertTrue(records.all { it.count == 3 })
        assertEquals(1, records.count { it.relation == FailureRelation.FIRST })
        // Records queued while the first write was blocked share later syncs.
        assertTrue(writes.get() < 24)
    }

    @Test fun `batched writes produce the same journal as sequential records`() {
        val sessions = listOf("a".repeat(32), "b".repeat(32))
        val observations = (0 until 40).map { index ->
            observation(FailureStage.entries[index % 7], sessions[index % 2]).copy(blocked = index % 5 == 0,
                failure = if (index % 5 == 0) SafeFailure(FailureCategory.NONE) else SafeFailure(FailureCategory.IO))
        }
        val sequential = FailureJournal(journalFile()).also { journal -> observations.forEach(journal::record) }.read()
        val batchedFile = journalFile()
        val batches = Collections.synchronizedList(mutableListOf<Int>())
        val release = CountDownLatch(1)
        val writer = FailureJournalWriter({ values ->
            release.await(); batches += values.size; FailureJournal(batchedFile).recordAll(values)
        }, maxBatch = 16)
        observations.forEach(writer::submit)
        release.countDown()
        assertTrue(writer.flush(5_000))
        fun shape(records: List<FailureRecord>) = records.map {
            listOf(it.sessionId, it.stage, it.slot, it.relation, it.count, it.thread, it.failure, it.reason, it.timestampMs)
        }
        assertEquals(shape(sequential), shape(FailureJournal(batchedFile).read()))
        assertEquals(40, batches.sum())
        assertTrue(batches.all { it <= 16 })
    }

    @Test fun `full queue drops with a closed loss and counters while reporters stay unblocked`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val losses = Collections.synchronizedList(mutableListOf<FailureJournalLoss>())
        val lines = Collections.synchronizedList(mutableListOf<String>())
        var fallback = 0
        val writer = FailureJournalWriter({ entered.countDown(); release.await() }, lost = { losses += it },
            log = { lines += it }, capacity = 4, maxBatch = 4)
        val reporter = FailureReporter(writer::submit, fallback = { fallback++ })
        try {
            reporter.report(FailureStage.ROUTE_CREATE)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            FailureStage.entries.drop(1).take(5).forEach { reporter.report(it) }
            assertEquals(listOf(FailureJournalLoss.QUEUE_FULL), losses.toList())
            assertEquals(0, fallback)
        } finally { release.countDown() }
        assertTrue(writer.flush(5_000))
        val line = lines.last()
        assertTrue(line, line.startsWith("journal=FAILURE_WRITER windowMs="))
        listOf(" queued=5 ", " dropped=1 ", " queueDepth=4 ", " batches=", " written=5 ", " write=", " writeMaxMs=",
            " pending=5 ", " pendingMaxMs=", " flush=1 ", " flushMaxMs=").forEach { assertTrue("$it in $line", "$line ".contains(it)) }
        assertTrue(line, line.matches(Regex("[A-Za-z_=0-9 ]+")))
    }

    @Test fun `storage failure loses only the failing records and preserves corrupt evidence`() {
        val file = journalFile()
        val journal = FailureJournal(file)
        val release = CountDownLatch(1)
        val losses = Collections.synchronizedList(mutableListOf<FailureJournalLoss>())
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val writer = FailureJournalWriter({ values ->
            release.await()
            if (values.any { it.stage == FailureStage.ROUTE_CLOSE }) throw IOException("synthetic-private")
            journal.recordAll(values)
        }, lost = { losses += it }, log = { lines += it })
        listOf(FailureStage.ROUTE_CREATE, FailureStage.ROUTE_CLOSE, FailureStage.ROUTE_DISCONNECT).forEach { writer.submit(observation(it)) }
        release.countDown()
        assertTrue(writer.flush(5_000))
        assertEquals(listOf(FailureJournalLoss.STORAGE), losses.toList())
        assertTrue(FailureJournal(file).read().map { it.stage }.containsAll(listOf(FailureStage.ROUTE_CREATE, FailureStage.ROUTE_DISCONNECT)))
        assertTrue(lines.last().contains(" lost=1 "))
        assertFalse(lines.joinToString().contains("synthetic"))

        val corrupt = journalFile().apply { writeText("corrupt-preserved-evidence") }
        losses.clear()
        val damaged = FailureJournalWriter(FailureJournal(corrupt)::recordAll, lost = { losses += it })
        damaged.submit(observation()); damaged.submit(observation(FailureStage.ROUTE_CLOSE))
        assertTrue(damaged.flush(5_000))
        assertEquals(listOf(FailureJournalLoss.STORAGE, FailureJournalLoss.STORAGE), losses.toList())
        assertEquals("corrupt-preserved-evidence", corrupt.readText())
    }

    @Test fun `flush is bounded and the idle writer exits and restarts`() {
        val starts = AtomicInteger()
        val release = CountDownLatch(1)
        val writer = FailureJournalWriter({ release.await() }, idleMs = 50,
            start = { body -> starts.incrementAndGet(); Thread(body).apply { isDaemon = true } })
        assertTrue(writer.flush(0))
        writer.submit(observation())
        val began = System.nanoTime()
        assertFalse(writer.flush(100))
        assertTrue(elapsedMs(began) in 90..1_000)
        release.countDown()
        assertTrue(writer.flush(5_000))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            writer.submit(observation(FailureStage.ROUTE_CLOSE))
            assertTrue(writer.flush(5_000))
            if (starts.get() >= 2 || System.nanoTime() > deadline) break
            Thread.sleep(100)
        }
        assertEquals(2, starts.get())
    }
}
