package net.fstab.tachiai.feature.diagnostic

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import java.net.URI
import net.fstab.tachiai.platform.web.BrowserPane
import net.fstab.tachiai.platform.web.rememberBrowserPaneController
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.presentation.ResourceLocator
import net.fstab.tachiai.provider.BrowserCommand
import net.fstab.tachiai.provider.BrowserRequest
import net.fstab.tachiai.provider.ProviderAdapter
import net.fstab.tachiai.provider.isExactHttpsOrigin

internal const val DEFAULT_ALIGNMENT_VIDEO = "2080217716"
internal const val ALIGNMENT_PATH = "/assets/twitch/replay-alignment.html"

internal fun validAlignmentVideo(value: String?): String? =
    value?.takeIf { Regex("[1-9][0-9]{0,19}").matches(it) }

internal class TwitchReplayAlignmentAdapter(private val video: String) : ProviderAdapter {
    init {
        require(validAlignmentVideo(video) != null) { "Invalid public Twitch video ID." }
    }

    override val id = ProviderId("twitch-replay-alignment")
    override val displayName = "Same-replay alignment probe"

    override fun browserRequest(resource: ResourceLocator) = BrowserRequest(
        url = "https://appassets.androidplatform.net$ALIGNMENT_PATH?video=$video",
        isPackagedAsset = true,
        acceptsThirdPartyCookies = true,
    )

    override fun isTopLevelNavigationAllowed(uri: URI): Boolean =
        uri.isExactHttpsOrigin("appassets.androidplatform.net") && uri.rawPath == ALIGNMENT_PATH

    override fun isProtectedMediaOriginAllowed(uri: URI): Boolean =
        uri.isExactHttpsOrigin("player.twitch.tv")

    // Direct app-page gestures call the official SDK; no injection into provider pages.
    override fun scriptFor(command: BrowserCommand, requestedUri: URI, currentUri: URI): String? = null
}

@Composable
internal fun TwitchReplayAlignmentProbeScreen(video: String, modifier: Modifier = Modifier) {
    val adapter = remember(video) { TwitchReplayAlignmentAdapter(video) }
    val request = remember(adapter) { adapter.browserRequest(ResourceLocator("twitch:video:$video")) }
    val controller = rememberBrowserPaneController(adapter)
    BrowserPane(
        adapter = adapter,
        request = request,
        controller = controller,
        initiallyMuted = false,
        modifier = modifier.fillMaxSize(),
    )
}
