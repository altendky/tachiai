package net.fstab.tachiai.feature.presentation

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.lifecycle.Lifecycle
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.R
import net.fstab.tachiai.feature.connections.ConnectionProfilesActivity
import net.fstab.tachiai.feature.connections.ObsoleteSourceSetupException
import net.fstab.tachiai.feature.connections.ProvidersActivity
import net.fstab.tachiai.feature.connections.sourceSetupStore
import net.fstab.tachiai.feature.connections.streamQualityStore
import net.fstab.tachiai.feature.connections.configuredSourceStore
import net.fstab.tachiai.platform.media.NativeQualityKind
import net.fstab.tachiai.platform.media.NativeQualityPreferences
import net.fstab.tachiai.platform.media.NativeQualityRequest
import net.fstab.tachiai.presentation.ViewerQualityState
import net.fstab.tachiai.feature.connections.providerInstanceStore
import net.fstab.tachiai.feature.connections.legacyProviderSettings
import net.fstab.tachiai.presentation.SourceSetup
import net.fstab.tachiai.presentation.SourceRouteChoice
import net.fstab.tachiai.presentation.PrototypeSource
import net.fstab.tachiai.presentation.defaultSourceSetups
import net.fstab.tachiai.presentation.defaultProviderInstances
import net.fstab.tachiai.presentation.ProviderInstance
import net.fstab.tachiai.presentation.ConfiguredSource
import net.fstab.tachiai.presentation.ConfiguredFeedChoice
import net.fstab.tachiai.presentation.ConfiguredFeedAssignments
import net.fstab.tachiai.presentation.ConfiguredPrototypeSelectionResult
import net.fstab.tachiai.presentation.resolveConfiguredPrototypeSelection
import net.fstab.tachiai.presentation.legacyConfiguredSources
import net.fstab.tachiai.presentation.configuredSourceDisplayTitle
import net.fstab.tachiai.presentation.restoreConfiguredFeedAssignments
import net.fstab.tachiai.presentation.encodeConfiguredFeedChoice
import net.fstab.tachiai.platform.network.RouteSession
import net.fstab.tachiai.platform.network.ConnectionProfile
import net.fstab.tachiai.platform.network.AbemaWebViewRoute
import net.fstab.tachiai.platform.network.connectionProfileStore
import net.fstab.tachiai.platform.network.RouteSessionRegistry
import net.fstab.tachiai.platform.network.RoutePreparation
import net.fstab.tachiai.platform.network.planProviderInstanceRoutes
import net.fstab.tachiai.platform.media.NativeMixedSide
import net.fstab.tachiai.platform.media.NativePlaybackAudioGroup
import net.fstab.tachiai.platform.media.NativePlaybackBudget
import net.fstab.tachiai.platform.media.PrototypePlaybackController
import net.fstab.tachiai.platform.media.NativeSeekOutcome
import net.fstab.tachiai.platform.media.PrototypeFeedEvent
import net.fstab.tachiai.platform.media.PrototypeFeedSession
import net.fstab.tachiai.presentation.PrototypePlayBarrier
import net.fstab.tachiai.presentation.PrototypeFeedFailure
import net.fstab.tachiai.presentation.PrototypeFailureReason
import net.fstab.tachiai.presentation.PrototypePlaybackKind
import net.fstab.tachiai.presentation.ConfiguredPlaybackSelection
import net.fstab.tachiai.presentation.PrototypeService
import net.fstab.tachiai.provider.abema.PrototypeAbemaSession
import net.fstab.tachiai.provider.abema.CachedPrototypeAbemaSession
import net.fstab.tachiai.provider.twitch.PrototypeTwitchSession
import net.fstab.tachiai.provider.twitch.configuredTwitchBroadcasterSession
import net.fstab.tachiai.provider.twitch.supportedConfiguredTwitchBroadcaster
import net.fstab.tachiai.provider.twitch.catalog.androidTwitchLiveIdentityResolver
import net.fstab.tachiai.provider.twitch.catalog.TwitchBroadcasterRetryGate
import net.fstab.tachiai.provider.twitch.CatalogTwitchPreparation
import net.fstab.tachiai.platform.diagnostics.FailureDiagnostics
import net.fstab.tachiai.platform.diagnostics.FailureReporter
import net.fstab.tachiai.platform.diagnostics.FailureStage
import net.fstab.tachiai.platform.diagnostics.FailureSlot

// Product-flow prototype, separate from historical experiment screens. The
// unsupported playback adapters deliberately remain confined to the debug APK.
@UnstableApi
@SuppressLint("SetTextI18n") // Preliminary English UX; localization is deferred.
open class PrototypeActivity : ComponentActivity() {
    companion object {
        private var profileConfigured = false
        // ProxyController is process-wide; recreation must not discard its in-flight generation.
        private val webRoute = AbemaWebViewRoute()
        private var routesPending = false
        private var routeCleanupPending = false
        private var routeCleanupFailed = false
        private var routeDiagnostics = FailureReporter.NONE
        private val recoveryState = PrototypeRecoveryState()
        private val routeCloser = Executors.newSingleThreadExecutor()
    }
    protected open val useCachedAbema: Boolean = false
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val resumed = AtomicBoolean()
    private val epoch = AtomicLong()
    private var routePreparation: RoutePreparation? = null
    private var selection: ConfiguredPlaybackSelection? = null
    private var pickerAssignments = restoreConfiguredFeedAssignments(false, null, null)
    private var sourceSetups by mutableStateOf(defaultSourceSetups())
    private var providerInstances by mutableStateOf(defaultProviderInstances())
    private var configuredSources by mutableStateOf(emptyList<ConfiguredSource>())
    private var activeConfiguredSources = emptyList<ConfiguredSource>()
    private var activeInstances: List<ProviderInstance> = defaultProviderInstances()
    private var setupReady by mutableStateOf(false)
    private var setupMessage by mutableStateOf<String?>(null)
    private var obsoleteSetup by mutableStateOf(false)
    private var setupRevision = 0L
    private var activeSetups: Map<PrototypeSource, SourceSetup> = defaultSourceSetups()
    private var legacyQualityDefaults = emptyMap<PrototypeSource, NativeQualityPreferences>()
    private var qualityDefaults = emptyMap<ConfiguredFeedChoice, NativeQualityPreferences>()
    private var qualitySession: ViewerQualityState<ConfiguredFeedChoice>? = null
    private var savingQuality = false
    private var qualityMessage: String? = null
    private var sessions = listOf<PrototypeFeedSession?>(null, null)
    private val failures = arrayOfNulls<PrototypeFeedFailure>(2)
    private val prepared = BooleanArray(2)
    private val initialCatchUp = BooleanArray(2)
    private val catchUpDeadline = LongArray(2)
    private var surfaces = emptyList<PlayerView>()
    private var budget: NativePlaybackBudget? = null
    private var playback: PrototypePlaybackController? = null
    private var focus: NativePlaybackAudioGroup? = null
    private var viewer: NativePairViewer? = null
    private var root: FrameLayout? = null
    private var progress: TextView? = null
    private var returnButton: Button? = null
    private var playBarrier: PrototypePlayBarrier? = null
    private var playMessage: String? = null
    private var cleanupFailed = false
    private var disposing = false
    private var resumeMessage: String? = null
    private var desiredPlaying = false
    private var lastAuthorizationPoll = 0L
    private var lastSample = 0L
    private val authorizationPolling = AtomicBoolean()
    private var routes = emptyMap<String, RouteSession>()
    private var routeProfiles = emptyMap<String, Result<ConnectionProfile?>>()
    private var routeChoices = emptyMap<String, SourceRouteChoice>()
    // Rate hints survive a stopped viewer so another explicit attempt cannot
    // skip its deadline. This Activity-owned map contains no grants or aliases.
    private val twitchBroadcasterRetryGate = TwitchBroadcasterRetryGate()
    private var diagnostics = FailureReporter.NONE
    private var playbackAvailable by mutableStateOf(true)
    private var recoveryMessage by mutableStateOf<String?>(null)
    private var hasRecovery by mutableStateOf(false)
    private var recoveryDialog: AlertDialog? = null
    private var restartMessage: String? = null
    private val recoveryObserver: () -> Unit = { handler.post { updateRecoveryState() } }
    private var restartOperation: () -> Unit = { restartPrototypeProcess(this, useCachedAbema, pickerAssignments) }

    private fun feedDiagnostics(index: Int) = diagnostics.forSlot(if (index == 0) FailureSlot.A else FailureSlot.B)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickerAssignments = restoreConfiguredFeedAssignments(savedInstanceState != null,
            savedInstanceState?.getString("prototype.feed.a"), savedInstanceState?.getString("prototype.feed.b"))
        if (!BuildConfig.DEBUG || android.os.Build.VERSION.SDK_INT < 28) { finish(); return }
        if (savedInstanceState == null) {
            when (val checkpoint = ConfiguredRecoveryCheckpoint(noBackupFilesDir, useCachedAbema).consume()) {
                ConfiguredRecoveryCheckpoint.Result.Absent -> Unit
                is ConfiguredRecoveryCheckpoint.Result.Restored -> pickerAssignments = checkpoint.assignments
                ConfiguredRecoveryCheckpoint.Result.Invalid -> {
                    pickerAssignments = ConfiguredFeedAssignments(null, null)
                    recoveryState.fail(PrototypeRecoveryKind.PICKER_RESTORE_FAILED)
                }
            }
        }
        initializeDiagnostics()
        if (!profileConfigured) {
            WebView.setDataDirectorySuffix(if (useCachedAbema) "prototype-cached-player" else "prototype-player")
            profileConfigured = true
        }
        WebView.setWebContentsDebuggingEnabled(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this) {
            if (budget != null) stopToPicker("Playback stopped.") else finish()
        }
        showPicker(null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("prototype.feed.a", encodeConfiguredFeedChoice(pickerAssignments.a))
        outState.putString("prototype.feed.b", encodeConfiguredFeedChoice(pickerAssignments.b))
        super.onSaveInstanceState(outState)
    }

    private fun initializeDiagnostics() {
        diagnostics = if (routesPending || routeCleanupPending || routeCleanupFailed || webRoute.busy)
            routeDiagnostics else FailureDiagnostics.create(this)
    }

    private fun showPicker(message: String?) {
        root = null; progress = null; returnButton = null
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        styleSystemBars(false)
        setContent { TachiaiPrototypeTheme { PrototypeSourcePicker(setupMessage ?: message,
            onConnections = { startActivity(Intent(this, ConnectionProfilesActivity::class.java)) },
            setupReady = setupReady, providerInstances = providerInstances, configuredSources = configuredSources,
            initialAssignments = pickerAssignments, onAssignmentsChanged = { pickerAssignments = it },
            onProviders = { startActivity(Intent(this, ProvidersActivity::class.java)) },
            obsoleteSetup = obsoleteSetup, onResetStreamSettings = ::resetStreamSettings,
            playbackAvailable = playbackAvailable, recoveryMessage = recoveryMessage,
            onRecovery = if (hasRecovery) ({ showRecoveryDialog(true) }) else null,
            onWatch = ::watch) } }
    }

    private fun updateRecoveryState() {
        if (routeCleanupFailed) recoveryState.fail(PrototypeRecoveryKind.ROUTE_CLEANUP_UNCONFIRMED)
        else if (cleanupFailed) recoveryState.fail(PrototypeRecoveryKind.PLAYBACK_CLEANUP_UNCONFIRMED)
        val incident = recoveryState.incident
        hasRecovery = incident != null
        playbackAvailable = incident == null && !routesPending && !routeCleanupPending && !webRoute.busy
        recoveryMessage = incident?.let { "Playback blocked. Recovery category: ${it.kind.code}." }
            ?: if (!playbackAvailable) "The previous route is still stopping. Please wait." else null
        if (incident?.restarting == true) dismissRecoveryDialog()
        else if (!disposing && incident?.acknowledged == false) showRecoveryDialog(false)
    }

    private fun showRecoveryDialog(explicit: Boolean) {
        val incident = recoveryState.incident ?: return
        if (!resumed.get() || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || isFinishing || isDestroyed ||
            incident.restarting || (!explicit && incident.acknowledged) || recoveryDialog != null) return
        recoveryDialog = AlertDialog.Builder(this)
            .setTitle("Playback recovery required")
            .setMessage("${incident.kind.message}\n\nIgnore dismisses this dialog; playback stays blocked. Restart returns to the main menu and preserves your saved setup and picker choices.\n\nRecovery category: ${incident.kind.code}." +
                (restartMessage?.let { "\n\n$it" } ?: ""))
            .setNegativeButton("Ignore") { _, _ -> recoveryState.ignore(incident.id) }
            .setPositiveButton("Restart") { _, _ -> restartRecovery(incident.id) }
            .setOnCancelListener { recoveryState.ignore(incident.id) }
            .create().also { dialog ->
                dialog.setOnDismissListener { if (recoveryDialog === dialog) recoveryDialog = null }
                dialog.show()
            }
    }

    private fun dismissRecoveryDialog() {
        recoveryDialog?.dismiss()
        recoveryDialog = null
    }

    private fun restartRecovery(id: Long) {
        if (!recoveryState.beginRestart(id)) return
        dismissRecoveryDialog()
        try {
            restartOperation()
        } catch (error: Exception) {
            diagnostics.report(FailureStage.RECOVERY_RESTART, error)
            restartMessage = "Restart could not be completed. Playback remains blocked; your saved setup was not cleared."
            recoveryState.restartFailed(id)
            handler.post { showRecoveryDialog(true) }
        }
    }

    @Suppress("DEPRECATION") // Pre-enforced-edge-to-edge Android still uses explicit bar colours.
    private fun styleSystemBars(video: Boolean) {
        val light = !video && resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        val color = if (video) android.graphics.Color.BLACK else getColor(R.color.prototype_background)
        window.statusBarColor = color
        window.navigationBarColor = color
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private fun watch(assignments: ConfiguredFeedAssignments) {
        pickerAssignments = assignments
        updateRecoveryState()
        if (recoveryState.incident != null) {
            diagnostics.blocked(if (routeCleanupFailed) FailureStage.ROUTE_BLOCKED else FailureStage.VIEWER_BLOCKED)
            showPicker(null); showRecoveryDialog(true); return
        }
        if (!setupReady) { showPicker("Provider setup is unavailable; playback was not started."); return }
        if (routesPending || routeCleanupPending || routeCleanupFailed || webRoute.busy) {
            if (cleanupFailed || routeCleanupFailed) routeDiagnostics.blocked(FailureStage.ROUTE_BLOCKED)
            showPicker(if (cleanupFailed || routeCleanupFailed)
                "Route cleanup could not be confirmed. Use Recovery options to restart."
                else "The previous route is still stopping. Please retry shortly.")
            return
        }
        val resolved = resolveConfiguredPrototypeSelection(assignments, configuredSources, providerInstances)
        if (resolved is ConfiguredPrototypeSelectionResult.Failure) {
            showPicker(configuredSelectionMessage(resolved.reason)); return
        }
        resolved as ConfiguredPrototypeSelectionResult.Ready
        val selected = resolved.selection
        selection = selected
        activeConfiguredSources = resolved.sources
        activeSetups = sourceSetups.toMap()
        activeInstances = providerInstances.toList()
        dispose()
        if (cleanupFailed) {
            updateRecoveryState()
            diagnostics.blocked(FailureStage.VIEWER_BLOCKED)
            showPicker("Playback stopped because cleanup could not be confirmed."); return
        }
        diagnostics = diagnostics.newSession()
        val run = epoch.incrementAndGet()
        qualitySession = ViewerQualityState(activeConfiguredSources.map { it.choice }, qualityDefaults)
        fun active() = resumed.get() && epoch.get() == run && !isFinishing
        val sharedBudget = NativePlaybackBudget(300_000, ::active, maximumDurationMs = 300_000)
        budget = sharedBudget
        failures.fill(null); prepared.fill(false); initialCatchUp.fill(false); catchUpDeadline.fill(0)
        desiredPlaying = true; lastAuthorizationPoll = 0; lastSample = 0
        styleSystemBars(false)
        val frame = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.prototype_background)) }
        val setup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(setup) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val margin = (16 * resources.displayMetrics.density).toInt()
            view.setPadding(bars.left + margin, bars.top + margin, bars.right + margin, bars.bottom + margin); insets
        }
        progress = TextView(this).apply { textSize = 18f; setTextColor(getColor(R.color.prototype_text)) }.also { setup.addView(it) }
        setup.addView(TextView(this).apply {
            text = if (useCachedAbema) "Preparing your feeds… ABEMA uses cached provider modules, without opening its player page. No account entry is needed."
                else "Preparing your feeds… ABEMA may ask you to keep using the web player. No account entry is needed for these fixed sources."
        })
        setup.addView(Button(this).apply { text = "Back to sources"; stylePrototypeControl(); setOnClickListener { stopToPicker("Playback stopped.") } })
        returnButton = Button(this).apply {
            text = "Return to viewer"; isEnabled = false
            stylePrototypeControl()
            setOnClickListener { styleSystemBars(true); viewer?.open() }
        }.also { setup.addView(it) }
        frame.addView(setup, FrameLayout.LayoutParams(-1, -1))
        root = frame
        setContentView(frame)
        createViewer()
        prepareRoutes(selected, sharedBudget, run, setup)
        updateProgress()
        handler.post(ticker)
    }

    private fun prepareRoutes(selected: ConfiguredPlaybackSelection, sharedBudget: NativePlaybackBudget, run: Long, setup: LinearLayout) {
        val preparation = RoutePreparation(diagnostics).also { routePreparation = it }
        routeDiagnostics = diagnostics
        val choices = activeInstances.toList()
        routesPending = true
        recoveryState.notifyChanged()
        worker.execute {
            val plan = planProviderInstanceRoutes(selected, choices, connectionProfileStore(this)::selected)
            if (!plan.abemaCompatible) {
                handler.post {
                    routesPending = false
                    recoveryState.notifyChanged()
                    if (sharedBudget.active && epoch.get() == run) stopToPicker(
                        "Selected ABEMA instances need available, identical routes. Their WebView proxy is shared; no route or playback was started.")
                }
                return@execute
            }
            val registry = RouteSessionRegistry { profile ->
                check(sharedBudget.active)
                RouteSession.create(profile, preparation)
            }
            val results = selected.feeds.distinctBy { it.instanceId }.associate { feed -> feed.instanceId to
                runCatching {
                    val profile = checkNotNull(plan.profiles[feed.instanceId]).getOrThrow()
                    if (feed.service == PrototypeService.ABEMA && !useCachedAbema && profile != null)
                        error("Historical page comparison has no imported route support")
                    registry.acquire(profile)
                }
            }
            handler.post {
                val created = results.mapNotNull { it.value.getOrNull() }.distinct()
                if (!sharedBudget.active || epoch.get() != run || isDestroyed || isFinishing || !preparation.cleanupConfirmed) {
                    closeRoutesAsync(created) { accepted ->
                        routesPending = false
                        if (!accepted || !preparation.cleanupConfirmed) {
                            routeCleanupFailed = true; routeCleanupPending = true; cleanupFailed = true
                            if (sharedBudget.active && epoch.get() == run && !isDestroyed && !isFinishing)
                                stopToPicker("Route cleanup could not be confirmed. Use Recovery options to restart.")
                        }
                        updateRecoveryState(); recoveryState.notifyChanged()
                    }
                    return@post
                }
                routesPending = false
                recoveryState.notifyChanged()
                routes = results.mapNotNull { (provider, result) -> result.getOrNull()?.let { provider to it } }.toMap()
                routeProfiles = plan.profiles.filterKeys { it in routes }
                routeChoices = choices.filter { it.id in routes }.associate { it.id to checkNotNull(it.setup.route) }
                results.filterValues { it.isFailure }.keys.forEach { instanceId ->
                    selected.feeds.forEachIndexed { index, feed -> if (feed.instanceId == instanceId)
                        failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.ROUTE_FAILED)) }
                }
                createFeeds(selected, sharedBudget, run, setup, setOf(PrototypeService.TWITCH))
                val abema = selected.feeds.firstOrNull { it.service == PrototypeService.ABEMA }?.let { routes[it.instanceId] }
                if (useCachedAbema && abema != null) {
                    var completed = false
                    fun complete(accepted: Boolean) {
                        if (completed) return
                        completed = true
                        if (sharedBudget.active && epoch.get() == run) {
                            if (!accepted) selected.feeds.forEachIndexed { index, source ->
                                if (source.service == PrototypeService.ABEMA) failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.ROUTE_FAILED))
                            }
                            else createFeeds(selected, sharedBudget, run, setup, setOf(PrototypeService.ABEMA))
                        }
                    }
                    webRoute.install(abema, ::complete, diagnostics)
                    handler.postDelayed({
                        if (!completed && sharedBudget.active && epoch.get() == run)
                            diagnostics.report(FailureStage.ROUTE_INSTALL_TIMEOUT)
                        complete(false)
                    }, 3_000)
                } else createFeeds(selected, sharedBudget, run, setup, setOf(PrototypeService.ABEMA))
            }
        }
    }

    private fun createFeeds(selected: ConfiguredPlaybackSelection, sharedBudget: NativePlaybackBudget, run: Long, setup: LinearLayout,
        providers: Set<PrototypeService>) {
        fun active() = resumed.get() && epoch.get() == run && !isFinishing
        val created = mutableListOf<Pair<Int, PrototypeFeedSession>>()
        selected.feeds.forEachIndexed { index, source ->
            if (source.service !in providers || failures[index] != null || sessions[index] != null) return@forEachIndexed
            try {
                val replay = source.kind == PrototypePlaybackKind.REPLAY
                val events: (PrototypeFeedEvent) -> Unit = { event ->
                    if (disposing && event == PrototypeFeedEvent.FAILED) {
                        feedDiagnostics(index).report(FailureStage.DISPOSING_FEED_FAILED)
                        cleanupFailed = true
                    }
                    else if (active() && failures[index] == null) {
                        Log.d("TachiaiPrototype", "slot=${if (index == 0) "A" else "B"} source=${source.diagnosticName} event=${event.name}")
                        if (event == PrototypeFeedEvent.NETWORK_APPROVAL_REQUIRED)
                            failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.MEDIA_APPROVAL_REQUIRED))
                        else if (event == PrototypeFeedEvent.FAILED || event == PrototypeFeedEvent.STOPPED)
                            failFeed(index, sessions[index]?.failure ?: PrototypeFeedFailure(
                                if (event == PrototypeFeedEvent.STOPPED) PrototypeFailureReason.STOPPED else PrototypeFailureReason.PREPARATION_FAILED))
                        else updateProgress()
                    }
                }
                val feedActive = { active() && failures[index] == null }
                val session = when (source.service) {
                    PrototypeService.ABEMA -> if (useCachedAbema) CachedPrototypeAbemaSession(this, replay, feedActive, events,
                        route = checkNotNull(routes[selected.feeds[index].instanceId]), diagnostics = feedDiagnostics(index))
                        else PrototypeAbemaSession(this, replay, feedActive, events, diagnostics = feedDiagnostics(index))
                    PrototypeService.TWITCH -> {
                        val instanceId = source.instanceId
                        val route = checkNotNull(routes[instanceId])
                        val profile = checkNotNull(routeProfiles[instanceId]).getOrThrow()
                        val routeChoice = checkNotNull(routeChoices[instanceId])
                        val preparation = {
                            CatalogTwitchPreparation(this, instanceId, profile, routeChoice, feedActive,
                                openConnection = route::open)
                        }
                        if (supportedConfiguredTwitchBroadcaster(source.resource)) {
                            configuredTwitchBroadcasterSession(this, source.resource, feedActive, events,
                                liveIdentityResolverFactory = {
                                    androidTwitchLiveIdentityResolver(this, instanceId, profile, feedActive,
                                        twitchBroadcasterRetryGate, expectedRoute = routeChoice)
                                }, openConnection = route::open,
                                diagnostics = feedDiagnostics(index), preparationFactory = preparation)
                        } else PrototypeTwitchSession(this, replay, source.resource.identity, feedActive, events,
                            openConnection = route::open, preparationFactory = preparation,
                            diagnostics = feedDiagnostics(index),
                            initialPositionMs = if (source.historicalSource == PrototypeSource.TWITCH_REPLAY) 70 * 60 * 1000L else 0L)
                    }
                }
                sessions = sessions.toMutableList().also { it[index] = session }
                created += index to session
                session.providerView?.let { page ->
                    if (useCachedAbema) {
                        // Runtime only: no provider page/video or interactive consent UI.
                        page.visibility = View.INVISIBLE
                        setup.addView(page, LinearLayout.LayoutParams(1, 1))
                    } else {
                        setup.addView(TextView(this).apply { text = sourceLabel(index) })
                        setup.addView(page, LinearLayout.LayoutParams(-1, 0, 1f))
                    }
                }
            } catch (error: Exception) {
                feedDiagnostics(index).report(FailureStage.FEED_CONSTRUCT, error)
                failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.PREPARATION_FAILED))
            }
        }
        created.forEach { (index, host) ->
            try { host.prepare(sharedBudget.child()) }
            catch (error: Exception) {
                feedDiagnostics(index).report(FailureStage.FEED_PREPARE, error)
                failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.PREPARATION_FAILED))
            }
        }
        updateProgress()
    }

    private fun updateProgress() {
        progress?.text = (selection ?: return).feeds.mapIndexed { index, _ ->
            val state = failures[index]?.message ?: if (prepared[index]) "Ready" else "Preparing"
            "${sourceLabel(index)}: $state"
        }.joinToString("\n")
    }

    private val ticker = object : Runnable {
        override fun run() {
            val currentBudget = budget ?: return
            if (!currentBudget.active) {
                failures.indices.forEach { failFeed(it, PrototypeFeedFailure(PrototypeFailureReason.PLAYBACK_LIMIT)) }
                releaseRoutes()
                focus?.release()
                viewer?.refresh()
                return
            }
            sessions.forEachIndexed { index, host ->
                try {
                    if (host?.member != null && !host.canContinue()) failFeed(index, authorizationFailure(index, host))
                    else if (host != null && failures[index] == null && !prepared[index]) prepareViewerSlot(index, host)
                } catch (error: Exception) {
                    feedDiagnostics(index).report(FailureStage.FEED_POLL, error)
                    failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED))
                }
            }
            updateProgress()
            playback?.poll()
            val available = NativeMixedSide.entries.mapNotNull { playback?.member(it) }
            if (desiredPlaying && playBarrier == null && playback?.busy != true && available.isNotEmpty() &&
                available.all { it.timingSnapshot()?.let { value -> value.state == Player.STATE_READY && !value.playingAd } == true } &&
                available.any { it.timingSnapshot()?.playWhenReady != true }) {
                playReadyFeeds()
            }
            viewer?.refresh()
            val now = SystemClock.elapsedRealtime()
            if (now - lastSample >= 5_000) {
                lastSample = now
                NativeMixedSide.entries.forEach { side ->
                    playback?.member(side)?.timingSnapshot()?.let { value ->
                        Log.d("TachiaiPrototype", "slot=${side.name} state=${value.state} playing=${value.playing} positionMs=${value.positionMs} contentTimeMs=${value.contentTimeMs}")
                        playback?.member(side)?.qualitySnapshot()?.let { quality ->
                            Log.d("TachiaiPrototype", "slot=${side.name} quality=${quality.summary().replace('\n', ' ')}")
                        }
                    }
                }
            }
            if (now - lastAuthorizationPoll >= 5_000 && sessions.any { it?.member != null } &&
                authorizationPolling.compareAndSet(false, true)) {
                lastAuthorizationPoll = now
                val run = epoch.get()
                val hosts = sessions
                worker.execute {
                    try {
                        hosts.forEachIndexed { index, host ->
                            if (host?.member != null && !runCatching { host.checkAuthorization() }.getOrDefault(false)) handler.post {
                                if (epoch.get() == run && sessions[index] === host)
                                    failFeed(index, authorizationFailure(index, host))
                            }
                        }
                    } finally { authorizationPolling.set(false) }
                }
            }
            if (budget === currentBudget) handler.postDelayed(this, 200)
        }
    }

    private fun createViewer() {
        val group = NativePlaybackAudioGroup(this) {
            desiredPlaying = false; invalidatePlay(); playback?.focusLost()
            sessions.forEachIndexed { index, host ->
                if (host?.member?.timingSnapshot()?.playWhenReady == true)
                    failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED))
            }
            viewer?.refresh()
        }
        focus = group
        playback = PrototypePlaybackController(group::acquire)
        fun surface() = (layoutInflater.inflate(R.layout.native_viewer_player, root, false) as PlayerView).apply {
            useController = false; keepScreenOn = true
        }
        surfaces = listOf(surface(), surface())
        val pane = NativePairViewer(this, surfaces[0], surfaces[1], sourceLabel(0), sourceLabel(1),
            { playback?.pair }, ::playPair, ::pausePair,
            onRelative = { delta -> desiredPlaying = false; invalidatePlay(); playback?.shiftRelative(delta) },
            onCatchUp = { side -> desiredPlaying = false; invalidatePlay(); playback?.catchUp(side) },
            onDiagnostics = { pausePair(); viewer?.visibility = View.GONE; styleSystemBars(false) },
            onStop = { stopToPicker("Playback stopped.") },
            onLandscape = { landscape -> requestedOrientation = if (landscape)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT },
            setupLabel = "Preparation / provider pages", statusOverride = { playMessage ?: playback?.status },
            members = { NativeMixedSide.entries.map { playback?.member(it) } },
            volumeRequest = { side, volume -> playback?.setVolume(side, volume) == true },
            feedMessage = { side ->
                val index = side.ordinal
                failures[index]?.let { "Could not load this feed\n\n${it.message}\n\nUse Sources to choose another feed." }
                    ?: if (!prepared[index]) "Preparing this feed…" else null
            }, onSources = { stopToPicker("Playback stopped.") },
            qualityState = { side, kind -> qualitySession?.read(side, kind)?.copy(saving = savingQuality, message = qualityMessage) },
            onQualityOverride = { side, kind, request ->
                if (!savingQuality && playback?.busy != true) {
                    qualitySession?.setOverride(side, kind, request)
                    applyQuality(side); viewer?.refresh()
                }
            },
            onSaveQuality = { side, kind -> saveQuality(side, kind, false) },
            onResetQuality = { side, kind -> saveQuality(side, kind, true) })
        viewer = pane
        root?.addView(pane, FrameLayout.LayoutParams(-1, -1))
        returnButton?.isEnabled = true
        styleSystemBars(true)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        pane.open()
    }

    private fun prepareViewerSlot(index: Int, host: PrototypeFeedSession) {
        if (initialCatchUp[index] && SystemClock.elapsedRealtime() >= catchUpDeadline[index]) {
            failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.LIVE_POSITION_FAILED)); return
        }
        val member = host.member ?: return
        val snapshot = member.timingSnapshot() ?: return
        if (snapshot.state != Player.STATE_READY || snapshot.playWhenReady || snapshot.playingAd) return
        if (!initialCatchUp[index] && (snapshot.live || snapshot.dynamic)) {
            initialCatchUp[index] = true
            catchUpDeadline[index] = SystemClock.elapsedRealtime() + 8_000
            // Initial live position is chosen once, before establishing a timing
            // anchor. No reload, retry, future seek or license exchange is added.
            if (member.seekLiveDefault().outcome !in setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.NO_CHANGE))
                failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.LIVE_POSITION_FAILED))
            return
        }
        if ((snapshot.live || snapshot.dynamic) && (snapshot.positionMs == null || snapshot.durationMs == null ||
                snapshot.durationMs <= 0 || snapshot.positionMs !in 0..snapshot.durationMs)) return
        prepared[index] = true
        invalidatePlay()
        playback?.setMember(NativeMixedSide.entries[index], member)
        applyQuality(NativeMixedSide.entries[index])
        surfaces[index].player = host.player
    }

    private fun applyQuality(side: NativeMixedSide): Boolean {
        val preferences = qualitySession?.effective(side) ?: return true
        val member = playback?.member(side) ?: return true
        val accepted = runCatching { member.setQualityPreferences(preferences) }.getOrDefault(false)
        qualityMessage = if (accepted) null else "Quality request refused; actual playback is shown separately."
        return accepted
    }

    private fun saveQuality(side: NativeMixedSide, kind: NativeQualityKind, reset: Boolean) {
        val state = qualitySession ?: return
        if (savingQuality || playback?.busy == true) return
        val source = activeConfiguredSources[side.ordinal]
        val instance = checkNotNull(activeInstances.singleOrNull { it.id == source.instanceId })
        val legacy = legacyConfiguredSources(instance, activeSetups, legacyQualityDefaults)
        val request = if (reset) NativeQualityRequest.auto else state.read(side, kind).effective
        val run = epoch.get()
        val reporter = diagnostics
        savingQuality = true; qualityMessage = "Saving stream quality…"; viewer?.refresh()
        worker.execute {
            val result = runCatching { configuredSourceStore(this, instance).saveQuality(source.id, kind, request) { legacy } }
                .onFailure { reporter.report(FailureStage.QUALITY_SETTINGS_SAVE, it) }
            handler.post {
                if (epoch.get() != run || qualitySession !== state || isDestroyed) return@post
                savingQuality = false
                result.fold(onSuccess = { values ->
                    qualityDefaults = qualityDefaults.filterKeys { it.instanceId != instance.id } +
                        values.associate { it.choice to it.quality }
                    state.replaceDefaults(qualityDefaults)
                    val applied = NativeMixedSide.entries.map(::applyQuality).all { it }
                    qualityMessage = if (reset) "Stream ${kind.name.lowercase()} default reset to Auto. Feed overrides stay in this session."
                        else "Stream ${kind.name.lowercase()} default saved. Feed overrides stay in this session."
                    if (!applied) qualityMessage += "\nQuality request refused; actual playback is shown separately."
                }, onFailure = {
                    qualityMessage = "Stream quality could not be saved. Saved preferences were not replaced."
                })
                viewer?.refresh()
            }
        }
    }

    private fun invalidatePlay() { playBarrier?.cancel(); playBarrier = null; playMessage = null }
    private fun pausePair() { desiredPlaying = false; invalidatePlay(); playback?.pause(); viewer?.refresh() }

    private fun playPair() {
        desiredPlaying = true
        playReadyFeeds()
    }

    private fun playReadyFeeds() {
        invalidatePlay()
        val current = playback ?: return
        // The comparison's original ABEMA page may autoplay while preparing.
        // Its adapter can only confirm isolation once its native host is ready.
        // The default cached flow has no original page and remains independent.
        if (!useCachedAbema && sessions.indices.any { sessions[it]?.providerView != null && !prepared[it] }) {
            playMessage = "Waiting to safely pause the original provider player."
            return
        }
        val hosts = sessions.mapIndexedNotNull { index, host ->
            if (host != null && prepared[index] && failures[index] == null) index to host else null
        }
        if (hosts.isEmpty()) return
        val run = epoch.get()
        val waiting = hosts.indices.toMutableSet()
        lateinit var barrier: PrototypePlayBarrier
        barrier = PrototypePlayBarrier(hosts.size) { accepted ->
            if (playBarrier !== barrier || !resumed.get() || epoch.get() != run || playback !== current) return@PrototypePlayBarrier
            playBarrier = null
            if (accepted) {
                if (!current.play()) desiredPlaying = false
            } else {
                current.pause()
                desiredPlaying = false
            }
            viewer?.refresh()
        }
        playBarrier = barrier
        hosts.forEachIndexed { slot, (index, host) ->
            try {
                host.pauseOriginal { accepted -> handler.post {
                    if (playBarrier !== barrier || epoch.get() != run || sessions[index] !== host) return@post
                    if (accepted) { waiting.remove(slot); barrier.result(slot, true) }
                    else failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.ORIGINAL_PLAYER_NOT_PAUSED))
                } }
            } catch (_: Exception) { failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.ORIGINAL_PLAYER_NOT_PAUSED)) }
        }
        handler.postDelayed({
            if (playBarrier === barrier) waiting.map { hosts[it].first }.forEach {
                failFeed(it, PrototypeFeedFailure(PrototypeFailureReason.ORIGINAL_PLAYER_NOT_PAUSED))
            }
        }, 3_000)
    }

    private fun authorizationFailure(index: Int, host: PrototypeFeedSession) = host.failure ?: PrototypeFeedFailure(
        if (checkNotNull(selection).feeds[index].service == PrototypeService.TWITCH) PrototypeFailureReason.LOGIN_EXPIRED
        else PrototypeFailureReason.PREPARATION_FAILED)

    private fun failFeed(index: Int, failure: PrototypeFeedFailure) {
        if (failures[index] != null) return
        // Deadline/normal stop is an expected lifecycle outcome, not a new error.
        if (failure.reason !in setOf(PrototypeFailureReason.STOPPED, PrototypeFailureReason.PLAYBACK_LIMIT))
            feedDiagnostics(index).report(FailureStage.FEED_FAILURE, reason = failure.reason)
        failures[index] = failure
        Log.d("TachiaiPrototype", "slot=${NativeMixedSide.entries[index].name} failure=${failure.reason.name} http=${failure.httpStatus ?: 0}")
        invalidatePlay()
        playback?.setMember(NativeMixedSide.entries[index], null)
        prepared[index] = false
        surfaces.getOrNull(index)?.player = null
        val host = sessions[index]
        sessions = sessions.toMutableList().also { it[index] = null }
        val closed = feedDiagnostics(index).cleanupAll(FailureStage.FEED_CLOSE) { host?.close() } && host?.cleanupFailed != true
        if (!closed) {
            feedDiagnostics(index).report(FailureStage.FEED_CLEANUP_UNCONFIRMED)
            cleanupFailed = true
            budget?.stop()
            failures.indices.filter { failures[it] == null }.forEach {
                failFeed(it, PrototypeFeedFailure(PrototypeFailureReason.CLEANUP_FAILED))
            }
        }
        if (NativeMixedSide.entries.all { playback?.member(it) == null }) focus?.release()
        if (failures.all { it != null }) releaseRoutes()
        updateProgress()
        viewer?.refresh()
        updateRecoveryState()
        if (cleanupFailed && !disposing) stopToPicker("Playback stopped because cleanup could not be confirmed.")
    }

    private fun stopToPicker(message: String) {
        dispose()
        val result = message + if (cleanupFailed) " Playback cleanup reported a failure." else ""
        if (resumed.get() && !isFinishing) {
            showPicker(result)
            // The worker serializes accepted quality writes before this read.
            // A disposed session cannot publish its callback into a new viewer.
            refreshSetup()
        } else resumeMessage = result
    }

    private fun dispose() {
        disposing = true
        cancelRoutePreparation()
        epoch.incrementAndGet(); invalidatePlay(); handler.removeCallbacks(ticker)
        budget?.stop()
        if (!diagnostics.cleanupAll(FailureStage.VIEWER_END) { viewer?.endSession() }) cleanupFailed = true
        viewer = null
        qualitySession?.clearOverrides(); qualitySession = null; savingQuality = false; qualityMessage = null
        playback?.close(); playback = null
        surfaces.forEach { it.player = null }; surfaces = emptyList()
        sessions.forEachIndexed { index, session ->
            if (!feedDiagnostics(index).cleanupAll(FailureStage.FEED_CLOSE) { session?.close() } || session?.cleanupFailed == true) {
                feedDiagnostics(index).report(FailureStage.FEED_CLEANUP_UNCONFIRMED)
                cleanupFailed = true
            }
        }
        sessions = listOf(null, null)
        releaseRoutes()
        if (!diagnostics.cleanupAll(FailureStage.AUDIO_FOCUS_CLOSE) { focus?.close() }) cleanupFailed = true
        focus = null
        budget = null
        disposing = false
        updateRecoveryState()
        if (cleanupFailed) Log.d("TachiaiPrototype", "cleanup=FAILED")
    }

    private fun releaseRoutes() {
        cancelRoutePreparation()
        val oldRoutes = routes.values.distinct()
        routes = emptyMap()
        routeProfiles = emptyMap()
        routeChoices = emptyMap()
        if (oldRoutes.isEmpty()) return
        routeCleanupPending = true
        recoveryState.notifyChanged()
        var completed = false
        fun closeRoutes(confirmed: Boolean) {
            if (completed) return
            completed = true
            closeRoutesAsync(oldRoutes) { accepted ->
                if (!accepted || !confirmed) { cleanupFailed = true; routeCleanupFailed = true }
                routeCleanupPending = routeCleanupFailed
                updateRecoveryState(); recoveryState.notifyChanged()
            }
            // A lost/failed Chromium clear keeps the coordinator busy: no next run can load directly.
            if (!confirmed) { cleanupFailed = true; routeCleanupFailed = true }
            updateRecoveryState(); recoveryState.notifyChanged()
        }
        if (useCachedAbema) {
            webRoute.clear(diagnostics) { closeRoutes(true) }
            handler.postDelayed({
                if (!completed) diagnostics.report(FailureStage.ROUTE_CLEAR_TIMEOUT)
                closeRoutes(false)
            }, 3_000)
        } else closeRoutes(true)
    }

    private fun closeRoutesAsync(owned: List<RouteSession>, onComplete: (Boolean) -> Unit) {
        val reporter = diagnostics
        routeCloser.execute {
            val accepted = owned.map { reporter.cleanupAll(FailureStage.ROUTE_CLOSE) { it.close() } }.all { it }
            handler.post { onComplete(accepted) }
        }
    }

    private fun cancelRoutePreparation() {
        if (routePreparation?.cancel() == false) {
            diagnostics.report(FailureStage.ROUTE_CANCEL_UNCONFIRMED)
            cleanupFailed = true; routeCleanupFailed = true; routeCleanupPending = true
        }
        routePreparation = null
        updateRecoveryState(); recoveryState.notifyChanged()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        viewer?.requestLayout()
    }
    override fun onResume() {
        super.onResume(); resumed.set(true)
        recoveryState.addObserver(recoveryObserver)
        handler.post { updateRecoveryState() }
        resumeMessage?.let { resumeMessage = null; showPicker(it) }
        if (budget == null) refreshSetup()
    }

    private fun refreshSetup() {
        if (budget != null || !resumed.get() || isDestroyed || isFinishing) return
        val current = ++setupRevision
        setupReady = false; setupMessage = null; obsoleteSetup = false
        worker.execute {
            val result = runCatching {
                val streams = sourceSetupStore(this).read()
                val instances = providerInstanceStore(this).read { legacyProviderSettings(this) }
                val qualities = streamQualityStore(this).read()
                val sources = instances.flatMap { instance ->
                    configuredSourceStore(this, instance).read { legacyConfiguredSources(instance, streams, qualities) }
                }
                PickerSetup(streams, instances, qualities, sources)
            }
            handler.post {
                if (isDestroyed || isFinishing || !resumed.get() || setupRevision != current || budget != null) return@post
                result.fold(onSuccess = {
                    sourceSetups = it.streams; providerInstances = it.instances; legacyQualityDefaults = it.qualities
                    configuredSources = it.sources; qualityDefaults = it.sources.associate { source -> source.choice to source.quality }
                    setupReady = true
                }, onFailure = {
                    if (it !is ObsoleteSourceSetupException) diagnostics.report(FailureStage.PROVIDER_SETUP_READ, it)
                    if (it is ObsoleteSourceSetupException) {
                        obsoleteSetup = true
                        setupMessage = "Stream settings belong to the previous catalogue. Reset stream settings to continue. Saved routes and provider configuration will be kept. No playback started."
                    } else {
                        setupMessage = "Provider, stream setup or quality preferences could not be read. Nothing was replaced or routed through a fallback. Close and reopen to retry."
                    }
                })
            }
        }
    }

    private fun resetStreamSettings() {
        if (!obsoleteSetup || budget != null || !resumed.get() || isDestroyed || isFinishing) return
        val current = ++setupRevision
        obsoleteSetup = false; setupReady = false
        setupMessage = "Resetting obsolete stream settings… Saved routes and provider configuration will be kept."
        worker.execute {
            val result = runCatching { sourceSetupStore(this).resetObsolete() }
            handler.post {
                if (isDestroyed || isFinishing || !resumed.get() || setupRevision != current || budget != null) return@post
                result.fold(onSuccess = { refreshSetup() }, onFailure = {
                    diagnostics.report(FailureStage.STREAM_SETTINGS_RESET, it)
                    setupMessage = "Stream settings could not be reset. Playback remains unavailable; saved routes and provider configuration were not changed. Close and reopen to retry."
                })
            }
        }
    }
    override fun onPause() {
        recoveryState.removeObserver(recoveryObserver)
        dismissRecoveryDialog()
        setupRevision++
        resumed.set(false)
        if (budget != null) { dispose(); resumeMessage = "Playback stopped while the app was in the background." }
        super.onPause()
    }
    override fun onDestroy() {
        recoveryState.removeObserver(recoveryObserver)
        dismissRecoveryDialog()
        resumed.set(false); dispose()
        // Let queued route preparation reach its cancellation/result cleanup;
        // dropping the task would strand process-wide routesPending forever.
        worker.shutdown()
        super.onDestroy()
    }

    private fun sourceLabel(index: Int): String {
        val instance = checkNotNull(checkNotNull(selection).feeds[index].resolve(activeInstances))
        val title = configuredSourceDisplayTitle(activeConfiguredSources[index])
        return "${if (index == 0) "A" else "B"} · ${instance.name} · $title"
    }

    private data class PickerSetup(val streams: Map<PrototypeSource, SourceSetup>, val instances: List<ProviderInstance>,
        val qualities: Map<PrototypeSource, NativeQualityPreferences>, val sources: List<ConfiguredSource>)
}
