package net.fstab.tachiai.platform.media

import android.os.Build
import android.os.SharedMemory
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

// #568: an in-memory rewind buffer for one live feed. It keeps the media this
// player fetched, plus segments of the rendition in use that would leave the
// provider's window before the player reaches them, so a held feed can sit
// further behind live than the provider allows. Bodies live in shared memory
// outside the Java heap (a direct buffer where that is unavailable),
// are bounded by time and bytes, are never written, exported or logged, and are
// discarded with the player. #618: it is provider-general. A format-specific
// recorder (the DASH manifest parser, or NativeHlsLiveRecorder) declares the
// segments; the span follows liveRewindSpanMs.
internal class NativeLiveSegmentCache(
    private val maxBytes: Long = LIVE_REWIND_MAX_BYTES,
    // A segment this close to leaving the provider's window is fetched by the
    // cache if the player has not fetched it yet. A short window (Twitch's
    // ~30 s) is recorded once a segment is a quarter of the window old, so a
    // player near live has fetched it already and the queue has the rest of
    // the window to catch up.
    private val recordMarginMs: Long = 30_000,
    // Two recordings at a time, so one slow response does not hold the queue
    // past a short window.
    private val executor: ExecutorService = Executors.newFixedThreadPool(RECORDING_THREADS) { task ->
        Thread(task, "native-live-segment-cache").apply { isDaemon = true }
    },
) : AutoCloseable {
    // What the manifest or playlist declared a URI to be. Times are on one
    // content clock per feed (Unix ms for DASH). HLS uses one period and
    // adaptation, with each media playlist as a rendition.
    internal data class Segment(
        val periodKey: String, val adaptationIndex: Int, val track: Int,
        val representationId: String, val bandwidth: Int,
        val startUs: Long, val absoluteStartMs: Long, val absoluteEndMs: Long, val initialization: Boolean,
        // HLS media sequence number, shared by every rendition; -1 for DASH.
        val sequence: Long = -1,
    )

    // A read of a cached body. Close it when done; an evicted body is freed
    // only after its last reader closes.
    internal class Lease(val body: ByteBuffer, private val release: () -> Unit) : AutoCloseable {
        private var closed = false
        override fun close() { if (!closed) { closed = true; release() } }
    }

    private class Stored(val size: Int, val memory: SharedMemory?, val buffer: ByteBuffer) {
        var readers = 0
        var dropped = false
        fun free() {
            // Shared memory exists only where it was created (API 27+).
            if (Build.VERSION.SDK_INT >= 27) memory?.let { SharedMemory.unmap(buffer); it.close() }
        }
    }

    private val lock = Any()
    private val declared = HashMap<URI, Segment>()
    private val bodies = LinkedHashMap<URI, Stored>()
    private var storedBytes = 0L
    private val inFlight = HashSet<URI>()
    // Rendition the player is using, per track type.
    private val activeRendition = HashMap<Int, Pair<String, Int>>()
    // HLS: the rendition whose playlist the player refreshed last. That is the
    // one it follows, even while it plays stand-ins recorded in another.
    private val refreshedRendition = HashMap<Int, String>()
    // Closed counters for diagnostics; no identifiers.
    private var recordedCount = 0L
    private var recordSkipped = 0L
    private var recordFetchFailed = 0L
    private var recordNotStored = 0L
    private var evictedByHorizon = 0L
    private var evictedByBytes = 0L
    private var newestEndMs = Long.MIN_VALUE
    private var providerWindowMs = 0L
    // #618: the feed's current distance behind live, from its player.
    @Volatile private var behindLiveMs = 0L
    @Volatile private var fetcher: ((URI) -> Unit)? = null
    @Volatile private var closed = false

    val bytes: Long get() = synchronized(lock) { storedBytes }

    // Recording outcomes: recorded; skipped because the segment was no longer
    // declared when its turn came; the fetch failed (network, HTTP, admission
    // or cancellation); or it completed but was not kept (no longer declared,
    // evicted on arrival, or an incomplete body).
    internal data class Diagnostics(val bytes: Long, val bodies: Int, val spanMs: Long, val behindLiveMs: Long,
        val recorded: Long, val recordSkipped: Long, val recordFetchFailed: Long, val recordNotStored: Long,
        val evictedByHorizon: Long, val evictedByBytes: Long, val inFlight: Int)

    // Counts and sizes only, for closed diagnostics.
    fun diagnostics(): Diagnostics = synchronized(lock) {
        Diagnostics(storedBytes, bodies.size, liveRewindSpanMs(providerWindowMs, behindLiveMs), behindLiveMs,
            recordedCount, recordSkipped, recordFetchFailed, recordNotStored, evictedByHorizon, evictedByBytes,
            inFlight.size)
    }

    // The player supplies its own bounded, policy-checked media transport.
    fun attach(fetch: (URI) -> Unit) { fetcher = fetch }

    // The player reports how far behind live it is; the next refresh resizes.
    fun setBehindLiveMs(value: Long) { behindLiveMs = value.coerceAtLeast(0) }

    // How much history to keep behind the newest declared media.
    fun retentionSpanMs(): Long = synchronized(lock) { liveRewindSpanMs(providerWindowMs, behindLiveMs) }

    // How far beyond a provider window of this size the manifest may reach.
    fun retentionBeyondWindowMs(windowMs: Long): Long =
        liveRewindSpanMs(windowMs, behindLiveMs) - maxOf(0L, windowMs)

    // Called by the recorder with every URI it declared this refresh. A
    // recorder that declares one rendition per refresh (HLS) names it, so other
    // renditions' declarations stay until they age out.
    fun publish(segments: Map<URI, Segment>, windowMs: Long, rendition: String? = null) {
        val work = synchronized(lock) {
            if (closed) return
            providerWindowMs = maxOf(0L, windowMs)
            declared.entries.removeAll { (uri, segment) -> uri !in segments && uri !in bodies &&
                (rendition == null || segment.representationId == rendition) }
            declared.putAll(segments)
            if (rendition != null) segments.values.firstOrNull { it.representationId == rendition }?.let {
                refreshedRendition[it.track] = rendition
            }
            segments.values.forEach { if (!it.initialization) newestEndMs = maxOf(newestEndMs, it.absoluteEndMs) }
            evictLocked()
            recordableLocked()
        }
        work.forEach(::schedule)
    }

    // Leases a cached body; noting it as the player's own request.
    fun lease(uri: URI, player: Boolean): Lease? = synchronized(lock) {
        if (closed) return null
        if (player) notePlayerLocked(uri)
        val stored = bodies[uri] ?: return null
        stored.readers++
        Lease(stored.buffer.duplicate().asReadOnlyBuffer().also { it.position(0).limit(stored.size) }) {
            synchronized(lock) { stored.readers--; if (stored.dropped && stored.readers == 0) stored.free() }
        }
    }

    fun notePlayerRequest(uri: URI) { synchronized(lock) { if (!closed) notePlayerLocked(uri) } }

    fun accepts(uri: URI): Boolean = synchronized(lock) { !closed && uri in declared }

    fun cached(uri: URI): Boolean = synchronized(lock) { !closed && uri in bodies }

    // #711: while the feed plays older than (or within 10 s of) the start of
    // the provider's window, the rendition recorded for this track type; the
    // player stays on it. Null near live, where adaptive selection is free.
    fun recordedRendition(trackType: Int): NativeRecordedRendition? = synchronized(lock) {
        if (closed || providerWindowMs <= 0 || behindLiveMs <= providerWindowMs - RECORDED_RENDITION_MARGIN_MS)
            return null
        activeRendition[trackType]?.let { (id, bandwidth) -> NativeRecordedRendition(id, bandwidth) }
    }

    fun put(uri: URI, body: ByteArray) {
        if (body.isEmpty() || body.size > maxBytes) return
        synchronized(lock) { if (closed || uri !in declared || uri in bodies) return }
        val stored = store(body)
        synchronized(lock) {
            if (closed || uri !in declared || uri in bodies) { stored.free(); return }
            bodies[uri] = stored
            storedBytes += body.size
            evictLocked()
        }
    }

    private fun store(body: ByteArray): Stored {
        if (Build.VERSION.SDK_INT >= 27) {
            val memory = SharedMemory.create(null, body.size)
            try {
                val writable = memory.mapReadWrite()
                try { writable.put(body) } finally { SharedMemory.unmap(writable) }
                return Stored(body.size, memory, memory.mapReadOnly())
            } catch (error: Throwable) { memory.close(); throw error }
        }
        return Stored(body.size, null, ByteBuffer.allocateDirect(body.size).put(body).also { it.flip() })
    }

    private fun dropLocked(stored: Stored) {
        storedBytes -= stored.size
        stored.dropped = true
        if (stored.readers == 0) stored.free()
    }

    // Start times (µs within the period) of cached segments of the rendition in
    // use for this adaptation set. The manifest keeps only these past the
    // provider's window.
    fun cachedStartsUs(periodKey: String, adaptationIndex: Int): Set<Long> = synchronized(lock) {
        val rendition = renditionLocked(periodKey, adaptationIndex) ?: return emptySet()
        bodies.keys.mapNotNullTo(HashSet()) { uri ->
            declared[uri]?.takeIf { !it.initialization && it.periodKey == periodKey &&
                it.adaptationIndex == adaptationIndex && it.representationId == rendition }?.startUs
        }
    }

    // A period the provider dropped stays only while everything the player
    // would need from it is cached.
    fun periodRetainable(periodKey: String): Boolean = synchronized(lock) {
        val segments = declared.entries.filter { it.value.periodKey == periodKey && !it.value.initialization }
        val adaptations = segments.map { it.value.adaptationIndex }.toSet()
        adaptations.isNotEmpty() && adaptations.all { adaptation ->
            val rendition = renditionLocked(periodKey, adaptation) ?: return@all false
            val needed = declared.entries.filter { it.value.periodKey == periodKey &&
                it.value.adaptationIndex == adaptation && it.value.representationId == rendition }
            needed.isNotEmpty() && needed.all { it.key in bodies }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            bodies.values.forEach(::dropLocked)
            bodies.clear(); declared.clear(); inFlight.clear(); storedBytes = 0
        }
        fetcher = null
        executor.shutdownNow()
    }

    private fun notePlayerLocked(uri: URI) {
        val segment = declared[uri] ?: return
        activeRendition[segment.track] = segment.representationId to segment.bandwidth
    }

    // The same rendition in another period, or the closest bandwidth.
    private fun renditionLocked(periodKey: String, adaptationIndex: Int): String? {
        val candidates = declared.values.filter { it.periodKey == periodKey && it.adaptationIndex == adaptationIndex }
        val track = candidates.firstOrNull()?.track ?: return null
        refreshedRendition[track]?.let { refreshed -> if (candidates.any { it.representationId == refreshed }) return refreshed }
        val (id, bandwidth) = activeRendition[track] ?: return null
        val renditions = candidates.associate { it.representationId to it.bandwidth }
        return if (id in renditions) id else renditions.minByOrNull { kotlin.math.abs(it.value.toLong() - bandwidth) }?.key
    }

    private fun evictLocked() {
        val horizon = if (newestEndMs == Long.MIN_VALUE) Long.MIN_VALUE
            else newestEndMs - liveRewindSpanMs(providerWindowMs, behindLiveMs)
        val iterator = bodies.entries.iterator()
        while (iterator.hasNext()) {
            val (uri, stored) = iterator.next()
            val segment = declared[uri]
            if (segment == null || (!segment.initialization && segment.absoluteEndMs < horizon)) {
                dropLocked(stored); iterator.remove(); evictedByHorizon++
            }
        }
        // Over the byte cap, the oldest media by content time goes first, not
        // by arrival: backfill records older segments after newer ones (#618).
        // Initialization segments are small and needed by every retained
        // media segment of their rendition.
        if (storedBytes > maxBytes) {
            val oldest = bodies.keys.filter { declared[it]?.initialization == false }
                .sortedBy { checkNotNull(declared[it]).absoluteStartMs }
            for (uri in oldest) {
                if (storedBytes <= maxBytes) break
                bodies.remove(uri)?.let(::dropLocked); evictedByBytes++
            }
        }
        declared.keys.retainAll { uri -> uri in bodies || declared[uri]?.let { it.initialization || it.absoluteEndMs >= horizon } == true }
    }

    private fun recordableLocked(): List<URI> {
        if (fetcher == null || newestEndMs == Long.MIN_VALUE) return emptyList()
        // #618 startup backfill: the provider's window behind the starting
        // point is recorded too, each segment just before it would leave.
        val due = newestEndMs - providerWindowMs + minOf(recordMarginMs, providerWindowMs * 3 / 4)
        // HLS renditions share media sequence numbers, and the playlist lists
        // a segment recorded in any rendition. One recording per sequence:
        // while adaptive selection alternates between variants, recording
        // each one's copy would double the work and let both fall behind.
        val covered = HashSet<Long>()
        for ((uri, segment) in declared) if (segment.sequence >= 0 && (uri in bodies || uri in inFlight))
            covered += segment.sequence
        return declared.entries.filter { (uri, segment) ->
            uri !in bodies && uri !in inFlight && (segment.sequence < 0 || segment.sequence !in covered) &&
                renditionLocked(segment.periodKey, segment.adaptationIndex) == segment.representationId &&
                (segment.initialization || segment.absoluteEndMs <= due)
        }.sortedBy { it.value.absoluteStartMs }.map { it.key }.onEach { inFlight.add(it) }
    }

    private fun schedule(uri: URI) {
        try {
            executor.execute {
                var skipped = false
                var failed = false
                // A segment that left the manifest while queued is no longer
                // declared media: don't even ask for it (#618).
                try { if (!closed && accepts(uri)) fetcher?.invoke(uri) else skipped = true }
                catch (_: Exception) { failed = true /* A missed recording only narrows the window. */ }
                finally { synchronized(lock) {
                    inFlight.remove(uri)
                    when {
                        uri in bodies -> recordedCount++
                        skipped -> recordSkipped++
                        failed -> recordFetchFailed++
                        else -> recordNotStored++
                    }
                } }
            }
        } catch (_: RejectedExecutionException) { synchronized(lock) { inFlight.remove(uri) } }
    }
}

private const val RECORDED_RENDITION_MARGIN_MS = 10_000L
private const val RECORDING_THREADS = 2
