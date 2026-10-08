package net.fstab.tachiai.feature.diagnostic

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
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
import androidx.media3.common.util.UnstableApi
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.platform.media.BoundedNativePlayer
import net.fstab.tachiai.platform.media.NativeMediaEvent
import net.fstab.tachiai.platform.media.NativePlaybackBudget
import net.fstab.tachiai.platform.media.NativePlayerSurface
import net.fstab.tachiai.platform.net.AccessProbeHttp
import net.fstab.tachiai.provider.twitch.AndroidTwitchAuthorization
import net.fstab.tachiai.provider.twitch.DeviceAuthorizationForeground
import net.fstab.tachiai.provider.twitch.TwitchAccessCase
import net.fstab.tachiai.provider.twitch.TwitchAuthorizationProfile
import net.fstab.tachiai.provider.twitch.TwitchDeviceHttpTransport
import net.fstab.tachiai.provider.twitch.allowedTwitchMediaUri
import net.fstab.tachiai.provider.twitch.resolveTwitchPlayback
import net.fstab.tachiai.provider.twitch.useSavedTwitchAuthorization
import net.fstab.tachiai.provider.twitch.validTwitchPlaybackResource
import net.fstab.tachiai.provider.twitch.twitchPublicCdnDiagnosticHost
import net.fstab.tachiai.provider.twitch.parseTwitchManifestRejection

@UnstableApi
@Composable
internal fun TwitchNativePlaybackProbeScreen(kind: TwitchAccessCase, modifier: Modifier = Modifier,
    observedReplayCdn: Boolean = false, timingControls: Boolean = false) {
    if (!BuildConfig.DEBUG) return
    require(!observedReplayCdn || kind == TwitchAccessCase.REPLAY)
    val mode = if (observedReplayCdn) "OBSERVED_REPLAY_CDN" else "STRICT"
    val context = LocalContext.current
    val cache = remember(context) {
        AndroidTwitchAuthorization.get(context, TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL)
    }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val foreground = remember(lifecycleOwner) {
        DeviceAuthorizationForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    val revision = remember { AtomicLong(0) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    var worker by remember { mutableStateOf<Job?>(null) }
    var validation by remember { mutableStateOf<TwitchDeviceHttpTransport?>(null) }
    var access by remember { mutableStateOf<AccessProbeHttp?>(null) }
    var host by remember { mutableStateOf<BoundedNativePlayer?>(null) }
    var status by remember { mutableStateOf("Ready; nothing requested yet.") }
    var events by remember { mutableStateOf<List<String>>(emptyList()) }
    var resource by remember { mutableStateOf(if (kind == TwitchAccessCase.LIVE)
        if (timingControls) "relaxbeats" else "bobross" else DEFAULT_ALIGNMENT_VIDEO) }

    fun stop() {
        revision.incrementAndGet() // Invalidates queued callbacks and stale worker results.
        worker?.cancel()
        validation?.close()
        access?.close()
        host?.close()
        worker = null
        validation = null
        access = null
        host = null
        status = "Stopped. The saved authorization was not changed."
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            val resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            foreground.setForeground(resumed)
            if (!resumed) stop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            foreground.setForeground(false)
            lifecycleOwner.lifecycle.removeObserver(observer)
            stop()
        }
    }
    Column(modifier.fillMaxSize().padding(8.dp)) {
        Text("Twitch native $kind · unsupported debug ${if (timingControls) "timing" else "playback"}")
        if (timingControls) Text("2 min, foreground only, 50% volume. Forward = later footage; live limited to current window. Requests are not proof of settled position.")
        else {
            if (observedReplayCdn) Text("Exact observed replay CDN comparison; earlier strict case remains unchanged.")
            Text("Saved LOCAL Smart TV grant; fresh validation per start. Maximum 2 minutes, foreground only, no ads removed or keys/licenses. 50% player volume; not proof of Turbo or permission.")
        }
        OutlinedTextField(value = resource, onValueChange = { if (it.length <= 25) resource = it },
            label = { Text(if (kind == TwitchAccessCase.LIVE) "Public channel (must be live)" else "Public replay ID") },
            singleLine = true, enabled = worker == null && host == null,
            isError = !validTwitchPlaybackResource(kind, resource))
        Row {
            Button(enabled = worker == null && host == null && validTwitchPlaybackResource(kind, resource), onClick = {
                val selectedResource = if (kind == TwitchAccessCase.LIVE) resource.lowercase(Locale.ROOT) else resource
                val attempt = revision.incrementAndGet()
                status = "Validating saved authorization…"
                events = emptyList()
                val validator = TwitchDeviceHttpTransport(canRequest = {
                    foreground.isForeground && revision.get() == attempt
                }, onHttpStatus = { endpoint, http ->
                    Log.d("TachiaiNative", "kind=${kind.name} mode=$mode endpoint=${endpoint.name} http=$http")
                })
                validation = validator
                worker = scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            useSavedTwitchAuthorization(cache, validator, foreground, onUse = { token, deadline, lease ->
                                val request = AccessProbeHttp(canRequest = {
                                    foreground.isForeground && revision.get() == attempt && cache.isCurrent(lease) &&
                                        System.nanoTime() / 1_000_000 < deadline
                                })
                                withContext(Dispatchers.Main) { access = request }
                                val source = try {
                                    resolveTwitchPlayback(request, kind,
                                        selectedResource, token,
                                        onHttpStatus = { http -> Log.d("TachiaiNative", "kind=${kind.name} mode=$mode endpoint=ACCESS http=$http") })
                                } finally { request.close() }
                                withContext(Dispatchers.Main) {
                                    access = null
                                    if (revision.get() != attempt || !foreground.isForeground || !cache.isCurrent(lease) ||
                                        System.nanoTime() / 1_000_000 >= deadline) throw IOException("Playback acceptance ended")
                                    val budget = NativePlaybackBudget(cache.remainingLocalMs(lease), canContinue = {
                                        foreground.isForeground && revision.get() == attempt && cache.isCurrent(lease) &&
                                            cache.remainingLocalMs(lease) > 0
                                    })
                                    val created = BoundedNativePlayer(context, budget, deadline, allowedUri = { uri ->
                                        val allowed = allowedTwitchMediaUri(uri, observedReplayCdn)
                                        if (!allowed) twitchPublicCdnDiagnosticHost(uri)?.let { publicHost ->
                                            Log.d("TachiaiNative", "kind=${kind.name} mode=$mode deniedCdnHost=$publicHost")
                                        }
                                        allowed
                                    }, onEvent = { event, code ->
                                        handler.post {
                                            if (revision.get() == attempt && foreground.isForeground) {
                                                val summary = "${event.name} code=$code"
                                                events = (events + summary).takeLast(6)
                                                Log.d("TachiaiNative", "kind=${kind.name} mode=$mode event=${event.name} code=$code")
                                                if (event == NativeMediaEvent.STOPPED) host = null
                                            }
                                        }
                                    }, onManifestRejection = { http, body ->
                                        val rejection = parseTwitchManifestRejection(http, body)
                                        Log.d("TachiaiNative", "kind=${kind.name} mode=$mode manifestRejection=${rejection.name}")
                                        handler.post {
                                            if (revision.get() == attempt && foreground.isForeground)
                                                events = (events + "Manifest: ${rejection.name}").takeLast(6)
                                        }
                                    }, onTimingDiscontinuity = { snapshot, reason ->
                                        if (timingControls) handler.post {
                                            if (revision.get() == attempt && foreground.isForeground)
                                                Log.d("TachiaiTiming", "discontinuity=$reason ${nativeTimingSummary(snapshot)}")
                                        }
                                    })
                                    try { host = created; created.start(source.uri) }
                                    catch (_: Exception) { created.close(); host = null; throw IOException("Player start failed") }
                                }
                            })
                        }
                        if (revision.get() == attempt) {
                            status = "Authorization: ${result.outcome.name} / validation HTTP ${result.validationHttp}"
                            Log.d("TachiaiNative", "kind=${kind.name} mode=$mode authorization=${result.outcome.name} http=${result.validationHttp}")
                        }
                    } catch (_: CancellationException) { /* stop owns invalidation */ }
                    finally {
                        validator.close()
                        if (validation === validator) { validation = null; access = null; worker = null }
                    }
                }
            }) { Text("Start native $kind") }
            Button(onClick = { stop() }) { Text("Stop") }
        }
        Text(status)
        Text((if (timingControls) events.takeLast(2) else events).joinToString(" · "))
        if (timingControls) NativeTimingControls(host, replayFixture = kind == TwitchAccessCase.REPLAY)
        NativePlayerSurface(host, Modifier.weight(1f))
    }
}
