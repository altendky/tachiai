package net.fstab.tachiai

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import net.fstab.tachiai.feature.diagnostic.AudioFocusProbeScreen
import net.fstab.tachiai.feature.diagnostic.AbemaTwitchSingleWebContentsProbeScreen
import net.fstab.tachiai.feature.diagnostic.TwitchReplayAlignmentProbeScreen
import net.fstab.tachiai.feature.diagnostic.DEFAULT_ALIGNMENT_VIDEO
import net.fstab.tachiai.feature.diagnostic.validAlignmentVideo
import net.fstab.tachiai.feature.diagnostic.ProviderTimingAdapter
import net.fstab.tachiai.feature.diagnostic.ProviderTimingProbeScreen
import net.fstab.tachiai.feature.diagnostic.TwitchSignInProbeScreen
import net.fstab.tachiai.feature.diagnostic.TwitchDeviceAuthProbeScreen
import net.fstab.tachiai.feature.diagnostic.NativeAccessProbeScreen
import net.fstab.tachiai.feature.diagnostic.initialTwitchSessionStage
import net.fstab.tachiai.feature.diagnostic.DebugLaunchState
import net.fstab.tachiai.feature.diagnostic.DebugLaunchSource
import net.fstab.tachiai.feature.diagnostic.DebugLaunchRoute
import net.fstab.tachiai.feature.diagnostic.logDebugLaunch
import net.fstab.tachiai.feature.diagnostic.nativeAccessAtStartup
import net.fstab.tachiai.feature.diagnostic.streamPickerAtStartup
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
private const val EXTRA_REPLAY_ALIGNMENT_PROBE = "net.fstab.tachiai.extra.REPLAY_ALIGNMENT_PROBE"
private const val EXTRA_TWITCH_VIDEO = "net.fstab.tachiai.extra.TWITCH_VIDEO"
private const val EXTRA_TIMING_PROBE = "net.fstab.tachiai.extra.TIMING_PROBE"
private const val EXTRA_TWITCH_SIGN_IN = "net.fstab.tachiai.extra.TWITCH_SIGN_IN"
private const val EXTRA_TWITCH_WEB_MODE = "net.fstab.tachiai.extra.TWITCH_WEB_MODE"
private const val EXTRA_TWITCH_DEVICE_AUTH = "net.fstab.tachiai.extra.TWITCH_DEVICE_AUTH"
private const val EXTRA_NATIVE_ACCESS_PROBE = "net.fstab.tachiai.extra.NATIVE_ACCESS_PROBE"

class MainActivity : ComponentActivity() {
    private val debugLaunch = DebugLaunchState()
    private val accountReadiness by lazy { net.fstab.tachiai.feature.connections.startupAccountReadiness(this) }

    override fun onResume() { super.onResume(); accountReadiness.onResume() }
    override fun onPause() { accountReadiness.onPause(); super.onPause() }
    override fun onDestroy() { accountReadiness.close(); super.onDestroy() }

    private fun nativeMenuRequest() = if (BuildConfig.DEBUG && intent.hasExtra(EXTRA_NATIVE_ACCESS_PROBE))
        intent.getBooleanExtra(EXTRA_NATIVE_ACCESS_PROBE, false) else null

    private fun isLauncher() = intent.action == Intent.ACTION_MAIN &&
        (intent.hasCategory(Intent.CATEGORY_LAUNCHER) || intent.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER))

    private fun hasExplicitDiagnostic() = BuildConfig.DEBUG && intent.extras?.keySet()?.any {
        it.startsWith("net.fstab.tachiai.extra.") && it != EXTRA_NATIVE_ACCESS_PROBE
    } == true

    private fun showStreamPicker() = streamPickerAtStartup(BuildConfig.DEBUG, android.os.Build.VERSION.SDK_INT,
        nativeMenuRequest(), isLauncher(), hasExplicitDiagnostic())

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun openStreamPicker(): Boolean {
        if (!showStreamPicker()) return false
        startActivity(Intent(this, net.fstab.tachiai.feature.presentation.CachedPrototypeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
        return true
    }

    private fun showNativeAccessMenu() = nativeAccessAtStartup(
        debug = BuildConfig.DEBUG,
        requested = nativeMenuRequest(),
        launcher = isLauncher(),
        explicitDiagnostic = hasExplicitDiagnostic(),
    )

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        debugLaunch.delivered(BuildConfig.DEBUG)
        reportDebugLaunch(DebugLaunchSource.NEW_INTENT)
        openStreamPicker()
    }

    private fun reportDebugLaunch(source: DebugLaunchSource) {
        logDebugLaunch(source,
            when {
                showStreamPicker() -> DebugLaunchRoute.STREAM_PICKER
                showNativeAccessMenu() -> DebugLaunchRoute.NATIVE_ACCESS
                BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_TWITCH_DEVICE_AUTH, false) -> DebugLaunchRoute.TWITCH_DEVICE_AUTH
                BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_TWITCH_SIGN_IN, false) -> DebugLaunchRoute.TWITCH_SESSION
                else -> DebugLaunchRoute.OTHER
            },
            debugLaunch.revision)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reportDebugLaunch(DebugLaunchSource.CREATE)
        if (openStreamPicker()) return
        enableEdgeToEdge()
        if (!BuildConfig.DEBUG || android.os.Build.VERSION.SDK_INT < 28 &&
            nativeMenuRequest() == null && !hasExplicitDiagnostic()) {
            setContent { MaterialTheme {
                androidx.compose.material3.Text("Tachiai requires Android 9 or later.", Modifier.safeDrawingPadding())
            } }
            return
        }
        setContent {
            DebugLaunchTheme(debugLaunch.revision) {
                if (showNativeAccessMenu()) {
                    NativeAccessProbeScreen(Modifier.safeDrawingPadding())
                    return@DebugLaunchTheme
                }
                if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_TWITCH_DEVICE_AUTH, false)) {
                    TwitchDeviceAuthProbeScreen(Modifier.safeDrawingPadding(), inspectSmartTvLifetime = true)
                    return@DebugLaunchTheme
                }
                if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_TWITCH_SIGN_IN, false)) {
                    val channel = intent.getStringExtra(EXTRA_TWITCH_CHANNEL) ?: "bobross"
                    if (Regex("[A-Za-z0-9_]{3,25}").matches(channel)) {
                        TwitchSignInProbeScreen(channel, window, Modifier.safeDrawingPadding(),
                            initialTwitchSessionStage(intent.getStringExtra(EXTRA_TWITCH_WEB_MODE)))
                    } else androidx.compose.material3.Text("Invalid Twitch session probe configuration")
                    return@DebugLaunchTheme
                }
                if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_TIMING_PROBE, false)) {
                    val adapter = runCatching {
                        val provider = intent.getStringExtra("net.fstab.tachiai.extra.TIMING_PROVIDER") ?: "twitch"
                        val kind = intent.getStringExtra("net.fstab.tachiai.extra.TIMING_KIND") ?: "replay"
                        val resource = intent.getStringExtra("net.fstab.tachiai.extra.TIMING_RESOURCE") ?: DEFAULT_ALIGNMENT_VIDEO
                        ProviderTimingAdapter(provider, kind, resource)
                    }.getOrNull()
                    if (adapter != null) ProviderTimingProbeScreen(adapter, Modifier.safeDrawingPadding())
                    else androidx.compose.material3.Text("Invalid timing probe configuration")
                    return@DebugLaunchTheme
                }
                if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_REPLAY_ALIGNMENT_PROBE, false)) {
                    TwitchReplayAlignmentProbeScreen(
                        video = validAlignmentVideo(intent.getStringExtra(EXTRA_TWITCH_VIDEO))
                            ?: DEFAULT_ALIGNMENT_VIDEO,
                        modifier = Modifier.safeDrawingPadding(),
                    )
                    return@DebugLaunchTheme
                }
                val audioFocusProbe = BuildConfig.DEBUG &&
                    intent.getBooleanExtra(EXTRA_AUDIO_FOCUS_PROBE, false)
                if (audioFocusProbe) {
                    AudioFocusProbeScreen(Modifier.safeDrawingPadding())
                    return@DebugLaunchTheme
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
                    return@DebugLaunchTheme
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

// A new debug launch replaces the old probe even when its parameters match.
// Keeping this local wrapper avoids reformatting unrelated screen routing.
@Composable
private fun DebugLaunchTheme(revision: Int, content: @Composable () -> Unit) {
    key(revision) { MaterialTheme(content = content) }
}
