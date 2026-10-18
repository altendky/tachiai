package net.fstab.tachiai.feature.connections

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import net.fstab.tachiai.feature.presentation.RemoteButton as Button
import net.fstab.tachiai.feature.presentation.RemoteTextButton as TextButton
import net.fstab.tachiai.feature.presentation.RemoteOutlinedButton as OutlinedButton
import net.fstab.tachiai.feature.presentation.RemoteIconButton as IconButton
import net.fstab.tachiai.feature.presentation.RemoteDropdownMenuItem as DropdownMenuItem
import net.fstab.tachiai.feature.presentation.RemoteRadioButton as RadioButton
import net.fstab.tachiai.feature.presentation.tvFocusGroup
import net.fstab.tachiai.feature.presentation.tvFocusIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import net.fstab.tachiai.R
import net.fstab.tachiai.platform.network.ConnectionSummary
import net.fstab.tachiai.presentation.*

@Composable
internal fun ProvidersScreen(settings: Map<PrototypeService, ProviderSetup>, profiles: List<ConnectionSummary>, busy: Boolean,
    message: String?, onRoutes: () -> Unit, onSave: (PrototypeService, SourceRouteChoice) -> Unit, onBack: () -> Unit,
    twitchLogin: @Composable () -> Unit = {}) {
    ProviderInstancesScreen(defaultProviderInstances(settings), profiles, busy, message, onRoutes,
        { id, _, route -> onSave(settings.keys.single { defaultProviderInstanceId(it) == id }, route) },
        onBack, twitchLogin = { twitchLogin() })
}

@Composable
internal fun ProviderInstancesScreen(instances: List<ProviderInstance>, profiles: List<ConnectionSummary>, busy: Boolean,
    message: String?, onRoutes: () -> Unit, onSave: (String, String?, SourceRouteChoice) -> Unit, onBack: () -> Unit,
    onCreate: ((PrototypeService) -> Unit)? = null, twitchLogin: @Composable (String) -> Unit = {},
    onManageStreams: ((String) -> Unit)? = null, onCatalogConnection: ((String) -> Unit)? = null,
    onOpenProvider: ((String) -> Unit)? = null, onReorder: ((List<String>) -> Job?)? = null,
    onRemove: ((String) -> Unit)? = null) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val ids = instances.map { it.id }
    val latestIds by rememberUpdatedState(ids)
    val canDrag by rememberUpdatedState(!busy && editing == null && onReorder != null && ids.size > 1)
    val reorder by rememberUpdatedState(onReorder)
    val drag = remember(list, density.density) {
        ConfiguredStreamDrag(list, scope, { latestIds }, { canDrag }, { reorder?.invoke(it) },
            with(density) { 64.dp.toPx() }, with(density) { 720.dp.toPx() }, keyPrefix = "provider:")
    }
    DisposableEffect(drag) { onDispose { drag.cancel() } }
    LaunchedEffect(ids, busy, editing) { drag.cancelGestureIfStale() }
    val displayed = drag.order?.takeIf { it.size == ids.size && it.toSet() == ids.toSet() }
        ?.map { id -> instances.single { it.id == id } } ?: instances
    val actionsEnabled = !busy && !drag.busy
    BackHandler(enabled = editing != null && !busy) { editing = null }
    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().tvFocusGroup(!busy, ids).testTag("Providers list").onGloballyPositioned { drag.viewport = it },
        state = list, contentPadding = PaddingValues(16.dp)) {
        item(key = "heading") {
        SettingsPageHeader("Providers", if (editing == null) onBack else ({ editing = null }),
            backDescription = if (editing == null) "Back to streams" else "Back to providers",
            help = "Tap a provider to configure it. Drag its handle to change the saved order, or use the handle's menu to move it earlier or later. The trailing menu removes a provider from this setup after confirmation. Shared routes and other providers are kept. Each provider instance has its own name, routes and account.",
            backEnabled = !busy)
        Spacer(Modifier.height(12.dp))
        }
        message?.let { item(key = "message") { Text(it) } }
        val instance = instances.singleOrNull { it.id == editing }
        if (instance == null) {
            if (editing != null) item { Text("Selected provider instance is unavailable. Choose an existing instance.") }
            if (instances.isEmpty()) item { Text("No providers configured.", Modifier.padding(vertical = 12.dp)) }
            items(displayed, key = { "provider:${it.id}" }) { entry ->
                val index = displayed.indexOf(entry)
                fun move(delta: Int) {
                    val from = latestIds.indexOf(entry.id)
                    val to = from + delta
                    if (actionsEnabled && from in latestIds.indices && to in latestIds.indices)
                        reorder?.invoke(latestIds.toMutableList().apply { add(to, removeAt(from)) })
                }
                ProviderInstanceRow(entry, index, displayed.size, drag, canDrag, actionsEnabled,
                    onOpen = { onOpenProvider?.invoke(entry.id) ?: run { editing = entry.id } },
                    onMove = ::move, onRemove = onRemove?.let { { removing = entry.id } })
            }
            onCreate?.let { create -> PrototypeService.entries.forEach { service ->
                item(key = "create:${service.name}") {
                Button(onClick = { create(service) }, enabled = actionsEnabled && instances.size < MAX_PROVIDER_INSTANCES,
                    modifier = Modifier.padding(top = 12.dp)) {
                    Text("Add ${service.title} instance")
                }
                }
            } }
            item(key = "routes") {
                Button(onClick = onRoutes, enabled = actionsEnabled, modifier = Modifier.padding(top = 12.dp)) { Text("Routes · import / setup") }
            }
        } else item(key = "editor:${instance.id}") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ProviderRouteEditor(instance, instances, profiles, busy, onRoutes,
                { name, route -> onSave(instance.id, name, route) }, { editing = null }, { twitchLogin(instance.id) })
            onManageStreams?.let { manage -> Button(onClick = { manage(instance.id) }, enabled = !busy) { Text("Manage streams") } }
            if (instance.service in setOf(PrototypeService.TWITCH, PrototypeService.ABEMA)) onCatalogConnection?.let { connect ->
                Button(onClick = { connect(instance.id) }, enabled = !busy) { Text("${instance.service.title} account") }
            }
            }
        }
    }
    instances.singleOrNull { it.id == removing }?.let { entry ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Delete ${entry.name}?") },
            text = { Text("Remove this provider and its configured streams from this setup? Shared routes and other providers are kept.") },
            confirmButton = { TextButton(onClick = { removing = null; onRemove?.invoke(entry.id) }, enabled = actionsEnabled) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } })
    }
}

@Composable
private fun ProviderInstanceRow(entry: ProviderInstance, index: Int, count: Int, drag: ConfiguredStreamDrag,
    canDrag: Boolean, enabled: Boolean, onOpen: () -> Unit, onMove: (Int) -> Unit, onRemove: (() -> Unit)?) {
    var handleCoordinates by remember(entry.id) { mutableStateOf<LayoutCoordinates?>(null) }
    var moveMenu by remember(entry.id) { mutableStateOf(false) }
    var menu by remember(entry.id) { mutableStateOf(false) }
    val pinnable = LocalPinnableContainer.current
    Box(Modifier.zIndex(if (drag.draggedId == entry.id) 1f else 0f).onGloballyPositioned { drag.positioned(entry.id, it) }) {
        Column(Modifier.graphicsLayer { translationY = drag.translation(entry.id) }) {
            Surface(color = if (drag.draggedId == entry.id) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("Provider row ${entry.id}")
                    .tvFocusIndicator().clickable(enabled = enabled, role = Role.Button, onClickLabel = "Configure ${entry.name}", onClick = onOpen)
                    .semantics {
                        customActions = if (!enabled || !canDrag) emptyList() else buildList {
                            if (index > 0) add(CustomAccessibilityAction("Move earlier") { onMove(-1); true })
                            if (index < count - 1) add(CustomAccessibilityAction("Move later") { onMove(1); true })
                        }
                    }, verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        IconButton(onClick = { moveMenu = true }, enabled = canDrag && enabled,
                            modifier = Modifier.size(48.dp).onGloballyPositioned { handleCoordinates = it }
                                .semantics { contentDescription = "Reorder provider: ${entry.name}"; stateDescription = "${index + 1} of $count" }
                                .pointerInput(entry.id, drag, canDrag) {
                                    if (canDrag) detectDragGestures(onDragStart = { point ->
                                        handleCoordinates?.takeIf { it.isAttached }?.let { drag.start(entry.id, it.localToRoot(point).y, pinnable) }
                                    }, onDragEnd = { if (drag.draggedId == entry.id) drag.drop() },
                                        onDragCancel = { if (drag.draggedId == entry.id) drag.cancel() }) { change, amount ->
                                        if (drag.draggedId == entry.id) {
                                            change.consume()
                                            handleCoordinates?.takeIf { it.isAttached }?.let { drag.pointerAt(it.localToRoot(change.position).y, amount.y) }
                                        }
                                    }
                                }) { Icon(painterResource(R.drawable.stream_drag), null) }
                        DropdownMenu(expanded = moveMenu && enabled, onDismissRequest = { moveMenu = false }) {
                            DropdownMenuItem(text = { Text("Move earlier") }, enabled = index > 0, onClick = { moveMenu = false; onMove(-1) })
                            DropdownMenuItem(text = { Text("Move later") }, enabled = index < count - 1, onClick = { moveMenu = false; onMove(1) })
                        }
                    }
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(providerDisplayNames(entry).inline, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(entry.setup.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = enabled && onRemove != null,
                            modifier = Modifier.size(48.dp).semantics { contentDescription = "Provider actions: ${entry.name}" }) {
                            Text("⋮", Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.titleLarge)
                        }
                        DropdownMenu(expanded = menu && enabled, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Delete…") }, onClick = { menu = false; onRemove?.invoke() })
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

private fun routeKey(route: SourceRouteChoice) = "${route.mode.name}:${route.connectionId.orEmpty()}"

// This bounded, non-lazy list stays in the provider header. A gesture previews
// an ordering; only dropping a still-current pool commits a complete snapshot.
@Stable
private class ProviderRouteDrag(
    private val current: () -> List<SourceRouteChoice>,
    private val enabled: () -> Boolean,
    private val change: (List<SourceRouteChoice>) -> Unit,
) {
    val centers = mutableMapOf<String, Float>()
    var preview by mutableStateOf<List<SourceRouteChoice>?>(null)
        private set
    var dragged by mutableStateOf<String?>(null)
        private set
    private var original = emptyList<SourceRouteChoice>()
    private var anchor = 0f
    private var firstUpdate = false

    fun start(key: String, pointerY: Float) {
        if (!enabled() || dragged != null) return
        val center = centers[key] ?: return
        original = current().toList()
        if (original.none { routeKey(it) == key }) return
        preview = original; dragged = key; anchor = center - pointerY; firstUpdate = true
    }

    fun point(pointerY: Float, amountY: Float) {
        val key = dragged ?: return
        if (!enabled() || current() != original) { cancel(); return }
        if (firstUpdate) { anchor += amountY; firstUpdate = false }
        val center = pointerY + anchor
        val routes = preview ?: return
        val from = routes.indexOfFirst { routeKey(it) == key }
        val target = routes.indices.filter { index ->
            val other = centers[routeKey(routes[index])] ?: return@filter false
            index > from && center > other || index < from && center < other
        }.minByOrNull { kotlin.math.abs(center - checkNotNull(centers[routeKey(routes[it])])) } ?: return
        preview = routes.toMutableList().apply { add(target, removeAt(from)) }
    }

    fun drop() {
        val proposed = preview
        val accepted = dragged != null && enabled() && current() == original && proposed != original
        cancel()
        if (accepted && proposed != null) change(proposed)
    }

    fun cancel() { preview = null; dragged = null; original = emptyList() }
    fun cancelIfStale() { if (preview != null && (!enabled() || current() != original)) cancel() }
}

@Composable
internal fun ProviderRoutePoolSettings(instance: ProviderInstance, profiles: List<ConnectionSummary>,
    selectedRoutes: List<SourceRouteChoice>, onRoutesChanged: (List<SourceRouteChoice>) -> Unit,
    onRoutes: () -> Unit, enabled: Boolean = true, startupStatus: String? = null) {
    SettingsSection("Connection", "Routes are tried in this order during startup. Only confirmed transport availability failures can try another route; authentication, entitlement, DRM, TLS and unknown failures stop. The successful route stays selected for this session. Changes apply to this provider instance’s streams and account requests. System network is used only when explicitly listed and includes any external VPN. Missing profiles require repair. Earlier routes awaiting review are not alternatives." +
        if (selectedRoutes.isEmpty() && instance.setup.previousRoutes.isNotEmpty())
            " Earlier routes requiring review: ${instance.setup.previousRoutes.joinToString { it.title }}." else "", status = startupStatus)
    val current by rememberUpdatedState(selectedRoutes)
    val active by rememberUpdatedState(enabled)
    val changed by rememberUpdatedState(onRoutesChanged)
    val drag = remember(instance.id) { ProviderRouteDrag({ current }, { active }, { changed(it) }) }
    LaunchedEffect(selectedRoutes, enabled) { drag.cancelIfStale() }
    // An external update must never commit a preview derived from an older pool.
    DisposableEffect(drag) { onDispose { drag.cancel() } }
    val displayed = drag.preview ?: selectedRoutes
    val choices = (listOf(SourceRouteChoice.system) + profiles.map {
        SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, it.id, it.name)
    }).filter { candidate -> selectedRoutes.none { routeKey(it) == routeKey(candidate) } }
    var expanded by remember(instance.id) { mutableStateOf(false) }
    if (selectedRoutes.isEmpty()) Text("Choose a route.")
    displayed.forEachIndexed { index, route -> key(routeKey(route)) {
        val key = routeKey(route)
        val profile = profiles.firstOrNull { it.id == route.connectionId }
        val title = if (route.mode == SourceRouteMode.SAVED_CONNECTION) profile?.name ?: route.title else route.title
        val missing = route.mode == SourceRouteMode.SAVED_CONNECTION && profile == null
        var handleCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
        var moveMenu by remember { mutableStateOf(false) }
        fun move(delta: Int) {
            val from = current.indexOfFirst { routeKey(it) == key }
            val to = from + delta
            if (active && drag.dragged == null && from in current.indices && to in current.indices)
                changed(current.toMutableList().apply { add(to, removeAt(from)) })
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .onGloballyPositioned { coordinates ->
                drag.centers[key] = coordinates.localToRoot(androidx.compose.ui.geometry.Offset(0f, coordinates.size.height / 2f)).y
            }.semantics {
                customActions = if (!enabled || drag.dragged != null) emptyList() else buildList {
                    if (index > 0) add(CustomAccessibilityAction("Move earlier") { move(-1); true })
                    if (index < displayed.lastIndex) add(CustomAccessibilityAction("Move later") { move(1); true })
                }
            }, verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { moveMenu = true }, enabled = enabled && displayed.size > 1,
                    modifier = Modifier.size(48.dp).onGloballyPositioned { handleCoordinates = it }
                        .semantics { contentDescription = "Reorder route: $title"; stateDescription = "${index + 1} of ${displayed.size}" }
                        .pointerInput(key, drag, enabled) {
                            if (enabled) detectDragGestures(onDragStart = { point ->
                                handleCoordinates?.takeIf { it.isAttached }?.let { drag.start(key, it.localToRoot(point).y) }
                            }, onDragEnd = { drag.drop() }, onDragCancel = { drag.cancel() }) { event, amount ->
                                if (drag.dragged == key) {
                                    event.consume()
                                    handleCoordinates?.takeIf { it.isAttached }?.let { drag.point(it.localToRoot(event.position).y, amount.y) }
                                }
                            }
                        }) { Icon(painterResource(R.drawable.stream_drag), contentDescription = null) }
                DropdownMenu(expanded = moveMenu && enabled, onDismissRequest = { moveMenu = false }) {
                    DropdownMenuItem(text = { Text("Move earlier") }, enabled = index > 0,
                        onClick = { moveMenu = false; move(-1) })
                    DropdownMenuItem(text = { Text("Move later") }, enabled = index < displayed.lastIndex,
                        onClick = { moveMenu = false; move(1) })
                }
            }
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text("${index + 1}. $title", maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (missing) Text("Profile unavailable · remove or restore it", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            IconButton(onClick = { changed(current.filterNot { routeKey(it) == key }) }, enabled = enabled && drag.dragged == null) {
                Icon(painterResource(R.drawable.stream_remove), "Remove route: $title")
            }
        }
    } }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled && drag.dragged == null,
            modifier = Modifier.semantics { contentDescription = "Add ${instance.service.title} route" }) {
            Text("Add route")
            Icon(painterResource(R.drawable.settings_expand), contentDescription = null)
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            choices.forEach { route -> DropdownMenuItem(text = { Text(route.title) },
                enabled = selectedRoutes.size < MAX_PROVIDER_ROUTES,
                onClick = { expanded = false; changed(current + route) },
                modifier = Modifier.semantics { contentDescription = "Add route: ${route.title}" }) }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Setup / import routes") }, onClick = { expanded = false; onRoutes() })
        }
    }
}

@Composable
private fun ProviderRouteEditor(instance: ProviderInstance, instances: List<ProviderInstance>, profiles: List<ConnectionSummary>, busy: Boolean,
    onRoutes: () -> Unit, onSave: (String?, SourceRouteChoice) -> Unit, onBack: () -> Unit, twitchLogin: @Composable () -> Unit) {
    val initial = instance.setup
    var customName by rememberSaveable { mutableStateOf(instance.customName.orEmpty()) }
    val name = customName.trim().ifEmpty { null }
    val effectiveName = name ?: instance.defaultName
    val validName = validSourceSetupName(effectiveName) && instances.none {
        it.id != instance.id && it.service == instance.service && sameProviderInstanceName(it.name, effectiveName)
    }
    var selectedMode by rememberSaveable { mutableStateOf(initial.route?.mode?.name) }
    var selectedId by rememberSaveable { mutableStateOf(initial.route?.connectionId) }
    var selectedName by rememberSaveable { mutableStateOf(initial.route?.connectionName) }
    val selected = selectedMode?.let { SourceRouteChoice(SourceRouteMode.valueOf(it), selectedId, selectedName) }
    ProviderSettingsFields(instance, profiles, customName, selected, validName,
        { value ->
            customName = value
            val candidate = value.trim().ifEmpty { null }
            val effective = candidate ?: instance.defaultName
            if (validSourceSetupName(effective) && instances.none { it.id != instance.id && it.service == instance.service && sameProviderInstanceName(it.name, effective) })
                selected?.let { onSave(candidate, it) }
        },
        { route ->
            selectedMode = route.mode.name; selectedId = route.connectionId; selectedName = route.connectionName
            onSave(if (validName) name else instance.customName, route)
        }, onRoutes, !busy)
    if (instance.service == PrototypeService.TWITCH) twitchLogin()
}

@Composable
internal fun ProviderSettingsFields(instance: ProviderInstance, profiles: List<ConnectionSummary>, nameDraft: String,
    selectedRoute: SourceRouteChoice?, validName: Boolean, onNameChanged: (String) -> Unit,
    onRouteChanged: (SourceRouteChoice) -> Unit, onRoutes: () -> Unit, enabled: Boolean = true) {
    SettingsSection("General")
    OutlinedTextField(nameDraft, onNameChanged, enabled = enabled, singleLine = true,
        label = { Text("Provider instance name") }, placeholder = { Text(instance.defaultName) }, isError = !validName)
    if (!validName) Text("Use a unique name, up to 64 printable characters.")
    ProviderConnectionSettings(instance, profiles, selectedRoute, onRouteChanged, onRoutes, enabled, compact = false)
}

@Composable
internal fun ProviderDetailHeading(instance: ProviderInstance, nameDraft: String, validName: Boolean,
    onNameChanged: (String) -> Unit, onBack: () -> Unit, enabled: Boolean = true,
    saving: Boolean = false, error: String? = null) {
    var editing by rememberSaveable(instance.id) { mutableStateOf(false) }
    val names = providerDisplayNames(instance)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(painterResource(R.drawable.navigation_back), "Back to providers") }
        Column(Modifier.weight(1f)) {
            Text(names.primary, style = MaterialTheme.typography.headlineSmall)
            names.provider?.let { Text(it, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        IconButton(onClick = { editing = true }, enabled = enabled) {
            Icon(painterResource(R.drawable.settings_edit), "Edit provider name")
        }
    }
    if (!validName) Text("Edit name to fix an invalid name.", color = MaterialTheme.colorScheme.error)
    if (editing) AlertDialog(onDismissRequest = { editing = false },
        title = { Text("Edit provider name") }, text = {
            OutlinedTextField(nameDraft, onNameChanged, enabled = enabled, singleLine = true,
                label = { Text("Name") }, placeholder = { Text(instance.defaultName) }, isError = !validName,
                supportingText = { Text(when {
                    !validName -> "Use a unique name, up to 64 printable characters."
                    error != null -> "$error Close to retry."
                    saving -> "Saving changes…"
                    else -> "Changes save automatically."
                }) })
        }, confirmButton = { TextButton(onClick = { editing = false }) { Text("Done") } })
}

@Composable
internal fun ProviderConnectionSettings(instance: ProviderInstance, profiles: List<ConnectionSummary>,
    selectedRoute: SourceRouteChoice?, onRouteChanged: (SourceRouteChoice) -> Unit,
    onRoutes: () -> Unit, enabled: Boolean = true, compact: Boolean = true) {
    val provider = instance.service
    SettingsSection("Connection", "This route is used for this provider instance’s streams and account requests. System network includes any external VPN. Saved routes never silently fall back to System network. Simultaneous ABEMA feeds currently share one WebView route.")
    val choices = listOf(SourceRouteChoice.system) + profiles.map { SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, it.id, it.name) }
    val choice = choices.firstOrNull { routeKey(it) == selectedRoute?.let(::routeKey) }
    if (selectedRoute == null) {
        Text("Choose a route to resolve earlier settings.")
        instance.setup.previousRoutes.forEach { Text("Earlier route: ${it.title}") }
    }
    if (selectedRoute != null && choice == null) Text("Selected saved route is unavailable. Choose a replacement; no system fallback occurs.")
    if (compact) {
        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Choose ${provider.title} route" },
                shape = MaterialTheme.shapes.small) {
                Text(choice?.title ?: selectedRoute?.title ?: "Choose route", Modifier.weight(1f))
                Icon(painterResource(R.drawable.settings_expand), contentDescription = null)
            }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                choices.forEach { route ->
                    DropdownMenuItem(text = { Text(route.title) }, leadingIcon = {
                        RadioButton(selected = choice == route, onClick = null)
                    }, onClick = {
                        expanded = false; onRouteChanged(route)
                    }, modifier = Modifier.semantics { selected = choice == route
                        contentDescription = "${provider.title} route: ${route.title}" })
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Add route · setup / import") }, onClick = {
                    expanded = false; onRoutes()
                })
            }
        }
    } else {
    choices.forEach { route ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(choice == route, { onRouteChanged(route) }, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "${provider.title} route: ${route.title}" })
            Text(route.title)
        }
    }
    TextButton(onClick = onRoutes, enabled = enabled) { Text("Add route · setup / import") }
    }
}
