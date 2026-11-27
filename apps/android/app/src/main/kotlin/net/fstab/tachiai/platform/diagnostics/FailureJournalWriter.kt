package net.fstab.tachiai.platform.diagnostics

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal enum class FailureJournalLoss { QUEUE_FULL, STORAGE }

// One writer per process and journal (#757). Reporters hand it observations
// that are already the closed SafeFailure projection: no Throwable, message or
// stack enters the queue. Handing off takes only a short uninterruptible lock and
// never waits for file I/O, so a cancelled (interrupted) loader keeps its record
// and neither release nor the viewer's main thread waits on a sync. One owned
// thread applies queued records in order with one journal read, replace and sync
// per batch. The queue is memory only; flush() bounds how long a planned process
// exit waits for it, and an unplanned process death can lose what is queued.
internal class FailureJournalWriter(
    private val write: (List<FailureObservation>) -> Unit,
    private val lost: (FailureJournalLoss) -> Unit = {},
    private val log: (String) -> Unit = {},
    private val capacity: Int = 256,
    private val maxBatch: Int = 32,
    private val idleMs: Long = 5_000,
    private val start: (Runnable) -> Thread = { Thread(it, "TachiaiFailureJournal").apply { isDaemon = true } },
) {
    private class Entry(val value: FailureObservation, val queuedAtMs: Long)
    private val lock = ReentrantLock()
    private val work = lock.newCondition()
    private val done = lock.newCondition()
    private val pending = ArrayDeque<Entry>()
    private var accepted = 0L
    private var completed = 0L
    private var running = false
    private val stats = OperationStats("journal=FAILURE_WRITER")

    init { require(capacity in 1..4_096 && maxBatch in 1..capacity && idleMs > 0) }

    fun submit(value: FailureObservation) {
        val now = nowMs()
        var depth = -1
        var launch = false
        lock.withLock {
            if (pending.size < capacity) {
                pending.addLast(Entry(value, now)); accepted++; depth = pending.size
                if (!running) { running = true; launch = true }
                work.signal()
            }
        }
        if (depth < 0) {
            stats.count("dropped", now)
            report(FailureJournalLoss.QUEUE_FULL)
            return
        }
        stats.count("queued", now)
        stats.peak("queueDepth", depth.toLong(), now)
        if (launch) try { start(Runnable(::run)).start() } catch (error: Throwable) {
            // The record stays queued for the next successful start; this report
            // still surfaces as unavailable through the reporter's fallback.
            lock.withLock { running = false }
            throw error
        }
    }

    // Waits at most timeoutMs for records accepted before this call. True when
    // none of them is still pending (each was written or reported lost).
    fun flush(timeoutMs: Long): Boolean {
        val began = nowMs()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0))
        val remaining = lock.withLock {
            val target = accepted
            while (completed < target) {
                val left = deadline - System.nanoTime()
                if (left <= 0) break
                try { done.awaitNanos(left) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt(); break
                }
            }
            (target - completed).coerceAtLeast(0)
        }
        val now = nowMs()
        stats.time("flush", now - began, now)
        if (remaining > 0) { stats.count("flushTimeout", now); stats.count("flushPending", now, remaining) }
        emit(stats.drain(now, force = true))
        return remaining == 0L
    }

    private fun run() {
        while (true) {
            val batch = lock.withLock {
                var idle = false
                while (pending.isEmpty()) {
                    if (idle) { running = false; return@withLock null }
                    idle = try { !work.await(idleMs, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
                }
                List(minOf(maxBatch, pending.size)) { pending.removeFirst() }
            }
            if (batch == null) { emit(stats.drain(nowMs(), force = true)); return }
            persist(batch)
            lock.withLock { completed += batch.size; done.signalAll() }
            emit(stats.drain(nowMs()))
        }
    }

    private fun persist(batch: List<Entry>) {
        val began = nowMs()
        val values = batch.map { it.value }
        // An atomic batch either replaced the journal or left it unchanged, so a
        // failed batch retries record by record to isolate the lost ones.
        val failures = if (attempt(values)) 0 else if (values.size == 1) 1 else values.count { !attempt(listOf(it)) }
        val now = nowMs()
        stats.count("batches", now)
        stats.time("write", now - began, now)
        if (failures < values.size) stats.count("written", now, (values.size - failures).toLong())
        if (failures > 0) stats.count("lost", now, failures.toLong())
        batch.forEach { stats.time("pending", now - it.queuedAtMs, now) }
        repeat(failures) { report(FailureJournalLoss.STORAGE) }
    }

    private fun attempt(values: List<FailureObservation>): Boolean = try {
        // Only this owned thread writes; clear any stray interrupt so the
        // interruptible file lock cannot refuse the batch.
        Thread.interrupted()
        write(values); true
    } catch (_: Throwable) { false }

    private fun report(loss: FailureJournalLoss) {
        try { lost(loss) } catch (_: Throwable) { /* Diagnostics cannot replace the original failure. */ }
    }

    private fun emit(line: String?) {
        if (line != null) try { log(line) } catch (_: Throwable) { /* Counters are best effort. */ }
    }

    private fun nowMs() = System.nanoTime() / 1_000_000
}
