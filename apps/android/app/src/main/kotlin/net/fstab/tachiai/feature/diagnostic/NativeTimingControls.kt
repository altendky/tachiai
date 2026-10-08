package net.fstab.tachiai.feature.diagnostic

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.fstab.tachiai.platform.media.BoundedNativePlayer
import net.fstab.tachiai.platform.media.NativeTimingSnapshot

internal fun nativeTimingSummary(snapshot: NativeTimingSnapshot?): String = if (snapshot == null) "Unavailable"
    else "positionMs=${snapshot.positionMs} durationMs=${snapshot.durationMs} bufferedMs=${snapshot.bufferedMs} " +
        "liveOffsetMs=${snapshot.liveOffsetMs} windowStartMs=${snapshot.windowStartMs} contentTimeMs=${snapshot.contentTimeMs} " +
        "defaultMs=${snapshot.defaultPositionMs} live=${snapshot.live} dynamic=${snapshot.dynamic} " +
        "seekable=${snapshot.seekable} ad=${snapshot.playingAd} playing=${snapshot.playing} " +
        "playWhenReady=${snapshot.playWhenReady} state=${snapshot.state} speed=${snapshot.speed}"

@UnstableApi
@Composable
internal fun NativeTimingControls(host: BoundedNativePlayer?, replayFixture: Boolean) {
    var snapshot by remember(host) { mutableStateOf<NativeTimingSnapshot?>(null) }
    var request by remember(host) { mutableStateOf("No timing action requested.") }
    var actionRevision by remember(host) { mutableLongStateOf(0) }
    val currentHost by rememberUpdatedState(host)
    val scope = rememberCoroutineScope()
    LaunchedEffect(host) {
        var samples = 0
        while (host != null) {
            snapshot = host.timingSnapshot()
            if (snapshot == null) break
            if (samples++ % 10 == 0) Log.d("TachiaiTiming", "sample ${nativeTimingSummary(snapshot)}")
            delay(500)
        }
    }
    fun action(name: String, operation: (BoundedNativePlayer) -> String) {
        val selected = host ?: return
        val actionId = ++actionRevision
        Log.d("TachiaiTiming", "action=$actionId before=$name ${nativeTimingSummary(selected.timingSnapshot())}")
        request = "$name ${operation(selected)}"
        Log.d("TachiaiTiming", "action=$actionId request=$request")
        scope.launch {
            delay(1_500)
            if (currentHost === selected && actionRevision == actionId) {
                snapshot = selected.timingSnapshot()
                Log.d("TachiaiTiming", "action=$actionId after=$name ${nativeTimingSummary(snapshot)}")
            }
        }
    }
    Column {
        Row {
            Button(enabled = host != null, onClick = { action("PAUSE") { "requested=${it.setTimingPlaying(false)}" } }) { Text("Pause") }
            Button(enabled = host != null, onClick = { action("PLAY") { "requested=${it.setTimingPlaying(true)}" } }) { Text("Play") }
            Button(enabled = host != null, onClick = {
                snapshot = host?.timingSnapshot()
                Log.d("TachiaiTiming", "read ${nativeTimingSummary(snapshot)}")
            }) { Text("Read") }
        }
        Row {
            Button(enabled = host != null, onClick = { action("BACKWARD_5S") { it.shiftByMs(-5_000).toString() } }) { Text("Back 5 s") }
            Button(enabled = host != null, onClick = { action("FORWARD_5S") { it.shiftByMs(5_000).toString() } }) { Text("Forward 5 s") }
            if (replayFixture) Button(enabled = host != null, onClick = {
                action("REPLAY_70MIN") { it.seekToMs(4_200_000).toString() }
            }) { Text("70 min") }
            else Button(enabled = host != null, onClick = {
                action("LIVE_DEFAULT") { it.seekLiveDefault().toString() }
            }) { Text("Catch up") }
        }
        Text(request, fontSize = 12.sp)
        Text(nativeTimingSummary(snapshot), fontSize = 12.sp)
    }
}
