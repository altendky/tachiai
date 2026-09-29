package net.fstab.tachiai

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import net.fstab.tachiai.feature.diagnostic.AudioFocusProbeScreen
import net.fstab.tachiai.feature.diagnostic.AbemaTwitchSingleWebContentsProbeScreen
import net.fstab.tachiai.feature.presentation.ABEMA_SUMO_TITLE_URL
import net.fstab.tachiai.feature.presentation.PresentationScreen
import net.fstab.tachiai.feature.presentation.createAbemaDiagnosticPresentation
import net.fstab.tachiai.feature.presentation.createDualTwitchDiagnosticPresentation
import net.fstab.tachiai.feature.presentation.createInitialSpikePresentation
import net.fstab.tachiai.feature.presentation.createTwitchDiagnosticPresentation
import net.fstab.tachiai.provider.BrowserIdentity
import net.fstab.tachiai.provider.twitch.TwitchAdapter

private const val EXTRA_TWITCH_CHANNEL = "net.fstab.tachiai.extra.TWITCH_CHANNEL"
private const val EXTRA_TWITCH_SECONDARY_CHANNEL = "net.fstab.tachiai.extra.TWITCH_SECONDARY_CHANNEL"
private const val EXTRA_TWITCH_ONLY = "net.fstab.tachiai.extra.TWITCH_ONLY"
private const val EXTRA_TWITCH_FULL_SITE = "net.fstab.tachiai.extra.TWITCH_FULL_SITE"
private const val EXTRA_ABEMA_ONLY = "net.fstab.tachiai.extra.ABEMA_ONLY"
private const val EXTRA_ABEMA_URL = "net.fstab.tachiai.extra.ABEMA_URL"
private const val EXTRA_ABEMA_USER_AGENT = "net.fstab.tachiai.extra.ABEMA_USER_AGENT"
private const val EXTRA_AUDIO_FOCUS_PROBE = "net.fstab.tachiai.extra.AUDIO_FOCUS_PROBE"
private const val EXTRA_SINGLE_WEB_CONTENTS_PROBE =
    "net.fstab.tachiai.extra.SINGLE_WEB_CONTENTS_PROBE"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                val audioFocusProbe = BuildConfig.DEBUG &&
                    intent.getBooleanExtra(EXTRA_AUDIO_FOCUS_PROBE, false)
                if (audioFocusProbe) {
                    AudioFocusProbeScreen(Modifier.safeDrawingPadding())
                    return@MaterialTheme
                }
                val twitchChannel = if (BuildConfig.DEBUG) {
                    intent.getStringExtra(EXTRA_TWITCH_CHANNEL)
                } else {
                    null
                }
                val selectedChannel = twitchChannel ?: TwitchAdapter.CHANNEL
                val singleWebContentsProbe = BuildConfig.DEBUG &&
                    intent.getBooleanExtra(EXTRA_SINGLE_WEB_CONTENTS_PROBE, false)
                if (singleWebContentsProbe) {
                    AbemaTwitchSingleWebContentsProbeScreen(
                        twitchChannel = selectedChannel,
                        modifier = Modifier.safeDrawingPadding(),
                    )
                    return@MaterialTheme
                }
                val secondaryChannel = if (BuildConfig.DEBUG) {
                    intent.getStringExtra(EXTRA_TWITCH_SECONDARY_CHANNEL)
                } else {
                    null
                }
                val twitchOnly = BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_TWITCH_ONLY, false)
                val twitchFullSite = BuildConfig.DEBUG &&
                    intent.getBooleanExtra(EXTRA_TWITCH_FULL_SITE, false)
                val abemaOnly = BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_ABEMA_ONLY, false)
                val abemaUrl = if (BuildConfig.DEBUG) intent.getStringExtra(EXTRA_ABEMA_URL) else null
                val diagnosticBrowserIdentity = if (BuildConfig.DEBUG && abemaOnly) {
                    when (intent.getStringExtra(EXTRA_ABEMA_USER_AGENT)) {
                        "mobile-chrome" -> BrowserIdentity.MOBILE_CHROME
                        "desktop-chrome" -> BrowserIdentity.DESKTOP_CHROME
                        else -> BrowserIdentity.DEFAULT
                    }
                } else {
                    null
                }
                val presentation = when {
                    abemaOnly -> createAbemaDiagnosticPresentation(abemaUrl ?: ABEMA_SUMO_TITLE_URL)
                    twitchOnly && secondaryChannel != null -> createDualTwitchDiagnosticPresentation(
                        selectedChannel,
                        secondaryChannel,
                        twitchFullSite,
                    )
                    twitchOnly -> createTwitchDiagnosticPresentation(selectedChannel, twitchFullSite)
                    else -> createInitialSpikePresentation(selectedChannel)
                }
                PresentationScreen(
                    presentation = presentation,
                    modifier = Modifier.safeDrawingPadding(),
                    allowCompactTwoPane = BuildConfig.DEBUG && presentation.panes.size > 1,
                    diagnosticBrowserIdentity = diagnosticBrowserIdentity,
                )
            }
        }
    }
}
