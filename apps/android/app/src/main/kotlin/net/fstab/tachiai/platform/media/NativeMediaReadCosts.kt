package net.fstab.tachiai.platform.media

// Explicitly owned, finite diagnostics. No request/source identifiers, media,
// headers, credentials, SDK events or exceptions can enter this numeric model.
internal enum class NativeMediaReadCostType { MANIFEST, MEDIA, OTHER }
internal enum class NativeMediaReadCostPhase { ADMISSION, HEADERS, INPUT_OPEN, INPUT_READ, READ }

internal data class NativeMediaReadSizes(
    val upTo256: Long, val upTo1024: Long, val upTo4096: Long, val upTo16384: Long, val over16384: Long,
)

internal data class NativeMediaReadCostBucket(
    val openCount: Long, val openedCount: Long, val openFailureCount: Long,
    val completedCount: Long, val abortedCount: Long,
    val setupNanos: Long, val headersNanos: Long, val inputOpenNanos: Long,
    val setupAdmissionCount: Long, val setupAdmissionNanos: Long,
    val readAdmissionCount: Long, val readAdmissionNanos: Long,
    val readCount: Long, val readNanos: Long, val readFailureCount: Long, val readEofCount: Long,
    val requestedBytes: Long, val inputReadCount: Long, val inputReadNanos: Long,
    val inputRequestedBytes: Long, val inputReturnedBytes: Long,
    val inputPositiveReadCount: Long, val inputZeroReadCount: Long, val inputEofCount: Long,
    val requestedSizes: NativeMediaReadSizes, val inputRequestedSizes: NativeMediaReadSizes,
    val returnedSizes: NativeMediaReadSizes,
)

internal data class NativeMediaReadCostsSnapshot(
    val elapsedNanos: Long, val windowExpired: Boolean, val clockFailed: Boolean,
    val manifest: NativeMediaReadCostBucket, val media: NativeMediaReadCostBucket,
    val other: NativeMediaReadCostBucket,
)

// A boundary that finishes outside the window is omitted rather than recording
// an unbounded observation. Open requests may therefore remain unclassified at
// expiry. Summed durations can exceed wall time when loaders run concurrently.
internal class NativeMediaReadCosts(
    maximumDurationMs: Long = 90_000,
    private val clockNanos: () -> Long = System::nanoTime,
) {
    init { require(maximumDurationMs in 1..90_000) }
    private val windowNanos = maximumDurationMs * 1_000_000
    private var failedClock = false
    private var expired = false
    private var lastElapsed = 0L
    private val began = try { clockNanos() } catch (_: Throwable) { failedClock = true; 0L }
    private val buckets = NativeMediaReadCostType.entries.associateWith { MutableBucket() }

    private class Sizes {
        private val counts = LongArray(5)
        fun add(value: Int) {
            if (value <= 0) return
            counts[when { value <= 256 -> 0; value <= 1024 -> 1; value <= 4096 -> 2; value <= 16384 -> 3; else -> 4 }]++
        }
        fun snapshot() = NativeMediaReadSizes(counts[0], counts[1], counts[2], counts[3], counts[4])
    }
    private class MutableBucket {
        var openCount = 0L; var openedCount = 0L; var openFailureCount = 0L
        var completedCount = 0L; var abortedCount = 0L
        var setupNanos = 0L; var headersNanos = 0L; var inputOpenNanos = 0L
        var setupAdmissionCount = 0L; var setupAdmissionNanos = 0L
        var readAdmissionCount = 0L; var readAdmissionNanos = 0L
        var readCount = 0L; var readNanos = 0L; var readFailureCount = 0L; var readEofCount = 0L
        var requestedBytes = 0L; var inputReadCount = 0L; var inputReadNanos = 0L
        var inputRequestedBytes = 0L; var inputReturnedBytes = 0L
        var inputPositiveReadCount = 0L; var inputZeroReadCount = 0L; var inputEofCount = 0L
        val requestedSizes = Sizes(); val inputRequestedSizes = Sizes(); val returnedSizes = Sizes()
        fun snapshot() = NativeMediaReadCostBucket(openCount, openedCount, openFailureCount,
            completedCount, abortedCount, setupNanos, headersNanos, inputOpenNanos,
            setupAdmissionCount, setupAdmissionNanos, readAdmissionCount, readAdmissionNanos,
            readCount, readNanos, readFailureCount, readEofCount, requestedBytes,
            inputReadCount, inputReadNanos, inputRequestedBytes, inputReturnedBytes,
            inputPositiveReadCount, inputZeroReadCount, inputEofCount,
            requestedSizes.snapshot(), inputRequestedSizes.snapshot(), returnedSizes.snapshot())
    }

    // All observer entry points contain clock/observer failures. They never wrap
    // provider actions, so they cannot retry, swallow or replace those actions.
    private fun now(): Long? {
        if (failedClock || expired) return null
        val elapsed = clockNanos() - began
        if (elapsed < lastElapsed) { failedClock = true; return null }
        lastElapsed = minOf(elapsed, windowNanos)
        if (elapsed >= windowNanos) { expired = true; return null }
        return elapsed
    }
    private inline fun <T> observing(action: () -> T): T? = synchronized(this) {
        try { action() } catch (_: Throwable) { failedClock = true; null }
    }
    internal fun open(type: NativeMediaReadCostType): Request? = observing {
        val start = now() ?: return@observing null
        buckets.getValue(type).openCount++
        Request(type, start)
    }
    internal class Span internal constructor(
        internal val phase: NativeMediaReadCostPhase, internal val start: Long, internal val opening: Boolean,
    )
    internal inner class Request internal constructor(private val type: NativeMediaReadCostType, private val start: Long) {
        private var opening = true
        private var endedOpen = false
        private var closed = false
        private var failed = false
        private var complete = false
        private var excludedSetupNanos = 0L
        private val bucket get() = buckets.getValue(type)

        fun begin(phase: NativeMediaReadCostPhase): Span? = observing {
            if (closed) return@observing null
            now()?.let { Span(phase, it, opening) }
        }
        fun end(span: Span?) { observing {
            if (span == null || closed) return@observing
            val duration = (now() ?: return@observing) - span.start
            when (span.phase) {
                NativeMediaReadCostPhase.ADMISSION -> if (span.opening) {
                    bucket.setupAdmissionCount++; bucket.setupAdmissionNanos += duration
                } else { bucket.readAdmissionCount++; bucket.readAdmissionNanos += duration }
                NativeMediaReadCostPhase.HEADERS -> bucket.headersNanos += duration
                NativeMediaReadCostPhase.INPUT_OPEN -> bucket.inputOpenNanos += duration
                NativeMediaReadCostPhase.INPUT_READ -> bucket.inputReadNanos += duration
                NativeMediaReadCostPhase.READ -> bucket.readNanos += duration
            }
            if (span.opening && (span.phase == NativeMediaReadCostPhase.ADMISSION ||
                    span.phase == NativeMediaReadCostPhase.HEADERS || span.phase == NativeMediaReadCostPhase.INPUT_OPEN))
                excludedSetupNanos += duration
        } }
        fun opened() { finishOpen(false) }
        fun openFailed() { finishOpen(true) }
        private fun finishOpen(error: Boolean) { observing {
            if (endedOpen || closed) return@observing
            endedOpen = true; opening = false; failed = error
            val end = now() ?: return@observing
            bucket.setupNanos += (end - start - excludedSetupNanos).coerceAtLeast(0)
            if (error) bucket.openFailureCount++ else bucket.openedCount++
        } }
        fun beginRead(requested: Int): Span? = observing {
            if (closed) return@observing null
            val begin = now() ?: return@observing null
            bucket.readCount++; bucket.requestedBytes += requested.coerceAtLeast(0)
            bucket.requestedSizes.add(requested)
            Span(NativeMediaReadCostPhase.READ, begin, false)
        }
        fun beginInput(requested: Int): Span? = observing {
            if (closed) return@observing null
            val begin = now() ?: return@observing null
            bucket.inputReadCount++; bucket.inputRequestedBytes += requested.coerceAtLeast(0)
            bucket.inputRequestedSizes.add(requested)
            Span(NativeMediaReadCostPhase.INPUT_READ, begin, false)
        }
        fun inputReturned(count: Int) { observing {
            if (closed || now() == null) return@observing
            when {
                count > 0 -> { bucket.inputPositiveReadCount++; bucket.inputReturnedBytes += count; bucket.returnedSizes.add(count) }
                count == 0 -> bucket.inputZeroReadCount++
                else -> bucket.inputEofCount++
            }
        } }
        fun readFinished(span: Span?, eof: Boolean = false, error: Boolean = false) {
            end(span)
            observing {
                if (closed) return@observing
                if (error) failed = true
                if (now() == null) return@observing
                if (error) bucket.readFailureCount++
                if (eof) bucket.readEofCount++
            }
        }
        fun bodyComplete() { observing { complete = true } }
        fun close(error: Boolean = false) { observing {
            if (closed) return@observing
            closed = true
            if (now() == null) return@observing
            if (endedOpen && !opening && complete && !failed && !error) bucket.completedCount++ else bucket.abortedCount++
        } }
    }

    fun snapshot(): NativeMediaReadCostsSnapshot = synchronized(this) {
        observing { now() }
        NativeMediaReadCostsSnapshot(lastElapsed, expired, failedClock,
            buckets.getValue(NativeMediaReadCostType.MANIFEST).snapshot(),
            buckets.getValue(NativeMediaReadCostType.MEDIA).snapshot(),
            buckets.getValue(NativeMediaReadCostType.OTHER).snapshot())
    }
}
