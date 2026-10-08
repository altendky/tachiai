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
    message: String?, onRoutes: () -> Unit, onSave: (PrototypeService, SourceRouteChoice) -> Unit, onBack: () -> Unit) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = editing != null && !busy) { editing = null }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Providers", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text("A provider supplies streams. Each selected stream creates an independent feed; both feeds inherit their provider’s route.")
        message?.let { Text(it) }
        if (busy) Text("Reading or saving settings…")
        val provider = editing?.let(PrototypeService::valueOf)
        if (provider == null) {
            PrototypeService.entries.forEach { entry ->
                val setup = checkNotNull(settings[entry])
                Text("${entry.title} · ${setup.title}", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { editing = entry.name }, enabled = !busy) { Text("Configure ${entry.title}") }
            }
            Button(onClick = onRoutes, enabled = !busy) { Text("Routes · import / setup") }
            TextButton(onClick = onBack, enabled = !busy) { Text("Back to streams") }
        } else key(provider) {
            ProviderRouteEditor(provider, checkNotNull(settings[provider]), profiles, busy, onRoutes,
                { onSave(provider, it) }, { editing = null })
        }
    }
}

private fun routeKey(route: SourceRouteChoice) = "${route.mode.name}:${route.connectionId.orEmpty()}"

@Composable
private fun ProviderRouteEditor(provider: PrototypeService, initial: ProviderSetup, profiles: List<ConnectionSummary>, busy: Boolean,
    onRoutes: () -> Unit, onSave: (SourceRouteChoice) -> Unit, onBack: () -> Unit) {
    var selected by rememberSaveable { mutableStateOf(initial.route?.let(::routeKey)) }
    val choices = listOf(SourceRouteChoice.system) + profiles.map { SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, it.id, it.name) }
    val choice = choices.firstOrNull { routeKey(it) == selected }
    Text("${provider.title} setup", style = MaterialTheme.typography.titleLarge)
    Text("Default route for every ${provider.title} live or replay stream. This choice applies to both feeds, regardless of primary/floating position.")
    if (initial.route == null) {
        Text("Earlier stream defaults or feed overrides need review. Choose one provider route explicitly; the old settings remain stored.")
        initial.previousRoutes.forEach { Text("Earlier route: ${it.title}") }
    }
    if (selected != null && choice == null) Text("Selected saved route is unavailable. Choose a replacement; no system fallback occurs.")
    Text("System network inherits Android’s current network, including an external VPN. WireGuard and HTTP CONNECT routes connect when you open the native viewer. A failed route never falls back to System network.")
    Text("Provider default route", style = MaterialTheme.typography.titleMedium)
    choices.forEach { route ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected == routeKey(route), { selected = routeKey(route) }, enabled = !busy,
                modifier = Modifier.semantics { contentDescription = "${provider.title} route: ${route.title}" })
            Text(route.title + if (route.mode == SourceRouteMode.SAVED_CONNECTION) " · connects at playback" else "")
        }
    }
    Button(onClick = onRoutes, enabled = !busy) { Text("Add route · Proton / import") }
    Button(onClick = { choice?.let(onSave) }, enabled = !busy && choice != null) { Text("Save ${provider.title} setup") }
    TextButton(onClick = onBack, enabled = !busy) { Text("Back to providers") }
}
