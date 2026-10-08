package net.fstab.tachiai.feature.diagnostic

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.provider.twitch.AndroidTwitchAuthorization
import net.fstab.tachiai.provider.twitch.DeviceAuthorizationForeground
import net.fstab.tachiai.provider.twitch.TwitchAuthorizationProfile
import net.fstab.tachiai.provider.twitch.TwitchDeviceHttpTransport
import net.fstab.tachiai.provider.twitch.extendSavedTwitchLocalRetention

@Composable
internal fun TwitchLocalRetentionProbeScreen() {
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
    var worker by remember { mutableStateOf<Job?>(null) }
    var transport by remember { mutableStateOf<TwitchDeviceHttpTransport?>(null) }
    var status by remember { mutableStateOf("No extension requested.") }
    fun cancel() {
        revision.incrementAndGet()
        worker?.cancel(); transport?.close(); worker = null; transport = null
        status = "Cancelled."
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            val resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            foreground.setForeground(resumed)
            if (!resumed) cancel()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { foreground.setForeground(false); owner.lifecycle.removeObserver(observer); cancel() }
    }
    Column {
        Text("Extend saved LOCAL login · unsupported Smart TV identity experiment")
        Text("Explicit fresh validation, no new approval. Up to seven days locally, shorter for a known positive validation expiry. Not Twitch expiry, permanent validity or OAuth refresh. Expired records require private authorization. Ordinary playback never renews retention. No device code, playlist or media request.")
        Button(enabled = worker == null, onClick = {
            val attempt = revision.incrementAndGet()
            val deadline = System.nanoTime() / 1_000_000 + 30_000
            val request = TwitchDeviceHttpTransport(canRequest = {
                foreground.isForeground && revision.get() == attempt && System.nanoTime() / 1_000_000 < deadline
            }, onHttpStatus = { endpoint, http ->
                Log.d(DEVICE_AUTH_LOG_TAG, "localExtension endpoint=${endpoint.name} http=$http")
            })
            transport = request
            status = "Validating saved LOCAL authorization…"
            worker = scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        extendSavedTwitchLocalRetention(cache, request, foreground)
                    }
                    if (revision.get() == attempt && foreground.isForeground) {
                        status = "Extension ${result.outcome.name} / validation HTTP ${result.validationHttp}"
                        Log.d(DEVICE_AUTH_LOG_TAG, "localExtension outcome=${result.outcome.name} http=${result.validationHttp}")
                    }
                } catch (_: CancellationException) { /* cancellation owns status */ }
                finally { request.close(); if (transport === request) { transport = null; worker = null } }
            }
        }) { Text("Revalidate and extend saved login") }
        Button(enabled = worker != null, onClick = { cancel() }) { Text("Cancel") }
        Text(status)
    }
}
