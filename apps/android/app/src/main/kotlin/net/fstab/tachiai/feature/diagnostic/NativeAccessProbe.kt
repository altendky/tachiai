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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.platform.net.AccessProbeEndpoint
import net.fstab.tachiai.platform.net.AccessProbeHttp
import net.fstab.tachiai.platform.net.AccessProbeOutcome
import net.fstab.tachiai.platform.net.AccessProbeResult
import net.fstab.tachiai.provider.abema.probeAbemaNativeAccess
import net.fstab.tachiai.provider.abema.probeAbemaNativeHlsAccess
import net.fstab.tachiai.provider.abema.abemaDashFormatClassification
import net.fstab.tachiai.provider.twitch.TwitchAccessCase
import net.fstab.tachiai.provider.twitch.TwitchAuthorizationProfile

internal enum class NativeAccessCase { ABEMA_ANONYMOUS, TWITCH_PROVIDER_AUTHORIZE_SAVE, TWITCH_PROVIDER_LIVE, TWITCH_PROVIDER_REPLAY, TWITCH_SMART_TV_AUTHORIZE_SAVE, TWITCH_SMART_TV_LIVE, TWITCH_SMART_TV_REPLAY, TWITCH_SMART_TV_LIFETIME_INSPECTION, TWITCH_SMART_TV_LOCAL_SAVE, TWITCH_SMART_TV_LOCAL_LIVE, TWITCH_SMART_TV_LOCAL_REPLAY, TWITCH_NATIVE_LIVE, TWITCH_NATIVE_REPLAY, TWITCH_NATIVE_REPLAY_OBSERVED_CDN, TWITCH_NATIVE_LIVE_TIMING, TWITCH_NATIVE_REPLAY_TIMING, TWITCH_NATIVE_REPLAY_PAIR, TWITCH_SMART_TV_LOCAL_EXTEND, ABEMA_DASH_FORMAT, ABEMA_ANONYMOUS_HLS, ABEMA_HLS_VARIANT }
internal fun initialAccessEndpoint(case: NativeAccessCase): AccessProbeEndpoint = when (case) {
    NativeAccessCase.ABEMA_ANONYMOUS, NativeAccessCase.ABEMA_DASH_FORMAT,
    NativeAccessCase.ABEMA_ANONYMOUS_HLS, NativeAccessCase.ABEMA_HLS_VARIANT -> AccessProbeEndpoint.ABEMA_CHANNELS
    else -> AccessProbeEndpoint.TWITCH_ACCESS
}
internal fun nativeAuthorizationProfile(case: NativeAccessCase): TwitchAuthorizationProfile = when (case) {
    NativeAccessCase.TWITCH_PROVIDER_AUTHORIZE_SAVE, NativeAccessCase.TWITCH_PROVIDER_LIVE,
    NativeAccessCase.TWITCH_PROVIDER_REPLAY -> TwitchAuthorizationProfile.PROVIDER_PLAYBACK
    NativeAccessCase.TWITCH_SMART_TV_AUTHORIZE_SAVE, NativeAccessCase.TWITCH_SMART_TV_LIVE,
    NativeAccessCase.TWITCH_SMART_TV_REPLAY, NativeAccessCase.TWITCH_SMART_TV_LIFETIME_INSPECTION -> TwitchAuthorizationProfile.PROVIDER_SMART_TV
    NativeAccessCase.TWITCH_SMART_TV_LOCAL_SAVE, NativeAccessCase.TWITCH_SMART_TV_LOCAL_LIVE,
    NativeAccessCase.TWITCH_SMART_TV_LOCAL_REPLAY, NativeAccessCase.TWITCH_NATIVE_LIVE,
    NativeAccessCase.TWITCH_NATIVE_REPLAY, NativeAccessCase.TWITCH_NATIVE_REPLAY_OBSERVED_CDN,
    NativeAccessCase.TWITCH_NATIVE_LIVE_TIMING, NativeAccessCase.TWITCH_NATIVE_REPLAY_TIMING,
    NativeAccessCase.TWITCH_NATIVE_REPLAY_PAIR, NativeAccessCase.TWITCH_SMART_TV_LOCAL_EXTEND -> TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL
    else -> TwitchAuthorizationProfile.PROVIDER_SMART_TV
}
internal fun accessProbeSummary(result: AccessProbeResult) =
    "${result.endpoint.name} / ${result.outcome.name} / HTTP ${result.http}"

internal fun logAccessProbeResult(case: NativeAccessCase, result: AccessProbeResult) {
    // All callers use enum-derived case names. No resource IDs, body, headers,
    // tokens, manifest/signature values, URLs or exception text.
    Log.d("TachiaiAccess", "case=${case.name} ${accessProbeSummary(result)}")
}

@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun NativeAccessProbeScreen(modifier: Modifier = Modifier) {
    if (!BuildConfig.DEBUG) return
    val context = LocalContext.current
    var selected by remember { mutableStateOf<NativeAccessCase?>(null) }
    if (selected == NativeAccessCase.TWITCH_SMART_TV_LOCAL_EXTEND) {
        Column(modifier) {
            Button(onClick = { selected = null }) { Text("Back to access cases") }
            TwitchLocalRetentionProbeScreen()
        }
        return
    }
    if (selected == NativeAccessCase.TWITCH_NATIVE_REPLAY_PAIR) {
        Column(modifier) {
            Button(onClick = { selected = null }) { Text("Back to access cases") }
            TwitchNativeReplayPairProbeScreen()
        }
        return
    }
    if (selected in setOf(NativeAccessCase.TWITCH_NATIVE_LIVE, NativeAccessCase.TWITCH_NATIVE_REPLAY,
        NativeAccessCase.TWITCH_NATIVE_REPLAY_OBSERVED_CDN, NativeAccessCase.TWITCH_NATIVE_LIVE_TIMING,
        NativeAccessCase.TWITCH_NATIVE_REPLAY_TIMING)) {
        Column(modifier) {
            Button(onClick = { selected = null }) { Text("Back to access cases") }
            TwitchNativePlaybackProbeScreen(if (selected in setOf(NativeAccessCase.TWITCH_NATIVE_LIVE,
                NativeAccessCase.TWITCH_NATIVE_LIVE_TIMING))
                TwitchAccessCase.LIVE else TwitchAccessCase.REPLAY,
                observedReplayCdn = selected in setOf(NativeAccessCase.TWITCH_NATIVE_REPLAY_OBSERVED_CDN,
                    NativeAccessCase.TWITCH_NATIVE_REPLAY_TIMING),
                timingControls = selected in setOf(NativeAccessCase.TWITCH_NATIVE_LIVE_TIMING,
                    NativeAccessCase.TWITCH_NATIVE_REPLAY_TIMING))
        }
        return
    }
    if (selected in setOf(NativeAccessCase.TWITCH_PROVIDER_AUTHORIZE_SAVE, NativeAccessCase.TWITCH_PROVIDER_LIVE, NativeAccessCase.TWITCH_PROVIDER_REPLAY,
        NativeAccessCase.TWITCH_SMART_TV_AUTHORIZE_SAVE, NativeAccessCase.TWITCH_SMART_TV_LIVE, NativeAccessCase.TWITCH_SMART_TV_REPLAY,
        NativeAccessCase.TWITCH_SMART_TV_LIFETIME_INSPECTION, NativeAccessCase.TWITCH_SMART_TV_LOCAL_SAVE,
        NativeAccessCase.TWITCH_SMART_TV_LOCAL_LIVE, NativeAccessCase.TWITCH_SMART_TV_LOCAL_REPLAY)) {
        val profile = nativeAuthorizationProfile(checkNotNull(selected))
        Column(modifier) {
            Button(onClick = { selected = null }) { Text("Back to access cases") }
            if (selected == NativeAccessCase.TWITCH_SMART_TV_LIFETIME_INSPECTION)
                TwitchDeviceAuthProbeScreen(profile = profile, inspectSmartTvLifetime = true)
            else if (selected in setOf(NativeAccessCase.TWITCH_PROVIDER_AUTHORIZE_SAVE,
                NativeAccessCase.TWITCH_SMART_TV_AUTHORIZE_SAVE, NativeAccessCase.TWITCH_SMART_TV_LOCAL_SAVE))
                TwitchDeviceAuthProbeScreen(retainValidatedToken = true, profile = profile)
            else SavedTwitchAccessProbeScreen(if (selected in setOf(NativeAccessCase.TWITCH_PROVIDER_LIVE, NativeAccessCase.TWITCH_SMART_TV_LIVE,
                NativeAccessCase.TWITCH_SMART_TV_LOCAL_LIVE))
                TwitchAccessCase.LIVE else TwitchAccessCase.REPLAY,
                inspectErrors = true, blankClientHeader = true, profile = profile)
        }
        return
    }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var worker by remember { mutableStateOf<Job?>(null) }
    var transport by remember { mutableStateOf<AccessProbeHttp?>(null) }
    var result by remember { mutableStateOf<AccessProbeResult?>(null) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && worker != null) {
                worker?.cancel()
                transport?.close()
                worker = null
                transport = null
                selected?.let {
                    result = AccessProbeResult(initialAccessEndpoint(it), AccessProbeOutcome.CANCELLED)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            worker?.cancel()
            transport?.close()
        }
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
        Button(onClick = {
            context.startActivity(Intent().setClassName(context, "net.fstab.tachiai.feature.presentation.CachedPrototypeActivity"))
        }) { Text("Prototype · choose two streams") }
        Button(onClick = {
            context.startActivity(Intent().setClassName(context, "net.fstab.tachiai.feature.presentation.PrototypeActivity"))
        }) { Text("Prototype comparison · original web startup") }
        Button(onClick = {
            context.startActivity(Intent().setClassName(context, "net.fstab.tachiai.feature.diagnostic.AbemaCachedHelperActivity"))
        }) { Text("ABEMA cached helper · download and initialize") }
        Text("Native playback access gates · debug examples")
        Text("Preliminary native two-feed viewer · fixed sources, five-minute foreground test. Enable your usual ABEMA playback connection first; prepare using Start, then joint Play. For the floating-video layout, choose More → Landscape / PiP layout in the viewer. Earlier diagnostics remain available.")
        listOf("LIVE_RELATIVE", "REPLAY_RELATIVE", "LIVE_REPLAY_RELATIVE", "REPLAY_LIVE_RELATIVE").forEach { mode ->
            Button(onClick = {
                context.startActivity(Intent().setClassName(context, "net.fstab.tachiai.feature.diagnostic.NativePairViewerActivity")
                    .putExtra("net.fstab.tachiai.extra.ABEMA_NATIVE_PAIR", mode))
            }) { Text("Viewer · ${mode.removeSuffix("_RELATIVE").replace('_', ' ')}") }
        }
        Button(onClick = {
            context.startActivity(Intent(context, net.fstab.tachiai.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("net.fstab.tachiai.extra.NATIVE_ACCESS_PROBE", false))
        }) { Text("Earlier browser presentation") }
        Text("Earlier access-only cases discard Twitch fields and request no media. NATIVE cases explicitly resolve playlists and play unencrypted media for at most two minutes. REPLAY PAIR adds two same-replay copies under one cap. ABEMA remains metadata/manifest-only: HLS VARIANT reads at most one advertised child playlist, never segments, keys or licenses. Format markers do not prove playback. No case proves Turbo or provider permission.")
        NativeAccessCase.entries.forEach { case ->
            Button(enabled = worker == null, onClick = {
                selected = case
                result = null
                if (case in setOf(NativeAccessCase.TWITCH_PROVIDER_AUTHORIZE_SAVE, NativeAccessCase.TWITCH_PROVIDER_LIVE,
                    NativeAccessCase.TWITCH_PROVIDER_REPLAY, NativeAccessCase.TWITCH_SMART_TV_AUTHORIZE_SAVE,
                    NativeAccessCase.TWITCH_SMART_TV_LIVE, NativeAccessCase.TWITCH_SMART_TV_REPLAY,
                    NativeAccessCase.TWITCH_SMART_TV_LIFETIME_INSPECTION, NativeAccessCase.TWITCH_SMART_TV_LOCAL_SAVE,
                    NativeAccessCase.TWITCH_SMART_TV_LOCAL_LIVE, NativeAccessCase.TWITCH_SMART_TV_LOCAL_REPLAY,
                    NativeAccessCase.TWITCH_NATIVE_LIVE, NativeAccessCase.TWITCH_NATIVE_REPLAY,
                    NativeAccessCase.TWITCH_NATIVE_REPLAY_OBSERVED_CDN, NativeAccessCase.TWITCH_NATIVE_LIVE_TIMING,
                    NativeAccessCase.TWITCH_NATIVE_REPLAY_TIMING, NativeAccessCase.TWITCH_NATIVE_REPLAY_PAIR,
                    NativeAccessCase.TWITCH_SMART_TV_LOCAL_EXTEND)) return@Button
                val request = AccessProbeHttp(canRequest = {
                    lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                })
                transport = request
                worker = scope.launch {
                    try {
                        val checked = withContext(Dispatchers.IO) {
                            try {
                                when (case) {
                                    NativeAccessCase.ABEMA_ANONYMOUS -> probeAbemaNativeAccess(request)
                                    NativeAccessCase.ABEMA_DASH_FORMAT -> probeAbemaNativeAccess(request, ::abemaDashFormatClassification)
                                    NativeAccessCase.ABEMA_ANONYMOUS_HLS -> probeAbemaNativeHlsAccess(request)
                                    NativeAccessCase.ABEMA_HLS_VARIANT -> probeAbemaNativeHlsAccess(request, inspectVariant = true)
                                    else -> error("Twitch cases use their explicit provider authorization screen")
                                }
                            } catch (error: CancellationException) { throw error }
                            catch (_: Exception) { AccessProbeResult(initialAccessEndpoint(case), AccessProbeOutcome.NETWORK_FAILED) }
                        }
                        result = checked
                        logAccessProbeResult(case, checked)
                    } finally {
                        request.close()
                        if (transport === request) { transport = null; worker = null }
                    }
                }
            }) { Text(case.name.replace('_', ' ')) }
        }
        Text(if (worker != null) "Checking ${selected?.name}…" else "Select a case. Earlier browser and OAuth-only probes remain available.")
        result?.let { Text(accessProbeSummary(it)) }
    }
}
