package net.fstab.tachiai.feature.connections

import android.content.ActivityNotFoundException
import android.content.Intent
import java.net.URI
import net.fstab.tachiai.provider.twitch.DeviceActivation
import net.fstab.tachiai.provider.twitch.InvalidDeviceResponse
import net.fstab.tachiai.provider.twitch.parseDeviceActivation
import org.junit.Assert.*
import org.junit.Test

// Synthetic intents only: no browser launch, provider request or account storage.
class TwitchActivationBrowserTest {
    @Test fun bothBrowsersReceiveThePublicCodeForBareAndPublicOnlyLinks() {
        val base = "https://www.twitch.tv/activate"
        listOf(base to "$base?device-code=FIXTURE-123",
            "$base?public=true" to "$base?public=true&device-code=FIXTURE-123").forEach { (uri, expected) ->
            listOf("com.android.chrome", "com.brave.browser").forEach { browser ->
                val intent = createTwitchActivationBrowserIntent(DeviceActivation("FIXTURE-123", URI(uri)), browser)
                assertEquals(expected, intent.data.toString())
                assertEquals(Intent.ACTION_VIEW, intent.action)
                assertEquals(setOf(Intent.CATEGORY_BROWSABLE), intent.categories)
                assertEquals(browser, intent.`package`)
            }
        }
    }

    @Test fun matchingEncodedProviderLinkIsPreservedExactly() {
        val uri = "https://www.twitch.tv/activate?device%2Dcode=FIXTURE%31%32%33&public=true"
        val intent = createTwitchActivationBrowserIntent(DeviceActivation("FIXTURE123", URI(uri)), "com.android.chrome")
        assertEquals(uri, intent.data.toString())
    }

    @Test fun unsafeDestinationOrDifferentCodeCannotCreateBrowserIntent() {
        listOf("https://www.twitch.tv.evil.test/activate",
            "https://www.twitch.tv/activate?device-code=OTHER").forEach { uri ->
            assertThrows(InvalidDeviceResponse::class.java) {
                createTwitchActivationBrowserIntent(DeviceActivation("FIXTURE123", URI(uri)), "com.android.chrome")
            }
        }
    }

    @Test fun rejectedPrefillAndUnavailableBrowsersReturnFailureWithoutEscaping() {
        val prefix = "https://www.twitch.tv:"
        val suffix = "443/activate"
        val nearLimit = prefix + "0".repeat(2048 - prefix.length - suffix.length) + suffix
        val activation = parseDeviceActivation("FIXTURE123", nearLimit)
        assertFalse(openTwitchActivationBrowser(activation, "com.android.chrome") {
            fail("Rejected destination must not launch a browser")
        })
        val valid = DeviceActivation("FIXTURE123", URI("https://www.twitch.tv/activate"))
        listOf(ActivityNotFoundException(), SecurityException()).forEach { error ->
            assertFalse(openTwitchActivationBrowser(valid, "com.android.chrome") { throw error })
        }
        var destination: String? = null
        assertTrue(openTwitchActivationBrowser(valid, "com.android.chrome") { destination = it.data.toString() })
        assertEquals("https://www.twitch.tv/activate?device-code=FIXTURE123", destination)
    }
}
