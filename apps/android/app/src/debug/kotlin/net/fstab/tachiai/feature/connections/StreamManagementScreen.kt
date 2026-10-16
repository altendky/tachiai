package net.fstab.tachiai.feature.connections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import net.fstab.tachiai.R
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*

private const val DISCOVERY_CONTROLS_KEY = "discovery-controls"

private class DiscoveryListPosition {
    val list = LazyListState()
    var initialized = false
    var transition by mutableStateOf<DiscoveryTabTransition?>(null)
}

private class DiscoveryTabTransition(val tab: String, val epoch: Long, val offset: Int,
    initialLayout: LazyListLayoutInfo) {
    val tailKey = "discovery-tail:$epoch"
    val viewport = initialLayout.viewportSize
    // Enough room even when the destination has no results. Layout measurement
    // removes the excess without moving the controls or estimating row heights.
    var gapPx by mutableIntStateOf((initialLayout.viewportEndOffset - offset).coerceAtLeast(0))
    var beforeRequest: LazyListLayoutInfo? = null

    fun measured(layout: LazyListLayoutInfo, loading: Boolean, canScrollBackward: Boolean) {
        if (layout === beforeRequest) return
        if (layout.viewportSize != viewport || !canScrollBackward) { gapPx = 0; return }
        val tail = layout.visibleItemsInfo.firstOrNull { it.key == tailKey }
        val needed = if (tail != null) (layout.viewportEndOffset - tail.offset).coerceAtLeast(0)
            else if (!loading) 0 else return
        // Scrolling back toward the top retires space. Reversing direction,
        // loading results or returning from a child must never recreate it.
        gapPx = minOf(gapPx, needed)
    }
}

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
private fun entryDetails(entry: CatalogEntry, actionLabel: String? = null): String {
    val intent = actionLabel?.let { "$it ›" } ?: when (entry.resource.intent) {
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
    onChildren: (CatalogResource) -> Unit, onMore: () -> Unit,
    onAdd: (CatalogEntry) -> Unit, onRemove: (String) -> Unit, onMove: (String, Int) -> Unit,
    onRetry: () -> Unit, onBack: () -> Unit, backLabel: String = "Back to providers",
    onRefresh: (String) -> Unit = {}, onDiscoveryBack: () -> Unit = {},
    header: (@Composable () -> Unit)? = null, onReorder: ((List<String>) -> Job?)? = null,
    onOpenLink: () -> Unit = {}, onResolveLink: (String) -> Unit = {},
    onDismissLink: () -> Unit = {}, onAddLink: () -> Unit = {}, onEditLink: () -> Unit = {}) {
    var search by remember(state.instance.id, state.privacyRevision) { mutableStateOf("") }
    // An unvalidated URL may contain credentials. Keep this draft in memory;
    // never serialize it into an Activity Bundle before adapter normalization.
    var lookup by remember(state.instance.id, state.privacyRevision, state.link != null) { mutableStateOf("") }
    val mutable = !state.saving && !state.loading && !state.storageFailed && state.configured != null
    val capabilities = state.capabilities
    val providerId = ProviderId(state.instance.service.name.lowercase(java.util.Locale.ROOT))
    // Keep only public control labels/support across access rechecks. Old access
    // states, entries and cursors must not become authorization evidence.
    var discoveryLabels by remember(state.instance.id) { mutableStateOf<CatalogCapabilities?>(null) }
    if (capabilities != null) discoveryLabels = CatalogCapabilities(
        browseTitle = capabilities.browseTitle,
        collections = capabilities.collections.map { it.copy(access = CatalogAccess.NOT_VERIFIED) },
        search = if (capabilities.search == CatalogAccess.UNSUPPORTED) CatalogAccess.UNSUPPORTED else CatalogAccess.NOT_VERIFIED,
        lookup = if (capabilities.lookup == CatalogAccess.UNSUPPORTED) CatalogAccess.UNSUPPORTED else CatalogAccess.NOT_VERIFIED)
    val visibleCapabilities = capabilities ?: discoveryLabels
    val discoveryMutable = mutable && capabilities != null
    val retryAt = state.failure?.retryAtEpochMs
    val linkRetryAt = state.link?.failure?.retryAtEpochMs
    val retryDeadline = listOfNotNull(retryAt, linkRetryAt).maxOrNull()
    var retryClock by remember(retryDeadline) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(retryDeadline) {
        while (retryDeadline != null && retryClock < retryDeadline) {
            delay((retryDeadline - retryClock).coerceIn(1L, 1_000L))
            retryClock = System.currentTimeMillis()
        }
    }
    val retryReady = retryAt == null || retryClock >= retryAt
    // Queries, account-derived results and their scroll anchors stay in this
    // composition only. A privacy reset discards every suspended list state.
    val listStates = remember(state.instance.id, state.privacyRevision) { mutableMapOf<Long, DiscoveryListPosition>() }
    val hadContext = listStates.isNotEmpty()
    val retained = state.retainedDiscoveryContextIds.toSet() + state.discoveryContextId
    listStates.keys.retainAll(retained)
    val position = listStates.getOrPut(state.discoveryContextId) { DiscoveryListPosition() }
    val listState = position.list
    var tabEpoch by remember(state.instance.id, state.privacyRevision) { mutableLongStateOf(0) }
    var pendingTab by remember(state.instance.id, state.privacyRevision) { mutableStateOf<DiscoveryTabTransition?>(null) }
    val requestedTab = pendingTab?.takeIf { it.tab == state.selectedTabId && state.query.parent == null }
    val transition = requestedTab ?: position.transition
    val ids = state.configured.orEmpty().map { it.id }
    val leadingKeys = listOf(if (header != null) "provider-settings" else "management-heading", "configured-heading") +
        ids.map { "configured:$it" }
    val discoveryControlsIndex = leadingKeys.size
    val latestLoading by rememberUpdatedState(state.loading)
    SideEffect {
        if (requestedTab != null) {
            position.transition = requestedTab
            requestedTab.beforeRequest = listState.layoutInfo
            listState.requestScrollToItem(discoveryControlsIndex, -requestedTab.offset)
            pendingTab = null
        } else if (!position.initialized && (hadContext || state.query.parent != null)) {
            listState.requestScrollToItem(discoveryControlsIndex)
        }
        position.initialized = true
    }
    LaunchedEffect(listState, transition) {
        if (transition != null) snapshotFlow {
            Triple(listState.layoutInfo, latestLoading, listState.canScrollBackward)
        }.collect { (layout, loading, backward) ->
            transition.measured(layout, loading, backward)
        }
    }
    val selectTab: (String) -> Unit = { id ->
        val layout = listState.layoutInfo
        pendingTab = layout.visibleItemsInfo.firstOrNull { it.key == DISCOVERY_CONTROLS_KEY }?.let {
            DiscoveryTabTransition(id, ++tabEpoch, it.offset, layout)
        }
        if (id == DISCOVERY_ALL_TAB) onAll() else onCollection(id)
    }
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
    state.link?.let { link ->
        val busy = link.loading || link.saving
        val resolved = link.entry
        val duplicate = resolved != null && sources.any { it.entry.resource == resolved.resource }
        val canSubmit = mutable && !busy && (linkRetryAt == null || retryClock >= linkRetryAt) && lookup.isNotBlank() &&
            (resolved == null || !duplicate && sources.size < MAX_CONFIGURED_SOURCES)
        val submit = { if (canSubmit) { if (resolved == null) onResolveLink(lookup.trim()) else onAddLink() } }
        AlertDialog(onDismissRequest = { if (!link.saving) onDismissLink() },
            title = { Text("Add by URL or channel") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(lookup, { lookup = it.take(2048) }, enabled = !busy && resolved == null,
                    singleLine = true, label = { Text("Provider URL or channel") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().semantics {
                    contentDescription = if (link.saving) "Saving stream" else "Resolving stream"
                })
                resolved?.let {
                    Text(it.title, style = MaterialTheme.typography.titleMedium); Text(entryDetails(it))
                    TextButton(onClick = onEditLink, enabled = !busy) { Text("Edit link") }
                }
                val error = link.message ?: link.failure?.let(::catalogFailureExplanation)
                    ?: if (duplicate) "This stream is already configured." else if (sources.size >= MAX_CONFIGURED_SOURCES)
                        "Remove a configured stream before adding another." else null
                error?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            } },
            confirmButton = { TextButton(onClick = submit, enabled = canSubmit) { Text("Add") } },
            dismissButton = { TextButton(onClick = onDismissLink, enabled = !link.saving) { Text("Cancel") } })
    }
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
    val feedback = state.message ?: if (state.storageFailed) "Configured streams could not be read. Retry to continue."
        else state.failure?.let(::catalogFailureExplanation) ?: if (state.resultsTruncated)
            "Showing the first $MAX_DISCOVERY_RESULTS results. More results were withheld; narrow your search or choose another collection." else null
    val retryLabel = when {
        state.storageFailed -> "Retry"
        state.failure?.let { it.reason in setOf(CatalogFailure.TEMPORARY, CatalogFailure.RATE_LIMITED, CatalogFailure.ACCESS_REQUIRED) ||
            it.reason == CatalogFailure.INVALID_INPUT && capabilities == null } == true -> "Retry catalog"
        else -> null
    }
    var feedbackDismissed by remember(state.instance.id, state.privacyRevision, feedback) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).onGloballyPositioned { drag.viewport = it }
        .testTag("Stream discovery list"),
        state = listState) {
        if (header != null) item(key = leadingKeys[0]) { header() }
        if (header == null) item(key = leadingKeys[0]) {
            SettingsPageHeader("${providerDisplayNames(state.instance).primary} · Manage streams", onBack = onBack, backDescription = backLabel)
        }
        item(key = leadingKeys[1]) { Column(Modifier.padding(bottom = 12.dp)) {
            SettingsSection("Configured streams", "Add items to your selection page. Changes stay in Tachiai and do not change provider favorites." +
                " Drag a handle to reorder, or tap it for move actions. Refresh reloads the same stream’s title, availability and scheduled start; identity, order and quality are retained. It does not start playback or renew your account. Removing asks for confirmation." +
                (metadataRefreshExplanation(capabilities?.refresh)?.let { "\n\n$it" } ?: "") +
                "\n\nUp to $MAX_CONFIGURED_SOURCES streams can be configured. Remove an item to add another when the limit is reached." +
                (state.catalogNotice?.let { "\n\n$it" } ?: ""), actions = {
                if (visibleCapabilities?.lookup != null && visibleCapabilities.lookup != CatalogAccess.UNSUPPORTED)
                    TextButton(onClick = onOpenLink, enabled = actionsEnabled && capabilities != null) {
                        Icon(painterResource(R.drawable.stream_link), contentDescription = null)
                        Spacer(Modifier.width(4.dp)); Text("Add link")
                    }
            })
            if (state.configured?.isEmpty() == true) Text("No configured streams. Add an item below to show it on your selection page.")
            } }
        items(displayed, key = { "configured:${it.id}" }) { source ->
            val index = displayed.indexOf(source)
            var moveMenu by remember(source.id) { mutableStateOf(false) }
            var handleCoordinates by remember(source.id) { mutableStateOf<LayoutCoordinates?>(null) }
            val pinnable = LocalPinnableContainer.current
            val childCapability = capabilities?.childrenFor(source.entry.resource, providerId)
                ?.takeIf { it.access == CatalogAccess.AVAILABLE }
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
                    val browse = if (childCapability != null) Modifier.clickable(enabled = actionsEnabled, role = Role.Button,
                        onClickLabel = childCapability.actionLabel,
                        onClick = { onChildren(source.entry.resource) }).semantics(mergeDescendants = true) {
                            contentDescription = "${childCapability.actionLabel} for ${source.entry.title}"
                        }
                        else Modifier
                    Column(Modifier.weight(1f).heightIn(min = 48.dp).then(browse).padding(vertical = 8.dp)) {
                        Text(source.entry.title, style = MaterialTheme.typography.titleMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(entryDetails(source.entry, childCapability?.actionLabel), style = MaterialTheme.typography.bodySmall)
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
        item(key = DISCOVERY_CONTROLS_KEY) { Column(Modifier.padding(bottom = 12.dp).testTag("Find streams controls")) {
            SettingsSection("Find streams", buildString {
                append("Browse, search or look up items to add to your configured streams.")
                if (capabilities != null && capabilities.browse != CatalogAccess.AVAILABLE)
                    append("\n\n${capabilities.browseTitle}: ${catalogAccessExplanation(capabilities.browse)}")
                capabilities?.collections.orEmpty().filter { it.access != CatalogAccess.AVAILABLE }.forEach {
                    append("\n\n${it.title}: ${catalogAccessExplanation(it.access)}")
                }
            })
            val tabs = listOf(Triple(DISCOVERY_ALL_TAB, visibleCapabilities?.browseTitle ?: "All", visibleCapabilities?.browse)) +
                visibleCapabilities?.collections.orEmpty().map { Triple(it.id, it.title, it.access) }
            val selected = tabs.indexOfFirst { it.first == state.selectedTabId }
            @Suppress("DEPRECATION")
            ScrollableTabRow(selectedTabIndex = selected.coerceAtLeast(0), edgePadding = 0.dp,
                divider = {}, indicator = { positions -> if (selected >= 0) TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(positions[selected])) }) {
                tabs.forEach { (id, title, access) ->
                    Tab(selected = id == state.selectedTabId, enabled = !state.saving && !state.storageFailed &&
                        state.configured != null && capabilities != null && state.link == null,
                        onClick = { selectTab(id) },
                        modifier = Modifier.semantics { stateDescription = catalogAccessExplanation(access ?: CatalogAccess.NOT_VERIFIED) },
                        text = { Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(title, maxLines = 1)
                            Icon(painterResource(R.drawable.settings_info), contentDescription = null,
                                modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                    alpha = if (access == CatalogAccess.AVAILABLE) 0f else 1f))
                        } })
                }
            }
            if (visibleCapabilities != null && visibleCapabilities.search != CatalogAccess.UNSUPPORTED) {
                val searchEnabled = discoveryMutable && search.isNotBlank()
                val submitSearch = { if (searchEnabled) onSearch(search) }
                OutlinedTextField(search, { search = it.take(160) }, enabled = discoveryMutable, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), label = { Text("Search streams") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                    trailingIcon = { IconButton(onClick = submitSearch, enabled = searchEnabled,
                        modifier = Modifier.size(48.dp).semantics { contentDescription = "Search streams" }) {
                        Icon(painterResource(R.drawable.stream_search), contentDescription = null)
                    } })
            }
        } }
        item(key = "discovery-context") { Column {
            val heading = state.childHeading ?: state.query.parent?.let {
                "${capabilities?.childrenFor(it, providerId)?.headingPrefix ?: "Items under"} ${it.identity}"
            } ?: state.query.search?.let { "Search results" }
            if (state.navigationBackLabel != null) SettingsPageHeader(heading ?: "Find streams",
                onBack = onDiscoveryBack, backDescription = state.navigationBackLabel)
            else heading?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
            if (!state.loading && state.failure == null && state.configured != null && state.entries.isEmpty()) Text("No catalog items found.")
        } }
        items(state.entries, key = { "catalog:${it.resource.providerId.value}:${it.resource.kind}:${it.resource.identity}:${it.resource.intent}" }) { entry ->
            Column(Modifier.testTag("Discovery row ${entry.resource.kind}:${entry.resource.identity}")) {
                val added = state.configured.orEmpty().any { it.entry.resource == entry.resource }
                val childCapability = capabilities?.childrenFor(entry.resource, providerId)
                    ?.takeIf { it.access == CatalogAccess.AVAILABLE }
                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val browse = if (childCapability != null) Modifier.clickable(enabled = mutable, role = Role.Button,
                        onClickLabel = childCapability.actionLabel,
                        onClick = { onChildren(entry.resource) }).semantics { contentDescription = "Browse ${entry.title}" }
                        else Modifier
                    Column(Modifier.weight(1f).heightIn(min = 48.dp).then(browse).padding(vertical = 8.dp)) {
                        Text(entry.title, style = MaterialTheme.typography.titleMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Top) {
                            Text(entryDetails(entry), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            if (childCapability != null) Text("${childCapability.actionLabel} ›",
                                modifier = Modifier.widthIn(max = 100.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    IconButton(onClick = { onAdd(entry) },
                        enabled = mutable && !added && state.configured.orEmpty().size < MAX_CONFIGURED_SOURCES,
                        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier.size(48.dp).semantics {
                            contentDescription = "Add ${entry.title}"
                            stateDescription = if (added) "Already added" else "Not added"
                        }) {
                        Icon(painterResource(if (added) R.drawable.stream_check else R.drawable.stream_plus), contentDescription = null)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        if (state.nextCursor != null) item(key = "discovery-more") {
            TextButton(onClick = onMore, enabled = mutable, modifier = Modifier.padding(top = 4.dp)) { Text("More items") }
        }
        transition?.let { tail -> item(key = tail.tailKey) {
            Spacer(Modifier.height(with(density) { tail.gapPx.toDp() }).testTag("Discovery trailing space"))
        } }
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
