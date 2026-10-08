package net.fstab.tachiai.feature.diagnostic

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.fstab.tachiai.platform.media.NativeReplayPair
import net.fstab.tachiai.platform.media.nativeReplayOffsetMs

@Composable
internal fun NativeReplayPairControls(pair: NativeReplayPair?) {
    var sample by remember(pair) { mutableStateOf("No clocks yet.") }
    var status by remember(pair) { mutableStateOf("Prepare both, then Play.") }
    var busy by remember(pair) { mutableStateOf(false) }
    var volumeA by remember(pair) { mutableFloatStateOf(0.5f) }
    var volumeB by remember(pair) { mutableFloatStateOf(0.5f) }
    LaunchedEffect(pair) {
        var count = 0
        var previousStatus = ""
        while (pair != null) {
            pair.poll()
            val a = pair.a.timingSnapshot()
            val b = pair.b.timingSnapshot()
            status = pair.status
            if (status != previousStatus) {
                Log.d("TachiaiPair", "transaction=$status")
                previousStatus = status
            }
            busy = pair.busy
            sample = "Requested A−B: ${pair.requestedOffsetMs} ms · observed: ${nativeReplayOffsetMs(a, b)} ms\n" +
                "A ${a?.positionMs} ms / playing=${a?.playing} · B ${b?.positionMs} ms / playing=${b?.playing}"
            if (count++ % 20 == 0) {
                Log.d("TachiaiPair", "sample A ${nativeTimingSummary(a)}")
                Log.d("TachiaiPair", "sample B ${nativeTimingSummary(b)} requestedOffsetMs=${pair.requestedOffsetMs}")
            }
            delay(250)
        }
    }
    fun align(delta: Long? = null, anchor: Long? = null) {
        val selected = pair ?: return
        val measured = nativeReplayOffsetMs(selected.a.timingSnapshot(), selected.b.timingSnapshot())
        val target = if (delta == null) 0 else try { Math.addExact(measured ?: return, delta) }
            catch (_: ArithmeticException) { return }
        val plan = selected.align(target, anchor)
        Log.d("TachiaiPair", "align deltaMs=$delta requestedOffsetMs=$target anchorMs=$anchor plan=$plan")
        status = selected.status
        busy = selected.busy
    }
    Column {
        Text("Positive A−B = A shows later footage than B. A is top/left; B bottom/right.", fontSize = 12.sp)
        Row {
            Button(enabled = pair != null && !busy, onClick = { pair?.play(); status = pair?.status.orEmpty() }) { Text("Play") }
            Button(enabled = pair != null, onClick = { pair?.pause(); status = pair?.status.orEmpty(); busy = false }) { Text("Pause") }
            Button(enabled = pair != null && !busy, onClick = { align() }) { Text("Sync") }
            Button(enabled = pair != null && !busy, onClick = { align(anchor = 4_200_000) }) { Text("70 min") }
        }
        Row {
            listOf(-5_000L, -1_000L, 1_000L, 5_000L).forEach { delta ->
                Button(enabled = pair != null && !busy, onClick = { align(delta) }) {
                    Text("${if (delta > 0) "+" else ""}${delta / 1_000} s")
                }
            }
        }
        Row {
            Text("A ${(volumeA * 100).toInt()}%")
            Slider(value = volumeA, onValueChange = { if (pair?.a?.setVolume(it) == true) volumeA = it },
                enabled = pair != null, modifier = Modifier.weight(1f))
            Text("B ${(volumeB * 100).toInt()}%")
            Slider(value = volumeB, onValueChange = { if (pair?.b?.setVolume(it) == true) volumeB = it },
                enabled = pair != null, modifier = Modifier.weight(1f))
        }
        Text(status, fontSize = 12.sp)
        Text(sample, fontSize = 12.sp)
    }
}
