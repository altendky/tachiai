package net.fstab.tachiai.feature.diagnostic

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.platform.net.AccessProbeHttp
import net.fstab.tachiai.provider.twitch.AndroidTwitchAuthorization
import net.fstab.tachiai.provider.twitch.DeviceAuthorizationForeground
import net.fstab.tachiai.provider.twitch.SavedAuthorizationState
import net.fstab.tachiai.provider.twitch.SavedTwitchUseResult
import net.fstab.tachiai.provider.twitch.TwitchAccessCase
import net.fstab.tachiai.provider.twitch.TwitchAccessErrorResult
import net.fstab.tachiai.provider.twitch.TwitchErrorCategory
import net.fstab.tachiai.provider.twitch.TwitchErrorShape
import net.fstab.tachiai.provider.twitch.probeTwitchAccessErrors
import net.fstab.tachiai.provider.twitch.safeSummary
import net.fstab.tachiai.provider.twitch.TwitchDeviceHttpTransport
import net.fstab.tachiai.provider.twitch.useSavedTwitchAuthorization
import net.fstab.tachiai.provider.twitch.TwitchAuthorizationProfile

@Composable
internal fun SavedTwitchAccessProbeScreen(kind: TwitchAccessCase, modifier: Modifier = Modifier,
    inspectErrors: Boolean = true, blankClientHeader: Boolean = true,
    profile: TwitchAuthorizationProfile = TwitchAuthorizationProfile.PROVIDER_SMART_TV) {
    if (!BuildConfig.DEBUG) return
    require(blankClientHeader && inspectErrors)
    val logProfile = "profile=${profile.name} "
    val context = LocalContext.current
    val cache = remember(context, profile) { AndroidTwitchAuthorization.get(context, profile) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val foreground = remember(lifecycleOwner) {
        DeviceAuthorizationForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var worker by remember { mutableStateOf<Job?>(null) }
    var validationTransport by remember { mutableStateOf<TwitchDeviceHttpTransport?>(null) }
    var accessTransport by remember { mutableStateOf<AccessProbeHttp?>(null) }
    var useResult by remember { mutableStateOf<SavedTwitchUseResult?>(null) }
    var errorResult by remember { mutableStateOf<TwitchAccessErrorResult?>(null) }
    var storageState by remember { mutableStateOf<SavedAuthorizationState?>(null) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            foreground.setForeground(event == Lifecycle.Event.ON_RESUME ||
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            if (event == Lifecycle.Event.ON_STOP && worker != null) {
                worker?.cancel()
                validationTransport?.close()
                accessTransport?.close()
                worker = null
                validationTransport = null
                accessTransport = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            worker?.cancel()
            validationTransport?.close()
            accessTransport?.close()
        }
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Saved Twitch $kind access · ${profile.name} debug case")
        if (profile == TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL)
            Text("Seven-day LOCAL retention cap, not a provider expiry promise. Earlier saved records keep their shorter expiry until explicit revalidation/extension. Every use validates the exact Smart TV identity and zero scopes. Integer-zero validation expiry is experimental; this use has at most 30 seconds and cannot extend stored retention. No playlist or media.")
        Text("Separate provider-client grant and encrypted slot. Exact provider-client validation before this existing query with an empty Client-ID header. Unsupported identity experiment; no manifest, media, integrity spoofing or claim of Turbo/playback permission.")
        if (inspectErrors) Text("Error diagnostic: bounded 200/400/401/403 JSON, closed categories only. Provider-reported indications are not verified causes; no raw response is displayed or saved.")
        Text("Use the encrypted token saved by Authorize and save. This validates it with Twitch before each request; no new approval, refresh, browser login or video playback. A private access rejection does not erase a token that passed official validation.")
        Button(enabled = worker == null, onClick = {
            useResult = null
            errorResult = null
            storageState = null
            val validation = TwitchDeviceHttpTransport(canRequest = { foreground.isForeground },
                onHttpStatus = { endpoint, status -> Log.d(DEVICE_AUTH_LOG_TAG, "${logProfile}saved endpoint=${endpoint.name} http=$status") })
            validationTransport = validation
            worker = scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        useSavedTwitchAuthorization(cache, validation, foreground, onUse = { token, deadline, lease ->
                            val access = AccessProbeHttp(canRequest = {
                                foreground.isForeground && cache.isCurrent(lease) && System.nanoTime() / 1_000_000 < deadline
                            })
                            withContext(Dispatchers.Main) { accessTransport = access }
                            var diagnostic: TwitchAccessErrorResult? = null
                            val resource = if (kind == TwitchAccessCase.LIVE) "bobross" else DEFAULT_ALIGNMENT_VIDEO
                            try {
                                diagnostic = probeTwitchAccessErrors(access, kind, resource, token, blankClientHeader, profile)
                            } catch (error: CancellationException) { throw error }
                            catch (_: Exception) {
                                diagnostic = TwitchAccessErrorResult(0, TwitchErrorShape.NOT_READ, setOf(TwitchErrorCategory.REQUEST_FAILED))
                            }
                            finally { access.close() }
                            withContext(Dispatchers.Main) {
                                accessTransport = null
                                errorResult = diagnostic
                                diagnostic?.let {
                                    val case = if (profile == TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL) {
                                        if (kind == TwitchAccessCase.LIVE) NativeAccessCase.TWITCH_SMART_TV_LOCAL_LIVE else NativeAccessCase.TWITCH_SMART_TV_LOCAL_REPLAY
                                    } else if (profile == TwitchAuthorizationProfile.PROVIDER_SMART_TV) {
                                        if (kind == TwitchAccessCase.LIVE) NativeAccessCase.TWITCH_SMART_TV_LIVE else NativeAccessCase.TWITCH_SMART_TV_REPLAY
                                    } else {
                                        if (kind == TwitchAccessCase.LIVE) NativeAccessCase.TWITCH_PROVIDER_LIVE else NativeAccessCase.TWITCH_PROVIDER_REPLAY
                                    }
                                    Log.d("TachiaiAccess", "case=${case.name} ${it.safeSummary()}")
                                }
                            }
                        })
                    }
                    useResult = result
                    Log.d(DEVICE_AUTH_LOG_TAG, "${logProfile}saved outcome=${result.outcome.name} validationHttp=${result.validationHttp}")
                } catch (_: CancellationException) {
                    // Disposal/background owns cancellation; no stale publication.
                } finally {
                    validation.close()
                    if (validationTransport === validation) { validationTransport = null; worker = null }
                }
            }
        }) { Text("Use saved token") }
        Button(enabled = worker == null, onClick = {
            useResult = null
            errorResult = null
            worker = scope.launch {
                val activeJob = currentCoroutineContext()[Job]
                try {
                    val state = withContext(Dispatchers.IO) { cache.forget() }
                    storageState = state
                    Log.d(DEVICE_AUTH_LOG_TAG, "${logProfile}saved storage=${state.name}")
                } finally { if (worker === activeJob) worker = null }
            }
        }) { Text("Forget ${profile.name} token on this phone") }
        Text("Forget overwrites only this encrypted token slot. It does not clear provider browser sessions or revoke the Twitch grant. Expired/invalid entries are never used without fresh validation; reconnect to replace them.")
        Text(if (worker != null) "Working…" else "Ready for an explicit saved-token action.")
        useResult?.let { Text("Saved use: ${it.outcome.name} / validation HTTP ${it.validationHttp}") }
        errorResult?.let { Text("Error diagnostic: ${it.safeSummary()}") }
        storageState?.let { Text("Storage: ${it.name}") }
    }
}
