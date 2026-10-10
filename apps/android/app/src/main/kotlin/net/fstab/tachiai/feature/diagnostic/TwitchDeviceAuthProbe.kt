package net.fstab.tachiai.feature.diagnostic

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.provider.twitch.DeviceActivation
import net.fstab.tachiai.provider.twitch.DeviceAuthPhase
import net.fstab.tachiai.provider.twitch.DeviceHttpFailure
import net.fstab.tachiai.provider.twitch.DeviceResponseIssue
import net.fstab.tachiai.provider.twitch.DeviceValidationScopes
import net.fstab.tachiai.provider.twitch.DeviceAuthorizationForeground
import net.fstab.tachiai.provider.twitch.TwitchDeviceHttpTransport
import net.fstab.tachiai.provider.twitch.authorizeTwitchDevice
import net.fstab.tachiai.provider.twitch.validTwitchClientId
import net.fstab.tachiai.provider.twitch.SavedAuthorizationState
import net.fstab.tachiai.provider.twitch.AndroidTwitchAuthorization
import net.fstab.tachiai.provider.twitch.TwitchAuthorizationProfile
import net.fstab.tachiai.provider.twitch.DeviceRejection
import net.fstab.tachiai.provider.twitch.DeviceAuthEndpoint
import net.fstab.tachiai.provider.twitch.DeviceLifetimeShape

internal const val DEVICE_AUTH_LOG_TAG = "TachiaiOAuth"
internal enum class DeviceActivationBrowser(val packageName: String) {
    BRAVE("com.brave.browser"), CHROME("com.android.chrome"),
}

internal fun deviceFailureSummary(failure: DeviceHttpFailure): String =
    "${failure.endpoint.name} / ${failure.stage.name} / ${failure.category.name} / ${failure.elapsedMs} ms"

// Explicit user action only. Never copy the activation URL, private device code,
// grant or token, and never expose clipboard errors or contents in diagnostics.
internal fun copyDeviceActivationCode(activation: DeviceActivation?, copySensitiveText: (String) -> Unit): Boolean {
    val code = activation?.userCode ?: return false
    return try { copySensitiveText(code); true } catch (_: Exception) { false }
}

internal fun deviceAuthStatus(phase: DeviceAuthPhase): String = when (phase) {
    DeviceAuthPhase.READY -> "Ready for the selected provider identity."
    DeviceAuthPhase.INVALID_CLIENT_ID -> "Client ID must be 10–64 alphanumeric characters."
    DeviceAuthPhase.REQUESTING -> "Requesting device authorization."
    DeviceAuthPhase.WAITING -> "Waiting for your approval on Twitch's external activation page."
    DeviceAuthPhase.PAUSED -> "Polling paused while Tachiai is away. Return here after approval; challenge expiry still runs."
    DeviceAuthPhase.VALIDATING -> "Validating the returned token for the selected provider identity."
    DeviceAuthPhase.SUCCEEDED -> "App OAuth validated. Token discarded; web-player login and Turbo playback remain unverified."
    DeviceAuthPhase.CANCELLED -> "Attempt cancelled; a new attempt needs a new code."
    DeviceAuthPhase.EXPIRED -> "Device authorization expired. Start a new attempt."
    DeviceAuthPhase.DENIED -> "Authorization declined."
    DeviceAuthPhase.INVALID_CODE -> "Provider rejected the device code as invalid or already used."
    DeviceAuthPhase.REJECTED -> "Provider rejected the request. Check public client registration; empty scopes are experimental."
    DeviceAuthPhase.NETWORK_ERROR -> "Network request failed. No automatic retry."
    DeviceAuthPhase.INVALID_RESPONSE -> "Provider response failed the probe's bounded validation."
    DeviceAuthPhase.CLIENT_MISMATCH -> "Returned token belongs to a different client; rejected."
    DeviceAuthPhase.SCOPE_MISMATCH -> "Unexpected permissions were granted; rejected."
    DeviceAuthPhase.BROWSER_UNAVAILABLE -> "Selected browser is unavailable; attempt cancelled. No app fallback."
}

@Composable
internal fun TwitchDeviceAuthProbeScreen(
    modifier: Modifier = Modifier,
    retainValidatedToken: Boolean = false,
    profile: TwitchAuthorizationProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV,
    inspectSmartTvLifetime: Boolean = false,
) {
    if (!BuildConfig.DEBUG) return
    require(!inspectSmartTvLifetime || (profile == TwitchAuthorizationProfile.PROVIDER_SMART_TV &&
        !retainValidatedToken))
    val localRetention = profile == TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL
    require(!localRetention || (retainValidatedToken && !inspectSmartTvLifetime))
    val logProfile = "profile=${profile.name} "
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val foreground = remember(lifecycleOwner) {
        DeviceAuthorizationForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    val clientId = profile.clientId
    var phase by remember { mutableStateOf(DeviceAuthPhase.READY) }
    var activation by remember { mutableStateOf<DeviceActivation?>(null) }
    var codeCopied by remember { mutableStateOf<Boolean?>(null) }
    var worker by remember { mutableStateOf<Job?>(null) }
    var transport by remember { mutableStateOf<TwitchDeviceHttpTransport?>(null) }
    var attemptNumber by remember { mutableStateOf(0) }
    var failureDetails by remember { mutableStateOf<DeviceHttpFailure?>(null) }
    var responseIssue by remember { mutableStateOf<DeviceResponseIssue?>(null) }
    var validationScopes by remember { mutableStateOf<DeviceValidationScopes?>(null) }
    var grantLifetime by remember { mutableStateOf<DeviceLifetimeShape?>(null) }
    var validationLifetime by remember { mutableStateOf<DeviceLifetimeShape?>(null) }
    val savedAuthorization = remember(context, profile) { AndroidTwitchAuthorization.get(context, profile) }
    var saveState by remember { mutableStateOf<SavedAuthorizationState?>(null) }
    var rejection by remember { mutableStateOf<DeviceRejection?>(null) }
    fun report(next: DeviceAuthPhase) {
        phase = next
        if (next.terminal) { activation = null; codeCopied = null }
        // Closed native status only: no ID, code, URI, headers, bodies or tokens.
        Log.d(DEVICE_AUTH_LOG_TAG, "${logProfile}attempt=$attemptNumber phase=${next.name}")
    }
    fun cancel(next: DeviceAuthPhase) {
        worker?.cancel()
        transport?.close() // Coroutine cancellation alone cannot stop blocking I/O.
        worker = null
        transport = null
        report(next)
    }
    DisposableEffect(Unit) {
        onDispose {
            worker?.cancel()
            transport?.close()
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            foreground.setForeground(event == Lifecycle.Event.ON_RESUME ||
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            Log.d(DEVICE_AUTH_LOG_TAG, "lifecycle=${event.name}")
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text(if (inspectSmartTvLifetime) "Smart TV lifetime inspection · token discarded"
            else if (localRetention) "Smart TV local retention · authorize and save for up to seven days"
            else if (retainValidatedToken) "Authorize and save · ${profile.name} identity"
            else "Provider device authorization · ${profile.name} validation only")
        Text(if (inspectSmartTvLifetime)
            "Separate unsupported Smart TV identity experiment. Requests zero permissions. Verify the application's identity privately and decline unexpected permissions. Omitted or zero grant expiry may proceed to exact official validation within a 30-second local budget. Integer-zero validation expiry is experimental, NOT permanent validity. No token save, access request, playlist or playback."
            else if (localRetention)
            "Separate unsupported Smart TV experiment with its own encrypted slot. Verify the application's identity privately and decline unexpected permissions. Zero permissions; omitted/zero grant expiry and zero validation expiry are experimental. Save for at most SEVEN DAYS locally, shorter for known expiry. This is NOT Twitch's expiry or permanent validity. Revalidate before every explicit live/replay access check; no automatic refresh, playlist or media. Earlier strict examples remain unchanged."
            else
            "Separate unsupported ${profile.name} identity experiment. Requests zero permissions using this fixed observed provider client identifier. Verify the provider's application identity privately and cancel unexpected permissions. Retention, when selected, uses only its separate encrypted slot; no cookies, secret, refresh, playlist or media. Identity selection does not establish permission, platform support or Turbo playback.")
        OutlinedTextField(value = clientId, onValueChange = {},
            label = { Text("Public Twitch client ID") }, singleLine = true, enabled = false)
        Row {
            Button(enabled = worker == null, onClick = {
                val selectedId = clientId.trim()
                if (!validTwitchClientId(selectedId)) {
                    report(DeviceAuthPhase.INVALID_CLIENT_ID)
                    return@Button
                }
                activation = null
                codeCopied = null
                failureDetails = null
                responseIssue = null
                validationScopes = null
                grantLifetime = null
                validationLifetime = null
                saveState = null
                rejection = null
                val saveRevision = savedAuthorization.revision()
                attemptNumber++
                val attemptId = attemptNumber
                var observedFailure: DeviceHttpFailure? = null
                var observedIssue: DeviceResponseIssue? = null
                var observedScopes: DeviceValidationScopes? = null
                var observedGrantLifetime: DeviceLifetimeShape? = null
                var observedValidationLifetime: DeviceLifetimeShape? = null
                var observedRejection: DeviceRejection? = null
                val attempt = TwitchDeviceHttpTransport(onHttpStatus = { endpoint, status ->
                    Log.d(DEVICE_AUTH_LOG_TAG, "${logProfile}attempt=$attemptId endpoint=${endpoint.name} http=$status")
                }, onFailure = { failure ->
                    observedFailure = failure
                    Log.d(DEVICE_AUTH_LOG_TAG, "${logProfile}attempt=$attemptId failure=${deviceFailureSummary(failure)}")
                }, canRequest = { foreground.isForeground }, onDeviceRejection = { category ->
                    observedRejection = category
                    Log.d(DEVICE_AUTH_LOG_TAG, "${logProfile}attempt=$attemptId deviceRejection=${category.name}")
                })
                transport = attempt
                worker = scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            val onValidated: (suspend (String, Long) -> Unit)? = if (!retainValidatedToken) null else { token, deadline ->
                                withContext(Dispatchers.Main) { activation = null }
                                val saved = savedAuthorization.saveValidated(token, deadline, saveRevision)
                                withContext(Dispatchers.Main) {
                                    saveState = saved
                                    Log.d(DEVICE_AUTH_LOG_TAG, "${logProfile}attempt=$attemptId storage=${saved.name}")
                                }
                            }
                            authorizeTwitchDevice(selectedId, attempt,
                                onPhase = { withContext(Dispatchers.Main) { report(it) } },
                                onActivation = { prompt ->
                                    withContext(Dispatchers.Main) { activation = prompt }
                                }, foreground = foreground,
                                onResponseIssue = { issue ->
                                    observedIssue = issue
                                    Log.d(DEVICE_AUTH_LOG_TAG, "${logProfile}attempt=$attemptId responseIssue=${issue.name}")
                                }, onGrantScope = { shape ->
                                    Log.d(DEVICE_AUTH_LOG_TAG, "${logProfile}attempt=$attemptId grantScope=${shape.name}")
                                }, onValidationScopes = { shape ->
                                    observedScopes = shape
                                    Log.d(DEVICE_AUTH_LOG_TAG, "${logProfile}attempt=$attemptId validationScopes=${shape.name}")
                                }, onProviderClientValidated = onValidated,
                                providerProfile = profile, inspectSmartTvLifetime = inspectSmartTvLifetime,
                                retainSmartTvLocally = localRetention,
                                onLifetimeShape = { endpoint, shape ->
                                    if (endpoint == DeviceAuthEndpoint.TOKEN) observedGrantLifetime = shape
                                    if (endpoint == DeviceAuthEndpoint.VALIDATE) observedValidationLifetime = shape
                                    Log.d(DEVICE_AUTH_LOG_TAG, "${logProfile}attempt=$attemptId endpoint=${endpoint.name} lifetime=${shape.name}")
                                })
                        }
                        failureDetails = observedFailure.takeIf { result == DeviceAuthPhase.NETWORK_ERROR }
                        responseIssue = observedIssue.takeIf { result == DeviceAuthPhase.INVALID_RESPONSE }
                        validationScopes = observedScopes.takeIf { result in setOf(DeviceAuthPhase.INVALID_RESPONSE,
                            DeviceAuthPhase.SUCCEEDED, DeviceAuthPhase.SCOPE_MISMATCH) }
                        grantLifetime = observedGrantLifetime
                        validationLifetime = observedValidationLifetime
                        report(result)
                        rejection = observedRejection.takeIf { result == DeviceAuthPhase.REJECTED }
                    } catch (_: CancellationException) {
                        // Cancel owns the UI status; stale workers publish nothing.
                    } finally {
                        attempt.close()
                        if (transport === attempt) {
                            transport = null
                            worker = null
                        }
                    }
                }
            }) { Text("Start") }
            Button(enabled = worker != null, onClick = { cancel(DeviceAuthPhase.CANCELLED) }) {
                Text("Cancel")
            }
        }
        activation?.let { prompt ->
            Text("Your activation code: ${prompt.userCode}")
            Button(onClick = {
                codeCopied = copyDeviceActivationCode(activation) { code ->
                    val clipboard = checkNotNull(context.getSystemService(ClipboardManager::class.java))
                    val clip = ClipData.newPlainText("Twitch activation code", code).apply {
                        description.extras = PersistableBundle().apply {
                            val sensitiveKey = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                                ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE"
                            putBoolean(sensitiveKey, true)
                        }
                    }
                    clipboard.setPrimaryClip(clip)
                }
            }) { Text("Copy code") }
            codeCopied?.let { Text(if (it) "Code copied. Paste it on Twitch's activation page."
                else "Could not copy code. You can still enter it manually.") }
            Text("This is the provider's application identity. Inspect it privately; cancel if the identity or requested permissions are unexpected. Do not send the code or account details.")
            Text("Open activation in a full browser, not Twitch's app. Choose an installed browser below.")
            Text("After approval, return to Tachiai. Background polling waits; the code's original expiry is unchanged.")
            DeviceActivationBrowser.entries.forEach { browser ->
                Button(onClick = {
                    try {
                        foreground.setForeground(false) // Close the gate before handing off.
                        context.startActivity(Intent(Intent.ACTION_VIEW, prompt.verificationUri.toString().toUri())
                            .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(browser.packageName))
                        Log.d(DEVICE_AUTH_LOG_TAG, "attempt=$attemptNumber browser=${browser.name} handoff=STARTED")
                    } catch (_: ActivityNotFoundException) {
                        foreground.setForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                        Log.d(DEVICE_AUTH_LOG_TAG, "attempt=$attemptNumber browser=${browser.name} handoff=UNAVAILABLE")
                        cancel(DeviceAuthPhase.BROWSER_UNAVAILABLE)
                    } catch (_: SecurityException) {
                        foreground.setForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                        Log.d(DEVICE_AUTH_LOG_TAG, "attempt=$attemptNumber browser=${browser.name} handoff=DENIED")
                        cancel(DeviceAuthPhase.BROWSER_UNAVAILABLE)
                    }
                }) { Text(if (browser == DeviceActivationBrowser.BRAVE) "Open in Brave" else "Open in Chrome") }
            }
        }
        Text(if (phase in setOf(DeviceAuthPhase.READY, DeviceAuthPhase.VALIDATING))
            "${phase.name}: separate provider-client experiment; exact provider identity validation required."
            else if (inspectSmartTvLifetime && phase == DeviceAuthPhase.SUCCEEDED)
            "Official validation accepted the selected identity and scopes. Token discarded. Zero expiry is not a promise of permanent validity. No save, access or playback."
            else if (localRetention && phase == DeviceAuthPhase.SUCCEEDED)
            "Official validation passed. Local retention is at most seven days; storage outcome below. No access or playback tested."
            else if (retainValidatedToken && phase == DeviceAuthPhase.SUCCEEDED)
            "App OAuth validated. Storage outcome below; no playback tested."
            else deviceAuthStatus(phase))
        saveState?.let { Text("Saved authorization: ${it.name}") }
        failureDetails?.let { Text("Native failure: ${deviceFailureSummary(it)}") }
        responseIssue?.let { Text("Native response check: ${it.name}") }
        validationScopes?.let { Text("Validation scope field shape: ${it.name}") }
        grantLifetime?.let { Text("Grant lifetime field shape: ${it.name}") }
        validationLifetime?.let { Text("Validation lifetime field shape: ${it.name}") }
        rejection?.let { Text("Device rejection: ${it.name} (provider-reported, not a verified cause)") }
    }
}
