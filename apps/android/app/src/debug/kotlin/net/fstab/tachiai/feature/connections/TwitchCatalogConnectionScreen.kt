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
        Text("Twitch account", style = MaterialTheme.typography.headlineSmall)
        state.name?.let { Text("Provider instance: $it") }
        state.routeTitle?.let { Text("Saved request route: $it") }
        Text("Connect this instance for Following, stream discovery and playback. The connection uses the Smart TV client identity and requests permission to read the channels you follow. Other provider instances keep their own accounts.")
        Text("Tachiai’s authorization requests use the saved route. The external consent browser uses its own network and login. Return here after approving; polling pauses while you are away and the original code expiry still applies.")
        Text(state.status)
        Button(onConnect, enabled = state.ready && !state.busy) {
            Text(if (state.hasSavedGrant) "Reconnect Twitch" else "Connect Twitch")
        }
        Button(onValidate, enabled = state.ready && state.hasSavedGrant && !state.busy) { Text("Validate Twitch account") }
        TextButton(onForget, enabled = state.canForget && state.operation != TwitchCatalogConnectionOperation.FORGET) {
            Text("Forget Twitch account on this device")
        }
        Text("Forget disconnects discovery and playback for this instance. Browser login, configured streams, routes and other provider instances are retained.")
        if (state.operation == TwitchCatalogConnectionOperation.CONNECT || state.operation == TwitchCatalogConnectionOperation.VALIDATE)
            TextButton(onCancel) { Text("Cancel Twitch action") }
        state.activation?.let {
            Text("Your activation code: ${it.userCode}")
            Button({ onBrowser("com.brave.browser") }) { Text("Open Twitch activation in Brave") }
            Button({ onBrowser("com.android.chrome") }) { Text("Open Twitch activation in Chrome") }
        }
        state.phase?.let { Text(catalogPhaseMessage(it)) }
        state.message?.let { Text(it) }
        onRetryOpen?.let { retry -> TextButton(retry, enabled = !state.busy) { Text("Retry opening Twitch connection") } }
        TextButton(onBack, enabled = state.operation != TwitchCatalogConnectionOperation.FORGET) { Text("Back to providers") }
    }
}

private fun catalogPhaseMessage(phase: DeviceAuthPhase) = when (phase) {
    DeviceAuthPhase.READY -> "Ready to connect."
    DeviceAuthPhase.REQUESTING -> "Requesting Twitch activation…"
    DeviceAuthPhase.WAITING -> "Waiting for Twitch approval."
    DeviceAuthPhase.PAUSED -> "Twitch polling paused."
    DeviceAuthPhase.VALIDATING -> "Validating Twitch authorization…"
    DeviceAuthPhase.SUCCEEDED -> "Twitch authorization validated."
    DeviceAuthPhase.CANCELLED -> "Twitch action cancelled."
    DeviceAuthPhase.EXPIRED -> "Activation expired. Connect again for a new code."
    DeviceAuthPhase.DENIED -> "Twitch authorization declined."
    DeviceAuthPhase.INVALID_CODE -> "Activation code rejected. Connect again."
    DeviceAuthPhase.NETWORK_ERROR -> "Twitch could not be reached. Check the saved route and retry."
    DeviceAuthPhase.BROWSER_UNAVAILABLE -> "Selected browser unavailable. Use another installed browser."
    else -> "Twitch authorization could not be accepted. Reconnect to retry."
}
