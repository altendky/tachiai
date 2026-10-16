package net.fstab.tachiai.feature.connections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import net.fstab.tachiai.R
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
    onRefresh: (String) -> Unit = {}, onDiscoveryBack: () -> Unit = {},
    header: (@Composable () -> Unit)? = null, onReorder: ((List<String>) -> Job?)? = null) {
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
    val ids = state.configured.orEmpty().map { it.id }
    val latestIds by rememberUpdatedState(ids)
    val canDrag by rememberUpdatedState(mutable && onReorder != null && ids.size > 1)
    val reorder by rememberUpdatedState(onReorder)
    val latestState by rememberUpdatedState(state)
    val remove by rememberUpdatedState(onRemove)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val drag = remember(state.instance.id, state.privacyRevision, listState, density.density) {
        ConfiguredStreamDrag(listState, scope, { latestIds }, { canDrag }, { reorder?.invoke(it) },
            with(density) { 64.dp.toPx() }, with(density) { 720.dp.toPx() })
    }
    DisposableEffect(drag) { onDispose { drag.cancel() } }
    LaunchedEffect(ids, mutable) { drag.cancelGestureIfStale() }
    val sources = state.configured.orEmpty()
    val displayed = drag.order?.takeIf { it.size == ids.size && it.toSet() == ids.toSet() }
        ?.map { id -> sources.single { it.id == id } } ?: sources
    val actionsEnabled = mutable && !drag.busy
    var pendingRemoval by remember(state.instance.id, state.privacyRevision) { mutableStateOf<String?>(null) }
    LaunchedEffect(ids) { if (pendingRemoval !in ids) pendingRemoval = null }
    pendingRemoval?.let { id -> sources.singleOrNull { it.id == id }?.let { source ->
        AlertDialog(onDismissRequest = { pendingRemoval = null }, title = { Text("Remove stream?") },
            text = { Text("Remove “${source.entry.title}” from your configured streams?",
                Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = {
                val current = latestState
                if (!current.saving && !current.loading && !current.storageFailed &&
                    current.configured?.any { it.id == id } == true && !drag.busy) {
                    pendingRemoval = null; remove(id)
                }
            }, enabled = actionsEnabled, modifier = Modifier.semantics {
                contentDescription = "Confirm remove ${source.entry.title}"
            }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") } })
    } }
    LaunchedEffect(listState) {
        if (freshContext && (hadContext || state.query.parent != null)) listState.scrollToItem(3 + state.configured.orEmpty().size + if (header != null) 1 else 0)
    }
    val feedback = state.message ?: if (state.storageFailed) "Configured streams could not be read. Retry to continue."
        else state.failure?.let(::catalogFailureExplanation) ?: if (state.resultsTruncated)
            "Showing the first $MAX_DISCOVERY_RESULTS results. More results were withheld; narrow your search or choose another collection." else null
    val retryLabel = when {
        state.storageFailed -> "Retry"
        state.failure?.let { it.reason in setOf(CatalogFailure.TEMPORARY, CatalogFailure.RATE_LIMITED, CatalogFailure.ACCESS_REQUIRED) ||
            it.reason == CatalogFailure.INVALID_INPUT && capabilities == null } == true -> "Retry catalog"
        else -> null
    }
    var feedbackDismissed by remember(state.instance.id, state.privacyRevision, feedback, state.loading, state.saving) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).onGloballyPositioned { drag.viewport = it }
        .testTag("Stream discovery list"),
        state = listState) {
        if (header != null) item(key = "provider-settings") { header() }
        item(key = "management-heading") { Column(Modifier.padding(bottom = 12.dp)) {
            if (header == null) Text("${state.instance.name} · Manage streams", style = MaterialTheme.typography.headlineSmall)
            SettingsSection("Streams", "Add items to your selection page. Changes stay in Tachiai and do not change provider favorites." +
                " Drag a handle to reorder, or tap it for move actions. Refresh reloads the same stream’s title, availability and scheduled start; identity, order and quality are retained. It does not start playback or renew your account. Removing asks for confirmation." +
                (metadataRefreshExplanation(capabilities?.refresh)?.let { "\n\n$it" } ?: "") +
                "\n\nUp to $MAX_CONFIGURED_SOURCES streams can be configured. Remove an item to add another when the limit is reached." +
                (state.catalogNotice?.let { "\n\n$it" } ?: ""))
            if (header == null) TextButton(onClick = onBack) { Text(backLabel) }
        } }
        item(key = "configured-heading") { Column(Modifier.padding(bottom = 12.dp)) {
            Text("Configured streams", style = MaterialTheme.typography.titleLarge)
            if (state.configured?.isEmpty() == true) Text("No configured streams. Add an item below to show it on your selection page.")
            } }
        items(displayed, key = { "configured:${it.id}" }) { source ->
            val index = displayed.indexOf(source)
            var moveMenu by remember(source.id) { mutableStateOf(false) }
            var handleCoordinates by remember(source.id) { mutableStateOf<LayoutCoordinates?>(null) }
            val pinnable = LocalPinnableContainer.current
            val browsable = capabilities?.children == CatalogAccess.AVAILABLE &&
                source.entry.resource.intent in setOf(CatalogIntent.COLLECTION, CatalogIntent.CHANNEL)
            Box(Modifier.zIndex(if (drag.draggedId == source.id) 1f else 0f)
                .onGloballyPositioned { drag.positioned(source.id, it) }) {
            Column(Modifier.graphicsLayer {
                translationY = drag.translation(source.id)
                shadowElevation = if (drag.draggedId == source.id) 8.dp.toPx() else 0f
            }) {
                Surface(color = if (drag.draggedId == source.id) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("Configured row ${source.id}").semantics {
                    customActions = if (!actionsEnabled) emptyList() else buildList {
                        if (index > 0) add(CustomAccessibilityAction("Move earlier") { onMove(source.id, -1); true })
                        if (index < displayed.lastIndex) add(CustomAccessibilityAction("Move later") { onMove(source.id, 1); true })
                    }
                }, verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        IconButton(onClick = { moveMenu = true }, enabled = actionsEnabled && displayed.size > 1,
                            modifier = Modifier.size(48.dp).onGloballyPositioned { handleCoordinates = it }
                                .semantics { contentDescription = "Reorder ${source.entry.title}"
                                    stateDescription = "${index + 1} of ${displayed.size}" }
                                .pointerInput(source.id, drag, canDrag) {
                                    if (canDrag)
                                    detectDragGestures(onDragStart = { point ->
                                        handleCoordinates?.takeIf { it.isAttached }?.let { drag.start(source.id, it.localToRoot(point).y, pinnable) }
                                    }, onDragEnd = { if (drag.draggedId == source.id) drag.drop() },
                                        onDragCancel = { if (drag.draggedId == source.id) drag.cancel() }) { change, amount ->
                                        if (drag.draggedId == source.id) {
                                            change.consume()
                                            handleCoordinates?.takeIf { it.isAttached }?.let { drag.pointerAt(it.localToRoot(change.position).y, amount.y) }
                                        }
                                    }
                                }) { Icon(painterResource(R.drawable.stream_drag), contentDescription = null) }
                        DropdownMenu(expanded = moveMenu && actionsEnabled, onDismissRequest = { moveMenu = false }) {
                            DropdownMenuItem(text = { Text("Move earlier") }, enabled = index > 0,
                                onClick = { moveMenu = false; onMove(source.id, -1) },
                                modifier = Modifier.semantics { contentDescription = "Move ${source.entry.title} earlier" })
                            DropdownMenuItem(text = { Text("Move later") }, enabled = index < displayed.lastIndex,
                                onClick = { moveMenu = false; onMove(source.id, 1) },
                                modifier = Modifier.semantics { contentDescription = "Move ${source.entry.title} later" })
                        }
                    }
                    val browse = if (browsable) Modifier.clickable(enabled = actionsEnabled, role = Role.Button,
                        onClick = { onChildren(source.entry.resource) }).semantics { contentDescription = "Browse configured ${source.entry.title}" }
                        else Modifier
                    Column(Modifier.weight(1f).heightIn(min = 48.dp).then(browse).padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(source.entry.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (browsable) Text("›")
                        }
                        Text(entryDetails(source.entry), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { onRefresh(source.id) },
                        enabled = actionsEnabled && retryReady && capabilities?.refresh == CatalogAccess.AVAILABLE,
                        modifier = Modifier.size(48.dp).semantics { contentDescription = "Refresh metadata for ${source.entry.title}" }) {
                        Icon(painterResource(R.drawable.stream_refresh), contentDescription = null)
                    }
                    IconButton(onClick = { pendingRemoval = source.id }, enabled = actionsEnabled,
                        modifier = Modifier.size(48.dp).semantics { contentDescription = "Remove ${source.entry.title}" }) {
                        Icon(painterResource(R.drawable.stream_remove), contentDescription = null)
                    }
                }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            }
        }
        item(key = "discovery-controls") { Column(Modifier.padding(bottom = 12.dp)) {
            SettingsSection("Find streams", buildString {
                append("Browse, search or look up items to add to your configured streams.")
                if (capabilities != null && capabilities.browse != CatalogAccess.AVAILABLE)
                    append("\n\n${capabilities.browseTitle}: ${catalogAccessExplanation(capabilities.browse)}")
                capabilities?.collections.orEmpty().filter { it.access != CatalogAccess.AVAILABLE }.forEach {
                    append("\n\n${it.title}: ${catalogAccessExplanation(it.access)}")
                }
            })
            Button(onClick = onAll, enabled = mutable && capabilities != null) { Text(capabilities?.browseTitle ?: "All") }
            capabilities?.collections.orEmpty().forEach { collection ->
                TextButton(onClick = { onCollection(collection.id) }, enabled = mutable) { Text(collection.title) }
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
    if (state.loading || state.saving) LinearProgressIndicator(
        Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 16.dp).fillMaxWidth().semantics {
                contentDescription = if (state.saving) "Saving configured streams" else "Reading streams"
            })
    if (feedback != null && !feedbackDismissed) Snackbar(
        modifier = Modifier.align(Alignment.BottomCenter).safeDrawingPadding().imePadding().padding(16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        actionOnNewLine = true,
        action = { retryLabel?.let { label -> TextButton(onClick = onRetry,
            enabled = !state.loading && !state.saving && retryReady && (state.storageFailed || mutable)) { Text(label) } } },
        dismissAction = if (state.storageFailed) null else ({ IconButton(onClick = { feedbackDismissed = true }, modifier = Modifier.semantics {
            contentDescription = "Dismiss stream notice"
        }) { Icon(painterResource(R.drawable.stream_remove), contentDescription = null) } })) { Text(feedback) }
    }
}
