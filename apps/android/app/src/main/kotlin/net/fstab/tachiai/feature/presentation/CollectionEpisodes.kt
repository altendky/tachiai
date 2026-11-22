package net.fstab.tachiai.feature.presentation

import androidx.compose.runtime.mutableStateMapOf
import java.util.concurrent.Executor
import net.fstab.tachiai.presentation.ConfiguredSource
import net.fstab.tachiai.provider.catalog.*

// One collection's episode list on the main picker (#550), newest first in the
// provider's own order. Pages already loaded stay for the session. groups are
// the provider's episode groups (tabs, #622) in its order, and group the one
// listed; both stay empty/null for collections without groups.
internal data class CollectionEpisodesState(
    val entries: List<CatalogEntry> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val more: Boolean = false,
    val failure: String? = null,
    val groups: List<CatalogChildGroup> = emptyList(),
    val group: String? = null,
)

internal interface CollectionEpisodes {
    fun state(source: ConfiguredSource): CollectionEpisodesState
    // more = false (re)loads the current group from the newest page; true
    // appends the next page.
    fun load(source: ConfiguredSource, more: Boolean)
    // Lists another of the collection's groups from its newest page.
    fun selectGroup(source: ConfiguredSource, group: String)
}

internal fun collectionEpisodesFailureMessage(reason: CatalogFailure): String = when (reason) {
    CatalogFailure.NOT_FOUND -> "This collection was not found."
    CatalogFailure.RATE_LIMITED -> "The provider is limiting requests. Try again shortly."
    CatalogFailure.UNSUPPORTED, CatalogFailure.NOT_VERIFIED ->
        "Episodes are not available for this collection now. Check its route in Providers."
    // Includes a load interrupted by leaving the app.
    CatalogFailure.ACCESS_REQUIRED, CatalogFailure.TEMPORARY, CatalogFailure.INVALID_INPUT -> "Episodes could not be loaded."
}

// Main-thread state; catalog calls run on the worker. Each collection keeps its
// own catalog, because adapters bind single-use continuations to one listing.
internal class PickerCollectionEpisodes(
    private val worker: Executor,
    private val post: (() -> Unit) -> Unit,
    // Called on main before a first page or Retry, as Watch and Manage streams
    // check the saved account before discovery. It must answer exactly once:
    // null to continue, or the message to show instead of loading.
    private val accountReady: (ConfiguredSource, (String?) -> Unit) -> Unit = { _, done -> done(null) },
    private val open: (ConfiguredSource) -> ProviderCatalog,
) : CollectionEpisodes {
    private val states = mutableStateMapOf<String, CollectionEpisodesState>()
    private val cursors = mutableMapOf<String, String>() // Main-owned.
    private val requests = mutableMapOf<String, Long>() // Main-owned; the latest load per collection.
    private val catalogs = mutableMapOf<String, ProviderCatalog>() // Worker-owned.
    private var generation = 0L // Main-owned.
    private fun key(source: ConfiguredSource) = source.instanceId + "|" + source.entry.resource.kind + "|" + source.entry.resource.identity

    override fun state(source: ConfiguredSource) = states[key(source)] ?: CollectionEpisodesState()

    override fun load(source: ConfiguredSource, more: Boolean) = request(source, more, state(source).group)

    override fun selectGroup(source: ConfiguredSource, group: String) {
        val current = state(source)
        if (group == current.group || current.groups.none { it.id == group }) return
        request(source, false, group)
    }

    private fun request(source: ConfiguredSource, more: Boolean, group: String?) {
        val key = key(source)
        val current = state(source)
        // A tab switch supersedes a pending load; anything else waits for it.
        if (current.loading && (more || group == current.group)) return
        // A continuation is single use; a failed or missing one restarts from the newest page.
        val cursor = if (more) cursors.remove(key) ?: return else null
        if (!more) cursors.remove(key)
        val switching = group != current.group
        states[key] = if (switching) current.copy(entries = emptyList(), group = group, loading = true, more = false, failure = null)
            else current.copy(loading = true, failure = null)
        val local = generation
        val token = (requests[key] ?: 0L) + 1
        requests[key] = token
        val latest = { local == generation && requests[key] == token }
        // Later pages continue an already admitted listing.
        if (more) browse(source, key, cursor, group, latest)
        else accountReady(source) { refused ->
            if (!latest()) return@accountReady
            if (refused == null) { browse(source, key, null, group, latest); return@accountReady }
            val previous = states[key] ?: CollectionEpisodesState()
            states[key] = previous.copy(loading = false, loaded = true, more = false, failure = refused)
        }
    }

    private fun browse(source: ConfiguredSource, key: String, cursor: String?, group: String?, latest: () -> Boolean) {
        val parent = source.entry.resource
        val more = cursor != null
        worker.execute {
            val result = try {
                // A continuation already names its group.
                catalogs.getOrPut(key) { open(source) }.browse(CatalogQuery(parent = parent, cursor = cursor, newest = true,
                    group = group.takeUnless { more }))
            } catch (_: Throwable) { CatalogResult.Failure(CatalogFailure.TEMPORARY) }
            // A catalog captures its route when opened and can stay refused or
            // poisoned; after any failure the next load opens a fresh one.
            if (result is CatalogResult.Failure) catalogs.remove(key)?.let { runCatching { it.close() } }
            post {
                if (!latest()) return@post
                val previous = states[key] ?: CollectionEpisodesState()
                states[key] = when (result) {
                    is CatalogResult.Value -> {
                        result.value.nextCursor?.let { cursors[key] = it }
                        val entries = (if (more) previous.entries else emptyList()) + result.value.entries
                        CollectionEpisodesState(entries.distinctBy { it.resource }, loaded = true,
                            more = result.value.nextCursor != null, groups = result.value.groups,
                            group = result.value.group ?: group?.takeIf { id -> result.value.groups.any { it.id == id } })
                    }
                    is CatalogResult.Failure -> previous.copy(loading = false, loaded = true, more = false,
                        failure = collectionEpisodesFailureMessage(result.reason))
                }
            }
        }
    }

    // Ends every listing; loaded pages are discarded with the session.
    fun close() {
        generation++
        states.clear(); cursors.clear(); requests.clear()
        worker.execute {
            catalogs.values.forEach { runCatching { it.close() } }
            catalogs.clear()
        }
    }
}
