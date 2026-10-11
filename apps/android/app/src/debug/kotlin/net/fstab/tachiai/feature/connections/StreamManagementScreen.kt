package net.fstab.tachiai.feature.connections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*

internal fun catalogAccessExplanation(access: CatalogAccess): String = when (access) {
    CatalogAccess.AVAILABLE -> "Available"
    CatalogAccess.AUTHORIZATION_REQUIRED -> "Connect a catalog account to access this list."
    CatalogAccess.RECONNECT_REQUIRED -> "Reconnect the catalog account to access this list."
    CatalogAccess.SCOPE_REQUIRED -> "Additional catalog permission is required for this list."
    CatalogAccess.UNSUPPORTED -> "This list is not supported."
    CatalogAccess.NOT_VERIFIED -> "Access to this list has not been verified."
}
internal fun catalogFailureExplanation(failure: CatalogResult.Failure): String = when (failure.reason) {
    CatalogFailure.ACCESS_REQUIRED -> "Catalog account access is required. Your configured streams are retained."
    CatalogFailure.UNSUPPORTED -> "This catalog operation is not supported."
    CatalogFailure.NOT_VERIFIED -> "Provider catalog access has not been verified."
    CatalogFailure.NOT_FOUND -> "No matching item was found. Your configured streams are retained."
    CatalogFailure.INVALID_INPUT -> "The input or catalog selection is invalid."
    CatalogFailure.RATE_LIMITED -> "The provider asked us to wait." + (failure.retryAtEpochMs?.let {
        " Retry after ${DateFormat.getDateTimeInstance().format(Date(it))}." } ?: " Try again later.")
    CatalogFailure.TEMPORARY -> "The catalog could not be loaded. Retry; your configured streams are retained."
}
private fun metadataRefreshExplanation(access: CatalogAccess?): String? = when (access) {
    CatalogAccess.AVAILABLE -> null
    CatalogAccess.AUTHORIZATION_REQUIRED -> "Refresh metadata: Connect a catalog account."
    CatalogAccess.RECONNECT_REQUIRED -> "Refresh metadata: Reconnect the catalog account."
    CatalogAccess.SCOPE_REQUIRED -> "Refresh metadata: Additional catalog permission is required."
    CatalogAccess.UNSUPPORTED -> "Refresh metadata is not supported by this provider."
    CatalogAccess.NOT_VERIFIED -> "Refresh metadata access is not verified."
    null -> "Refresh metadata is unavailable until provider access is checked."
}
private fun entryDetails(entry: CatalogEntry): String {
    val intent = when (entry.resource.intent) {
        CatalogIntent.CHANNEL -> "Ongoing channel"
        CatalogIntent.BROADCAST -> "Specific broadcast"
        CatalogIntent.VIDEO -> "On-demand video"
        CatalogIntent.COLLECTION -> "Show / series · choose an item before playback"
    }
    val availability = when (entry.availability) {
        CatalogAvailability.UNKNOWN -> "Availability unknown"
        CatalogAvailability.UPCOMING -> "Upcoming"
        CatalogAvailability.LIVE -> "Live"
        CatalogAvailability.OFFLINE -> "Offline"
        CatalogAvailability.AVAILABLE -> "Available"
        CatalogAvailability.EXPIRED -> "Expired"
        CatalogAvailability.UNAVAILABLE -> "Unavailable"
    }
    return "$intent · $availability" + (entry.scheduledStartEpochMs?.let {
        " · Scheduled ${DateFormat.getDateTimeInstance().format(Date(it))}" } ?: "")
}

@Composable
internal fun StreamManagementScreen(state: StreamManagementState,
    onSearch: (String) -> Unit, onAll: () -> Unit, onCollection: (String) -> Unit,
    onChildren: (CatalogResource) -> Unit, onMore: () -> Unit, onLookup: (String) -> Unit,
    onAdd: (CatalogEntry) -> Unit, onRemove: (String) -> Unit, onMove: (String, Int) -> Unit,
    onRetry: () -> Unit, onBack: () -> Unit, backLabel: String = "Back to providers",
    onRefresh: (String) -> Unit = {}, onDiscoveryBack: () -> Unit = {}) {
    var search by remember(state.instance.id, state.privacyRevision) { mutableStateOf("") }
    // An unvalidated URL may contain credentials. Keep this draft in memory;
    // never serialize it into an Activity Bundle before adapter normalization.
    var lookup by remember(state.instance.id, state.privacyRevision) { mutableStateOf("") }
    val mutable = !state.saving && !state.loading && !state.storageFailed && state.configured != null
    val capabilities = state.capabilities
    val retryAt = state.failure?.retryAtEpochMs
    var retryClock by remember(retryAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(retryAt) {
        while (retryAt != null && retryClock < retryAt) {
            delay((retryAt - retryClock).coerceIn(1L, 1_000L))
            retryClock = System.currentTimeMillis()
        }
    }
    val retryReady = retryAt == null || retryClock >= retryAt
    // Queries, account-derived results and their scroll anchors stay in this
    // composition only. A privacy reset discards every suspended list state.
    val listStates = remember(state.instance.id, state.privacyRevision) { mutableMapOf<Long, LazyListState>() }
    val hadContext = listStates.isNotEmpty()
    val retained = state.retainedDiscoveryContextIds.toSet() + state.discoveryContextId
    listStates.keys.retainAll(retained)
    val freshContext = state.discoveryContextId !in listStates
    val listState = listStates.getOrPut(state.discoveryContextId) { LazyListState() }
    LaunchedEffect(listState) {
        if (freshContext && (hadContext || state.query.parent != null)) listState.scrollToItem(3 + state.configured.orEmpty().size)
    }
    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).testTag("Stream discovery list"),
        state = listState) {
        item(key = "management-heading") { Column(Modifier.padding(bottom = 12.dp)) {
            Text("${state.instance.name} · Manage streams", style = MaterialTheme.typography.headlineSmall)
            Text("Add items to the list shown on your selection page. Changes stay in Tachiai and do not change provider favorites.")
            state.catalogNotice?.let { Text(it) }
            state.message?.let { Text(it) }
            if (state.loading) Text("Reading streams…")
            if (state.saving) Text("Saving configured streams…")
            if (state.storageFailed) Button(onClick = onRetry, enabled = !state.loading && !state.saving) { Text("Retry") }
            TextButton(onClick = onBack) { Text(backLabel) }
        } }
        item(key = "configured-heading") { Column(Modifier.padding(bottom = 12.dp)) {
            Text("Configured streams", style = MaterialTheme.typography.titleLarge)
            if (state.configured?.isEmpty() == true) Text("No configured streams. Add an item below to show it on your selection page.")
            if (state.configured.orEmpty().size >= MAX_CONFIGURED_SOURCES) Text("Stream limit reached. Remove an item to add another.")
            metadataRefreshExplanation(capabilities?.refresh)?.let { Text(it) } } }
        items(state.configured.orEmpty(), key = { "configured:${it.id}" }) { source ->
            Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(source.entry.title, style = MaterialTheme.typography.titleMedium)
                Text(entryDetails(source.entry), style = MaterialTheme.typography.bodySmall)
                val index = state.configured.orEmpty().indexOf(source)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onMove(source.id, -1) }, enabled = mutable && index > 0,
                        modifier = Modifier.semantics { contentDescription = "Move ${source.entry.title} earlier" }) { Text("Up") }
                    TextButton(onClick = { onMove(source.id, 1) }, enabled = mutable && index < state.configured.orEmpty().lastIndex,
                        modifier = Modifier.semantics { contentDescription = "Move ${source.entry.title} later" }) { Text("Down") }
                    TextButton(onClick = { onRemove(source.id) }, enabled = mutable,
                        modifier = Modifier.semantics { contentDescription = "Remove ${source.entry.title}" }) { Text("Remove") }
                }
                TextButton(onClick = { onRefresh(source.id) }, enabled = mutable && retryReady && capabilities?.refresh == CatalogAccess.AVAILABLE,
                    modifier = Modifier.semantics { contentDescription = "Refresh metadata for ${source.entry.title}" }) { Text("Refresh metadata") }
                if (capabilities?.children == CatalogAccess.AVAILABLE && source.entry.resource.intent in setOf(CatalogIntent.COLLECTION, CatalogIntent.CHANNEL))
                    TextButton(onClick = { onChildren(source.entry.resource) }, enabled = mutable,
                        modifier = Modifier.semantics { contentDescription = "Browse configured ${source.entry.title}" }) { Text("Browse items") }
            }
        }
        item(key = "discovery-controls") { Column(Modifier.padding(bottom = 12.dp)) {
            Text("Find streams", style = MaterialTheme.typography.titleLarge)
            Button(onClick = onAll, enabled = mutable && capabilities != null) { Text(capabilities?.browseTitle ?: "All") }
            if (capabilities != null && capabilities.browse != CatalogAccess.AVAILABLE)
                Text("${capabilities.browseTitle}: ${catalogAccessExplanation(capabilities.browse)}")
            capabilities?.collections.orEmpty().forEach { collection ->
                TextButton(onClick = { onCollection(collection.id) }, enabled = mutable) { Text(collection.title) }
                if (collection.access != CatalogAccess.AVAILABLE) Text("${collection.title}: ${catalogAccessExplanation(collection.access)}")
            }
            if (capabilities != null && capabilities.search != CatalogAccess.UNSUPPORTED) {
                OutlinedTextField(search, { search = it.take(160) }, enabled = mutable, singleLine = true,
                    label = { Text("Search streams") })
                Button(onClick = { onSearch(search) }, enabled = mutable && search.isNotBlank()) { Text("Search") }
            }
            if (capabilities != null && capabilities.lookup != CatalogAccess.UNSUPPORTED) {
                OutlinedTextField(lookup, { lookup = it.take(2048) }, enabled = mutable, singleLine = true,
                    label = { Text("Provider URL or channel") })
                Button(onClick = { onLookup(lookup.trim()) }, enabled = mutable && lookup.isNotBlank()) { Text("Look up") }
            }
        } }
        item(key = "discovery-context") { Column {
            val heading = state.childHeading ?: state.query.parent?.let {
                "${capabilities?.childrenHeadingPrefix ?: "Items under"} ${it.identity}"
            }
            heading?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
            state.navigationBackLabel?.let { label ->
                TextButton(onClick = onDiscoveryBack) { Text(label) }
            }
            if (state.resultsTruncated) Text("Showing the first $MAX_DISCOVERY_RESULTS results. More results were withheld; narrow your search or choose another collection.")
            state.failure?.let { failure ->
                Text(catalogFailureExplanation(failure))
                if (failure.reason in setOf(CatalogFailure.TEMPORARY, CatalogFailure.RATE_LIMITED, CatalogFailure.ACCESS_REQUIRED) ||
                    failure.reason == CatalogFailure.INVALID_INPUT && capabilities == null)
                    Button(onClick = onRetry, enabled = mutable && retryReady) { Text("Retry catalog") }
            }
            if (!state.loading && state.failure == null && state.configured != null && state.entries.isEmpty()) Text("No catalog items found.")
        } }
        items(state.entries, key = { "catalog:${it.resource.providerId.value}:${it.resource.kind}:${it.resource.identity}:${it.resource.intent}" }) { entry ->
            Column(Modifier.testTag("Discovery row ${entry.resource.kind}:${entry.resource.identity}")) {
                val added = state.configured.orEmpty().any { it.entry.resource == entry.resource }
                val browsable = entry.resource.intent in setOf(CatalogIntent.COLLECTION, CatalogIntent.CHANNEL) &&
                    capabilities?.children == CatalogAccess.AVAILABLE
                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val browse = if (browsable) Modifier.clickable(enabled = mutable, role = Role.Button,
                        onClick = { onChildren(entry.resource) }).semantics { contentDescription = "Browse ${entry.title}" }
                        else Modifier
                    Column(Modifier.weight(1f).heightIn(min = 48.dp).then(browse).padding(vertical = 8.dp)) {
                        Text(entry.title, style = MaterialTheme.typography.titleMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Top) {
                            Text(entryDetails(entry), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            if (browsable) Text("${capabilities.childrenTitle} ›",
                                modifier = Modifier.widthIn(max = 100.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(onClick = { onAdd(entry) },
                        enabled = mutable && !added && state.configured.orEmpty().size < MAX_CONFIGURED_SOURCES,
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Add ${entry.title}" }) {
                        Text(if (added) "✓ Added" else "Add")
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        if (state.nextCursor != null) item(key = "discovery-more") {
            TextButton(onClick = onMore, enabled = mutable, modifier = Modifier.padding(top = 4.dp)) { Text("More items") }
        }
    }
}
