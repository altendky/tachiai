package net.fstab.tachiai.feature.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import net.fstab.tachiai.platform.network.ConnectionProfile
import net.fstab.tachiai.platform.network.ConnectionSummary
import net.fstab.tachiai.platform.network.validConnectionName

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
        Text("Routes", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text("Import and save WireGuard or HTTP CONNECT route configurations, then assign one in Providers. Saving does not connect or change your system VPN; the native viewer opens the selected route at playback.")
        message?.let { Text(it) }
        if (busy) Text("Processing…")
        if (draft == null) {
            Button(onClick = onProton, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Set up Proton") }
            Button(onClick = onWindscribe, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Set up Windscribe") }
            Text("Proton export", style = MaterialTheme.typography.titleMedium)
            Text("1. Sign in on Proton’s site in your browser.\n2. Downloads → WireGuard configuration: select Android and your desired Japan server, then Create and Download.\n3. Open the downloaded file with Tachiai, or share the file to Tachiai—not the web page.\n4. Preview and save here. No filename renaming is needed.")
            Text("Proton export is a one-server profile, not access to its app’s automatic server selection. Browser handoff varies; use Import file if Open/Share is unavailable.",
                style = MaterialTheme.typography.bodySmall)
            Text("Windscribe export", style = MaterialTheme.typography.titleMedium)
            Text("Paid Windscribe account required: Pro includes all locations; Build-A-Plan includes your paid locations.",
                style = MaterialTheme.typography.bodySmall)
            Text("1. Sign in on Windscribe’s site in your browser.\n2. My Account → Config Generator → WireGuard: choose a location, a port (443 if unsure), and a new key pair for this device.\n3. Download Config, then open or share the downloaded file with Tachiai.\n4. Preview and save here. Use Import file if Open/Share is unavailable.")
            Text("Choose WireGuard, not OpenVPN or IKEv2. This imports one configuration; it does not control Windscribe’s app or automatic server selection.",
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = onFile, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Import file (fallback)") }
            TextButton(onClick = { editor = !editor; input = "" }, enabled = !busy) { Text(if (editor) "Close manual entry" else "Paste / enter manually") }
            if (editor) {
                OutlinedTextField(value = input, onValueChange = { if (it.length <= 8192) input = it },
                    label = { Text("WireGuard configuration or http:// proxy URL") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Text("Input is hidden because configurations can contain private keys or proxy credentials.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { val value = input; input = ""; editor = false; onParse(value) }, enabled = !busy && input.isNotBlank()) { Text("Preview import") }
            }
        } else {
            Text("Import preview", style = MaterialTheme.typography.titleLarge)
            Text("${draft.kind.title}\nEndpoint: ${draft.endpoint}\nPrivate keys and credentials: hidden")
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Route name") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Saved locally with encryption, excluded from backups. Not connected or tested. The original downloaded file remains outside Tachiai; delete it yourself after saving if no longer needed.",
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = { onSave(name.trim()) }, enabled = !busy && validConnectionName(name.trim())) { Text("Save route") }
            TextButton(onClick = onDiscard, enabled = !busy) { Text("Discard import") }
        }
        Text("Saved routes", style = MaterialTheme.typography.titleLarge)
        if (profiles.isEmpty()) Text("No saved routes.")
        profiles.forEach { profile ->
            Text("${profile.name} · ${profile.kind.title}\n${profile.endpoint}\nSaved only · not connected")
            TextButton(onClick = { removal = profile }, enabled = !busy) { Text("Delete ${profile.name}") }
        }
        TextButton(onClick = onClose, enabled = !busy) { Text("Back") }
    }
    removal?.let { profile ->
        AlertDialog(onDismissRequest = { removal = null }, title = { Text("Delete route?") },
            text = { Text("Delete ${profile.name} and its locally stored configuration? This does not revoke it at your VPN or proxy provider or delete your downloaded file.") },
            confirmButton = { TextButton(onClick = { removal = null; onRemove(profile.id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { removal = null }) { Text("Cancel") } })
    }
}
