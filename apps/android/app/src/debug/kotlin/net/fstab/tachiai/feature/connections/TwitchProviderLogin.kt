package net.fstab.tachiai.feature.connections

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.DateFormat
import java.util.Date
import net.fstab.tachiai.provider.twitch.*

@Composable
internal fun TwitchProviderLogin() {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val controller = remember(context, owner, scope) {
        TwitchProviderLoginController(
            AndroidTwitchAuthorization.get(context, TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL),
            scope, { gate -> TwitchDeviceHttpTransport(canRequest = gate) },
            initiallyForeground = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    val state by controller.state.collectAsState()
    var copied by remember(state.activation) { mutableStateOf<Boolean?>(null) }
    DisposableEffect(controller, owner) {
        val observer = LifecycleEventObserver { _, _ ->
            controller.setForeground(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        owner.lifecycle.addObserver(observer)
        controller.setForeground(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { owner.lifecycle.removeObserver(observer); controller.close() }
    }
    TwitchProviderLoginSection(state, copied, controller::connect, controller::revalidate, controller::forget,
        { controller.cancel() }, onCopy = {
            state.activation?.let { prompt ->
                copied = try {
                    val clipboard = checkNotNull(context.getSystemService(ClipboardManager::class.java))
                    val clip = ClipData.newPlainText("Twitch activation code", prompt.userCode).apply {
                        description.extras = PersistableBundle().apply {
                            val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                                ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE"
                            putBoolean(key, true)
                        }
                    }
                    clipboard.setPrimaryClip(clip)
                    true
                } catch (_: Exception) { false }
            }
        }, onBrowser = { packageName ->
            state.activation?.let { prompt ->
                controller.setForeground(false)
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, prompt.verificationUri.toString().toUri())
                        .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(packageName))
                } catch (_: ActivityNotFoundException) {
                    controller.cancel(DeviceAuthPhase.BROWSER_UNAVAILABLE)
                    controller.setForeground(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                } catch (_: SecurityException) {
                    controller.cancel(DeviceAuthPhase.BROWSER_UNAVAILABLE)
                    controller.setForeground(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                }
            }
        })
}

@Composable
internal fun TwitchProviderLoginSection(state: TwitchProviderLoginState, copied: Boolean? = null,
    onConnect: () -> Unit, onRevalidate: () -> Unit, onForget: () -> Unit, onCancel: () -> Unit,
    onCopy: () -> Unit, onBrowser: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Twitch login", style = MaterialTheme.typography.titleMedium)
        Text(when (state.storage) {
            null -> "Reading saved login…"
            SavedAuthorizationState.MISSING, SavedAuthorizationState.FORGOTTEN -> "No saved Twitch login."
            SavedAuthorizationState.AVAILABLE, SavedAuthorizationState.SAVED -> "Saved Twitch login available on this device. Playback validates it again."
            SavedAuthorizationState.EXPIRED -> "Saved Twitch login expired. Reconnect to continue."
            else -> "Saved Twitch login unavailable. Reconnect or retry reading it."
        })
        state.expiresAtMs?.let { Text("Local retention until ${DateFormat.getDateTimeInstance().format(Date(it))}.") }
        Text("This debug login uses Twitch’s Smart TV application identity and requests no permissions. Check the identity privately before approving. Login is encrypted on this device and used by Twitch live/replay feeds.",
            style = MaterialTheme.typography.bodySmall)
        Button(onClick = onConnect, enabled = !state.busy && state.storage != null) {
            Text(if (state.storage == SavedAuthorizationState.MISSING || state.storage == SavedAuthorizationState.FORGOTTEN)
                "Connect Twitch" else "Reconnect Twitch")
        }
        Button(onClick = onRevalidate, enabled = !state.busy && state.storage == SavedAuthorizationState.AVAILABLE) {
            Text("Revalidate saved Twitch login")
        }
        Text("Revalidation explicitly updates local retention for up to seven days, shorter for a known expiry. This is not a promise of Twitch validity; playback never extends it automatically.",
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onForget, enabled = state.storage != null && state.operation != ProviderLoginOperation.FORGET) {
            Text("Forget Twitch login on this device")
        }
        Text("Forget clears only this saved grant and invalidates its playback authorization. It does not revoke Twitch access or clear browser login.",
            style = MaterialTheme.typography.bodySmall)
        if (state.operation == ProviderLoginOperation.CONNECT || state.operation == ProviderLoginOperation.REVALIDATE)
            TextButton(onClick = onCancel) { Text("Cancel login action") }
        state.activation?.let { prompt ->
            Text("Your activation code: ${prompt.userCode}")
            Button(onClick = onCopy) { Text("Copy activation code") }
            copied?.let { Text(if (it) "Code copied. Paste it on Twitch’s activation page."
                else "Could not copy code. Enter it manually.") }
            Text("Approve in a full browser, then return here. Polling waits while you are away; the code’s original expiry still applies.")
            Button(onClick = { onBrowser("com.brave.browser") }) { Text("Open activation in Brave") }
            Button(onClick = { onBrowser("com.android.chrome") }) { Text("Open activation in Chrome") }
        }
        state.phase?.let { Text(providerLoginPhaseMessage(it)) }
        state.message?.let { Text(it) }
    }
}

private fun providerLoginPhaseMessage(phase: DeviceAuthPhase): String = when (phase) {
    DeviceAuthPhase.REQUESTING -> "Requesting an activation code…"
    DeviceAuthPhase.WAITING -> "Waiting for your approval."
    DeviceAuthPhase.PAUSED -> "Polling paused. Return here after approval."
    DeviceAuthPhase.VALIDATING -> "Validating the approved login…"
    DeviceAuthPhase.SUCCEEDED -> "Twitch validation completed."
    DeviceAuthPhase.CANCELLED -> "Login action cancelled."
    DeviceAuthPhase.EXPIRED -> "Activation expired. Reconnect for a new code."
    DeviceAuthPhase.DENIED -> "Authorization declined."
    DeviceAuthPhase.INVALID_CODE -> "Twitch rejected this activation code. Reconnect for a new code."
    DeviceAuthPhase.NETWORK_ERROR -> "Could not reach Twitch. No automatic retry."
    DeviceAuthPhase.BROWSER_UNAVAILABLE -> "Selected browser unavailable. Install it or use another browser with a new activation code."
    DeviceAuthPhase.CLIENT_MISMATCH, DeviceAuthPhase.SCOPE_MISMATCH -> "Twitch returned an unexpected identity or permissions. Login was not saved."
    DeviceAuthPhase.INVALID_RESPONSE, DeviceAuthPhase.INVALID_CLIENT_ID, DeviceAuthPhase.REJECTED -> "Twitch authorization could not be accepted. Login was not saved."
    DeviceAuthPhase.READY -> "Ready to connect Twitch."
}
