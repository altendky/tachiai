package net.fstab.tachiai.feature.presentation

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import net.fstab.tachiai.platform.diagnostics.FailureJournal
import net.fstab.tachiai.platform.diagnostics.FailureJournalWriter
import net.fstab.tachiai.platform.diagnostics.FailureReporter
import net.fstab.tachiai.platform.diagnostics.FailureSlot
import net.fstab.tachiai.platform.diagnostics.FailureStage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrototypeRestartTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun ownedProcessRestartsOnlyAfterCheckpointAndLaunch() {
        for (cached in listOf(true, false)) {
            val steps = mutableListOf<String>()
            performPrototypeRestart("app" + if (cached) ":prototype_cached_player" else ":prototype_player", "app", cached,
                { steps += "checkpoint" }, { steps += "launch" }, { steps += "finish" }, { it() },
                { steps += "flush" }, { steps += "terminate" })
            assertEquals(listOf("checkpoint", "launch", "finish", "flush", "terminate"), steps)
        }
    }

    @Test fun theOwnedProcessEndsOnlyOnceTheRelayCoversIt() {
        val steps = mutableListOf<String>()
        var covered: (() -> Unit)? = null
        performPrototypeRestart("app:prototype_cached_player", "app", true, { steps += "checkpoint" },
            { steps += "launch" }, { steps += "finish" }, { covered = it }, { steps += "flush" }, { steps += "terminate" })
        assertEquals(listOf("checkpoint", "launch", "finish"), steps)
        checkNotNull(covered).invoke()
        assertEquals(listOf("checkpoint", "launch", "finish", "flush", "terminate"), steps)
    }

    @Test fun mainAndOtherProcessesCannotBeTerminated() {
        for (process in listOf("app", "app:prototype_player", "other:prototype_cached_player")) {
            var called = false
            assertThrows(IllegalStateException::class.java) {
                performPrototypeRestart(process, "app", true,
                    { called = true }, { called = true }, { called = true }, { called = true; it() }, { called = true }, { called = true })
            }
            assertFalse(called)
        }
    }

    @Test fun recordsReportedBeforeRestartAreDurableWhenTheProcessIsTerminated() {
        val file = File(temporary.newFolder(), "failure-diagnostics.tsv")
        val journal = FailureJournal(file)
        val writes = AtomicInteger()
        // A slow sync models the emulator's 0.2-1.6 s journal writes.
        val writer = FailureJournalWriter({ values -> Thread.sleep(150); journal.recordAll(values); writes.incrementAndGet() })
        val reporter = FailureReporter(writer::submit)
        reporter.forSlot(FailureSlot.A).report(FailureStage.PLAYER_RELEASE, IOException("synthetic-private"))
        reporter.forSlot(FailureSlot.B).report(FailureStage.MEDIA_REQUEST_CLOSE)
        reporter.report(FailureStage.VIEWER_DISPOSE)
        var beforeFlush = -1
        var durable: List<FailureStage>? = null
        performPrototypeRestart("app:prototype_player", "app", false, {}, {},
            finish = { beforeFlush = FailureJournal(file).read().size }, afterCovered = { it() },
            flush = { assertTrue(writer.flush(RESTART_JOURNAL_FLUSH_MS)) },
            terminate = { durable = FailureJournal(file).read().map { it.stage } })
        // Without the flush, termination here would have lost queued records.
        assertTrue(beforeFlush in 0..2)
        assertEquals(listOf(FailureStage.PLAYER_RELEASE, FailureStage.MEDIA_REQUEST_CLOSE, FailureStage.VIEWER_DISPOSE), durable)
        assertTrue(writes.get() in 1..2)
        assertFalse(file.readText().contains("synthetic-private"))
    }

    @Test fun stalledOrFailingJournalFlushCannotPreventTermination() {
        val release = CountDownLatch(1)
        val writer = FailureJournalWriter({ release.await() })
        try {
            FailureReporter(writer::submit).report(FailureStage.PLAYER_RELEASE)
            val steps = mutableListOf<String>()
            val began = System.nanoTime()
            performPrototypeRestart("app:prototype_cached_player", "app", true, {}, {}, {}, { it() },
                flush = { steps += "flush=${writer.flush(100)}" }, terminate = { steps += "terminate" })
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < RESTART_JOURNAL_FLUSH_MS)
            assertEquals(listOf("flush=false", "terminate"), steps)
            steps.clear()
            performPrototypeRestart("app:prototype_cached_player", "app", true, {}, {}, {}, { it() },
                flush = { steps += "flush"; throw IllegalStateException("synthetic") }, terminate = { steps += "terminate" })
            assertEquals(listOf("flush", "terminate"), steps)
        } finally { release.countDown() }
    }

    @Test fun checkpointOrLaunchFailureKeepsProcessAlive() {
        for (failAt in listOf("checkpoint", "launch")) {
            val steps = mutableListOf<String>()
            val failure = IllegalStateException("synthetic")
            fun step(name: String) { steps += name; if (name == failAt) throw failure }
            assertSame(failure, assertThrows(IllegalStateException::class.java) {
                performPrototypeRestart("app:prototype_cached_player", "app", true,
                    { step("checkpoint") }, { step("launch") }, { step("finish") }, { it() }, { step("flush") }, { step("terminate") })
            })
            assertEquals(if (failAt == "checkpoint") listOf("checkpoint") else listOf("checkpoint", "launch"), steps)
        }
    }
}
