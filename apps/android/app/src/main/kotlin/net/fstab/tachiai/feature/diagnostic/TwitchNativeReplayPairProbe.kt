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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.platform.media.BoundedNativePlayer
import net.fstab.tachiai.platform.media.NativeMediaEvent
import net.fstab.tachiai.platform.media.NativePlaybackAudioGroup
import net.fstab.tachiai.platform.media.NativePlaybackBudget
import net.fstab.tachiai.platform.media.NativePlayerSurface
import net.fstab.tachiai.platform.media.NativeReplayPair
import net.fstab.tachiai.platform.net.AccessProbeHttp
import net.fstab.tachiai.provider.twitch.AndroidTwitchAuthorization
import net.fstab.tachiai.provider.twitch.DeviceAuthorizationForeground
import net.fstab.tachiai.provider.twitch.TwitchAccessCase
import net.fstab.tachiai.provider.twitch.TwitchAuthorizationProfile
import net.fstab.tachiai.provider.twitch.TwitchDeviceHttpTransport
import net.fstab.tachiai.provider.twitch.allowedTwitchMediaUri
import net.fstab.tachiai.provider.twitch.resolveTwitchPlayback
import net.fstab.tachiai.provider.twitch.useSavedTwitchAuthorization

@UnstableApi
@Composable
internal fun TwitchNativeReplayPairProbeScreen(modifier: Modifier = Modifier) {
    if (!BuildConfig.DEBUG) return
    val context = LocalContext.current
    val cache = remember(context) {
        AndroidTwitchAuthorization.get(context, TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL)
    }
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    val foreground = remember(owner) {
        DeviceAuthorizationForeground(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    val revision = remember { AtomicLong(0) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    var worker by remember { mutableStateOf<Job?>(null) }
    var validator by remember { mutableStateOf<TwitchDeviceHttpTransport?>(null) }
    var access by remember { mutableStateOf<AccessProbeHttp?>(null) }
    var pair by remember { mutableStateOf<NativeReplayPair?>(null) }
    var a by remember { mutableStateOf<BoundedNativePlayer?>(null) }
    var b by remember { mutableStateOf<BoundedNativePlayer?>(null) }
    var status by remember { mutableStateOf("No request yet. Saved LOCAL grant required.") }

    fun stop() {
        revision.incrementAndGet()
        worker?.cancel()
        validator?.close()
        access?.close()
        val stoppedPair = pair
        stoppedPair?.close()
        a?.close()
        b?.close()
        worker = null; validator = null; access = null; pair = null; a = null; b = null
        status = if (stoppedPair?.cleanupFailed == true) "Cleanup reported failure; saved authorization unchanged."
            else "Stopped; saved authorization unchanged."
        Log.d("TachiaiPair", "stop cleanupFailed=${stoppedPair?.cleanupFailed == true}")
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            val resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            foreground.setForeground(resumed)
            if (!resumed) stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { foreground.setForeground(false); owner.lifecycle.removeObserver(observer); stop() }
    }
    Column(modifier.fillMaxSize().padding(8.dp)) {
        Text("Two native copies · unsupported debug replay comparison")
        Text("Same moving-match replay, exact observed CDN policy. Shared 2-min cap; 50% each initially. No Turbo/permission/precise-sync claim.")
        Row {
            Button(enabled = worker == null && pair == null, onClick = {
                val attempt = revision.incrementAndGet()
                status = "Validating saved LOCAL authorization…"
                val validation = TwitchDeviceHttpTransport(canRequest = {
                    foreground.isForeground && revision.get() == attempt
                }, onHttpStatus = { endpoint, http ->
                    Log.d("TachiaiPair", "endpoint=${endpoint.name} http=$http")
                })
                validator = validation
                worker = scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            useSavedTwitchAuthorization(cache, validation, foreground, onUse = { token, deadline, lease ->
                                val request = AccessProbeHttp(canRequest = {
                                    foreground.isForeground && revision.get() == attempt && cache.isCurrent(lease) &&
                                        System.nanoTime() / 1_000_000 < deadline
                                })
                                withContext(Dispatchers.Main) { access = request }
                                val source = try {
                                    resolveTwitchPlayback(request, TwitchAccessCase.REPLAY, DEFAULT_ALIGNMENT_VIDEO, token,
                                        onHttpStatus = { http -> Log.d("TachiaiPair", "endpoint=ACCESS http=$http") })
                                } finally { request.close() }
                                withContext(Dispatchers.Main) {
                                    access = null
                                    if (revision.get() != attempt || !foreground.isForeground || !cache.isCurrent(lease) ||
                                        System.nanoTime() / 1_000_000 >= deadline) throw IOException("Playback acceptance ended")
                                    val budget = NativePlaybackBudget(cache.remainingLocalMs(lease), canContinue = {
                                        foreground.isForeground && revision.get() == attempt && cache.isCurrent(lease) &&
                                            cache.remainingLocalMs(lease) > 0
                                    })
                                    var createdPair: NativeReplayPair? = null
                                    val focus = NativePlaybackAudioGroup(context) { createdPair?.focusLost() }
                                    val created = mutableListOf<BoundedNativePlayer>()
                                    try {
                                        for (side in listOf("A", "B")) {
                                            val host = BoundedNativePlayer(context, budget, deadline,
                                                allowedUri = { allowedTwitchMediaUri(it, observedReplayCdn = true) },
                                                handleAudioFocus = false, onEvent = { event, code ->
                                                    handler.post {
                                                        if (revision.get() == attempt && foreground.isForeground) {
                                                            Log.d("TachiaiPair", "side=$side event=${event.name} code=$code")
                                                            if (event == NativeMediaEvent.STOPPED) stop()
                                                        }
                                                    }
                                                }, onTimingDiscontinuity = { snapshot, reason ->
                                                    handler.post {
                                                        if (revision.get() == attempt && foreground.isForeground)
                                                            Log.d("TachiaiPair", "side=$side discontinuity=$reason ${nativeTimingSummary(snapshot)}")
                                                    }
                                                })
                                            created.add(host)
                                            host.start(source.uri, playWhenReady = false)
                                        }
                                        createdPair = NativeReplayPair(created[0], created[1], focus::acquire,
                                            releaseFocus = { budget.stop(); focus.close() })
                                        a = created[0]; b = created[1]; pair = createdPair
                                    } catch (_: Exception) {
                                        budget.stop(); created.forEach { it.close() }; focus.close()
                                        throw IOException("Pair preparation failed")
                                    }
                                }
                            })
                        }
                        if (revision.get() == attempt) {
                            status = "Authorization ${result.outcome.name} / validation HTTP ${result.validationHttp}"
                            Log.d("TachiaiPair", "authorization=${result.outcome.name} http=${result.validationHttp}")
                        }
                    } catch (_: CancellationException) { /* teardown invalidates all callbacks */ }
                    finally {
                        validation.close()
                        if (validator === validation) { validator = null; access = null; worker = null }
                    }
                }
            }) { Text("Prepare pair") }
            Button(onClick = { stop() }) { Text("Stop") }
        }
        Text(status)
        NativeReplayPairControls(pair)
        val size = LocalWindowInfo.current.containerSize
        if (size.width > size.height) {
            Row(Modifier.weight(1f)) {
                NativePlayerSurface(a, Modifier.weight(1f), useController = false)
                NativePlayerSurface(b, Modifier.weight(1f), useController = false)
            }
        } else Column(Modifier.weight(1f)) {
            NativePlayerSurface(a, Modifier.weight(1f), useController = false)
            NativePlayerSurface(b, Modifier.weight(1f), useController = false)
        }
    }
}
