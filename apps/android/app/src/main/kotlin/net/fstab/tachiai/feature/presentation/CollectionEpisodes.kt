package net.fstab.tachiai.feature.presentation

import androidx.compose.runtime.mutableStateMapOf
import java.util.concurrent.Executor
import net.fstab.tachiai.presentation.ConfiguredSource
import net.fstab.tachiai.provider.catalog.*

// One collection's episode list on the main picker (#550), newest first in the
// provider's own order. Pages already loaded stay for the session.
internal data class CollectionEpisodesState(
    val entries: List<CatalogEntry> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val more: Boolean = false,
    val failure: String? = null,
)

internal interface CollectionEpisodes {
    fun state(source: ConfiguredSource): CollectionEpisodesState
    // more = false (re)loads from the newest page; true appends the next page.
    fun load(source: ConfiguredSource, more: Boolean)
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
    private val open: (ConfiguredSource) -> ProviderCatalog,
) : CollectionEpisodes {
    private val states = mutableStateMapOf<String, CollectionEpisodesState>()
    private val cursors = mutableMapOf<String, String>() // Main-owned.
    private val catalogs = mutableMapOf<String, ProviderCatalog>() // Worker-owned.
    private var generation = 0L // Main-owned.
    private fun key(source: ConfiguredSource) = source.instanceId + "|" + source.entry.resource.kind + "|" + source.entry.resource.identity

    override fun state(source: ConfiguredSource) = states[key(source)] ?: CollectionEpisodesState()

    override fun load(source: ConfiguredSource, more: Boolean) {
        val key = key(source)
        val current = state(source)
        if (current.loading) return
        // A continuation is single use; a failed or missing one restarts from the newest page.
        val cursor = if (more) cursors.remove(key) ?: return else null
        if (!more) cursors.remove(key)
        states[key] = current.copy(loading = true, failure = null)
        val local = generation
        val parent = source.entry.resource
        worker.execute {
            val result = try {
                catalogs.getOrPut(key) { open(source) }.browse(CatalogQuery(parent = parent, cursor = cursor, newest = true))
            } catch (_: Throwable) { CatalogResult.Failure(CatalogFailure.TEMPORARY) }
            // A catalog captures its route when opened and can stay refused or
            // poisoned; after any failure the next load opens a fresh one.
            if (result is CatalogResult.Failure) catalogs.remove(key)?.let { runCatching { it.close() } }
            post {
                if (local != generation) return@post
                val previous = states[key] ?: CollectionEpisodesState()
                states[key] = when (result) {
                    is CatalogResult.Value -> {
                        result.value.nextCursor?.let { cursors[key] = it }
                        val entries = (if (more) previous.entries else emptyList()) + result.value.entries
                        CollectionEpisodesState(entries.distinctBy { it.resource }, loaded = true,
                            more = result.value.nextCursor != null)
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
        states.clear(); cursors.clear()
        worker.execute {
            catalogs.values.forEach { runCatching { it.close() } }
            catalogs.clear()
        }
    }
}
