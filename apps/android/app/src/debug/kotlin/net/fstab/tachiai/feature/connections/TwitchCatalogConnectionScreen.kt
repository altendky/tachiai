package net.fstab.tachiai.feature.connections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.fstab.tachiai.provider.twitch.DeviceAuthPhase
import net.fstab.tachiai.provider.twitch.catalog.*

@Composable
internal fun TwitchCatalogConnectionScreen(state: TwitchCatalogConnectionState, onConnect: () -> Unit,
    onValidate: () -> Unit, onForget: () -> Unit, onCancel: () -> Unit, onBrowser: (String) -> Unit,
    onBack: () -> Unit, onRetryOpen: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Twitch catalog account", style = MaterialTheme.typography.headlineSmall)
        state.name?.let { Text("Provider instance: $it") }
        state.routeTitle?.let { Text("Saved request route: $it") }
        Text("Connect this instance using Tachiai’s application with permission to read the channels you follow. Catalog access is separate from the experimental playback login and other provider instances.")
        Text("Tachiai’s authorization requests use the saved route. The external consent browser uses its own network and login. Return here after approving; polling pauses while you are away and the original code expiry still applies.")
        Text(state.status)
        Button(onConnect, enabled = state.ready && !state.busy) {
            Text(if (state.hasSavedGrant) "Reconnect catalog account" else "Connect catalog account")
        }
        Button(onValidate, enabled = state.ready && state.hasSavedGrant && !state.busy) { Text("Validate catalog account") }
        TextButton(onForget, enabled = state.canForget && state.operation != TwitchCatalogConnectionOperation.FORGET) {
            Text("Forget catalog account on this device")
        }
        Text("Forget clears only this instance’s catalog grant. It does not revoke Twitch access, clear browser login, remove configured streams, or change playback login.")
        if (state.operation == TwitchCatalogConnectionOperation.CONNECT || state.operation == TwitchCatalogConnectionOperation.VALIDATE)
            TextButton(onCancel) { Text("Cancel catalog action") }
        state.activation?.let {
            Text("Your activation code: ${it.userCode}")
            Button({ onBrowser("com.brave.browser") }) { Text("Open catalog activation in Brave") }
            Button({ onBrowser("com.android.chrome") }) { Text("Open catalog activation in Chrome") }
        }
        state.phase?.let { Text(catalogPhaseMessage(it)) }
        state.message?.let { Text(it) }
        onRetryOpen?.let { retry -> TextButton(retry, enabled = !state.busy) { Text("Retry opening catalog connection") } }
        TextButton(onBack, enabled = state.operation != TwitchCatalogConnectionOperation.FORGET) { Text("Back to providers") }
    }
}

private fun catalogPhaseMessage(phase: DeviceAuthPhase) = when (phase) {
    DeviceAuthPhase.READY -> "Ready to connect."
    DeviceAuthPhase.REQUESTING -> "Requesting catalog activation…"
    DeviceAuthPhase.WAITING -> "Waiting for catalog approval."
    DeviceAuthPhase.PAUSED -> "Catalog polling paused."
    DeviceAuthPhase.VALIDATING -> "Validating catalog authorization…"
    DeviceAuthPhase.SUCCEEDED -> "Catalog authorization validated."
    DeviceAuthPhase.CANCELLED -> "Catalog action cancelled."
    DeviceAuthPhase.EXPIRED -> "Activation expired. Connect again for a new code."
    DeviceAuthPhase.DENIED -> "Catalog authorization declined."
    DeviceAuthPhase.INVALID_CODE -> "Activation code rejected. Connect again."
    DeviceAuthPhase.NETWORK_ERROR -> "Twitch could not be reached. Check the saved route and retry."
    DeviceAuthPhase.BROWSER_UNAVAILABLE -> "Selected browser unavailable. Use another installed browser."
    else -> "Catalog authorization could not be accepted. Reconnect to retry."
}
