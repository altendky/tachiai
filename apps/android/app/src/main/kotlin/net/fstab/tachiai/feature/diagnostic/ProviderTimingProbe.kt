package net.fstab.tachiai.feature.diagnostic

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import java.net.URI
import kotlinx.coroutines.delay
import net.fstab.tachiai.platform.web.BrowserPane
import net.fstab.tachiai.platform.web.rememberBrowserPaneController
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.presentation.ResourceLocator
import net.fstab.tachiai.provider.BrowserCommand
import net.fstab.tachiai.provider.BrowserIdentity
import net.fstab.tachiai.provider.BrowserRequest
import net.fstab.tachiai.provider.ProviderAdapter
import net.fstab.tachiai.provider.abema.AbemaAdapter
import net.fstab.tachiai.provider.isExactHttpsOrigin
import org.json.JSONObject
import org.json.JSONTokener

internal const val TIMING_PATH = "/assets/twitch/timing.html"
internal val TIMING_ACTIONS = setOf("read", "play", "pause", "back", "forward", "hold5", "hold15", "edge")

internal class ProviderTimingAdapter(
    val provider: String,
    val kind: String,
    private val resource: String,
    private val allowsExternalNavigation: Boolean = true,
    private val suppressScriptDialogsAndConsole: Boolean = false,
) : ProviderAdapter {
    private val selected: URI
    init {
        require(provider in setOf("abema", "twitch") && kind in setOf("live", "replay"))
        selected = if (provider == "abema") {
            URI(resource).also {
                require((it.isExactHttpsOrigin("abema.tv") || it.isExactHttpsOrigin("www.abema.tv")) &&
                    it.rawQuery == null && it.rawFragment == null)
                require(if (kind == "live") Regex("/(?:channels|now-on-air)/[A-Za-z0-9_-]+(?:/slots/[A-Za-z0-9_-]+)?").matches(it.rawPath)
                    else Regex("/video/episode/[A-Za-z0-9_-]+").matches(it.rawPath))
            }
        } else {
            require(if (kind == "replay") validAlignmentVideo(resource) != null else Regex("[A-Za-z0-9_]{3,25}").matches(resource))
            URI("https://appassets.androidplatform.net$TIMING_PATH?${if (kind == "replay") "video" else "channel"}=$resource")
        }
    }
    override val id = ProviderId("provider-timing-probe")
    override val displayName = "$provider $kind timing probe"
    override fun browserRequest(resource: ResourceLocator): BrowserRequest =
        if (provider == "abema") BrowserRequest(selected.toString(), browserIdentity = BrowserIdentity.DESKTOP_CHROME,
            allowsExternalNavigation = allowsExternalNavigation,
            suppressScriptDialogsAndConsole = suppressScriptDialogsAndConsole)
        else BrowserRequest(selected.toString(), isPackagedAsset = true, acceptsThirdPartyCookies = true,
            allowsExternalNavigation = allowsExternalNavigation,
            suppressScriptDialogsAndConsole = suppressScriptDialogsAndConsole)

    override fun isTopLevelNavigationAllowed(uri: URI): Boolean =
        if (provider == "abema")
            (uri.isExactHttpsOrigin("abema.tv") || uri.isExactHttpsOrigin("www.abema.tv")) &&
                uri.rawPath == selected.rawPath && uri.rawQuery == null && uri.rawFragment == null
        else uri == selected

    override fun isDiagnosticRouteAllowed(uri: URI): Boolean = isTopLevelNavigationAllowed(uri)
    override fun isProtectedMediaOriginAllowed(uri: URI): Boolean =
        if (provider == "abema") AbemaAdapter.isProtectedMediaOriginAllowed(uri)
        else uri.isExactHttpsOrigin("player.twitch.tv")
    override fun scriptFor(command: BrowserCommand, requestedUri: URI, currentUri: URI): String? = null

    fun diagnosticScript(action: String, tools: String, requested: URI, current: URI): String? {
        if (action !in TIMING_ACTIONS || !isTopLevelNavigationAllowed(requested) || !isDiagnosticRouteAllowed(current)) return null
        val expected = JSONObject.quote(selected.rawPath)
        val guard = if (provider == "abema") """
            const isPlaybackRoute = () => location.protocol === 'https:' &&
              (location.port === '' || location.port === '443') &&
              ['abema.tv', 'www.abema.tv'].includes(location.hostname) &&
              location.pathname === $expected && !location.search && !location.hash;
            if (!isPlaybackRoute()) { window.__tachiaiTiming?.dispose(); return 'route unavailable'; }
        """.trimIndent() else ""
        val initialize = if (provider == "abema") """
            if (!window.__tachiaiTiming) {
              const find = () => isPlaybackRoute() ? Array.from(document.querySelectorAll('video'))
                .filter(v => v.isConnected && v.offsetWidth > 0 && v.offsetHeight > 0)
                .sort((a, b) => b.offsetWidth * b.offsetHeight - a.offsetWidth * a.offsetHeight)[0] : null;
              window.__tachiaiTiming = new TachiaiTimingTools.TimingTools(TachiaiTimingTools.mediaBackend(find, '$kind'));
              window.addEventListener('pagehide', () => window.__tachiaiTiming?.dispose(), { once: true });
            }
        """.trimIndent() else ""
        return "(() => { $guard $tools $initialize return window.__tachiaiTiming?.command('$action') || 'tools unavailable'; })()"
    }
}

@Composable
internal fun ProviderTimingProbeScreen(adapter: ProviderTimingAdapter, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val tools = remember { context.assets.open("diagnostics/timing-tools.js").bufferedReader().use { it.readText() } }
    val controller = rememberBrowserPaneController(adapter)
    var readback by remember(adapter) { mutableStateOf("Waiting for player") }
    fun execute(action: String) {
        controller.evaluatePlaybackDiagnostic(
            { requested, current -> adapter.diagnosticScript(action, tools, requested, current) },
        ) { value ->
            readback = runCatching { JSONTokener(value ?: "null").nextValue() as? String }.getOrNull()
                ?: "Readback unavailable"
        }
    }
    LaunchedEffect(adapter, controller) {
        while (true) { execute("read"); delay(1000) }
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            listOf("read" to "Read", "play" to "Play", "pause" to "Pause", "back" to "Back 5",
                "forward" to "Forward 5", "hold5" to "Hold 5", "hold15" to "Hold 15", "edge" to "Edge")
                .forEach { (action, label) -> Button(onClick = { execute(action) }) { Text(label) } }
        }
        Text("${adapter.displayName}: $readback", fontSize = 9.sp, maxLines = 5)
        BrowserPane(adapter, adapter.browserRequest(ResourceLocator("unused")), controller,
            initiallyMuted = false, modifier = Modifier.weight(1f))
    }
}
