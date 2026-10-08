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
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.net.URI
import net.fstab.tachiai.feature.presentation.ABEMA_SUMO_REPLAY_URL
import net.fstab.tachiai.platform.web.BrowserPane
import net.fstab.tachiai.platform.web.rememberBrowserPaneController
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.presentation.PaneId
import net.fstab.tachiai.presentation.ResourceLocator
import net.fstab.tachiai.provider.BrowserCommand
import net.fstab.tachiai.provider.BrowserRequest
import net.fstab.tachiai.provider.BrowserCommandScript
import net.fstab.tachiai.provider.ProviderAdapter
import net.fstab.tachiai.provider.abema.AbemaAdapter
import net.fstab.tachiai.provider.isExactHttpsOrigin
import net.fstab.tachiai.provider.twitch.TwitchCompositeEndpoint

internal val COMPOSITE_ABEMA_PANE = PaneId("abema")
internal val COMPOSITE_TWITCH_PANE = PaneId("twitch")

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
        return abemaRequest.copy(
            acceptsThirdPartyCookies = true,
            packagedChildPaths = TwitchCompositeEndpoint.assetPaths,
        )
    }

    override fun isTopLevelNavigationAllowed(uri: URI): Boolean =
        AbemaAdapter.isTopLevelNavigationAllowed(uri)

    override fun isProtectedMediaOriginAllowed(uri: URI): Boolean =
        AbemaAdapter.isProtectedMediaOriginAllowed(uri) || uri.isExactHttpsOrigin("player.twitch.tv")

    override fun isDiagnosticRouteAllowed(uri: URI): Boolean =
        AbemaAdapter.isDiagnosticRouteAllowed(uri)

    override fun scriptFor(command: BrowserCommand, requestedUri: URI, currentUri: URI): String? =
        AbemaAdapter.scriptFor(command, requestedUri, currentUri)

    override fun scriptForPane(
        pane: PaneId,
        command: BrowserCommand,
        requestId: Long,
        requestedUri: URI,
        currentUri: URI,
    ): BrowserCommandScript? {
        if (!AbemaAdapter.isDiagnosticRouteAllowed(currentUri)) return null
        return when (pane) {
            COMPOSITE_ABEMA_PANE -> AbemaAdapter.scriptFor(command, requestedUri, currentUri)
                ?.let { BrowserCommandScript(it) }
            COMPOSITE_TWITCH_PANE -> TwitchCompositeEndpoint.commandScript(command, requestId)
            else -> null
        }
    }

    override fun focusScriptFor(requestedUri: URI, currentUri: URI): String? {
        if (!AbemaAdapter.isDiagnosticRouteAllowed(currentUri)) return null
        return """
            (() => {
              window.__tachiaiSingleWebContentsCleanup?.();
              const frameId = 'tachiai-twitch-audio-focus-probe';
              const allowedHosts = new Set(['abema.tv', 'www.abema.tv']);
              const isPlaybackRoute = () => location.protocol === 'https:' &&
                (location.port === '' || location.port === '443') &&
                allowedHosts.has(location.hostname) &&
                (location.pathname.startsWith('/video/') ||
                  location.pathname.startsWith('/channels/'));
              const removeFrame = () => document.getElementById(frameId)?.remove();
              const endpoint = { frameId, isPlaybackRoute, requestId: null, result: null };
              window.__tachiaiComposite = endpoint;
              const onResult = (event) => {
                const frame = document.getElementById(frameId);
                if (!isPlaybackRoute() || !frame || event.source !== frame.contentWindow ||
                    event.origin !== '${TwitchCompositeEndpoint.ORIGIN}') return;
                const data = event.data;
                if (!data || typeof data !== 'object' || Object.keys(data).length !== 6 ||
                    data.version !== 1 || data.type !== 'result' || data.channel !== '$twitchChannel' ||
                    data.requestId !== endpoint.requestId || data.frameSession !== endpoint.frameSession ||
                    typeof data.status !== 'string' || data.status.length > 32 ||
                    !/^(play requested|pause requested|mute requested|unmute requested|not ready|failed|volume (100|[0-9]{1,2})% requested)$/.test(data.status)) return;
                endpoint.result = data.status;
              };
              window.addEventListener('message', onResult);
              let timer = null;
              const stopProbe = () => {
                if (timer !== null) clearInterval(timer);
                removeFrame();
                window.removeEventListener('message', onResult);
                delete window.__tachiaiComposite;
                delete window.__tachiaiSingleWebContentsCleanup;
              };
              const installFrame = () => {
                if (!isPlaybackRoute()) {
                  stopProbe();
                  return false;
                }
                if (document.getElementById(frameId)) return true;
                const frameUrl = new URL('${TwitchCompositeEndpoint.ORIGIN}${TwitchCompositeEndpoint.PAGE_PATH}');
                frameUrl.searchParams.set('channel', '$twitchChannel');
                endpoint.frameSession = Array.from(crypto.getRandomValues(new Uint32Array(4)))
                  .map(value => value.toString(16).padStart(8, '0')).join('');
                endpoint.requestId = null;
                endpoint.result = null;
                frameUrl.searchParams.set('session', endpoint.frameSession);
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
                  minHeight: '340px',
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
            listOf(COMPOSITE_ABEMA_PANE to "ABEMA", COMPOSITE_TWITCH_PANE to "Twitch").forEach { (pane, label) ->
                Column {
                    Text("$label: ${controller.statusFor(pane)}", style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            BrowserCommand.PLAY to "Play", BrowserCommand.PAUSE to "Pause",
                            BrowserCommand.MUTE to "Mute", BrowserCommand.UNMUTE to "Sound",
                            BrowserCommand.VOLUME_DOWN to "Vol −", BrowserCommand.VOLUME_UP to "Vol +",
                        ).forEach { (command, text) ->
                            Button(onClick = { controller.execute(command, pane) }) { Text(text) }
                        }
                    }
                }
            }
            Button(onClick = { controller.recover() }) { Text("Reload both") }
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
