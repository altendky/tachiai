package net.fstab.tachiai.feature.connections

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import net.fstab.tachiai.provider.twitch.DeviceActivation
import net.fstab.tachiai.provider.twitch.parseDeviceActivation

// Transient local modules only: never saved, logged, or sent to an image service.
internal class TwitchActivationQrCode(private val modules: BitMatrix) {
    val size: Int get() = modules.width
    operator fun get(x: Int, y: Int): Boolean = modules[x, y]
    override fun toString() = "TwitchActivationQrCode(redacted)"
}

internal fun createTwitchActivationQrCode(activation: DeviceActivation): TwitchActivationQrCode {
    // Reuse the challenge parser's exact first-party/code binding policy. Preserve
    // the provider's URI spelling, query order and encoding, including a bare URI.
    val validated = parseDeviceActivation(activation.userCode, activation.verificationUri.toString())
    val modules = QRCodeWriter().encode(validated.verificationUri.toString(), BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    return TwitchActivationQrCode(modules)
}
