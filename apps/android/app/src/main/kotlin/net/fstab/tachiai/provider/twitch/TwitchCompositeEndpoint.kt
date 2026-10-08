package net.fstab.tachiai.provider.twitch

import java.util.Locale
import net.fstab.tachiai.provider.BrowserCommand
import net.fstab.tachiai.provider.BrowserCommandScript

internal object TwitchCompositeEndpoint {
    const val ORIGIN = "https://appassets.androidplatform.net"
    const val PAGE_PATH = "/assets/twitch/composite.html"
    val assetPaths = setOf(PAGE_PATH, "/assets/twitch/composite.js")

    fun commandScript(command: BrowserCommand, requestId: Long): BrowserCommandScript {
        require(requestId > 0 && requestId <= 9_007_199_254_740_991L)
        val name = command.name.lowercase(Locale.ROOT)
        return BrowserCommandScript(
            send = """
                (() => {
                  const endpoint = window.__tachiaiComposite;
                  if (!endpoint || !endpoint.isPlaybackRoute()) return 'unavailable';
                  const frame = document.getElementById(endpoint.frameId);
                  if (!frame || !frame.contentWindow) return 'unavailable';
                  endpoint.requestId = $requestId;
                  endpoint.result = null;
                  frame.contentWindow.postMessage({
                    version: 1, type: 'command', requestId: $requestId,
                    command: '$name', frameSession: endpoint.frameSession
                  }, '$ORIGIN');
                  return 'pending';
                })()
            """.trimIndent(),
            poll = """
                (() => {
                  const endpoint = window.__tachiaiComposite;
                  if (!endpoint || !endpoint.isPlaybackRoute()) return 'unavailable';
                  return endpoint.requestId === $requestId ? endpoint.result : null;
                })()
            """.trimIndent(),
        )
    }
}
