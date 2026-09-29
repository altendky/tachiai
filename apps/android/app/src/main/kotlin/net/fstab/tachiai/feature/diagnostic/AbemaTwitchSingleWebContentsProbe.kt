package net.fstab.tachiai.feature.diagnostic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.net.URI
import net.fstab.tachiai.feature.presentation.ABEMA_SUMO_REPLAY_URL
import net.fstab.tachiai.platform.web.BrowserPane
import net.fstab.tachiai.platform.web.rememberBrowserPaneController
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.presentation.ResourceLocator
import net.fstab.tachiai.provider.BrowserCommand
import net.fstab.tachiai.provider.BrowserRequest
import net.fstab.tachiai.provider.ProviderAdapter
import net.fstab.tachiai.provider.abema.AbemaAdapter
import net.fstab.tachiai.provider.isExactHttpsOrigin

internal class AbemaTwitchSingleWebContentsAdapter(
    private val twitchChannel: String,
) : ProviderAdapter {
    private val channelPattern = Regex("[A-Za-z0-9_]{3,25}")

    init {
        require(channelPattern.matches(twitchChannel)) { "Invalid Twitch channel name." }
    }

    override val id = ProviderId("abema-twitch-single-web-contents")
    override val displayName = "ABEMA + Twitch single-WebView probe"

    override fun browserRequest(resource: ResourceLocator): BrowserRequest {
        val abemaRequest = AbemaAdapter.browserRequest(resource)
        return abemaRequest.copy(acceptsThirdPartyCookies = true)
    }

    override fun isTopLevelNavigationAllowed(uri: URI): Boolean =
        AbemaAdapter.isTopLevelNavigationAllowed(uri)

    override fun isProtectedMediaOriginAllowed(uri: URI): Boolean =
        AbemaAdapter.isProtectedMediaOriginAllowed(uri) || uri.isExactHttpsOrigin("player.twitch.tv")

    override fun isDiagnosticRouteAllowed(uri: URI): Boolean =
        AbemaAdapter.isDiagnosticRouteAllowed(uri)

    override fun scriptFor(command: BrowserCommand, requestedUri: URI, currentUri: URI): String? =
        AbemaAdapter.scriptFor(command, requestedUri, currentUri)

    override fun focusScriptFor(requestedUri: URI, currentUri: URI): String? {
        if (!AbemaAdapter.isDiagnosticRouteAllowed(currentUri)) return null
        return """
            (() => {
              window.__tachiaiSingleWebContentsCleanup?.();
              const frameId = 'tachiai-twitch-audio-focus-probe';
              const allowedHosts = new Set(['abema.tv', 'www.abema.tv']);
              const isPlaybackRoute = () => location.protocol === 'https:' &&
                allowedHosts.has(location.hostname) &&
                (location.pathname.startsWith('/video/') ||
                  location.pathname.startsWith('/channels/'));
              const removeFrame = () => document.getElementById(frameId)?.remove();
              let timer = null;
              const stopProbe = () => {
                if (timer !== null) clearInterval(timer);
                removeFrame();
                delete window.__tachiaiSingleWebContentsCleanup;
              };
              const installFrame = () => {
                if (!isPlaybackRoute()) {
                  stopProbe();
                  return false;
                }
                if (document.getElementById(frameId)) return true;
                const frameUrl = new URL('https://player.twitch.tv/');
                frameUrl.searchParams.set('channel', '$twitchChannel');
                frameUrl.searchParams.append('parent', 'abema.tv');
                frameUrl.searchParams.append('parent', 'www.abema.tv');
                frameUrl.searchParams.set('autoplay', 'true');
                frameUrl.searchParams.set('muted', 'true');
                const frame = document.createElement('iframe');
                frame.id = frameId;
                frame.title = 'Twitch $twitchChannel audio-focus probe';
                frame.src = frameUrl.toString();
                frame.allow = 'autoplay; fullscreen';
                frame.allowFullscreen = true;
                Object.assign(frame.style, {
                  position: 'fixed',
                  inset: '0 0 0 auto',
                  width: '50vw',
                  minWidth: '400px',
                  height: '100vh',
                  minHeight: '300px',
                  border: '0',
                  background: 'black',
                  zIndex: '2147483647'
                });
                document.documentElement.appendChild(frame);
                return true;
              };
              timer = setInterval(installFrame, 1000);
              window.__tachiaiSingleWebContentsCleanup = stopProbe;
              installFrame();
              return 'single-WebView probe installed';
            })()
        """.trimIndent()
    }
}

@Composable
fun AbemaTwitchSingleWebContentsProbeScreen(
    twitchChannel: String,
    modifier: Modifier = Modifier,
) {
    val adapter = remember(twitchChannel) { AbemaTwitchSingleWebContentsAdapter(twitchChannel) }
    val controller = rememberBrowserPaneController(adapter)
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Button(onClick = { controller.execute(BrowserCommand.PLAY) }) { Text("ABEMA Play") }
            Button(onClick = { controller.execute(BrowserCommand.PAUSE) }) { Text("ABEMA Pause") }
            Button(onClick = { controller.execute(BrowserCommand.MUTE) }) { Text("ABEMA Mute") }
            Button(onClick = { controller.execute(BrowserCommand.UNMUTE) }) { Text("ABEMA Sound") }
        }
        BrowserPane(
            adapter = adapter,
            request = adapter.browserRequest(ResourceLocator(ABEMA_SUMO_REPLAY_URL)),
            controller = controller,
            initiallyMuted = false,
            modifier = Modifier.weight(1f),
        )
    }
}
