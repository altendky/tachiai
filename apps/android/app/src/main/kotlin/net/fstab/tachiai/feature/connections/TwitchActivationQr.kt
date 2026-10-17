package net.fstab.tachiai.feature.connections

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.fstab.tachiai.provider.twitch.DeviceActivation

@Composable
internal fun TwitchActivationQr(activation: DeviceActivation, maxSize: Dp = 320.dp,
    encode: (DeviceActivation) -> TwitchActivationQrCode = ::createTwitchActivationQrCode) {
    val qr = remember(activation, encode) {
        try { encode(activation) } catch (_: Exception) { null }
    }
    val availablePixels = with(LocalDensity.current) { maxSize.roundToPx() }
    if (qr == null || availablePixels < qr.size) {
        Text("QR code unavailable. Use the activation code or open a browser.")
        return
    }
    Text("Scan with another device to open Twitch activation. If Twitch asks for a code, enter the code below.")
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.widthIn(max = maxSize).fillMaxWidth().aspectRatio(1f)
            .semantics { contentDescription = "Twitch activation QR code" }) {
            drawRect(Color.White)
            val modulePixels = size.minDimension.toInt() / qr.size
            if (modulePixels > 0) {
                val originX = ((size.width.toInt() - modulePixels * qr.size) / 2).toFloat()
                val originY = ((size.height.toInt() - modulePixels * qr.size) / 2).toFloat()
                val moduleSize = Size(modulePixels.toFloat(), modulePixels.toFloat())
                for (y in 0 until qr.size) for (x in 0 until qr.size) {
                    if (qr[x, y]) drawRect(Color.Black,
                        Offset(originX + x * modulePixels, originY + y * modulePixels), moduleSize)
                }
            }
        }
    }
}
