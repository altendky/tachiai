package net.fstab.tachiai.feature.connections

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import net.fstab.tachiai.provider.twitch.DeviceActivation
import net.fstab.tachiai.provider.twitch.twitchActivationUri

// Transient local modules only: never saved, logged, or sent to an image service.
internal class TwitchActivationQrCode(private val modules: BitMatrix) {
    val size: Int get() = modules.width
    operator fun get(x: Int, y: Int): Boolean = modules[x, y]
    override fun toString() = "TwitchActivationQrCode(redacted)"
}

internal fun createTwitchActivationQrCode(activation: DeviceActivation): TwitchActivationQrCode {
    // The provider adapter validates the first-party/code binding and supplies
    // a prefilled destination while preserving any existing matching URL.
    val uri = twitchActivationUri(activation)
    val modules = QRCodeWriter().encode(uri.toString(), BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    return TwitchActivationQrCode(modules)
}
