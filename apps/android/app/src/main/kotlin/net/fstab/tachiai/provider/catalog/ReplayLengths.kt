package net.fstab.tachiai.provider.catalog

// Real replay lengths of listed broadcasts (#672), which a provider may list
// with their scheduled span. Display metadata only, kept in memory for the app
// process, so the picker's Episodes and the provider screen's Browse episodes
// share what either has read. Thread-safe.
internal class ReplayLengthCache(private val capacity: Int = 2_000) {
    private val known = object : LinkedHashMap<CatalogResource, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CatalogResource, Long>) = size > capacity
    }
    operator fun get(resource: CatalogResource): Long? = synchronized(known) { known[resource] }
    // The entry with its known length in place of its listed duration.
    fun withLength(entry: CatalogEntry): CatalogEntry = get(entry.resource)?.let { entry.copy(durationSeconds = it) } ?: entry
    // Keeps only lengths that were asked for and are valid display durations.
    fun record(asked: Collection<CatalogResource>, found: Map<CatalogResource, Long>) = synchronized(known) {
        for ((resource, seconds) in found) if (resource in asked && seconds in 1..MAX_CATALOG_DURATION_SECONDS) known[resource] = seconds
    }
    companion object { val session = ReplayLengthCache() }
}

// One listing owner's length requests: a page of listed broadcasts at a time,
// each tried once until the listing is reloaded. A broadcast without a length
// (live, upcoming or unreadable) keeps its span. Not thread-safe; the owner
// serializes calls.
internal class ReplayLengthRequests(val cache: ReplayLengthCache) {
    private val tried = mutableSetOf<CatalogResource>()
    // A reloaded listing tries its broadcasts again.
    fun reset() = tried.clear()
    // One broadcast is tried again, for example after Refresh metadata.
    fun retry(resource: CatalogResource) { tried -= resource }
    // The next request, marked tried: up to REPLAY_LENGTH_LIMIT listed broadcasts without a known length.
    fun next(entries: List<CatalogEntry>): List<CatalogResource> = entries.map { it.resource }
        .filter { it.intent == CatalogIntent.BROADCAST && cache[it] == null && it !in tried }.distinct()
        .take(REPLAY_LENGTH_LIMIT).also { tried += it }
    // Records a request's outcome; result is null when it never reached the
    // provider, and current is false once it was cancelled. Returns true when
    // the owner can ask for the listing's next page of broadcasts.
    fun finished(asked: List<CatalogResource>, result: CatalogResult<Map<CatalogResource, Long>>?, current: Boolean): Boolean {
        if (result is CatalogResult.Value) {
            cache.record(asked, result.value)
            if (current) return true
            // A cancelled request may have returned only part of its page.
            tried -= asked.filter { cache[it] == null }.toSet()
        } else if (result == null || result is CatalogResult.Failure &&
            result.reason in setOf(CatalogFailure.TEMPORARY, CatalogFailure.ACCESS_REQUIRED)) tried -= asked.toSet()
        return false
    }
}
