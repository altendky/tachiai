package net.fstab.tachiai.feature.connections

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
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
    onOpenProvider: ((String) -> Unit)? = null) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = editing != null && !busy) { editing = null }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Providers", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        SettingsHelp("Providers", "Each provider instance has its own name, route and account. Choose streams from an instance for either feed.")
        message?.let { Text(it) }
        if (busy) Text("Reading or saving settings…")
        val instance = instances.singleOrNull { it.id == editing }
        if (instance == null) {
            if (editing != null) Text("Selected provider instance is unavailable. Choose an existing instance.")
            instances.forEach { entry ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("${entry.name} · ${entry.service.title} · ${entry.setup.title}", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { onOpenProvider?.invoke(entry.id) ?: run { editing = entry.id } }, enabled = !busy) { Text("Configure ${entry.name}") }
            }
            onCreate?.let { create -> PrototypeService.entries.forEach { service ->
                Button(onClick = { create(service) }, enabled = !busy && instances.size < MAX_PROVIDER_INSTANCES) {
                    Text("Add ${service.title} instance")
                }
            } }
            Button(onClick = onRoutes, enabled = !busy) { Text("Routes · import / setup") }
            TextButton(onClick = onBack, enabled = !busy) { Text("Back to streams") }
        } else key(instance.id) {
            ProviderRouteEditor(instance, instances, profiles, busy, onRoutes,
                { name, route -> onSave(instance.id, name, route) }, { editing = null }, { twitchLogin(instance.id) })
            onManageStreams?.let { manage -> Button(onClick = { manage(instance.id) }, enabled = !busy) { Text("Manage streams") } }
            if (instance.service in setOf(PrototypeService.TWITCH, PrototypeService.ABEMA)) onCatalogConnection?.let { connect ->
                Button(onClick = { connect(instance.id) }, enabled = !busy) { Text("${instance.service.title} account") }
            }
        }
    }
}

private fun routeKey(route: SourceRouteChoice) = "${route.mode.name}:${route.connectionId.orEmpty()}"

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
    TextButton(onClick = onBack, enabled = !busy) { Text("Back to providers") }
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(painterResource(R.drawable.settings_back), "Back to providers") }
        Column(Modifier.weight(1f)) {
            Text(instance.name, style = MaterialTheme.typography.headlineSmall)
            Text(instance.service.title, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
