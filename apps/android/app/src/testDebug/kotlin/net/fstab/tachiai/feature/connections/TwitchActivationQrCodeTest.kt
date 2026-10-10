package net.fstab.tachiai.feature.connections

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.net.URI
import net.fstab.tachiai.provider.twitch.DeviceActivation
import net.fstab.tachiai.provider.twitch.InvalidDeviceResponse
import org.junit.Assert.*
import org.junit.Test

class TwitchActivationQrCodeTest {
    private fun decode(qr: TwitchActivationQrCode): String {
        val scale = 4
        val width = qr.size * scale
        val pixels = IntArray(width * width) { index ->
            if (qr[(index % width) / scale, (index / width) / scale]) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, width, pixels)))).text
    }

    @Test fun providerPrefilledUriRoundTripsWithoutChangingQueryOrderOrEncoding() {
        val uri = "https://www.twitch.tv/activate?device-code=FIXTURE%31%32%33&public=true"
        val qr = createTwitchActivationQrCode(DeviceActivation("FIXTURE123", URI(uri)))
        assertEquals(uri, decode(qr))
        assertEquals("TwitchActivationQrCode(redacted)", qr.toString())
        for (border in 0 until 4) for (position in 0 until qr.size) {
            assertFalse(qr[border, position]); assertFalse(qr[position, border])
            assertFalse(qr[qr.size - 1 - border, position]); assertFalse(qr[position, qr.size - 1 - border])
        }
    }

    @Test fun bareProviderUriQrIncludesPublicUserCodeWithoutChangingBrowserUri() {
        val uri = "https://www.twitch.tv/activate"
        val activation = DeviceActivation("FIXTURE123", URI(uri))
        assertEquals("$uri?device-code=FIXTURE123", decode(createTwitchActivationQrCode(activation)))
        assertEquals(uri, activation.verificationUri.toString())
    }

    @Test fun publicFlagOnlyUriKeepsItsFlagAndAddsPublicUserCode() {
        val uri = "https://www.twitch.tv/activate?public=true"
        assertEquals("$uri&device-code=FIXTURE-123",
            decode(createTwitchActivationQrCode(DeviceActivation("FIXTURE-123", URI(uri)))))
    }

    @Test fun encodedMatchingParameterNameIsPreservedWithoutAddingDuplicateCode() {
        val uri = "https://www.twitch.tv/activate?device%2Dcode=FIXTURE%31%32%33&public=true"
        assertEquals(uri, decode(createTwitchActivationQrCode(DeviceActivation("FIXTURE123", URI(uri)))))
    }

    @Test fun unsafeUrisAndMismatchedCodesAreRejectedBeforeEncoding() {
        val invalid = listOf(
            "http://www.twitch.tv/activate", "https://www.twitch.tv.evil.test/activate",
            "https://user@www.twitch.tv/activate", "https://www.twitch.tv:444/activate",
            "https://www.twitch.tv/login", "https://www.twitch.tv/activate#secret",
            "https://www.twitch.tv/activate?device-code=OTHER", "https://www.twitch.tv/activate?public=false",
            "https://www.twitch.tv/activate?public=true&public=true", "https://www.twitch.tv/activate?token=secret",
            "https://www.twitch.tv/activate?device-code=FIXTURE123&device%2Dcode=FIXTURE123",
        )
        invalid.forEach { uri ->
            assertThrows(InvalidDeviceResponse::class.java) {
                createTwitchActivationQrCode(DeviceActivation("FIXTURE123", URI(uri)))
            }
        }
        listOf("ABC", "bad code", "x".repeat(65)).forEach { code ->
            assertThrows(InvalidDeviceResponse::class.java) {
                createTwitchActivationQrCode(DeviceActivation(code, URI("https://www.twitch.tv/activate")))
            }
        }
    }
}
