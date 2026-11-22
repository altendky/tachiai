package net.fstab.tachiai.platform.media

import android.os.Build
import android.os.SharedMemory
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

// #568: an in-memory rewind buffer for one live DASH feed. It keeps the media
// this player fetched, plus segments of the rendition in use that would leave
// the provider's window before the player reaches them, so a held feed can sit
// further behind live than the provider allows. Bodies live in shared memory
// outside the Java heap (a direct buffer where that is unavailable),
// are bounded by time and bytes, are never written, exported or logged, and are
// discarded with the player.
internal class NativeLiveSegmentCache(
    // Kept beyond the provider's own window.
    private val retentionMs: Long = 120_000,
    private val maxBytes: Long = 160L * 1024 * 1024,
    // A segment this close to leaving the provider's window is fetched by the
    // cache if the player has not fetched it yet.
    private val recordMarginMs: Long = 30_000,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "native-live-segment-cache").apply { isDaemon = true }
    },
) : AutoCloseable {
    // What the manifest declared a URI to be. Times are absolute (Unix ms).
    internal data class Segment(
        val periodKey: String, val adaptationIndex: Int, val track: Int,
        val representationId: String, val bandwidth: Int,
        val startUs: Long, val absoluteStartMs: Long, val absoluteEndMs: Long, val initialization: Boolean,
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
    // Rendition the player is using, per track type; and how far back it began.
    private val activeRendition = HashMap<Int, Pair<String, Int>>()
    private val earliestRequestedMs = HashMap<Int, Long>()
    private var newestEndMs = Long.MIN_VALUE
    private var providerWindowMs = 0L
    @Volatile private var fetcher: ((URI) -> Unit)? = null
    @Volatile private var closed = false

    val bytes: Long get() = synchronized(lock) { storedBytes }

    // The player supplies its own bounded, policy-checked media transport.
    fun attach(fetch: (URI) -> Unit) { fetcher = fetch }

    // Called by the manifest parser with every URI it declared this refresh.
    fun publish(segments: Map<URI, Segment>, windowMs: Long) {
        val work = synchronized(lock) {
            if (closed) return
            providerWindowMs = maxOf(0L, windowMs)
            declared.keys.retainAll { it in segments || it in bodies }
            declared.putAll(segments)
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
        if (!segment.initialization) earliestRequestedMs[segment.track] =
            minOf(earliestRequestedMs[segment.track] ?: Long.MAX_VALUE, segment.absoluteStartMs)
    }

    // The same rendition in another period, or the closest bandwidth.
    private fun renditionLocked(periodKey: String, adaptationIndex: Int): String? {
        val candidates = declared.values.filter { it.periodKey == periodKey && it.adaptationIndex == adaptationIndex }
        val track = candidates.firstOrNull()?.track ?: return null
        val (id, bandwidth) = activeRendition[track] ?: return null
        val renditions = candidates.associate { it.representationId to it.bandwidth }
        return if (id in renditions) id else renditions.minByOrNull { kotlin.math.abs(it.value.toLong() - bandwidth) }?.key
    }

    private fun evictLocked() {
        val horizon = if (newestEndMs == Long.MIN_VALUE) Long.MIN_VALUE else newestEndMs - providerWindowMs - retentionMs
        // Oldest media first; initialization segments are small and needed by
        // every retained media segment of their rendition.
        val iterator = bodies.entries.iterator()
        while (iterator.hasNext()) {
            val (uri, stored) = iterator.next()
            val segment = declared[uri]
            val expired = segment == null || (!segment.initialization && segment.absoluteEndMs < horizon)
            if (expired || (storedBytes > maxBytes && segment?.initialization != true)) {
                dropLocked(stored); iterator.remove()
            }
        }
        declared.keys.retainAll { uri -> uri in bodies || declared[uri]?.let { it.initialization || it.absoluteEndMs >= horizon } == true }
    }

    private fun recordableLocked(): List<URI> {
        if (fetcher == null || newestEndMs == Long.MIN_VALUE) return emptyList()
        val due = newestEndMs - providerWindowMs + recordMarginMs
        return declared.entries.filter { (uri, segment) ->
            uri !in bodies && uri !in inFlight &&
                renditionLocked(segment.periodKey, segment.adaptationIndex) == segment.representationId &&
                (segment.initialization || (segment.absoluteStartMs >= (earliestRequestedMs[segment.track] ?: Long.MAX_VALUE) &&
                    segment.absoluteEndMs <= due))
        }.sortedBy { it.value.absoluteStartMs }.map { it.key }.onEach { inFlight.add(it) }
    }

    private fun schedule(uri: URI) {
        try {
            executor.execute {
                try { if (!closed) fetcher?.invoke(uri) }
                catch (_: Exception) { /* A missed recording only narrows the window. */ }
                finally { synchronized(lock) { inFlight.remove(uri) } }
            }
        } catch (_: RejectedExecutionException) { synchronized(lock) { inFlight.remove(uri) } }
    }
}
