package net.fstab.tachiai.feature.presentation

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.session.PlaybackState
import android.media.session.MediaController
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
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
import net.fstab.tachiai.feature.connections.pairOffsetStore
import net.fstab.tachiai.platform.media.NativeMixedPair
import net.fstab.tachiai.platform.media.NativePendingSeekDiagnostic
import net.fstab.tachiai.platform.diagnostics.TimingFailureKind
import net.fstab.tachiai.platform.diagnostics.TimingFailureSide
import net.fstab.tachiai.platform.diagnostics.TimingFailureTarget
import net.fstab.tachiai.platform.diagnostics.TimingFailureEvidence
import net.fstab.tachiai.platform.diagnostics.TimingFailureJournal
import net.fstab.tachiai.presentation.PairOffsetIdentity
import net.fstab.tachiai.presentation.PairOffsetPreference
import net.fstab.tachiai.presentation.PairOffsetSession
import net.fstab.tachiai.presentation.PairOffsetRetryEstimate
import net.fstab.tachiai.presentation.PairOffsetCommand
import net.fstab.tachiai.presentation.ViewerPairOffsetReadback
import net.fstab.tachiai.presentation.FeedRetryPlaybackIntent
import net.fstab.tachiai.presentation.FeedRetryPlaybackReceipt
import net.fstab.tachiai.presentation.PairOffsetCorrespondence
import net.fstab.tachiai.presentation.PairOffsetTimelineCheck
import net.fstab.tachiai.presentation.checkPairOffsetTimeline
import net.fstab.tachiai.platform.media.NativeTimelineConsistency
import net.fstab.tachiai.feature.connections.viewerSelectionStore
import net.fstab.tachiai.presentation.ViewerSelectionRestore
import net.fstab.tachiai.platform.media.NativePairMember
import net.fstab.tachiai.platform.media.NativeFailureStage
import net.fstab.tachiai.platform.media.NativePlaybackLivenessTracker
import net.fstab.tachiai.platform.network.ProviderTransportEvidence
import net.fstab.tachiai.platform.diagnostics.PlaybackHealthIntent
import net.fstab.tachiai.platform.diagnostics.PlaybackHealthReason
import net.fstab.tachiai.platform.diagnostics.PlaybackHealthSlot
import net.fstab.tachiai.platform.diagnostics.PlaybackHealthPlayer
import net.fstab.tachiai.platform.diagnostics.PlaybackHealthTransport
import net.fstab.tachiai.platform.diagnostics.PlaybackHealthRecord
import net.fstab.tachiai.platform.diagnostics.PlaybackHealthJournal
import java.util.concurrent.atomic.AtomicReferenceArray
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
import net.fstab.tachiai.platform.network.revalidateProviderInstanceRoutes
import net.fstab.tachiai.platform.network.ProviderInstanceRoutePlan
import net.fstab.tachiai.platform.network.ProviderEffectiveRoute
import net.fstab.tachiai.platform.network.ProviderRouteStartup
import net.fstab.tachiai.platform.network.ProviderStartupResult
import net.fstab.tachiai.platform.network.ProviderStartupCleanupAdmission
import net.fstab.tachiai.platform.network.ProviderStartupScopeCleanup
import net.fstab.tachiai.platform.network.androidProviderRouteStartup
import net.fstab.tachiai.platform.network.captureProviderInstanceRoutePools
import net.fstab.tachiai.platform.network.effectiveRoutePlan
import net.fstab.tachiai.platform.network.providerStartupStatus
import net.fstab.tachiai.platform.media.NativeMixedSide
import net.fstab.tachiai.platform.media.NativePlaybackAudioGroup
import net.fstab.tachiai.platform.media.NativePlaybackBudget
import net.fstab.tachiai.platform.media.PrototypePlaybackController
import net.fstab.tachiai.platform.media.NativeSeekOutcome
import net.fstab.tachiai.platform.media.NativeSeekPlan
import net.fstab.tachiai.platform.media.PrototypeFeedEvent
import net.fstab.tachiai.platform.media.PrototypeFeedSession
import net.fstab.tachiai.presentation.PrototypePlayBarrier
import net.fstab.tachiai.presentation.PrototypeFeedFailure
import net.fstab.tachiai.presentation.FeedRetryState
import net.fstab.tachiai.presentation.recoveryAction
import net.fstab.tachiai.presentation.viewerMessage
import net.fstab.tachiai.presentation.PrototypeFailureReason
import net.fstab.tachiai.presentation.PrototypePlaybackKind
import net.fstab.tachiai.presentation.ConfiguredPlaybackSelection
import net.fstab.tachiai.presentation.PrototypeService
import net.fstab.tachiai.provider.abema.androidAbemaEpisodePlaybackOwnership
import net.fstab.tachiai.provider.abema.CachedPrototypeAbemaSession
import net.fstab.tachiai.provider.abema.configureAbemaCachedPlaybackProfile
import net.fstab.tachiai.provider.abema.prepareAndroidAbemaViewerStartup
import net.fstab.tachiai.provider.abema.account.AbemaAccountReadiness
import net.fstab.tachiai.provider.abema.account.AbemaAccountReadinessResult
import net.fstab.tachiai.provider.abema.account.abemaAccountReadinessMessage
import net.fstab.tachiai.provider.twitch.PrototypeTwitchSession
import net.fstab.tachiai.provider.twitch.configuredTwitchBroadcasterSession
import net.fstab.tachiai.provider.twitch.supportedConfiguredTwitchBroadcaster
import net.fstab.tachiai.provider.twitch.catalog.androidTwitchLiveIdentityResolver
import net.fstab.tachiai.provider.twitch.catalog.TwitchBroadcasterRetryGate
import net.fstab.tachiai.provider.twitch.catalog.prepareAndroidTwitchCatalogStartup
import net.fstab.tachiai.provider.twitch.catalog.TwitchCatalogReadOnlyValidation
import net.fstab.tachiai.provider.twitch.catalog.TwitchCatalogStartupReceipt
import net.fstab.tachiai.provider.twitch.CatalogTwitchPreparation
import net.fstab.tachiai.platform.diagnostics.FailureDiagnostics
import net.fstab.tachiai.platform.diagnostics.FailureReporter
import net.fstab.tachiai.platform.diagnostics.FailureStage
import net.fstab.tachiai.platform.diagnostics.FailureSlot

// Primary picker/viewer. Existing names and process/storage identities are kept
// so updates retain configured sources, routes and provider authorization.
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
        // Writes accepted before recreation must finish before a new Activity
        // reads or saves its choices. This lane outlives individual Activities.
        private val selectionPersistence = Executors.newSingleThreadExecutor()
        private val cleanupHandler = Handler(Looper.getMainLooper())
        private val cachedCleanup: CachedViewerCleanupCoordinator by lazy { CachedViewerCleanupCoordinator(
            post = { cleanupHandler.post(it) },
            delay = { millis, action -> cleanupHandler.postDelayed(action, millis) },
            closeWorker = routeCloser,
            clearProxy = { reporter, complete -> webRoute.clear(reporter, complete) },
            changed = {
                if (cachedCleanup.failed) {
                    if (!cachedCleanup.playbackFailed) routeCleanupFailed = true
                    recoveryState.fail(if (cachedCleanup.playbackFailed) PrototypeRecoveryKind.PLAYBACK_CLEANUP_UNCONFIRMED
                        else PrototypeRecoveryKind.ROUTE_CLEANUP_UNCONFIRMED)
                }
                recoveryState.notifyChanged()
            },
        ) }
    }
    protected open val useCachedAbema: Boolean = true
    // Only the debug comparison subclass supplies a page-backed session.
    internal open fun historicalAbemaSession(replay: Boolean, active: () -> Boolean,
        events: (PrototypeFeedEvent) -> Unit, diagnostics: FailureReporter): PrototypeFeedSession =
        error("Historical playback is unavailable in this build")
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val resumed = AtomicBoolean()
    private val epoch = AtomicLong()
    private var routePreparation: RoutePreparation? = null
    private var cachedRun: CachedViewerCleanupCoordinator.Run? = null
    private var selection: ConfiguredPlaybackSelection? = null
    private val accountReadiness by lazy { AbemaAccountReadiness(this) }
    private var checkingAccount = false
    @Volatile private var accountCheckRevision = 0L
    private val startupLock = Any()
    private var startupScopes = emptyMap<String, ProviderRouteStartup>()
    private var startupTwitchValidation = emptyMap<String, TwitchCatalogReadOnlyValidation>()
    private var pendingAccountSelection: ConfiguredFeedAssignments? = null
    private var pendingAccountPlan: ProviderInstanceRoutePlan? = null
    private var pickerAssignments = restoreConfiguredFeedAssignments(false, null, null)
    private var pickerSelectionRestore = ViewerSelectionRestore(false)
    private var persistRestoredSelection = false
    private var pickerRestoreGeneration by mutableStateOf(0L)
    private var pickerSelectionRevision = 0L
    private var pickerSelectionMessage by mutableStateOf<String?>(null)
    private var sourceSetups by mutableStateOf(defaultSourceSetups())
    private var providerInstances by mutableStateOf(defaultProviderInstances())
    private var configuredSources by mutableStateOf(emptyList<ConfiguredSource>())
    private var activeConfiguredSources = emptyList<ConfiguredSource?>()
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
    private var pairOffsetIdentity: PairOffsetIdentity? = null
    private var pairOffsetSession: PairOffsetSession? = null
    private var pairOffsetOwner: NativeMixedPair? = null
    private var pairOffsetLoading = false
    private var pairOffsetReadFailed = false
    private var pairOffsetSkipStartup = false
    private var pairOffsetBaselineLost = false
    private var pairOffsetRetryEstimate = PairOffsetRetryEstimate()
    private var pairOffsetSaveId: Long? = null
    private data class TimingEvidenceRequest(val pair: NativeMixedPair, val kind: TimingFailureKind,
        val deltaMs: Long, val beforeRevision: Long, var targets: List<NativePendingSeekDiagnostic>)
    private var timingEvidenceRequest: TimingEvidenceRequest? = null
    private var playbackIntentReason = PlaybackHealthIntent.STARTUP
    private var playbackIntentRevision = 0L
    private var lastHealthSampleMs = 0L
    private val healthTrackers = Array(2) { NativePlaybackLivenessTracker() }
    private val healthOwners = arrayOfNulls<NativePairMember>(2)
    private val healthSignatures = arrayOfNulls<List<Any?>>(2)
    private data class EarlyTransportFailure(val run: Long, val retry: FeedRetryState, val attempt: Long,
        val at: Long, val stage: NativeFailureStage, val evidence: ProviderTransportEvidence)
    private val earlyTransportFailures = AtomicReferenceArray<EarlyTransportFailure?>(2)
    private var sessions = listOf<PrototypeFeedSession?>(null, null)
    private val failures = arrayOfNulls<PrototypeFeedFailure>(2)
    private val prepared = BooleanArray(2)
    private val initialCatchUp = BooleanArray(2)
    private val catchUpDeadline = LongArray(2)
    private var feedRetries = List(2) { FeedRetryState() }
    private val retryPlaybackIntent = FeedRetryPlaybackIntent()
    private data class RetryPlaybackJoin(val receipt: FeedRetryPlaybackReceipt, val token: Any,
        val retry: FeedRetryState, val run: Long, val budget: NativePlaybackBudget, val preserveSibling: Boolean)
    private val retryPlaybackJoins = arrayOfNulls<RetryPlaybackJoin>(2)
    private data class RetryPlaybackStart(val side: NativeMixedSide, val replacement: NativePairMember,
        val sibling: NativePairMember)
    private var retryPlaybackStart: RetryPlaybackStart? = null
    private val retryDeadline = LongArray(2)
    private val replayRestoreTarget = arrayOfNulls<Long>(2)
    private var surfaces = emptyList<PlayerView>()
    private var budget: NativePlaybackBudget? = null
    private var pairMediaSession: ForegroundPairMediaSession? = null
    private var playback: PrototypePlaybackController? = null
    private var focus: NativePlaybackAudioGroup? = null
    private var viewer: NativePairViewer? = null
    private var root: FrameLayout? = null
    private var progress: TextView? = null
    private var returnButton: android.view.View? = null
    private var playBarrier: PrototypePlayBarrier? = null
    private var playMessage: String? = null
    private var cleanupFailed = false
    private var disposing = false
    private var resumeMessage: String? = null
    private var desiredPlaying = false
    private var lastAuthorizationPoll = 0L
    private var lastSample = 0L
    private var lastTimingSample = 0L
    private var lastTimingStatus: String? = null
    private val authorizationPolling = AtomicBoolean()
    private var routes = emptyMap<String, RouteSession>()
    private var routeProfiles = emptyMap<String, Result<ConnectionProfile?>>()
    private var routeChoices = emptyMap<String, SourceRouteChoice>()
    private var routeOwners = emptyMap<String, ProviderEffectiveRoute>()
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

    private fun feedDiagnostics(index: Int, attempt: Long? = null): FailureReporter {
        val reporter = diagnostics.forSlot(if (index == 0) FailureSlot.A else FailureSlot.B)
        if (attempt == null) return reporter
        val run = epoch.get()
        val retry = feedRetries[index]
        return reporter.withNativeEvidence { _, stage, evidence ->
            val observation = EarlyTransportFailure(run, retry, attempt, SystemClock.elapsedRealtime(), stage, evidence)
            handler.post {
                if (epoch.get() == run && feedRetries[index] === retry && retry.current(attempt))
                    earlyTransportFailures.set(index, observation)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickerAssignments = restoreConfiguredFeedAssignments(savedInstanceState != null,
            savedInstanceState?.getString("prototype.feed.a"), savedInstanceState?.getString("prototype.feed.b"))
        var preferCurrentSelection = savedInstanceState != null
        var persistCurrentSelection = savedInstanceState != null &&
            (savedInstanceState.getString("prototype.feed.a") == null || pickerAssignments.a != null) &&
            (savedInstanceState.getString("prototype.feed.b") == null || pickerAssignments.b != null)
        if (android.os.Build.VERSION.SDK_INT < 28) { finish(); return }
        if (savedInstanceState == null) {
            when (val checkpoint = ConfiguredRecoveryCheckpoint(noBackupFilesDir, useCachedAbema).consume()) {
                ConfiguredRecoveryCheckpoint.Result.Absent -> Unit
                is ConfiguredRecoveryCheckpoint.Result.Restored -> {
                    pickerAssignments = checkpoint.assignments
                    preferCurrentSelection = true
                    persistCurrentSelection = true
                }
                ConfiguredRecoveryCheckpoint.Result.Invalid -> {
                    preferCurrentSelection = true
                    persistCurrentSelection = false
                    pickerAssignments = ConfiguredFeedAssignments(null, null)
                    recoveryState.fail(PrototypeRecoveryKind.PICKER_RESTORE_FAILED)
                }
            }
        }
        pickerSelectionRestore = ViewerSelectionRestore(preferCurrentSelection)
        persistRestoredSelection = persistCurrentSelection
        initializeDiagnostics()
        if (!profileConfigured) {
            if (useCachedAbema) configureAbemaCachedPlaybackProfile(this)
            else WebView.setDataDirectorySuffix("prototype-player")
            profileConfigured = true
        }
        net.fstab.tachiai.platform.web.DeveloperAccess.configureWebInspection()
        onBackPressedDispatcher.addCallback(this) {
            if (viewer?.handleBack() == true) return@addCallback
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
        diagnostics = if (routesPending || routeCleanupPending || routeCleanupFailed || webRoute.busy || (useCachedAbema && cachedCleanup.unavailable))
            routeDiagnostics else FailureDiagnostics.create(this)
    }

    private fun showPicker(message: String?) {
        root = null; progress = null; returnButton = null
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        styleSystemBars(false)
        setContent { TachiaiPrototypeTheme { PrototypeSourcePicker(listOfNotNull(setupMessage ?: message, pickerSelectionMessage).joinToString("\n").ifBlank { null },
            onConnections = { startActivity(Intent(this, ConnectionProfilesActivity::class.java)) },
            setupReady = setupReady, providerInstances = providerInstances, configuredSources = configuredSources,
            initialAssignments = pickerAssignments, onAssignmentsChanged = ::rememberPickerAssignments,
            assignmentRestoreGeneration = pickerRestoreGeneration,
            onProviders = { startActivity(Intent(this, ProvidersActivity::class.java)) },
            onScreenSharing = { target -> openScreenSharingTarget(target,
                { packageManager.getLaunchIntentForPackage(GOOGLE_HOME_PACKAGE) }, ::startActivity) },
            onDeveloperTools = if (BuildConfig.DEBUG) ({ startActivity(Intent(this, net.fstab.tachiai.MainActivity::class.java)
                .putExtra("net.fstab.tachiai.extra.NATIVE_ACCESS_PROBE", true)) }) else null,
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
        playbackAvailable = incident == null && !routesPending && !routeCleanupPending && !webRoute.busy &&
            !(useCachedAbema && cachedCleanup.unavailable)
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

    private fun rememberPickerAssignments(assignments: ConfiguredFeedAssignments) {
        pickerAssignments = assignments
        pickerSelectionRestore.userChanged()
        val revision = ++pickerSelectionRevision
        val app = applicationContext
        selectionPersistence.execute {
            val saved = runCatching { viewerSelectionStore(app).save(assignments) }.isSuccess
            handler.post {
                if (!isDestroyed && pickerSelectionRevision == revision)
                    pickerSelectionMessage = if (saved) null else "A/B choices could not be saved. Your current choices remain selected."
            }
        }
    }

    private fun watch(assignments: ConfiguredFeedAssignments) {
        rememberPickerAssignments(assignments)
        watchScoped(assignments, null)
    }

    private fun watchScoped(assignments: ConfiguredFeedAssignments, capturedPlan: ProviderInstanceRoutePlan?) {
        if (checkingAccount || !resumed.get() || isFinishing || isDestroyed) return
        if (!setupReady) { showPicker("Provider setup is unavailable; playback was not started."); return }
        val resolved = resolveConfiguredPrototypeSelection(assignments, configuredSources, providerInstances,
            allowConfiguredAbemaEpisodes = useCachedAbema, allowConfiguredAbemaChannels = useCachedAbema,
            allowConfiguredAbemaSlots = useCachedAbema)
        if (resolved is ConfiguredPrototypeSelectionResult.Failure) {
            showPicker(configuredSelectionMessage(resolved.reason)); return
        }
        val selected = (resolved as ConfiguredPrototypeSelectionResult.Ready).selection
        val check = ++accountCheckRevision
        retireStartupScopes()
        checkingAccount = true
        showPicker("Checking provider connections…")
        worker.execute {
            fun instances() = providerInstanceStore(this).read { legacyProviderSettings(this) }
            val pools = captureProviderInstanceRoutePools(selected, ::instances, connectionProfileStore(this)::snapshot)
            val validatedTwitch = mutableMapOf<String, TwitchCatalogReadOnlyValidation>()
            fun active() = check == accountCheckRevision && resumed.get() && !isFinishing && !isDestroyed
            val effective = pools.mapValues { (id, captured) -> runCatching {
                val pool = captured.getOrThrow()
                check(ProviderStartupScopeCleanup.awaitReady(id, SystemClock.elapsedRealtime() + 45_000L, ::active) ==
                    ProviderStartupCleanupAdmission.READY)
                val startup = androidProviderRouteStartup(this, pool) { status -> handler.post {
                    if (active()) showPicker(if (pool.configuredInstance.service == PrototypeService.YOUTUBE &&
                        status.phase == net.fstab.tachiai.platform.network.ProviderStartupPhase.HELD_FOR_MUTATION)
                        "Using ${status.label.orEmpty()} · preparing playback" else providerStartupStatus(status))
                } }
                val registered = synchronized(startupLock) {
                    if (!active()) false else { startupScopes = startupScopes + (id to startup); true }
                }
                if (!registered) { ProviderStartupScopeCleanup.retire(id, startup); error("Startup stopped") }
                if (capturedPlan != null) {
                    val previous = checkNotNull(capturedPlan.effective[id]).getOrThrow()
                    check(previous.poolProof == pool.proof)
                    check(startup.holdForMutation(previous.proof) is ProviderStartupResult.HeldForMutation)
                }
                val result = if (pool.configuredInstance.service == PrototypeService.ABEMA) {
                    if (useCachedAbema) prepareAndroidAbemaViewerStartup(this, startup, ::active)
                    else startup.holdFirstForMutation()
                } else if (pool.configuredInstance.service == PrototypeService.YOUTUBE) {
                    check(pool.candidates.all { it.choice.mode == net.fstab.tachiai.presentation.SourceRouteMode.SYSTEM })
                    startup.holdFirstForMutation()
                } else prepareAndroidTwitchCatalogStartup(this, id, startup, ::active)
                when (result) {
                    is ProviderStartupResult.Ready -> {
                        if (pool.configuredInstance.service == PrototypeService.TWITCH)
                            validatedTwitch[id] = checkNotNull((result.value as? TwitchCatalogStartupReceipt)?.startupValidation)
                        result.owner
                    }
                    is ProviderStartupResult.HeldForMutation -> result.owner
                    is ProviderStartupResult.Failed -> error("Provider startup stopped: ${result.failure.reason.name}")
                }
            } }
            val plan = effectiveRoutePlan(selected, effective)
            handler.post {
                if (check != accountCheckRevision || !resumed.get() || isFinishing || isDestroyed) return@post
                startupTwitchValidation = validatedTwitch.toMap()
                if (!plan.abemaCompatible) {
                    checkingAccount = false
                    retireStartupScopes()
                    showPicker("Selected ABEMA instances need available, identical routes. Their WebView proxy is shared.")
                    return@post
                }
                val abema = selected.feeds.filter { it.service == PrototypeService.ABEMA }.map { it.instanceId }.distinct()
                val targets = abema.mapNotNull { id -> plan.effective[id]?.getOrNull()?.let {
                    net.fstab.tachiai.provider.abema.account.AbemaReadinessTarget(id, it.proof)
                } }
                // Failed owners remain in the plan for per-feed failure. Only
                // successfully captured ABEMA owners can enter readiness; a
                // failed ABEMA startup must not discard a healthy Twitch feed.
                if (targets.isEmpty()) {
                    checkingAccount = false
                    watchReady(assignments, plan)
                    return@post
                }
                accountReadiness.ensure(targets) { result ->
                    if (check != accountCheckRevision || !resumed.get() || isFinishing || isDestroyed) return@ensure
                    checkingAccount = false
                    when (result) {
                        AbemaAccountReadinessResult.READY -> {
                            pendingAccountSelection = null; pendingAccountPlan = null
                            watchReady(assignments, plan)
                        }
                        AbemaAccountReadinessResult.HANDOFF -> {
                            pendingAccountSelection = assignments
                            pendingAccountPlan = plan
                            showPicker("Renewing the remembered ABEMA account…")
                        }
                        else -> {
                            pendingAccountSelection = null
                            pendingAccountPlan = null
                            retireStartupScopes()
                            showPicker(abemaAccountReadinessMessage(result))
                        }
                    }
                }
            }
        }
    }

    private fun watchReady(assignments: ConfiguredFeedAssignments, routePlan: ProviderInstanceRoutePlan) {
        try { openPreparedViewer(assignments, routePlan) }
        finally { if (budget == null) retireStartupScopes() }
    }

    private fun openPreparedViewer(assignments: ConfiguredFeedAssignments, routePlan: ProviderInstanceRoutePlan) {
        pickerAssignments = assignments
        updateRecoveryState()
        if (recoveryState.incident != null) {
            diagnostics.blocked(if (routeCleanupFailed) FailureStage.ROUTE_BLOCKED else FailureStage.VIEWER_BLOCKED)
            showPicker(null); showRecoveryDialog(true); return
        }
        if (!setupReady) { showPicker("Provider setup is unavailable; playback was not started."); return }
        if (routesPending || routeCleanupPending || routeCleanupFailed || webRoute.busy || (useCachedAbema && cachedCleanup.unavailable)) {
            if (cleanupFailed || routeCleanupFailed) routeDiagnostics.blocked(FailureStage.ROUTE_BLOCKED)
            showPicker(if (cleanupFailed || routeCleanupFailed)
                "Route cleanup could not be confirmed. Use Recovery options to restart."
                else "The previous route is still stopping. Please retry shortly.")
            return
        }
        val resolved = resolveConfiguredPrototypeSelection(assignments, configuredSources, providerInstances,
            allowConfiguredAbemaEpisodes = useCachedAbema, allowConfiguredAbemaChannels = useCachedAbema,
            allowConfiguredAbemaSlots = useCachedAbema)
        if (resolved is ConfiguredPrototypeSelectionResult.Failure) {
            showPicker(configuredSelectionMessage(resolved.reason)); return
        }
        resolved as ConfiguredPrototypeSelectionResult.Ready
        val selected = resolved.selection
        selection = selected
        activeConfiguredSources = resolved.sourceSlots
        activeSetups = sourceSetups.toMap()
        activeInstances = providerInstances.toList()
        dispose(retainStartups = true)
        if (cleanupFailed) {
            updateRecoveryState()
            diagnostics.blocked(FailureStage.VIEWER_BLOCKED)
            showPicker("Playback stopped because cleanup could not be confirmed."); return
        }
        diagnostics = diagnostics.newSession()
        if (useCachedAbema) {
            cachedRun = cachedCleanup.begin(diagnostics)
            if (cachedRun == null) { showPicker("The previous route is still stopping. Please retry shortly."); return }
        }
        val run = epoch.incrementAndGet()
        qualitySession = ViewerQualityState(activeConfiguredSources.map { it?.choice }, qualityDefaults)
        fun active() = resumed.get() && epoch.get() == run && !isFinishing
        val sharedBudget = NativePlaybackBudget.continuous(::active)
        budget = sharedBudget
        loadPairOffset(selected, run)
        failures.fill(null); prepared.fill(false); initialCatchUp.fill(false); catchUpDeadline.fill(0)
        feedRetries = List(2) { FeedRetryState() }; retryDeadline.fill(0); replayRestoreTarget.fill(null)
        setPlaybackIntent(true, PlaybackHealthIntent.STARTUP); lastAuthorizationPoll = 0; lastSample = 0
        lastHealthSampleMs = 0
        healthOwners.fill(null); healthSignatures.fill(null)
        healthTrackers.forEach { it.reset() }
        repeat(2) { earlyTransportFailures.set(it, null) }
        lastTimingSample = 0; lastTimingStatus = null
        styleSystemBars(false)
        val frame = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.prototype_background)) }
        val setup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(setup) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val margin = (16 * resources.displayMetrics.density).toInt()
            view.setPadding(bars.left + margin, bars.top + margin, bars.right + margin, bars.bottom + margin); insets
        }
        setup.addView(nativePageHeading(this, "Preparing feeds", "Back to sources") { stopToPicker("Playback stopped.") })
        progress = TextView(this).apply { textSize = 18f; setTextColor(getColor(R.color.prototype_text)) }.also { setup.addView(it) }
        setup.addView(TextView(this).apply {
            text = if (!selected.isPair) "Preparing your feed…"
                else if (useCachedAbema) "Preparing your feeds… ABEMA uses cached provider modules, without opening its player page. No account entry is needed."
                else "Preparing your feeds… ABEMA may ask you to keep using the web player. No account entry is needed for these fixed sources."
        })
        setup.addView(nativePageHeading(this, "Viewer", "Return to viewer") { styleSystemBars(true); viewer?.open() }.also { heading ->
            returnButton = heading.getChildAt(0).apply { isEnabled = false }
        })
        frame.addView(setup, FrameLayout.LayoutParams(-1, -1))
        root = frame
        setContentView(frame)
        createViewer()
        prepareRoutes(selected, sharedBudget, run, setup, routePlan)
        updateProgress()
        handler.post(ticker)
    }

    private fun prepareRoutes(selected: ConfiguredPlaybackSelection, sharedBudget: NativePlaybackBudget, run: Long, setup: LinearLayout,
        capturedPlan: ProviderInstanceRoutePlan) {
        val preparation = RoutePreparation(diagnostics).also { routePreparation = it }
        val ownedRun = cachedRun
        ownedRun?.let { cachedCleanup.preparation(it, preparation::cancel) }
        routeDiagnostics = diagnostics
        routesPending = true
        recoveryState.notifyChanged()
        worker.execute {
            try {
            val scopes = synchronized(startupLock) { startupScopes.toMap() }
            val plan = revalidateProviderInstanceRoutes(selected, capturedPlan,
                { providerInstanceStore(this).read { legacyProviderSettings(this) } }, connectionProfileStore(this)::snapshot)
            if (!plan.abemaCompatible) {
                handler.post {
                    ownedRun?.let { cachedCleanup.prepared(it, preparation.cleanupConfirmed) }
                    routesPending = false
                    recoveryState.notifyChanged()
                    if (sharedBudget.active && epoch.get() == run) stopToPicker(
                        "Selected ABEMA instances need available, identical routes. Their WebView proxy is shared; no route or playback was started.")
                }
                return@execute
            }
            val registry = RouteSessionRegistry { profile ->
                check(sharedBudget.active)
                if (ownedRun == null) RouteSession.create(profile, preparation)
                else {
                    var partial: CachedViewerRouteBackend? = null
                    RouteSession.createRetained(profile, preparation, ownBackend = { backend ->
                        CachedViewerRouteBackend(backend).also {
                            partial = it
                            cachedCleanup.route(ownedRun, it)
                        }
                    }).also { route -> cachedCleanup.route(ownedRun, route, partial) }
                }
            }
            val results = selected.feeds.distinctBy { it.instanceId }.associate { feed -> feed.instanceId to
                runCatching {
                    check(scopes[feed.instanceId]?.canContinue() == true)
                    val profile = checkNotNull(plan.profiles[feed.instanceId]).getOrThrow()
                    if (feed.service == PrototypeService.ABEMA && !useCachedAbema && profile != null)
                        error("Historical page comparison has no imported route support")
                    registry.acquire(profile)
                }
            }
            handler.post {
                ownedRun?.let { cachedCleanup.prepared(it, preparation.cleanupConfirmed) }
                val created = results.mapNotNull { it.value.getOrNull() }.distinct()
                val startupStopped = plan.effective.any { (id, owner) -> owner.isSuccess && scopes[id]?.canContinue() != true }
                if (!sharedBudget.active || epoch.get() != run || isDestroyed || isFinishing || !preparation.cleanupConfirmed ||
                    startupStopped || (ownedRun != null && !cachedCleanup.current(ownedRun))) {
                    if (ownedRun != null) {
                        cachedCleanup.end(ownedRun)
                        routesPending = false
                        recoveryState.notifyChanged()
                        if (startupStopped && sharedBudget.active && epoch.get() == run && !isDestroyed && !isFinishing)
                            stopToPicker("The provider connection stopped. Check its routes and retry.")
                        return@post
                    }
                    closeRoutesAsync(created) { accepted ->
                        routesPending = false
                        if (!accepted || !preparation.cleanupConfirmed) {
                            routeCleanupFailed = true; routeCleanupPending = true; cleanupFailed = true
                            if (sharedBudget.active && epoch.get() == run && !isDestroyed && !isFinishing)
                                stopToPicker("Route cleanup could not be confirmed. Use Recovery options to restart.")
                        }
                        else if (startupStopped && sharedBudget.active && epoch.get() == run && !isDestroyed && !isFinishing)
                            stopToPicker("The provider connection stopped. Check its routes and retry.")
                        updateRecoveryState(); recoveryState.notifyChanged()
                    }
                    return@post
                }
                routesPending = false
                recoveryState.notifyChanged()
                routes = results.mapNotNull { (provider, result) -> result.getOrNull()?.let { provider to it } }.toMap()
                routeProfiles = plan.profiles.filterKeys { it in routes }
                routeOwners = plan.effective.mapNotNull { (id, result) -> result.getOrNull()?.takeIf { id in routes }?.let { id to it } }.toMap()
                routeChoices = routeOwners.mapValues { (_, owner) -> owner.choice }
                results.filterValues { it.isFailure }.keys.forEach { instanceId ->
                    selected.slots.forEachIndexed { index, feed -> if (feed?.instanceId == instanceId)
                        failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.ROUTE_FAILED)) }
                }
                createFeeds(selected, sharedBudget, run, setup, setOf(PrototypeService.TWITCH, PrototypeService.YOUTUBE))
                val abema = selected.feeds.firstOrNull { it.service == PrototypeService.ABEMA }?.let { routes[it.instanceId] }
                if (useCachedAbema && abema != null) {
                    checkNotNull(ownedRun).let(cachedCleanup::proxyWillChange)
                    var completed = false
                    fun complete(accepted: Boolean) {
                        if (completed) return
                        completed = true
                        if (sharedBudget.active && epoch.get() == run) {
                            if (!accepted) selected.slots.forEachIndexed { index, source ->
                                if (source?.service == PrototypeService.ABEMA) failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.ROUTE_FAILED))
                            }
                            else createFeeds(selected, sharedBudget, run, setup, setOf(PrototypeService.ABEMA))
                        }
                    }
                    try { webRoute.install(abema, ::complete, diagnostics) }
                    catch (error: Throwable) {
                        diagnostics.report(FailureStage.WEB_ROUTE_INSTALL, error)
                        complete(false)
                    }
                    handler.postDelayed({
                        if (!completed && sharedBudget.active && epoch.get() == run)
                            diagnostics.report(FailureStage.ROUTE_INSTALL_TIMEOUT)
                        complete(false)
                    }, 3_000)
                } else createFeeds(selected, sharedBudget, run, setup, setOf(PrototypeService.ABEMA))
            }
            } catch (error: Throwable) {
                if (ownedRun == null) throw error
                diagnostics.report(FailureStage.ROUTE_PREPARATION, error)
                handler.post {
                    ownedRun?.let { cachedCleanup.prepared(it, preparation.cleanupConfirmed); cachedCleanup.end(it) }
                    routesPending = false
                    if (sharedBudget.active && epoch.get() == run) stopToPicker("Route preparation failed. Playback stopped.")
                    recoveryState.notifyChanged()
                }
            }
        }
    }

    private fun createFeeds(selected: ConfiguredPlaybackSelection, sharedBudget: NativePlaybackBudget, run: Long, setup: LinearLayout,
        providers: Set<PrototypeService>, slots: Set<Int> = setOf(0, 1)) {
        val ownedRun = cachedRun
        fun active() = resumed.get() && epoch.get() == run && !isFinishing &&
            (ownedRun == null || cachedCleanup.current(ownedRun))
        if (!active()) return
        val created = mutableListOf<Pair<Int, PrototypeFeedSession>>()
        selected.slots.forEachIndexed { index, selectedSource ->
            val source = selectedSource ?: return@forEachIndexed
            if (index !in slots || source.service !in providers || failures[index] != null || sessions[index] != null) return@forEachIndexed
            val attemptState = feedRetries[index]
            val attempt = attemptState.newAttempt()
            earlyTransportFailures.set(index, null)
            fun currentAttempt() = active() && feedRetries[index] === attemptState && attemptState.current(attempt)
            try {
                val replay = source.kind == PrototypePlaybackKind.REPLAY
                val events: (PrototypeFeedEvent) -> Unit = { event ->
                    if (currentAttempt() && failures[index] == null) {
                        Log.d("TachiaiPrototype", "slot=${if (index == 0) "A" else "B"} source=${source.diagnosticName} event=${event.name}")
                        if (event == PrototypeFeedEvent.NETWORK_APPROVAL_REQUIRED)
                            failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.MEDIA_APPROVAL_REQUIRED))
                        else if (event == PrototypeFeedEvent.FAILED || event == PrototypeFeedEvent.STOPPED)
                            failFeed(index, sessions[index]?.failure ?: PrototypeFeedFailure(
                                if (event == PrototypeFeedEvent.STOPPED) PrototypeFailureReason.STOPPED else PrototypeFailureReason.PREPARATION_FAILED))
                        else updateProgress()
                    }
                }
                val startupScope = checkNotNull(synchronized(startupLock) { startupScopes[source.instanceId] })
                val feedActive = { currentAttempt() && failures[index] == null && startupScope.canContinue() }
                val abemaOwnership = if (source.service == PrototypeService.ABEMA && useCachedAbema)
                    checkNotNull(routeOwners[source.instanceId]).let { owner ->
                        androidAbemaEpisodePlaybackOwnership(this, owner.effectiveInstance, owner.profile,
                            feedActive, feedDiagnostics(index), expectedProof = owner.proof)
                    } else null
                val session = when (source.service) {
                    PrototypeService.YOUTUBE -> net.fstab.tachiai.provider.youtube.YouTubeFeedSession(
                        this, source.resource.identity, feedActive, events,
                        onCleanupChanged = { cleanupHandler.post { ownedRun?.let(cachedCleanup::feedCleanupChanged) } })
                    PrototypeService.ABEMA -> if (useCachedAbema) CachedPrototypeAbemaSession(this, replay, feedActive, events,
                        route = checkNotNull(routes[source.instanceId]), diagnostics = feedDiagnostics(index, attempt),
                        onHelperCreated = { helper -> cachedCleanup.helper(checkNotNull(ownedRun), helper,
                            helper::destroy) { !helper.cleanupFailed } },
                        onCleanupChanged = { cleanupHandler.post { ownedRun?.let(cachedCleanup::feedCleanupChanged) } },
                        episodeResource = source.resource.takeIf { replay && it.kind == "episode" },
                        channelResource = source.resource.takeIf { !replay && source.historicalSource == null },
                        slotResource = source.resource.takeIf { it.kind == "slot" && source.historicalSource == null },
                        continuousSlotPlayback = source.resource.kind == "slot" && source.historicalSource == null,
                        ownership = if (!replay && source.historicalSource != null) null else abemaOwnership,
                        routeOwnership = abemaOwnership)
                        else historicalAbemaSession(replay, feedActive, events, feedDiagnostics(index))
                    PrototypeService.TWITCH -> {
                        val instanceId = source.instanceId
                        val route = checkNotNull(routes[instanceId])
                        val profile = checkNotNull(routeProfiles[instanceId]).getOrThrow()
                        val routeChoice = checkNotNull(routeChoices[instanceId])
                        val ownerProof = checkNotNull(routeOwners[instanceId]).proof
                        val startupValidation = startupTwitchValidation[instanceId].takeUnless { attemptState.retrying }
                        val preparation = {
                            CatalogTwitchPreparation(this, instanceId, profile, routeChoice, feedActive,
                                openConnection = route::open, expectedOwnerProof = ownerProof, startupValidation = startupValidation)
                        }
                        if (supportedConfiguredTwitchBroadcaster(source.resource)) {
                            configuredTwitchBroadcasterSession(this, source.resource, feedActive, events,
                                liveIdentityResolverFactory = {
                                    androidTwitchLiveIdentityResolver(this, instanceId, profile, feedActive,
                                        twitchBroadcasterRetryGate, expectedRoute = routeChoice,
                                        expectedOwnerProof = ownerProof)
                                }, openConnection = route::open,
                                diagnostics = feedDiagnostics(index, attempt), preparationFactory = preparation,
                                onCleanupChanged = { cleanupHandler.post { ownedRun?.let(cachedCleanup::feedCleanupChanged) } })
                        } else PrototypeTwitchSession(this, replay, source.resource.identity, feedActive, events,
                            openConnection = route::open, preparationFactory = preparation,
                            diagnostics = feedDiagnostics(index, attempt),
                            initialPositionMs = if (source.historicalSource == PrototypeSource.TWITCH_REPLAY) 70 * 60 * 1000L else 0L,
                            onCleanupChanged = { cleanupHandler.post { ownedRun?.let(cachedCleanup::feedCleanupChanged) } })
                    }
                }
                sessions = sessions.toMutableList().also { it[index] = session }
                ownedRun?.let { cachedCleanup.feed(it, index, session,
                    pending = { when (session) {
                        is CachedPrototypeAbemaSession -> session.cleanupPending
                        is PrototypeTwitchSession -> session.cleanupPending
                        is net.fstab.tachiai.provider.youtube.YouTubeFeedSession -> session.cleanupPending
                        else -> false
                    } },
                    knownFailed = { session.cleanupFailed }) {
                    when (session) {
                        is CachedPrototypeAbemaSession -> session.cleanupConfirmed
                        is PrototypeTwitchSession -> session.cleanupConfirmed
                        is net.fstab.tachiai.provider.youtube.YouTubeFeedSession -> session.cleanupConfirmed
                        else -> !session.cleanupFailed
                    }
                } }
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
        progress?.text = (selection ?: return).slots.mapIndexedNotNull { index, source ->
            if (source == null) return@mapIndexedNotNull null
            val state = failures[index]?.message ?: if (prepared[index]) "Ready" else "Preparing"
            "${sourceLabel(index)}: $state"
        }.joinToString("\n")
    }

    private val ticker = object : Runnable {
        override fun run() {
            val currentBudget = budget ?: return
            if (!currentBudget.active) {
                selectedSlotIndices().forEach { failFeed(it, PrototypeFeedFailure(PrototypeFailureReason.PLAYBACK_LIMIT)) }
                releaseRoutes()
                focus?.release()
                viewer?.refresh()
                return
            }
            processFeedRetries(currentBudget)
            if (budget !== currentBudget) return
            sessions.forEachIndexed { index, host ->
                try {
                    if (prepared[index] && selection?.slots?.get(index)?.kind == PrototypePlaybackKind.REPLAY) {
                        host?.member?.timingSnapshot()?.takeIf {
                            it.state == Player.STATE_READY && !it.live && !it.dynamic && !it.playingAd
                        }?.positionMs?.let(feedRetries[index]::rememberReplay)
                    }
                    if (host?.member != null && !host.canContinue()) failFeed(index, authorizationFailure(index, host))
                    else if (host != null && failures[index] == null && !prepared[index]) prepareViewerSlot(index, host)
                } catch (error: Exception) {
                    feedDiagnostics(index).report(FailureStage.FEED_POLL, error)
                    failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED))
                }
            }
            updateProgress()
            timingEvidenceRequest?.let { evidence ->
                if (playback?.pair === evidence.pair && evidence.pair.busy) {
                    val currentTargets = evidence.pair.pendingSeekDiagnostics
                    if (currentTargets.isNotEmpty()) evidence.targets = currentTargets
                }
            }
            playback?.poll()
            finishTimingEvidence()
            processPairOffset()
            val available = NativeMixedSide.entries.mapNotNull { playback?.member(it) }
            if (desiredPlaying && playBarrier == null && playback?.busy != true && !pairOffsetBlocksPlay() && available.isNotEmpty() &&
                available.all { it.timingSnapshot()?.let { value -> value.state == Player.STATE_READY && !value.playingAd } == true } &&
                available.any { it.timingSnapshot()?.playWhenReady != true }) {
                playReadyFeeds()
            }
            viewer?.refresh()
            val now = SystemClock.elapsedRealtime()
            val timingStatus = playback?.status
            if (timingStatus != lastTimingStatus) {
                lastTimingStatus = timingStatus
                Log.d("TachiaiPrototype", "timingStatus=$timingStatus")
            }
            if (playback?.busy == true && now - lastTimingSample >= 1_000) {
                lastTimingSample = now
                logTimingSnapshots("CHECK")
                playback?.pair?.pendingSeekDiagnostics?.forEach { target ->
                    val error = target.actualClockMs?.let { runCatching { Math.subtractExact(it, target.targetClockMs) }.getOrNull() }
                    Log.d("TachiaiPrototype", "timingTarget side=${target.side} errorMs=$error liveClock=${target.liveClock} state=${target.state}")
                }
            }
            if (now - lastSample >= 5_000) {
                lastSample = now
                NativeMixedSide.entries.forEach { side ->
                    playback?.member(side)?.timingSnapshot()?.let { value ->
                        Log.d("TachiaiPrototype", "slot=${side.name} state=${value.state} playing=${value.playing} positionMs=${value.positionMs} durationMs=${value.durationMs}")
                        playback?.member(side)?.qualitySnapshot()?.let { quality ->
                            Log.d("TachiaiPrototype", "slot=${side.name} quality=${quality.summary().replace('\n', ' ')}")
                        }
                    }
                }
            }
            if (now - lastHealthSampleMs >= 1_000) {
                lastHealthSampleMs = now
                repeat(2) { recordPlaybackHealth(it, PlaybackHealthReason.SAMPLE_STATE_CHANGED) }
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
            publishPairTransport()
            if (budget === currentBudget) handler.postDelayed(this, 200)
        }
    }

    private fun createViewer() {
        val group = NativePlaybackAudioGroup(this) {
            cancelPairOffsetPending()
            setPlaybackIntent(false, PlaybackHealthIntent.FOCUS_LOSS); invalidatePlay(); playback?.focusLost()
            sessions.forEachIndexed { index, host ->
                if (host?.member?.timingSnapshot()?.playWhenReady == true)
                    failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED))
            }
            viewer?.refresh()
        }
        focus = group
        playback = PrototypePlaybackController(group::acquire, admission = { member ->
            sessions.any { host -> host != null && host.member === member && host.canContinue() }
        }, playAllowed = { resumed.get() && desiredPlaying && budget?.active == true }, singleFeed = selection?.isPair == false)
        fun surface() = (layoutInflater.inflate(R.layout.native_viewer_player, root, false) as PlayerView).apply {
            useController = false; keepScreenOn = true
        }
        surfaces = listOf(surface(), surface())
        val pane = NativePairViewer(this, surfaces[0], surfaces[1], sourceLabel(0), sourceLabel(1),
            { playback?.pair }, ::playPair, ::pausePair,
            onRelative = { delta -> requestRelativeAdjustment(delta) },
            onCatchUp = ::requestBackToLive,
            onDiagnostics = { pausePair(); viewer?.visibility = View.GONE; styleSystemBars(false) },
            onStop = { stopToPicker("Playback stopped.") },
            onLandscape = { landscape -> requestedOrientation = if (landscape)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT },
            setupLabel = "Preparation / provider pages", statusOverride = { playMessage ?: playback?.status },
            members = { NativeMixedSide.entries.map { playback?.member(it) } },
            catchUpPlan = { side -> playback?.catchUpPlan(side) ?: NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE) },
            playbackBusy = { playback?.busy == true || pairOffsetLoading || pairOffsetSession?.state?.busy == true },
            onMarkedAlignment = ::requestMarkedAlignment,
            volumeRequest = { side, volume -> playback?.setVolume(side, volume) == true },
            feedMessage = { side ->
                val index = side.ordinal
                if (feedRetries[index].retrying) "Retrying this feed…"
                else failures[index]?.let { it.viewerMessage }
                    ?: if (!prepared[index]) "Preparing this feed…" else null
            }, feedRecovery = { side ->
                val index = side.ordinal
                failures[index]?.recoveryAction()?.takeIf { canRetryFeed(index) }?.let {
                    ViewerFeedRecovery(it.label, !feedRetries[index].retrying)
                } ?: if (feedRetries[index].retrying) ViewerFeedRecovery("Retrying…", false) else null
            }, onRecoverFeed = { side -> requestFeedRetry(side.ordinal) },
            onSources = { stopToPicker("Playback stopped.") },
            qualityState = { side, kind -> qualitySession?.read(side, kind)?.copy(saving = savingQuality, message = qualityMessage) },
            onQualityOverride = { side, kind, request ->
                if (!savingQuality && playback?.busy != true) {
                    qualitySession?.setOverride(side, kind, request)
                    applyQuality(side); viewer?.refresh()
                }
            },
            onSaveQuality = { side, kind -> saveQuality(side, kind, false) },
            onResetQuality = { side, kind -> saveQuality(side, kind, true) },
            pairOffsetState = { if (selection?.isPair == true) pairOffsetReadback() else null },
            onPairOffsetEnabled = ::togglePairOffset,
            onMarkedCorrespondence = { delta, correspondence -> requestTimingAdjustment(delta, true, correspondence) },
            selectedSlots = selectedSlotIndices().map { NativeMixedSide.entries[it] }.toSet())
        viewer = pane
        root?.addView(pane, FrameLayout.LayoutParams(-1, -1))
        if (isTelevision(resources.configuration)) {
            val current = playback
            val ownedBudget = budget
            val run = epoch.get()
            pairMediaSession = ForegroundPairMediaSession(this,
                admitted = { resumed.get() && epoch.get() == run && playback === current &&
                    current != null && budget === ownedBudget && ownedBudget?.active == true &&
                    viewer === pane && pane.visibility == View.VISIBLE && !isFinishing && !isDestroyed },
                playingRequested = { desiredPlaying || current?.playingRequested == true },
                play = ::playPair, pause = ::pausePair)
            // Route this foreground window's media keys to its own pair rather
            // than relying on another application's global media priority.
            mediaController = MediaController(this, checkNotNull(pairMediaSession).token)
        }
        returnButton?.isEnabled = true
        styleSystemBars(true)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        pane.open()
    }

    private fun requestRelativeAdjustment(delta: Long) = requestTimingAdjustment(delta, marked = false)

    private fun requestMarkedAlignment(delta: Long) = requestTimingAdjustment(delta, marked = true)

    private fun requestBackToLive(side: NativeMixedSide) {
        cancelRetryPlaybackIntent()
        pairOffsetSkipStartup = true
        pairOffsetSession?.skipStartup()
        val pair = playback?.pair
        val beforeAttempt = pair?.lastTimingAttempt
        setPlaybackIntent(false, PlaybackHealthIntent.BACK_TO_LIVE); invalidatePlay()
        logTimingSnapshots("BEFORE")
        val result = playback?.catchUp(side) ?: NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        if (result.outcome == NativeSeekOutcome.REQUESTED || pair?.lastTimingAttempt !== beforeAttempt)
            pairOffsetRetryEstimate.clear()
        if (pair != null && playback?.pair === pair && pair.lastTimingAttempt !== beforeAttempt) {
            pairOffsetBaselineLost = true
            pairOffsetSession?.invalidate(pair, "Returned to live; reopen this pair to reapply its saved offset.")
        }
        Log.d("TachiaiPrototype", "timing=BACK_TO_LIVE side=$side outcome=${result.outcome}")
    }

    private fun requestTimingAdjustment(delta: Long, marked: Boolean,
        correspondence: PairOffsetCorrespondence? = null): NativeSeekPlan {
        if (pairOffsetLoading || pairOffsetSession?.state?.busy == true)
            return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        processPairOffset(allowStartup = false)
        cancelRetryPlaybackIntent()
        pairOffsetSkipStartup = true
        val pair = playback?.pair
        val command = pair?.let { pairOffsetSession?.planManual(it, delta, it.lastTimingSettlement?.revision ?: 0L, correspondence) }
        if (pair != null && pairOffsetSession != null && command == null) return NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        val beforeAttempt = pair?.lastTimingAttempt
        val beforeRevision = pair?.lastTimingSettlement?.revision ?: 0L
        if (delta != 0L) { setPlaybackIntent(false, PlaybackHealthIntent.TIMING); invalidatePlay() }
        logTimingSnapshots("BEFORE")
        val result = (if (marked) playback?.alignRelative(delta) else playback?.shiftRelative(delta))
            ?: NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        if (result.outcome in setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.NO_CHANGE) ||
            pair?.lastTimingAttempt !== beforeAttempt) pairOffsetRetryEstimate.clear()
        if (pair != null && pair.lastTimingAttempt !== beforeAttempt) {
            trackTimingEvidence(pair, if (marked) TimingFailureKind.MARK_ALIGNMENT else TimingFailureKind.RELATIVE,
                delta, beforeRevision, result)
        }
        if (command != null && pair != null) {
            pairOffsetSession?.dispatched(command, result.outcome,
                pair.lastTimingAttempt !== beforeAttempt && result.outcome !in setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.NO_CHANGE))
            savePairOffsetIfNeeded()
        }
        if (marked) {
            val movements = playback?.pair?.lastAlignmentPlan?.targets?.map { "${it.side}:${it.movementMs}" }
            Log.d("TachiaiPrototype", "timing=MARK_ALIGNMENT deltaMs=$delta movements=$movements outcome=${result.outcome}")
        } else {
            val relative = playback?.pair?.lastRelativePlan
            Log.d("TachiaiPrototype", "timing=RELATIVE deltaMs=$delta side=${relative?.side} movementMs=${relative?.movementMs} outcome=${result.outcome}")
        }
        return result
    }

    private fun loadPairOffset(selected: ConfiguredPlaybackSelection, run: Long) {
        if (!selected.isPair) {
            pairOffsetIdentity = null; pairOffsetSession = null; pairOffsetOwner = null
            pairOffsetLoading = false; pairOffsetReadFailed = false; pairOffsetSaveId = null
            return
        }
        val identity = PairOffsetIdentity.forSelection(selected)
        pairOffsetIdentity = identity; pairOffsetSession = null; pairOffsetOwner = null
        pairOffsetLoading = true; pairOffsetReadFailed = false; pairOffsetSaveId = null
        pairOffsetSkipStartup = false; pairOffsetBaselineLost = false
        pairOffsetRetryEstimate = PairOffsetRetryEstimate()
        worker.execute {
            val result = runCatching {
                pairOffsetStore(this).read()[identity.key]?.let { preference ->
                    checkNotNull(identity.toDisplay(preference))
                }
            }
            handler.post {
                if (epoch.get() != run || pairOffsetIdentity !== identity || budget == null || isDestroyed) return@post
                pairOffsetLoading = false
                result.fold(onSuccess = { pairOffsetSession = PairOffsetSession(it) },
                    onFailure = { pairOffsetReadFailed = true })
                processPairOffset(allowStartup = false)
                viewer?.refresh()
            }
        }
    }

    private fun pairOffsetReadback(): ViewerPairOffsetReadback {
        if (pairOffsetLoading) return ViewerPairOffsetReadback(null, false, null, busy = true,
            available = false, message = "Loading pair offset…")
        val state = pairOffsetSession?.state
            ?: return ViewerPairOffsetReadback(null, false, null, available = false,
                message = if (pairOffsetReadFailed) "Pair offset could not be read; saved data was not replaced." else "Waiting for both feeds.")
        val correspondence = state.preference?.correspondence
        val timeline = currentPairTimelineCheck()
        return ViewerPairOffsetReadback(state.preference?.offsetMs, state.preference?.enabled == true,
            state.appliedOffsetMs, busy = state.busy,
            available = state.available && playback?.pair === pairOffsetOwner && playback?.pair != null,
            message = state.message ?: if (pairOffsetOwner == null) "Waiting for both feeds." else null,
            offsetActive = state.enabledApplied, hasCorrespondence = correspondence != null,
            timelineStatus = if (correspondence == null) null else when (timeline.consistency) {
                NativeTimelineConsistency.CONSISTENT -> "Marked timestamps saved. Timelines appear consistent; alignment remains an estimate."
                NativeTimelineConsistency.CHANGED -> "Marked timestamps saved. A timeline changed; timestamp restore is unavailable. The seconds offset remains an estimate."
                NativeTimelineConsistency.UNKNOWN -> "Marked timestamps saved. Timeline continuity is unverified. The seconds offset remains a fallback estimate."
            })
    }

    private fun currentPairTimelineCheck(): PairOffsetTimelineCheck = runCatching {
        val pair = playback?.pair
        val ta = runCatching { playback?.member(NativeMixedSide.A)?.timelineEvidence() }.getOrNull()
        val tb = runCatching { playback?.member(NativeMixedSide.B)?.timelineEvidence() }.getOrNull()
        val correspondence = if (pair == null) pairOffsetSession?.observeUnboundTimelines(ta, tb)
            else pairOffsetSession?.observeTimelines(pair, ta, tb)
        checkPairOffsetTimeline(correspondence, pair?.a?.timingSnapshot(), pair?.b?.timingSnapshot(), ta, tb)
    }.getOrElse { PairOffsetTimelineCheck(NativeTimelineConsistency.UNKNOWN) }

    private fun pairOffsetBlocksPlay() = playback?.pair != null &&
        (pairOffsetLoading || pairOffsetSession?.state?.busy == true)

    private fun cancelPairOffsetPending() {
        cancelRetryPlaybackIntent()
        timingEvidenceRequest = null
        pairOffsetSkipStartup = true
        pairOffsetSession?.skipStartup()
        playback?.pair?.let { pairOffsetSession?.cancelPending(it) }
    }

    private fun processPairOffset(allowStartup: Boolean = true) {
        val state = pairOffsetSession ?: return
        val pair = playback?.pair
        if (pair == null) {
            if (pairOffsetOwner != null) { state.unbind(); pairOffsetOwner = null; pairOffsetBaselineLost = true }
            currentPairTimelineCheck()
            savePairOffsetIfNeeded()
            return
        }
        if (pairOffsetOwner !== pair) {
            state.bind(pair); pairOffsetOwner = pair
            if (pairOffsetBaselineLost) state.invalidate(pair, "Waiting to restore the previous offset after feed recovery.")
            if (pairOffsetSkipStartup) state.skipStartup()
        }
        val previouslyKnown = state.state.appliedOffsetMs != null
        val previouslyBusy = state.state.busy
        val settlement = pair.lastTimingSettlement
        state.observe(pair, settlement?.revision ?: 0L, settlement?.deltaMs ?: 0L, pair.busy)
        if ((previouslyKnown || previouslyBusy && !state.state.busy && !state.state.enabledApplied) &&
            state.state.appliedOffsetMs == null) {
            setPlaybackIntent(false, PlaybackHealthIntent.OFFSET_UNCONFIRMED); invalidatePlay()
        }
        savePairOffsetIfNeeded()
        // Advance either surviving continuity witness even while the other
        // member is missing, buffering, or waiting for a transport decision.
        val timeline = currentPairTimelineCheck()
        val values = listOf(pair.a, pair.b).map { runCatching { it.timingSnapshot() }.getOrNull() }
        val now = SystemClock.elapsedRealtime()
        if (!pair.busy && !state.state.busy && state.state.enabledApplied)
            pairOffsetRetryEstimate.remember(values[0], values[1], now)
        if (!allowStartup || !desiredPlaying || !resumed.get() || budget?.active != true ||
            pair.busy || state.state.busy || !prepared.all { it } || failures.any { it != null }) return
        if (values.any { it == null || it.state != Player.STATE_READY || it.playingAd }) return
        val correction = timeline.correctionMs
        if (pairOffsetBaselineLost && !pairOffsetSkipStartup &&
            values.all { it?.playing == true && it.playWhenReady }) {
            val estimate = pairOffsetRetryEstimate.correctionMs(values[0], values[1], now)
            state.planRestart(pair, settlement?.revision ?: 0L, correction, estimate)?.let {
                Log.d("TachiaiPrototype", "timing=RETRY_OFFSET timeline=${timeline.consistency} source=${if (it.timelineRestore) "MARKED" else if (estimate != null) "PREVIOUS_PLAYBACK" else "SAVED_SECONDS"} deltaMs=${it.deltaMs}")
                dispatchPairOffset(it)
            }
        } else state.planStartup(pair, settlement?.revision ?: 0L, correction)?.let(::dispatchPairOffset)
    }

    private fun togglePairOffset(enabled: Boolean) {
        if (pairOffsetLoading || playback?.busy == true || pairOffsetSession?.state?.busy == true) return
        processPairOffset(allowStartup = false)
        val pair = playback?.pair ?: return
        val state = pairOffsetSession ?: return
        cancelRetryPlaybackIntent()
        pairOffsetSkipStartup = true
        state.planToggle(pair, enabled, pair.lastTimingSettlement?.revision ?: 0L,
            if (enabled) currentPairTimelineCheck().correctionMs else null)?.let {
            setPlaybackIntent(false, PlaybackHealthIntent.OFFSET)
            dispatchPairOffset(it)
        }
        viewer?.refresh()
    }

    private fun dispatchPairOffset(command: PairOffsetCommand) {
        val state = pairOffsetSession ?: return
        val pair = playback?.pair ?: return
        if (pair !== command.pairToken || !state.isCurrent(command)) return
        invalidatePlay()
        val beforeAttempt = pair.lastTimingAttempt
        val beforeRevision = pair.lastTimingSettlement?.revision ?: 0L
        val result = playback?.alignRelative(command.deltaMs) ?: NativeSeekPlan(NativeSeekOutcome.UNAVAILABLE)
        if (result.outcome in setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.NO_CHANGE) ||
            pair.lastTimingAttempt !== beforeAttempt) pairOffsetRetryEstimate.clear()
        if (pair.lastTimingAttempt !== beforeAttempt)
            trackTimingEvidence(pair, TimingFailureKind.SAVED_OFFSET, command.deltaMs, beforeRevision, result)
        val movementUncertain = pair.lastTimingAttempt !== beforeAttempt &&
            result.outcome !in setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.NO_CHANGE)
        state.dispatched(command, result.outcome, movementUncertain)
        if (state.state.appliedOffsetMs == null &&
            result.outcome !in setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.NO_CHANGE)) {
            setPlaybackIntent(false, PlaybackHealthIntent.OFFSET_UNCONFIRMED); invalidatePlay()
        }
        savePairOffsetIfNeeded()
        Log.d("TachiaiPrototype", "timing=PAIR_OFFSET origin=${command.origin} deltaMs=${command.deltaMs} outcome=${result.outcome}")
    }

    private fun savePairOffsetIfNeeded() {
        val state = pairOffsetSession ?: return
        val identity = pairOffsetIdentity ?: return
        val request = state.pendingSave ?: return
        if (pairOffsetSaveId == request.id) return
        val preference = identity.toCanonical(request.preference)
        if (preference == null) { state.saveCompleted(request.id, false); return }
        val run = epoch.get()
        pairOffsetSaveId = request.id
        worker.execute {
            if (epoch.get() != run) return@execute
            val success = runCatching { pairOffsetStore(this).save(identity.key, preference) }.isSuccess
            handler.post {
                if (epoch.get() != run || pairOffsetSession !== state || pairOffsetIdentity !== identity || isDestroyed) return@post
                state.saveCompleted(request.id, success)
                if (pairOffsetSaveId == request.id) pairOffsetSaveId = null
                viewer?.refresh()
            }
        }
    }

    private fun trackTimingEvidence(pair: NativeMixedPair, kind: TimingFailureKind, deltaMs: Long,
        beforeRevision: Long, result: NativeSeekPlan) {
        val evidence = TimingEvidenceRequest(pair, kind, deltaMs, beforeRevision, pair.pendingSeekDiagnostics)
        if (result.outcome == NativeSeekOutcome.REQUESTED && pair.busy) timingEvidenceRequest = evidence
        else if (result.outcome !in setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.NO_CHANGE)) writeTimingEvidence(evidence)
    }

    private fun finishTimingEvidence() {
        val evidence = timingEvidenceRequest ?: return
        if (playback?.pair !== evidence.pair) { timingEvidenceRequest = null; return }
        if (evidence.pair.busy) return
        timingEvidenceRequest = null
        val settled = evidence.pair.lastTimingSettlement
        if (settled?.revision != evidence.beforeRevision + 1 || settled.deltaMs != evidence.deltaMs)
            writeTimingEvidence(evidence)
    }

    private fun writeTimingEvidence(evidence: TimingEvidenceRequest) {
        val targets = NativeMixedSide.entries.map { side ->
            val value = runCatching { (if (side == NativeMixedSide.A) evidence.pair.a else evidence.pair.b).timingSnapshot() }.getOrNull()
            val target = evidence.targets.singleOrNull { it.side == side }
            val planned = evidence.pair.lastAlignmentPlan?.targets?.singleOrNull { it.side == side }
                ?.takeIf { evidence.kind != TimingFailureKind.RELATIVE }
            val expectedClock = target?.targetClockMs ?: planned?.targetClockMs
            val liveClock = target?.liveClock ?: planned?.liveClock
            val actualClock = value?.takeIf { (it.live || it.dynamic) == liveClock }
                ?.let { if (liveClock == true) it.contentTimeMs else it.positionMs }
            val error = if (expectedClock != null && actualClock != null) runCatching {
                Math.subtractExact(actualClock, expectedClock)
            }.getOrNull() else null
            TimingFailureTarget(TimingFailureSide.valueOf(side.name), error,
                (target?.elapsedMs ?: 0L).coerceIn(0L, 60_000L), value?.state?.takeIf { it in 0..4 },
                value?.positionMs, value?.durationMs?.takeIf { it >= 0 }, value?.bufferedMs,
                value?.playing, value?.playWhenReady, value?.playingAd)
        }
        val record = TimingFailureEvidence(evidence.kind, evidence.deltaMs, targets)
        worker.execute {
            runCatching { TimingFailureJournal(java.io.File(noBackupFilesDir, "timing-failures.tsv")).append(record) }
                .onFailure { Log.d("TachiaiPrototype", "timingEvidence=UNAVAILABLE") }
        }
    }

    private fun setPlaybackIntent(playing: Boolean, reason: PlaybackHealthIntent) {
        desiredPlaying = playing
        playbackIntentReason = reason
        if (playbackIntentRevision < Long.MAX_VALUE) playbackIntentRevision++
    }

    private fun recordPlaybackHealth(index: Int, reason: PlaybackHealthReason) {
        if (selection?.slots?.getOrNull(index) == null) return
        try {
            val member = sessions.getOrNull(index)?.member ?: playback?.member(NativeMixedSide.entries[index])
            if (healthOwners[index] !== member) {
                healthOwners[index] = member
                healthTrackers[index].reset()
                healthSignatures[index] = null
            }
            val snapshot = member?.diagnosticSnapshot()
            val observation = healthTrackers[index].observe(snapshot)
            val player = snapshot?.let {
                PlaybackHealthPlayer(it.sampleAgeMs.coerceAtLeast(0), it.released, it.playWhenReady, it.playing,
                    it.state.takeIf { value -> value in 0..4 } ?: 0,
                    it.suppressionReason.takeIf { value -> value in -1..4 } ?: -1,
                    it.errorCode?.takeIf { value -> value >= 0 }, it.positionMs,
                    it.durationMs?.takeIf { value -> value >= 0 }, it.bufferedMs,
                    observation.clockProgressAgeMs?.coerceAtLeast(0),
                    observation.renderProgressAgeMs?.coerceAtLeast(0),
                    it.renderedFrames?.takeIf { value -> value >= 0 },
                    it.droppedFrames?.takeIf { value -> value >= 0 },
                    it.decoderGeneration?.takeIf { value -> value >= 0 })
            }
            val now = SystemClock.elapsedRealtime()
            val early = earlyTransportFailures.get(index)?.takeIf {
                it.run == epoch.get() && it.retry === feedRetries[index] && it.retry.current(it.attempt)
            }?.let { PlaybackHealthTransport((now - it.at).coerceAtLeast(0), it.evidence, it.stage) }
            val native = snapshot?.transportFailure?.let {
                PlaybackHealthTransport(it.ageMs.coerceAtLeast(0), it.evidence, it.stage)
            }
            val transport = listOfNotNull(early, native).minByOrNull { it.ageMs }
            val retry = feedRetries[index]
            val record = PlaybackHealthRecord(System.currentTimeMillis().coerceAtLeast(0),
                PlaybackHealthSlot.valueOf(NativeMixedSide.entries[index].name), reason, observation.state,
                playbackIntentReason, playbackIntentRevision, desiredPlaying, prepared[index], retry.requested,
                retry.retrying, playback?.busy == true, pairOffsetSession?.state?.busy == true || pairOffsetLoading,
                playBarrier != null, player, transport)
            // Advancing counters and ages are sampled evidence, not a reason
            // to evict an earlier failure every second while playback is fine.
            val signature = listOf(observation.state, playbackIntentReason, playbackIntentRevision,
                desiredPlaying, prepared[index], retry.requested, retry.retrying, record.timingBusy,
                record.offsetBusy, record.barrierPending, player?.released, player?.playWhenReady,
                player?.playing, player?.state, player?.suppressionReason, player?.errorCode,
                transport?.evidence, transport?.stage)
            if (reason == PlaybackHealthReason.SAMPLE_STATE_CHANGED && healthSignatures[index] == signature) return
            healthSignatures[index] = signature
            Log.d("TachiaiPrototype", "playbackHealth slot=${record.slot} health=${record.health} intent=${record.intent}" +
                " intentRevision=${record.intentRevision} desired=${record.appDesiredPlaying} prepared=${record.prepared}" +
                " retrying=${record.retrying} timingBusy=${record.timingBusy} pwr=${player?.playWhenReady}" +
                " playing=${player?.playing} state=${player?.state} suppression=${player?.suppressionReason}" +
                " errorCode=${player?.errorCode} clockAgeMs=${player?.clockProgressAgeMs}" +
                " renderAgeMs=${player?.renderProgressAgeMs} rendered=${player?.renderedFrames}" +
                " transport=${transport?.evidence?.failure} operation=${transport?.evidence?.operation}" +
                " exception=${transport?.evidence?.exceptionKind}")
            worker.execute {
                runCatching { PlaybackHealthJournal(java.io.File(noBackupFilesDir, "playback-health.tsv")).append(record) }
                    .onFailure { Log.d("TachiaiPrototype", "playbackHealthJournal=UNAVAILABLE") }
            }
        } catch (_: Throwable) {
            Log.d("TachiaiPrototype", "playbackHealth=UNAVAILABLE")
        }
    }

    private fun logTimingSnapshots(phase: String) {
        NativeMixedSide.entries.forEach { side ->
            runCatching { playback?.member(side)?.timingSnapshot() }.getOrNull()?.let { value ->
                Log.d("TachiaiPrototype", "timingSnapshot=$phase slot=${side.name} state=${value.state} playing=${value.playing} playWhenReady=${value.playWhenReady} positionMs=${value.positionMs} durationMs=${value.durationMs} bufferedMs=${value.bufferedMs} seekable=${value.seekable} playingAd=${value.playingAd}")
            }
        }
    }

    private fun prepareViewerSlot(index: Int, host: PrototypeFeedSession) {
        if (replayRestoreTarget[index] != null && SystemClock.elapsedRealtime() >= catchUpDeadline[index]) {
            failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED)); return
        }
        if (initialCatchUp[index] && SystemClock.elapsedRealtime() >= catchUpDeadline[index]) {
            failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.LIVE_POSITION_FAILED)); return
        }
        val member = host.member ?: return
        val snapshot = member.timingSnapshot() ?: return
        if (snapshot.state != Player.STATE_READY || snapshot.playWhenReady || snapshot.playingAd) return
        val retry = feedRetries[index]
        if (retry.retrying && selection?.slots?.get(index)?.kind == PrototypePlaybackKind.REPLAY &&
            !snapshot.live && !snapshot.dynamic && retry.replayPositionMs != null) {
            val target = replayRestoreTarget[index]
            if (target == null) {
                val duration = snapshot.durationMs ?: return
                if (duration <= 0) return
                val requested = checkNotNull(retry.replayPositionMs).coerceIn(0, duration - 1)
                val plan = member.seekToMs(requested)
                if (plan.outcome !in setOf(NativeSeekOutcome.REQUESTED, NativeSeekOutcome.NO_CHANGE) || plan.targetMs != requested) {
                    failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED)); return
                }
                replayRestoreTarget[index] = requested
                catchUpDeadline[index] = SystemClock.elapsedRealtime() + 8_000
                return
            }
            if (SystemClock.elapsedRealtime() >= catchUpDeadline[index]) {
                failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED)); return
            }
            if (snapshot.positionMs == null || kotlin.math.abs(snapshot.positionMs - target) > 1_000) return
        }
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
        retry.ready()
        invalidatePlay()
        playback?.setMember(NativeMixedSide.entries[index], member)
        applyQuality(NativeMixedSide.entries[index])
        surfaces[index].player = host.player
        recordPlaybackHealth(index, PlaybackHealthReason.FEED_PREPARED)
        val join = retryPlaybackJoins[index]
        retryPlaybackJoins[index] = null
        val rejoin = join?.let { retryPlaybackIntent.consume(it.receipt, it.token,
            playback?.member(NativeMixedSide.entries[1 - index])) } == true
        if (join != null && join.retry === retry && epoch.get() == join.run && budget === join.budget &&
            join.budget.active && resumed.get() && !isFinishing && !isDestroyed && sessions[index] === host &&
            playback?.member(NativeMixedSide.entries[index]) === member &&
            rejoin) {
            // Native timing restoration owns its play intent separately from
            // desiredPlaying. Retry may rejoin an exact still-playing sibling,
            // using the ordinary original-player/admission barrier on the tick.
            retryPlaybackStart = null
            playback?.member(NativeMixedSide.entries[1 - index])?.let { sibling ->
                if (join.preserveSibling || runCatching { sibling.timingSnapshot()?.playWhenReady == true }.getOrDefault(false))
                    retryPlaybackStart = RetryPlaybackStart(NativeMixedSide.entries[index], member, sibling)
            }
            setPlaybackIntent(true, PlaybackHealthIntent.RETRY_REJOIN)
            recordPlaybackHealth(index, PlaybackHealthReason.RETRY_REJOIN)
        }
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
        val source = activeConfiguredSources.getOrNull(side.ordinal) ?: return
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
    private fun pausePair() {
        cancelPairOffsetPending()
        setPlaybackIntent(false, PlaybackHealthIntent.USER_PAUSE); invalidatePlay(); playback?.pause(); viewer?.refresh(); publishPairTransport()
    }

    private fun playPair() {
        cancelPairOffsetPending()
        setPlaybackIntent(true, PlaybackHealthIntent.USER_PLAY)
        playReadyFeeds()
        publishPairTransport()
    }

    private fun publishPairTransport() {
        val session = pairMediaSession ?: return
        val current = playback
        val playing = NativeMixedSide.entries.any { side ->
            runCatching { current?.member(side)?.timingSnapshot()?.playing == true }.getOrDefault(false)
        }
        val state = when {
            playing -> PlaybackState.STATE_PLAYING
            desiredPlaying && current?.playingRequested == true -> PlaybackState.STATE_BUFFERING
            else -> PlaybackState.STATE_PAUSED
        }
        session.publish(state)
    }

    private fun playReadyFeeds() {
        invalidatePlay()
        val current = playback ?: return
        if (pairOffsetBlocksPlay()) return
        retryPlaybackStart?.let { retry ->
            val siblingSide = NativeMixedSide.entries[1 - retry.side.ordinal]
            if (current.member(retry.side) !== retry.replacement || current.member(siblingSide) !== retry.sibling) {
                retryPlaybackStart = null
                setPlaybackIntent(false, PlaybackHealthIntent.PLAY_REFUSED)
                return
            }
            val peer = runCatching { retry.sibling.timingSnapshot() }.getOrNull()
            if (peer != null && peer.playWhenReady && !peer.playing && peer.state in setOf(2, 3) && !peer.playingAd) {
                playMessage = "Waiting for the existing feed to resume before rejoining."
                return
            }
        }
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
                fun active() = resumed.get() && epoch.get() == run && playback === current &&
                    desiredPlaying && budget?.active == true
                val retryStart = retryPlaybackStart
                retryPlaybackStart = null
                val played = active() && if (retryStart != null) {
                    current.rejoin(retryStart.side, retryStart.replacement, retryStart.sibling)
                } else current.play { side, member ->
                    val index = side.ordinal
                    if (!active() || sessions[index]?.member !== member) false else {
                        failFeed(index, PrototypeFeedFailure(PrototypeFailureReason.LIVE_WINDOW_EXPIRED))
                        active()
                    }
                }
                if (!played && playback === current) setPlaybackIntent(false, PlaybackHealthIntent.PLAY_REFUSED)
            } else {
                retryPlaybackStart = null
                current.pause()
                setPlaybackIntent(false, PlaybackHealthIntent.PLAY_REFUSED)
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
        if (selection?.slots?.getOrNull(index)?.service == PrototypeService.TWITCH) PrototypeFailureReason.LOGIN_EXPIRED
        else PrototypeFailureReason.PREPARATION_FAILED)

    private fun failFeed(index: Int, failure: PrototypeFeedFailure) {
        if (selection?.slots?.getOrNull(index) == null) return
        if (failures[index] != null) return
        // Deadline/normal stop is an expected lifecycle outcome, not a new error.
        if (failure.reason !in setOf(PrototypeFailureReason.STOPPED, PrototypeFailureReason.PLAYBACK_LIMIT))
            feedDiagnostics(index).report(FailureStage.FEED_FAILURE, reason = failure.reason)
        recordPlaybackHealth(index, PlaybackHealthReason.FEED_FAILURE)
        failures[index] = failure
        retryPlaybackIntent.cancel(index); retryPlaybackJoins[index] = null
        retryPlaybackStart = null
        timingEvidenceRequest?.let { writeTimingEvidence(it); timingEvidenceRequest = null }
        pairOffsetBaselineLost = true
        playback?.pair?.let { pairOffsetSession?.invalidate(it) }
        feedRetries[index].invalidate()
        Log.d("TachiaiPrototype", "slot=${NativeMixedSide.entries[index].name} failure=${failure.reason.name} http=${failure.httpStatus ?: 0}")
        invalidatePlay()
        playback?.setMember(NativeMixedSide.entries[index], null)
        prepared[index] = false
        surfaces.getOrNull(index)?.player = null
        val host = sessions[index]
        sessions = sessions.toMutableList().also { it[index] = null }
        val closed = cachedRun?.let { cachedCleanup.closeFeed(it, index) } ?:
            (feedDiagnostics(index).cleanupAll(FailureStage.FEED_CLOSE) { host?.close() } && host?.cleanupFailed != true)
        if (!closed) {
            feedDiagnostics(index).report(FailureStage.FEED_CLEANUP_UNCONFIRMED)
            cleanupFailed = true
            budget?.stop()
            selectedSlotIndices().filter { failures[it] == null }.forEach {
                failFeed(it, PrototypeFeedFailure(PrototypeFailureReason.CLEANUP_FAILED))
            }
        }
        if (NativeMixedSide.entries.all { playback?.member(it) == null }) focus?.release()
        if (selectedSlotIndices().all { failures[it] != null } && failures.none { it?.recoveryAction() != null }) releaseRoutes()
        updateProgress()
        viewer?.refresh()
        updateRecoveryState()
        if (cleanupFailed && !disposing) stopToPicker("Playback stopped because cleanup could not be confirmed.")
    }

    private fun canRetryFeed(index: Int): Boolean = useCachedAbema && !cleanupFailed &&
        budget?.active == true && cachedRun?.let(cachedCleanup::current) == true &&
        selection?.slots?.getOrNull(index)?.instanceId?.let(routes::containsKey) == true

    private fun requestFeedRetry(index: Int) {
        if (!canRetryFeed(index) || failures[index]?.recoveryAction() == null || !feedRetries[index].request()) return
        // This fresh explicit retry supersedes older timing actions. A later
        // Pause/focus loss/new adjustment will suppress restoration again.
        pairOffsetSkipStartup = false
        val currentBudget = budget ?: return
        val sibling = playback?.member(NativeMixedSide.entries[1 - index])
        val siblingRequested = runCatching { sibling?.timingSnapshot()?.playWhenReady == true }.getOrDefault(false)
        val token = Any()
        val receipt = retryPlaybackIntent.arm(index, token, sibling, desiredPlaying || siblingRequested)
        retryPlaybackJoins[index] = RetryPlaybackJoin(receipt, token, feedRetries[index], epoch.get(), currentBudget, siblingRequested)
        retryDeadline[index] = SystemClock.elapsedRealtime() + 20_000
        recordPlaybackHealth(index, PlaybackHealthReason.RETRY_REQUESTED)
        viewer?.refresh()
    }

    private fun cancelRetryPlaybackIntent() {
        retryPlaybackIntent.cancelAll()
        retryPlaybackJoins.fill(null)
        retryPlaybackStart = null
    }

    private fun processFeedRetries(currentBudget: NativePlaybackBudget) {
        feedRetries.forEachIndexed { index, retry ->
            if (!retry.requested) return@forEachIndexed
            if (!canRetryFeed(index)) return@forEachIndexed
            val owned = cachedRun ?: return@forEachIndexed
            if (!cachedCleanup.feedClosed(owned, index)) {
                if (SystemClock.elapsedRealtime() >= retryDeadline[index])
                    stopToPicker("Playback cleanup could not be confirmed. Use Recovery options to restart.")
                return@forEachIndexed
            }
            if (!cachedCleanup.retireClosedFeed(owned, index)) return@forEachIndexed
            val selected = selection ?: return@forEachIndexed
            val setup = root?.getChildAt(0) as? LinearLayout ?: return@forEachIndexed
            failures[index] = null
            prepared[index] = false; initialCatchUp[index] = false; catchUpDeadline[index] = 0
            replayRestoreTarget[index] = null
            val source = selected.slots[index] ?: return@forEachIndexed
            createFeeds(selected, currentBudget, epoch.get(), setup, setOf(source.service), setOf(index))
        }
    }

    private fun stopToPicker(message: String) {
        setPlaybackIntent(false, PlaybackHealthIntent.STOP)
        dispose()
        val result = message + if (cleanupFailed) " Playback cleanup reported a failure." else ""
        if (resumed.get() && !isFinishing) {
            showPicker(result)
            // The worker serializes accepted quality writes before this read.
            // A disposed session cannot publish its callback into a new viewer.
            refreshSetup()
        } else resumeMessage = result
    }

    private fun dispose(retainStartups: Boolean = false) {
        cancelRetryPlaybackIntent()
        if (pairMediaSession != null) mediaController = null
        pairMediaSession?.close(); pairMediaSession = null
        if (!retainStartups) retireStartupScopes()
        disposing = true
        feedRetries.forEach { it.invalidate() }
        cancelRoutePreparation()
        epoch.incrementAndGet(); invalidatePlay(); handler.removeCallbacks(ticker)
        budget?.stop()
        cachedRun?.let(cachedCleanup::end)
        if (!diagnostics.cleanupAll(FailureStage.VIEWER_END) { viewer?.endSession() }) cleanupFailed = true
        viewer = null
        qualitySession?.clearOverrides(); qualitySession = null; savingQuality = false; qualityMessage = null
        pairOffsetSession = null; pairOffsetIdentity = null; pairOffsetOwner = null
        pairOffsetRetryEstimate = PairOffsetRetryEstimate()
        timingEvidenceRequest = null
        pairOffsetLoading = false; pairOffsetReadFailed = false; pairOffsetSaveId = null
        if (useCachedAbema) {
            if (!diagnostics.cleanupAll(FailureStage.PLAYBACK_CLOSE) { playback?.close() }) cleanupFailed = true
        } else playback?.close()
        playback = null
        surfaces.forEach { it.player = null }; surfaces = emptyList()
        if (cachedRun == null) sessions.forEachIndexed { index, session ->
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
        cachedRun?.let { owned ->
            cachedCleanup.end(owned)
            if (cachedCleanup.failed) cleanupFailed = true
            cachedRun = null
            routePreparation = null
            routes = emptyMap(); routeProfiles = emptyMap(); routeChoices = emptyMap(); routeOwners = emptyMap()
            updateRecoveryState(); recoveryState.notifyChanged()
            return
        }
        // Cached resources are owned by the process coordinator even when the
        // Activity has already relinquished its local handle.
        if (useCachedAbema) return
        cancelRoutePreparation()
        val oldRoutes = routes.values.distinct()
        routes = emptyMap()
        routeProfiles = emptyMap()
        routeChoices = emptyMap()
        routeOwners = emptyMap()
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
        closeRoutes(true) // Noncached route lifecycle only; cached ownership returned above.
    }

    private fun closeRoutesAsync(owned: List<RouteSession>, onComplete: (Boolean) -> Unit) {
        val reporter = diagnostics
        routeCloser.execute {
            val accepted = owned.map { reporter.cleanupAll(FailureStage.ROUTE_CLOSE) { it.close() } }.all { it }
            handler.post { onComplete(accepted) }
        }
    }

    private fun cancelRoutePreparation() {
        if (cachedRun != null) return // The process owner signals and retains it.
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
        accountReadiness.onResume()
        recoveryState.addObserver(recoveryObserver)
        handler.post { updateRecoveryState() }
        resumeMessage?.let { resumeMessage = null; showPicker(it) }
        if (budget == null) refreshSetup()
    }

    private fun refreshSetup() {
        if (budget != null || !resumed.get() || isDestroyed || isFinishing) return
        val current = ++setupRevision
        val restoreSelection = pickerSelectionRestore.pending
        val selectionRevisionAtLoad = pickerSelectionRevision
        setupReady = false; setupMessage = null; obsoleteSetup = false
        worker.execute {
            val remembered = if (restoreSelection) runCatching {
                selectionPersistence.submit<ConfiguredFeedAssignments?> { viewerSelectionStore(applicationContext).read() }.get()
            } else null
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
                if (restoreSelection && pickerSelectionRestore.pending) {
                    pickerSelectionRestore.loaded(remembered?.getOrNull(), remembered?.isSuccess == true)?.let { assignments ->
                        pickerAssignments = assignments
                        pickerRestoreGeneration++
                    }
                    if (remembered?.isFailure == true && pickerSelectionRevision == selectionRevisionAtLoad)
                        pickerSelectionMessage = "Saved A/B choices could not be read. Choose sources again; the saved record was not replaced."
                    if (persistRestoredSelection && remembered?.isSuccess == true &&
                        pickerSelectionRevision == selectionRevisionAtLoad && remembered.getOrNull() != pickerAssignments)
                        rememberPickerAssignments(pickerAssignments)
                    persistRestoredSelection = false
                }
                result.fold(onSuccess = {
                    sourceSetups = it.streams; providerInstances = it.instances; legacyQualityDefaults = it.qualities
                    configuredSources = it.sources; qualityDefaults = it.sources.associate { source -> source.choice to source.quality }
                    setupReady = true
                    pendingAccountSelection?.let { assignments ->
                        val captured = pendingAccountPlan
                        pendingAccountSelection = null
                        pendingAccountPlan = null
                        if (captured != null) watchScoped(assignments, captured)
                        else showPicker("The saved connection check ended. Choose Watch to start again.")
                    }
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
        setPlaybackIntent(false, PlaybackHealthIntent.BACKGROUND)
        accountReadiness.onPause()
        accountCheckRevision++; checkingAccount = false
        recoveryState.removeObserver(recoveryObserver)
        dismissRecoveryDialog()
        setupRevision++
        resumed.set(false)
        retireStartupScopes()
        if (budget != null) { dispose(); resumeMessage = "Playback stopped while the app was in the background." }
        super.onPause()
    }
    override fun onDestroy() {
        accountReadiness.close()
        pendingAccountSelection = null
        pendingAccountPlan = null
        recoveryState.removeObserver(recoveryObserver)
        dismissRecoveryDialog()
        resumed.set(false); dispose()
        // Let queued route preparation reach its cancellation/result cleanup;
        // dropping the task would strand process-wide routesPending forever.
        worker.shutdown()
        super.onDestroy()
    }

    private fun sourceLabel(index: Int): String {
        val feed = selection?.slots?.getOrNull(index) ?: return "${NativeMixedSide.entries[index].name} · No source selected"
        val instance = checkNotNull(feed.resolve(activeInstances))
        val title = configuredSourceDisplayTitle(checkNotNull(activeConfiguredSources[index]))
        return "${if (index == 0) "A" else "B"} · ${net.fstab.tachiai.presentation.providerDisplayNames(instance).inline} · $title"
    }

    private fun selectedSlotIndices(): List<Int> = selection?.slots?.indices?.filter { selection?.slots?.get(it) != null }.orEmpty()

    private fun retireStartupScopes() {
        val previous = synchronized(startupLock) { startupScopes.also { startupScopes = emptyMap() } }
        previous.forEach { (id, startup) -> ProviderStartupScopeCleanup.retire(id, startup) }
        startupTwitchValidation = emptyMap()
    }

    private data class PickerSetup(val streams: Map<PrototypeSource, SourceSetup>, val instances: List<ProviderInstance>,
        val qualities: Map<PrototypeSource, NativeQualityPreferences>, val sources: List<ConfiguredSource>)
}
