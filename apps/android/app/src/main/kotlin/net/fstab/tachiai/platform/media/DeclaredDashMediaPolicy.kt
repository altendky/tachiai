package net.fstab.tachiai.platform.media

import java.io.IOException
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

// Exact declared files, not a CDN/directory wildcard. Keep one prior snapshot
// for chunks already selected when a live manifest refresh arrives.
internal class DeclaredDashMediaPolicy(
    private val manifest: URI,
    private val allowedMedia: (URI) -> Boolean,
) : AutoCloseable {
    private class Snapshot(val current: Set<URI>, val previous: Set<URI>, val closed: Boolean = false)
    private val snapshot = AtomicReference(Snapshot(emptySet(), emptySet()))

    fun allows(uri: URI): Boolean {
        val state = snapshot.get()
        return !state.closed && (uri == manifest || uri in state.current || uri in state.previous)
    }

    fun publish(uris: Collection<URI>) {
        if (uris.size !in 1..MAX_DECLARED_DASH_URIS || uris.any { it.toString().length > 2048 || !allowedMedia(it) })
            throw IOException("Declared media refused")
        val next = uris.toSet()
        while (true) {
            val prior = snapshot.get()
            if (prior.closed) throw IOException("Declared media ended")
            if (snapshot.compareAndSet(prior, Snapshot(next, prior.current))) return
        }
    }

    override fun close() { snapshot.set(Snapshot(emptySet(), emptySet(), true)) }
}

internal const val MAX_DECLARED_DASH_URIS = 32768

// Android-free range seam. Checked arithmetic prevents a hostile index from
// wrapping a range or allocating an unbounded set of URLs.
internal fun declaredDashSegmentNumbers(first: Long, count: Long): LongRange {
    if (first < 0 || count !in 0..8192) throw IOException("Segment window refused")
    if (count == 0L) return LongRange.EMPTY
    val last = try { Math.addExact(first, count - 1) } catch (_: ArithmeticException) {
        throw IOException("Segment window refused")
    }
    return first..last
}
