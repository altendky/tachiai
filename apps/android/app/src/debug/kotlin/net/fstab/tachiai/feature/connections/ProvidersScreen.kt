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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
    onManageStreams: ((String) -> Unit)? = null, onCatalogConnection: ((String) -> Unit)? = null) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = editing != null && !busy) { editing = null }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Providers", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text("Each provider instance has its own name, route and supported saved login. Assign a stream under an instance to either feed; each feed has an independent playback session.")
        message?.let { Text(it) }
        if (busy) Text("Reading or saving settings…")
        val instance = instances.singleOrNull { it.id == editing }
        if (instance == null) {
            if (editing != null) Text("Selected provider instance is unavailable. Choose an existing instance.")
            instances.forEach { entry ->
                Text("${entry.name} · ${entry.service.title} · ${entry.setup.title}", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { editing = entry.id }, enabled = !busy) { Text("Configure ${entry.name}") }
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
            if (instance.service == PrototypeService.TWITCH) onCatalogConnection?.let { connect ->
                Button(onClick = { connect(instance.id) }, enabled = !busy) { Text("Catalog account") }
            }
        }
    }
}

private fun routeKey(route: SourceRouteChoice) = "${route.mode.name}:${route.connectionId.orEmpty()}"

@Composable
private fun ProviderRouteEditor(instance: ProviderInstance, instances: List<ProviderInstance>, profiles: List<ConnectionSummary>, busy: Boolean,
    onRoutes: () -> Unit, onSave: (String?, SourceRouteChoice) -> Unit, onBack: () -> Unit, twitchLogin: @Composable () -> Unit) {
    val provider = instance.service
    val initial = instance.setup
    var customName by rememberSaveable { mutableStateOf(instance.customName.orEmpty()) }
    val name = customName.trim().ifEmpty { null }
    val effectiveName = name ?: instance.defaultName
    val validName = validSourceSetupName(effectiveName) && instances.none {
        it.id != instance.id && it.service == instance.service && sameProviderInstanceName(it.name, effectiveName)
    }
    var selected by rememberSaveable { mutableStateOf(initial.route?.let(::routeKey)) }
    val choices = listOf(SourceRouteChoice.system) + profiles.map { SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, it.id, it.name) }
    val choice = choices.firstOrNull { routeKey(it) == selected }
    Text("${instance.name} setup", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(customName, { customName = it }, enabled = !busy, singleLine = true,
        label = { Text("Provider instance name") }, supportingText = { Text("Leave blank to use ${instance.defaultName}.") })
    if (!validName) Text("Use a unique name for this provider, with 1–64 printable characters.")
    if (provider == PrototypeService.TWITCH) twitchLogin()
    Text("Default route for this instance’s live or replay streams, regardless of primary/floating position. Other ${provider.title} instances keep their own settings.")
    if (provider == PrototypeService.ABEMA) Text("ABEMA uses anonymous guest playback. Simultaneous ABEMA feeds must use the same route because its WebView proxy is shared.")
    if (initial.route == null) {
        Text("Earlier stream defaults or feed overrides need review. Choose one provider route explicitly; the old settings remain stored.")
        initial.previousRoutes.forEach { Text("Earlier route: ${it.title}") }
    }
    if (selected != null && choice == null) Text("Selected saved route is unavailable. Choose a replacement; no system fallback occurs.")
    Text("System network inherits Android’s current network, including an external VPN. Saved routes connect when you open the native viewer. A failed route never falls back to System network.")
    Text("Provider default route", style = MaterialTheme.typography.titleMedium)
    choices.forEach { route ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected == routeKey(route), { selected = routeKey(route) }, enabled = !busy,
                modifier = Modifier.semantics { contentDescription = "${provider.title} route: ${route.title}" })
            Text(route.title + if (route.mode == SourceRouteMode.SAVED_CONNECTION) " · connects at playback" else "")
        }
    }
    Button(onClick = onRoutes, enabled = !busy) { Text("Add route · setup / import") }
    Button(onClick = { choice?.let { onSave(name, it) } }, enabled = !busy && choice != null && validName) { Text("Save ${instance.name} setup") }
    TextButton(onClick = onBack, enabled = !busy) { Text("Back to providers") }
}
