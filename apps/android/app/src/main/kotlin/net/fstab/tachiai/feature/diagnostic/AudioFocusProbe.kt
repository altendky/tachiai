package net.fstab.tachiai.feature.diagnostic

import androidx.compose.runtime.Composable
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

private const val ASSET_HOST = "appassets.androidplatform.net"
private const val ASSET_PATH = "/assets/diagnostics/audio-focus.html"
private const val ASSET_URL = "https://$ASSET_HOST$ASSET_PATH"

internal object AudioFocusProbeAdapter : ProviderAdapter {
    override val id = ProviderId("audio-focus-probe")
    override val displayName = "Audio focus probe"

    override fun browserRequest(resource: ResourceLocator) = BrowserRequest(
        url = ASSET_URL,
        isPackagedAsset = true,
    )

    override fun isTopLevelNavigationAllowed(uri: URI): Boolean =
        uri.isExactHttpsOrigin(ASSET_HOST) && uri.path == ASSET_PATH && uri.rawQuery == null

    override fun isProtectedMediaOriginAllowed(uri: URI): Boolean = false

    override fun scriptFor(command: BrowserCommand, requestedUri: URI, currentUri: URI): String? = null
}

@Composable
fun AudioFocusProbeScreen(modifier: Modifier = Modifier) {
    val controller = rememberBrowserPaneController(AudioFocusProbeAdapter)
    BrowserPane(
        adapter = AudioFocusProbeAdapter,
        request = AudioFocusProbeAdapter.browserRequest(ResourceLocator("probe")),
        controller = controller,
        initiallyMuted = false,
        modifier = modifier,
    )
}
