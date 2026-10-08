package net.fstab.tachiai.feature.diagnostic

import net.fstab.tachiai.provider.twitch.DeviceAuthPhase
import net.fstab.tachiai.provider.twitch.DeviceAuthEndpoint
import net.fstab.tachiai.provider.twitch.DeviceHttpFailure
import net.fstab.tachiai.provider.twitch.DeviceNetworkFailure
import net.fstab.tachiai.provider.twitch.DeviceRequestStage
import net.fstab.tachiai.provider.twitch.DeviceActivation
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchDeviceAuthProbeTest {
    @Test fun `copy forwards only the current user code without activation URL`() {
        val activation = DeviceActivation("COPY123", URI("https://www.twitch.tv/activate?device-code=other-fixture"))
        var copied: String? = null
        assertTrue(copyDeviceActivationCode(activation) { copied = it })
        assertEquals("COPY123", copied)
    }

    @Test fun `copy without an active challenge does not touch the clipboard`() {
        var calls = 0
        assertFalse(copyDeviceActivationCode(null) { calls++ })
        assertEquals(0, calls)
    }

    @Test fun `clipboard failure becomes a fixed unsuccessful result`() {
        val activation = DeviceActivation("COPY123", URI("https://www.twitch.tv/activate"))
        assertFalse(copyDeviceActivationCode(activation) { throw SecurityException("private fixture detail") })
    }

    @Test fun `status messages are fixed and do not claim player authentication`() {
        DeviceAuthPhase.entries.forEach { phase -> assertTrue(deviceAuthStatus(phase).isNotBlank()) }
        val success = deviceAuthStatus(DeviceAuthPhase.SUCCEEDED)
        assertTrue(success.contains("Token discarded"))
        assertTrue(success.contains("remain unverified"))
        assertFalse(DeviceAuthPhase.WAITING.terminal)
        assertTrue(DeviceAuthPhase.CANCELLED.terminal)
    }

    @Test fun `failure summary contains only closed categories and numeric duration`() {
        assertEquals("TOKEN / READ / TIMEOUT / 15000 ms", deviceFailureSummary(DeviceHttpFailure(
            DeviceAuthEndpoint.TOKEN, DeviceRequestStage.READ, DeviceNetworkFailure.TIMEOUT, 15000)))
    }

    @Test fun `activation routes target full browsers rather than Twitch`() {
        assertEquals(listOf("com.brave.browser", "com.android.chrome"),
            DeviceActivationBrowser.entries.map { it.packageName })
        assertFalse(DeviceActivationBrowser.entries.any { it.packageName.contains("twitch") })
        assertTrue(deviceAuthStatus(DeviceAuthPhase.BROWSER_UNAVAILABLE).contains("No app fallback"))
    }
}
