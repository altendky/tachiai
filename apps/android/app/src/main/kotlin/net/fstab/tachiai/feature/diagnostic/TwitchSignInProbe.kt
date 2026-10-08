package net.fstab.tachiai.feature.diagnostic

import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.net.URI
import net.fstab.tachiai.platform.web.BrowserPane
import net.fstab.tachiai.platform.web.rememberBrowserPaneController
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.presentation.ResourceLocator
import net.fstab.tachiai.provider.BrowserCommand
import net.fstab.tachiai.provider.BrowserIdentity
import net.fstab.tachiai.provider.BrowserProbeRole
import net.fstab.tachiai.provider.BrowserRequest
import net.fstab.tachiai.provider.ProviderAdapter
import net.fstab.tachiai.provider.isExactHttpsOrigin
import net.fstab.tachiai.provider.twitch.TwitchLoginResourceClassifier

internal const val TWITCH_SIGN_IN_URL = "https://www.twitch.tv/login"

// No page scripts, focus mode, session readback, or external auth handoff.
// Native callback/geometry markers do not inspect provider content.
// The first-party website alone owns login and the session in the normal profile.
internal class TwitchSessionAdapter(
    val login: Boolean,
    channel: String,
    private val desktop: Boolean = false,
    private val wideViewport: Boolean = false,
    private val overviewMode: Boolean = false,
    private val defaultDialogs: Boolean = false,
) : ProviderAdapter {
    init { require(Regex("[A-Za-z0-9_]{3,25}").matches(channel)) }
    private val initial = if (login) TWITCH_SIGN_IN_URL else "https://www.twitch.tv/$channel"
    override val id = ProviderId("twitch-session-probe")
    override val displayName = "Twitch session probe"
    override fun browserRequest(resource: ResourceLocator) = BrowserRequest(
        initial,
        acceptsThirdPartyCookies = true,
        browserIdentity = if (desktop && !login) BrowserIdentity.DESKTOP_CHROME else BrowserIdentity.DEFAULT,
        allowsExternalNavigation = false,
        suppressScriptDialogsAndConsole = true,
        logNativePageEvents = true,
        nativeProbeRole = if (login) BrowserProbeRole.LOGIN else if (desktop) BrowserProbeRole.DESKTOP_WEB else BrowserProbeRole.WEB,
        debugUseDefaultScriptDialogs = !login && !desktop && defaultDialogs,
        useWideViewport = login && wideViewport,
        loadWithOverviewMode = login && wideViewport && overviewMode,
    )
    override fun isTopLevelNavigationAllowed(uri: URI) =
        uri.isExactHttpsOrigin("m.twitch.tv") || uri.isExactHttpsOrigin("www.twitch.tv")
    override fun isProtectedMediaOriginAllowed(uri: URI) = !login && uri.isExactHttpsOrigin("player.twitch.tv")
    override fun scriptFor(command: BrowserCommand, requestedUri: URI, currentUri: URI): String? = null
}

// App-owned wrapper only; Twitch owns its embed, Sign In controls and popup.
internal class TwitchEverythingSessionAdapter(channel: String) : ProviderAdapter {
    init { require(Regex("[A-Za-z0-9_]{3,25}").matches(channel)) }
    private val initial = URI("https://appassets.androidplatform.net/assets/twitch/session.html?channel=$channel")
    override val id = ProviderId("twitch-everything-session-probe")
    override val displayName = "Twitch full embed session probe"
    override fun browserRequest(resource: ResourceLocator) = BrowserRequest(
        initial.toString(), isPackagedAsset = true, acceptsThirdPartyCookies = true,
        // UA-only comparison: the real popup inherits this native identity.
        // Retains the installed engine version; acceptance is not established.
        browserIdentity = BrowserIdentity.MOBILE_CHROME,
        allowsExternalNavigation = false, popupHttpsHosts = setOf("www.twitch.tv"),
        popupDiagnosticAlternateHttpsHosts = setOf("m.twitch.tv"),
        suppressScriptDialogsAndConsole = true,
        logNativePageEvents = true,
        nativeProbeRole = BrowserProbeRole.EMBED,
    )
    override fun isTopLevelNavigationAllowed(uri: URI) = uri == initial
    override fun isProtectedMediaOriginAllowed(uri: URI) = uri.isExactHttpsOrigin("player.twitch.tv")
    override fun diagnosticResourceCategory(uri: URI) = TwitchLoginResourceClassifier.classify(uri)
    override fun scriptFor(command: BrowserCommand, requestedUri: URI, currentUri: URI): String? = null
}

internal enum class SessionStage { EVERYTHING, SIGN_IN, WEB, DEFAULT_DIALOGS_WEB, DESKTOP_WEB, EMBED }
private enum class LoginViewport { DEFAULT, WIDE, WIDE_OVERVIEW }

internal fun initialTwitchSessionStage(mode: String?): SessionStage = when (mode) {
    "web" -> SessionStage.WEB
    "default-dialogs" -> SessionStage.DEFAULT_DIALOGS_WEB
    else -> SessionStage.EVERYTHING
}

// Keep the compared WEB headers identical so labels cannot change viewport height.
// The selected dialog policy is recorded by the browser's native HOST_POLICY marker.
internal fun twitchSessionStageLabel(stage: SessionStage): String =
    if (stage == SessionStage.DEFAULT_DIALOGS_WEB) SessionStage.WEB.name else stage.name

@Composable
internal fun TwitchSignInProbeScreen(channel: String, window: Window, modifier: Modifier = Modifier,
    initialStage: SessionStage = SessionStage.EVERYTHING) {
    var stage by remember { mutableStateOf(initialStage) }
    var viewport by remember { mutableStateOf(LoginViewport.DEFAULT) }
    // Remains protected through manual web viewing because Twitch can show a
    // login modal without changing its channel URL. Do not capture this screen.
    DisposableEffect(window) {
        val wasSecure = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!wasSecure) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    fun changeStage(next: SessionStage) {
        CookieManager.getInstance().flush() // Persist without reading/copying any cookie values.
        stage = next
    }
    Column(modifier) {
        if (stage == SessionStage.SIGN_IN || stage == SessionStage.EVERYTHING) {
            Text(if (stage == SessionStage.EVERYTHING)
                "Use Twitch's own Log In / Sign In in the chat area below, not the Twitch logo. No page inspection or screenshots."
                else "Direct Twitch login rendering test · viewport=$viewport. Do not enter credentials yet. No page inspection or screenshots. Done does not verify login.")
            if (stage == SessionStage.EVERYTHING) {
                Button(onClick = { changeStage(SessionStage.SIGN_IN) }) { Text("Direct Twitch login") }
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Button(onClick = { viewport = LoginViewport.DEFAULT }) { Text("Default viewport") }
                    Button(onClick = { viewport = LoginViewport.WIDE }) { Text("Wide viewport") }
                    Button(onClick = { viewport = LoginViewport.WIDE_OVERVIEW }) { Text("Wide + overview") }
                }
            }
            Button(onClick = { changeStage(SessionStage.WEB) }) { Text("Done — open Twitch") }
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Button(onClick = { changeStage(SessionStage.EVERYTHING) }) { Text("Full embed / sign in") }
                Button(onClick = { changeStage(SessionStage.SIGN_IN) }) { Text("Direct Twitch login") }
                Button(onClick = { changeStage(SessionStage.WEB) }) { Text("Web") }
                Button(onClick = { changeStage(SessionStage.DEFAULT_DIALOGS_WEB) }) { Text("Web — default dialogs") }
                Button(onClick = { changeStage(SessionStage.DESKTOP_WEB) }) { Text("Desktop web") }
                Button(onClick = { changeStage(SessionStage.EMBED) }) { Text("Embed") }
            }
            Text("$channel · ${twitchSessionStageLabel(stage)} · use Twitch's original controls; Low Latency is in Settings / Advanced where available.")
        }
        if (stage == SessionStage.EMBED) {
            val adapter = remember(channel) {
                ProviderTimingAdapter("twitch", "live", channel, allowsExternalNavigation = false,
                    suppressScriptDialogsAndConsole = true)
            }
            ProviderTimingProbeScreen(adapter, Modifier.weight(1f))
        } else {
            val adapter: ProviderAdapter = remember(stage, channel, viewport) {
                if (stage == SessionStage.EVERYTHING) TwitchEverythingSessionAdapter(channel)
                else TwitchSessionAdapter(stage == SessionStage.SIGN_IN, channel,
                    stage == SessionStage.DESKTOP_WEB, viewport != LoginViewport.DEFAULT,
                    viewport == LoginViewport.WIDE_OVERVIEW, stage == SessionStage.DEFAULT_DIALOGS_WEB)
            }
            val controller = rememberBrowserPaneController(adapter)
            // onPageFinished establishes only a document load, not a rendered
            // authentication form or successful provider session.
            Text(if (controller.status == "Ready for user interaction")
                "Page load finished; Twitch form and login are unverified"
                else controller.status)
            controller.latestPopupEvent?.let { Text("Popup: ${it.fixedMessage()}") }
            controller.lastPopupFailure?.let { Text("Last popup failure: ${it.fixedMessage()}") }
            if (stage == SessionStage.SIGN_IN || stage == SessionStage.EVERYTHING) {
                Button(onClick = controller::recover) { Text("Retry page") }
            }
            BrowserPane(adapter, adapter.browserRequest(ResourceLocator("unused")), controller,
                initiallyMuted = false, modifier = Modifier.weight(1f))
        }
    }
}
