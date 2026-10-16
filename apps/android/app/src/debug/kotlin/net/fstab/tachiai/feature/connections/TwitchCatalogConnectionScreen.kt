package net.fstab.tachiai.feature.connections

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import net.fstab.tachiai.provider.twitch.DeviceAuthPhase
import net.fstab.tachiai.provider.twitch.catalog.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TwitchCatalogConnectionScreen(state: TwitchCatalogConnectionState, onConnect: () -> Unit,
    onValidate: () -> Unit, onForget: () -> Unit, onCancel: () -> Unit, onBrowser: (String) -> Unit,
    onBack: (() -> Unit)? = null, onRetryOpen: (() -> Unit)? = null, enabled: Boolean = true) {
    val modifier = if (onBack == null) Modifier.fillMaxWidth() else Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    BoxWithConstraints(modifier) {
        // A scrollable column has unbounded content height. Capture its actual
        // viewport first so a landscape QR can be scrolled fully into view.
        val maxQrSize = minOf(320.dp, maxWidth, if (constraints.hasBoundedHeight) maxHeight else screenHeight / 2)
        val columnModifier = if (onBack == null) Modifier else Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        Column(columnModifier,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsSection("Twitch account", "Connect this provider for Following, stream discovery and playback. The connection uses Twitch’s Smart TV identity and requests permission to read the channels you follow. Other provider instances keep their own accounts.\n\n" +
                "Tachiai’s authorization requests use the saved route. The external consent browser uses its own network and login. Return here after approving; polling pauses while you are away and the original code expiry still applies.\n\n" +
                "Forget disconnects discovery and playback for this provider. Browser login, configured streams, routes and other provider instances are retained.")
            Text(state.status)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                maxItemsInEachRow = 2) {
                Button(onConnect, enabled = enabled && state.ready && !state.busy) {
                    Text(if (state.hasSavedGrant) "Reconnect Twitch" else "Connect Twitch")
                }
                TextButton(onValidate, enabled = enabled && state.ready && state.hasSavedGrant && !state.busy) { Text("Validate account") }
                TextButton(onForget, enabled = enabled && state.canForget && state.operation != TwitchCatalogConnectionOperation.FORGET) {
                    Text("Forget account")
                }
                TextButton(onCancel, enabled = enabled && (state.operation == TwitchCatalogConnectionOperation.CONNECT ||
                    state.operation == TwitchCatalogConnectionOperation.VALIDATE)) { Text("Cancel") }
            }
            state.activation?.takeIf { state.operation == TwitchCatalogConnectionOperation.CONNECT &&
                (state.phase == null || state.phase == DeviceAuthPhase.BROWSER_UNAVAILABLE ||
                    (!state.phase.terminal && state.phase != DeviceAuthPhase.VALIDATING)) }?.let {
                TwitchActivationQr(it, maxSize = maxQrSize)
                SelectionContainer { Text("Your activation code: ${it.userCode}") }
                Button({ onBrowser("com.brave.browser") }, enabled = enabled) { Text("Open Twitch activation in Brave") }
                Button({ onBrowser("com.android.chrome") }, enabled = enabled) { Text("Open Twitch activation in Chrome") }
            }
            state.phase?.let { Text(catalogPhaseMessage(it)) }
            state.message?.let { Text(it) }
            onRetryOpen?.let { retry -> TextButton(retry, enabled = enabled && !state.busy) { Text("Retry opening Twitch connection") } }
            onBack?.let { back -> TextButton(back, enabled = enabled && state.operation != TwitchCatalogConnectionOperation.FORGET) { Text("Back to providers") } }
        }
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
