package net.fstab.tachiai.feature.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import net.fstab.tachiai.platform.network.ConnectionProfile
import net.fstab.tachiai.platform.network.ConnectionSummary
import net.fstab.tachiai.platform.network.validConnectionName
import net.fstab.tachiai.platform.network.routeProtocols
import net.fstab.tachiai.platform.network.CONNECTION_IMPORT_LIMIT

@Composable
internal fun ConnectionProfilesScreen(profiles: List<ConnectionSummary>, draft: ConnectionProfile?, busy: Boolean,
    message: String?, onProton: () -> Unit, onWindscribe: () -> Unit, onFile: () -> Unit, onParse: (String) -> Unit,
    onSave: (String) -> Unit, onDiscard: () -> Unit, onRemove: (String) -> Unit, onClose: () -> Unit) {
    var editor by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") } // Never save configuration in Bundle/saveable state.
    var name by remember(draft) { mutableStateOf("") }
    var removal by remember { mutableStateOf<ConnectionSummary?>(null) }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsPageHeader("Routes", onClose, help = "Import and save ${routeProtocols.titles} route configurations, then assign one in Providers. Saving does not connect or change your system VPN; the native viewer opens the selected route at playback.", backEnabled = !busy)
        message?.let { Text(it) }
        if (busy) Text("Processing…")
        if (draft == null) {
            SettingsSection("Add route")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onProton, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Set up Proton") }
            SettingsHelp("Proton setup", "1. Sign in on Proton’s site in your browser.\n2. Downloads → WireGuard configuration: select Android and your desired server, then Create and Download.\n3. Open or share the downloaded file with Tachiai—not the web page.\n4. Preview and save here. No filename renaming is needed.\n\nAn export is a one-server profile, not access to the app’s automatic server selection. Use Import file if Open/Share is unavailable.")
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onWindscribe, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Set up Windscribe") }
            SettingsHelp("Windscribe setup", "Paid Windscribe account required: Pro includes all locations; Build-A-Plan includes your paid locations.\n\n1. Sign in on Windscribe’s site in your browser.\n2. My Account → Config Generator → WireGuard: choose a location, a port (443 if unsure), and a new key pair for this device.\n3. Download Config, then open or share the downloaded file with Tachiai.\n4. Preview and save here. Use Import file if Open/Share is unavailable.\n\nChoose WireGuard. Password-based OpenVPN exports and IKEv2 are unsupported. This imports one configuration; it does not control Windscribe’s app or automatic server selection.")
            }
            Button(onClick = onFile, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Import file (fallback)") }
            TextButton(onClick = { editor = !editor; input = "" }, enabled = !busy) { Text(if (editor) "Close manual entry" else "Paste / enter manually") }
            if (editor) {
                SettingsSection("Manual import", "Input is hidden because configurations can contain private keys or proxy credentials.")
                OutlinedTextField(value = input, onValueChange = { if (it.toByteArray(Charsets.UTF_8).size <= CONNECTION_IMPORT_LIMIT) input = it },
                    label = { Text(routeProtocols.formatLabel) },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Button(onClick = { val value = input; input = ""; editor = false; onParse(value) }, enabled = !busy && input.isNotBlank()) { Text("Preview import") }
            }
        } else {
            SettingsSection("Import preview", actions = { SettingsHelp("Saving a route", "Saved locally with encryption, excluded from backups. The original downloaded file remains outside Tachiai; delete it yourself after saving if no longer needed.") })
            Text("${draft.kind.title}\nEndpoint: ${draft.endpoint}\nPrivate keys and credentials: hidden")
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Route name") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Not connected or tested.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { onSave(name.trim()) }, enabled = !busy && validConnectionName(name.trim())) { Text("Save route") }
            TextButton(onClick = onDiscard, enabled = !busy) { Text("Discard import") }
        }
        SettingsSection("Saved routes")
        if (profiles.isEmpty()) Text("No saved routes.")
        profiles.forEach { profile ->
            Text("${profile.name} · ${profile.kind.title}\n${profile.endpoint}\nSaved only · not connected")
            TextButton(onClick = { removal = profile }, enabled = !busy) { Text("Delete ${profile.name}") }
        }
    }
    removal?.let { profile ->
        AlertDialog(onDismissRequest = { removal = null }, title = { Text("Delete route?") },
            text = { Text("Delete ${profile.name} and its locally stored configuration? This does not revoke it at your VPN or proxy provider or delete your downloaded file.") },
            confirmButton = { TextButton(onClick = { removal = null; onRemove(profile.id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { removal = null }) { Text("Cancel") } })
    }
}
