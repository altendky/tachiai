package net.fstab.tachiai.feature.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import net.fstab.tachiai.feature.presentation.RemoteButton as Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import net.fstab.tachiai.feature.presentation.RemoteRadioButton as RadioButton
import androidx.compose.material3.Text
import net.fstab.tachiai.feature.presentation.RemoteTextButton as TextButton
import net.fstab.tachiai.feature.presentation.tvFocusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.fstab.tachiai.platform.network.ConnectionSummary
import net.fstab.tachiai.presentation.PrototypeSource
import net.fstab.tachiai.presentation.SourceSetup
import net.fstab.tachiai.presentation.SourceRouteChoice
import net.fstab.tachiai.presentation.SourceRouteMode
import net.fstab.tachiai.presentation.validSourceSetupName

// Only nonsecret metadata enters saved instance state, never a connection configuration.
private val routeSaver = listSaver<SourceRouteChoice, String>(
    save = { listOf(it.mode.name, it.connectionId.orEmpty(), it.connectionName.orEmpty()) },
    restore = { SourceRouteChoice(SourceRouteMode.valueOf(it[0]), it[1].ifEmpty { null }, it[2].ifEmpty { null }) },
)

@Composable
internal fun SourceSetupScreen(source: PrototypeSource, initial: SourceSetup, profiles: List<ConnectionSummary>, busy: Boolean,
    message: String?, onImport: () -> Unit, onSave: (SourceSetup) -> Unit, onBack: () -> Unit) {
    var name by rememberSaveable(source.name) { mutableStateOf(initial.name) }
    var default by rememberSaveable(source.name, stateSaver = routeSaver) { mutableStateOf(initial.defaultRoute) }
    var a by rememberSaveable(source.name, stateSaver = routeSaver) { mutableStateOf(initial.feedA) }
    var b by rememberSaveable(source.name, stateSaver = routeSaver) { mutableStateOf(initial.feedB) }
    var savedName by rememberSaveable(source.name) { mutableStateOf(initial.name) }
    fun saveChanges() { onSave(SourceSetup(savedName, default, a, b)) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().tvFocusGroup(!busy).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsPageHeader("Source setup", onBack)
        Text(source.title)
        SettingsSection("Name")
        OutlinedTextField(name, {
            if (it.length <= 64) {
                name = it
                if (validSourceSetupName(it.trim()) && savedName != it.trim()) {
                    savedName = it.trim()
                    saveChanges()
                }
            }
        }, label = { Text("Source name · max 64 characters") }, singleLine = true, enabled = !busy,
            isError = !validSourceSetupName(name.trim()))
        if (!validSourceSetupName(name.trim())) Text("Enter a name of 1–64 characters. The previous valid name is retained.")
        SettingsSection("Connection", "System network follows Android’s current connection, which may already include a VPN. Saved profiles here are configured only: playback routing from this legacy setup is not implemented.")
        Button(onClick = onImport, enabled = !busy) { Text("Add connection · setup / import") }
        RouteOptions("Source default connection", default, profiles, false, !busy) { if (default != it) { default = it; saveChanges() } }
        SettingsSection("Feed overrides", "Optional feed overrides let two copies of this source use different connections. A/B identifies the feeds, not primary/floating video.")
        RouteOptions("Feed A connection", a, profiles, true, !busy) { if (a != it) { a = it; saveChanges() } }
        RouteOptions("Feed B connection", b, profiles, true, !busy) { if (b != it) { b = it; saveChanges() } }
        message?.let { Text(it) }
        if (busy) Text("Reading settings…")
    }
}

@Composable
private fun RouteOptions(label: String, selected: SourceRouteChoice, profiles: List<ConnectionSummary>, inherit: Boolean,
    enabled: Boolean, onSelect: (SourceRouteChoice) -> Unit) {
    Text(label, style = MaterialTheme.typography.titleMedium)
    val choices = (if (inherit) listOf(SourceRouteChoice.inherit) else emptyList()) + SourceRouteChoice.system +
        profiles.map { SourceRouteChoice(SourceRouteMode.SAVED_CONNECTION, it.id, it.name) }
    if (selected.mode == SourceRouteMode.SAVED_CONNECTION && profiles.none { it.id == selected.connectionId })
        Text("Unavailable saved connection: ${selected.title}. Choose a replacement; it will not fall back to system network.")
    choices.forEach { choice ->
        val checked = selected.mode == choice.mode && selected.connectionId == choice.connectionId
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(checked, { onSelect(choice) }, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "$label: ${choice.title}" })
            Text(choice.title + if (choice.mode == SourceRouteMode.SAVED_CONNECTION) " · configured only" else "")
        }
    }
}
