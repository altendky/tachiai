package net.fstab.tachiai.feature.diagnostic

import java.net.URI
import net.fstab.tachiai.presentation.ResourceLocator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFocusProbeTest {
    @Test
    fun `probe is limited to its exact packaged asset`() {
        val request = AudioFocusProbeAdapter.browserRequest(ResourceLocator("ignored"))

        assertTrue(AudioFocusProbeAdapter.isTopLevelNavigationAllowed(URI(request.url)))
        assertFalse(
            AudioFocusProbeAdapter.isTopLevelNavigationAllowed(
                URI("https://appassets.androidplatform.net/assets/diagnostics/other.html"),
            ),
        )
        assertFalse(
            AudioFocusProbeAdapter.isTopLevelNavigationAllowed(
                URI("https://appassets.androidplatform.net/assets/diagnostics/audio-focus.html?extra=1"),
            ),
        )
    }
}
