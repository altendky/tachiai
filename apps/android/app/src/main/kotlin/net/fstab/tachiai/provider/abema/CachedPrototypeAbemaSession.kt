package net.fstab.tachiai.provider.abema

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.CookieHandler
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.platform.media.BoundedDashManifestParser
import net.fstab.tachiai.platform.media.BoundedMediaRequests
import net.fstab.tachiai.platform.media.BoundedNativeDashPlayer
import net.fstab.tachiai.platform.media.DeclaredDashMediaPolicy
import net.fstab.tachiai.platform.media.DashManifestPolicyEvent
import net.fstab.tachiai.platform.media.NativeDashEvent
import net.fstab.tachiai.platform.media.NativePairMember
import net.fstab.tachiai.platform.media.NativePlaybackBudget
import net.fstab.tachiai.platform.media.NativeMediaEvent
import net.fstab.tachiai.platform.media.NativeFailureStage
import net.fstab.tachiai.platform.media.PrototypeFeedEvent
import net.fstab.tachiai.platform.media.PrototypeFeedSession
import net.fstab.tachiai.platform.media.awaitOpaqueResponse
import net.fstab.tachiai.platform.media.closePlayer
import net.fstab.tachiai.platform.network.RouteSession
import net.fstab.tachiai.platform.web.configureSecureSettings
import net.fstab.tachiai.presentation.PrototypeFeedFailure
import net.fstab.tachiai.presentation.PrototypeFeedFailureLatch
import net.fstab.tachiai.presentation.PrototypeFailureReason
import net.fstab.tachiai.provider.catalog.CatalogResource
import net.fstab.tachiai.provider.abema.account.AndroidAbemaAccountAccessClient
import net.fstab.tachiai.provider.abema.account.AbemaAccountAccessIpc
import net.fstab.tachiai.provider.abema.account.AbemaAccountException
import net.fstab.tachiai.provider.abema.account.AbemaAccountFailure
import net.fstab.tachiai.provider.abema.account.AbemaSubscriptionProfileResult
import net.fstab.tachiai.provider.abema.account.AbemaChaseFieldState
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.platform.diagnostics.FailureReporter
import net.fstab.tachiai.platform.diagnostics.FailureCategory
import net.fstab.tachiai.platform.diagnostics.FailureStage
import net.fstab.tachiai.platform.diagnostics.nativeObserver

// Minimal app-owned bootstrap, not a provider player page. Each slot owns its
// unchanged cached helper, instance account lease and fresh one-exchange CDM.
// Historical page-backed PrototypeAbemaSession remains a separate example.
@UnstableApi
internal class CachedPrototypeAbemaSession(
    context: Context,
    private val replay: Boolean,
    private val active: () -> Boolean,
    private val onEvent: (PrototypeFeedEvent) -> Unit,
    private val route: RouteSession? = null,
    private val diagnostics: FailureReporter = FailureReporter.NONE,
    private val onHelperCreated: (CachedAbemaHelperCleanup) -> Unit = {},
    private val onCleanupChanged: () -> Unit = {},
    private val episodeResource: CatalogResource? = null,
    private val ownership: AbemaEpisodePlaybackOwnership? = null,
    private val channelResource: CatalogResource? = null,
    private val slotResource: CatalogResource? = null,
    private val onSlotSourceDiagnostic: ((AbemaSlotSourceDiagnostic) -> Unit)? = null,
    private val manifestOnlySlot: AbemaSlotRequest? = null,
    private val onManifestOnlyPrepared: ((AbemaInitialManifestSnapshot) -> Unit)? = null,
    private val observeChannelPreparation: Boolean = false,
    private val observeModernChannelContext: Boolean = false,
    private val continuousSlotPlayback: Boolean = false,
    private val requireSavedAccount: Boolean = false,
    // Historical guest live playback has no account/resource owner, but still
    // belongs to the viewer's immutable provider route scope.
    private val routeOwnership: AbemaEpisodePlaybackOwnership? = null,
    private val cleanupExecutor: Executor = transportCleanupWorkers,
) : PrototypeFeedSession {
    companion object {
        private val accountCleanup = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "abema-playback-account-release").apply { isDaemon = true }
        }
        private val transportCleanupWorkers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(32), { task -> Thread(task, "abema-playback-transport-release").apply { isDaemon = true } })
    }
    init {
        require(episodeResource == null || replay && supportedConfiguredAbemaEpisode(episodeResource) && route != null && ownership != null)
        require(channelResource == null || !replay && episodeResource == null && supportedConfiguredAbemaChannel(channelResource) && route != null && ownership != null)
        require(slotResource == null || replay && episodeResource == null && channelResource == null &&
            supportedConfiguredAbemaSlot(slotResource) && route != null && ownership != null)
        require(episodeResource != null || channelResource != null || slotResource != null || ownership == null)
        require((manifestOnlySlot == null) == (onManifestOnlyPrepared == null))
        require(manifestOnlySlot == null || channelResource != null && !replay &&
            episodeResource == null && slotResource == null && channelResource.identity == manifestOnlySlot.channelId)
        require(!observeChannelPreparation || manifestOnlySlot != null)
        require(!observeModernChannelContext || observeChannelPreparation && manifestOnlySlot != null)
        require(!continuousSlotPlayback || slotResource != null && manifestOnlySlot == null &&
            !observeChannelPreparation && !observeModernChannelContext)
        require(!requireSavedAccount || ownership != null)
        require(routeOwnership == null || route != null)
    }
    private val initialManifest = manifestOnlySlot?.let(::AbemaInitialManifestObservation)
    private val helperPreparationEvidence = AbemaChannelPreparationEvidence()
    private val nativePreparationEvidence = AbemaNativePreparationEvidence()
    val nativePreparationDiagnostic get() = nativePreparationEvidence.snapshot(
        sourcePending.get()?.isDone == false, pending.get()?.isDone == false)
    val helperPreparationDiagnostic get() = helperPreparationEvidence.snapshot
    val channelPreparationDiagnostic get() = helperPreparationDiagnostic.takeIf { observeChannelPreparation }
    @Volatile var nativeReadinessDeadlineReached: Boolean = false
        private set
    private val episodeId get() = episodeResource?.identity ?: "394-72_s10_p8529"
    private val channelId get() = if (slotLinearAdmission.linearSelected) slotChannelId else channelResource?.identity ?: "abema-news"
    private val effectiveReplay get() = replay && !slotLinearAdmission.linearSelected
    private val modernChannelPreparation get() = observeModernChannelContext ||
        continuousSlotPlayback && accountStatus == AbemaAccountAccessIpc.Status.ACCOUNT
    private val slotChannelId get() = checkNotNull(slotResource).identity.substringBefore('/')
    private val slotId get() = checkNotNull(slotResource).identity.substringAfter('/')
    private val application = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val worker: CachedCleanupWorker = CachedCleanupWorker(Executors.newSingleThreadExecutor()) {
        finishTransportCleanup(); onCleanupChanged()
    }
    private val transportFinalizing = AtomicBoolean()
    private val transportFinalized = AtomicBoolean()
    private val transportCancellationPending = AtomicBoolean()
    private val cleanupSetupComplete = AtomicBoolean()
    private val helperFinalized = AtomicBoolean()
    private val accountCleanupPending = AtomicBoolean()
    private val slotProfileCleanupPending = AtomicBoolean()
    private val slotProfile = AtomicReference<AbemaBorrowedSlotProfile?>()
    private val slotPremiumScope = AtomicReference<AbemaSlotAuthorityScope?>()
    private val slotPremiumReceipt = AtomicReference<AbemaSlotAuthorityReceipt?>()
    private val slotChaseScope = AtomicReference<AbemaSlotAuthorityScope?>()
    private val slotChaseReceipt = AtomicReference<AbemaSlotAuthorityReceipt?>()
    private val slotChaseAdmission = AbemaSlotChaseAdmission { slotChaseCandidateCurrent(revision.get()) }
    private val slotLinearAdmission = AbemaSlotLinearAdmission(continuousSlotPlayback)
    val resolvedSlotLinear get() = slotLinearAdmission.linearSelected
    private val slotChaseTimelineAccepted = AtomicBoolean()
    private val accountAdmitted = AtomicBoolean()
    private val accountClient = AtomicReference<AndroidAbemaAccountAccessClient?>()
    @Volatile var accountStatus: AbemaAccountAccessIpc.Status? = null
        private set
    private val accountLock = Any()
    private val privateInitialization = AtomicReference<ByteArray?>()
    val cleanupPending get() = worker.pending || accountCleanupPending.get() || slotProfileCleanupPending.get() ||
        transportCancellationPending.get() || nativeCleanup?.pending == true ||
        (closed.get() && (!transportFinalized.get() || !helperFinalized.get()))
    private val transportCleanup = net.fstab.tachiai.platform.media.NativeCleanupReceipt { cleanupFailed = true; onCleanupChanged() }
    @Volatile private var nativeCleanup: net.fstab.tachiai.platform.media.NativeCleanupReceipt? = null
    private val closed = AtomicBoolean(false)
    private val networkReviewNeeded = AtomicBoolean(false)
    private val cdnJournal = AbemaCdnReviewJournal(File(context.noBackupFilesDir, "abema-cdn-review.tsv"))
    private val revision = AtomicLong()
    private val routeCurrent = AtomicBoolean(false)
    private val bundleTransport = AtomicReference<AbemaPublicBundleHttp?>()
    private val sourceRequests = AtomicReference<BoundedMediaRequests?>()
    private val sourcePending = AtomicReference<CompletableFuture<URI?>?>()
    private val pending = AtomicReference<CompletableFuture<ByteArray?>?>()
    private val nonce = UUID.randomUUID().toString().replace("-", "")
    private val name = "__tachiaiNative_$nonce"
    @Volatile private var ownedResources: Map<String, ByteArray> = emptyMap()
    @Volatile private var nativeHost: BoundedNativeDashPlayer? = null
    @Volatile private var budget: NativePlaybackBudget? = null
    @Volatile private var deadline = 0L
    private val playbackAvailability = AtomicReference<AbemaPlaybackAvailability?>()
    private var started = false
    private var documentStarted = false
    private var preparingNative = false
    private var lastEvent: PrototypeFeedEvent? = null
    private var lastHelperState: String? = null
    private var lastMetadata: String? = null
    private val helperEvidence = AbemaCachedHelperEvidence()
    private val firstFailure = PrototypeFeedFailureLatch()
    private var helperFailure: PrototypeFeedFailure? = null
    override val failure: PrototypeFeedFailure? get() = firstFailure.failure
    @Volatile override var cleanupFailed: Boolean = false
        private set

    val cleanupConfirmed get() = closed.get() && !cleanupFailed && !cleanupPending &&
        transportCleanup.confirmed && (nativeCleanup?.confirmed != false) && slotProfile.get()?.cleanupConfirmed != false

    override val providerView: View = WebView(context)
    private val view get() = providerView as WebView
    private val helperCleanup = CachedAbemaHelperCleanup.forView(view, diagnostics)
    override val member: NativePairMember? get() = nativeHost
    override val player: Player? get() = nativeHost?.player

    init {
        check(Looper.myLooper() == Looper.getMainLooper())
        onHelperCreated(helperCleanup)
        configureBrowser()
    }

    private fun allows(value: String?) = value == AbemaNativeBootstrapPolicy.PAGE
    private fun preparationAdmitted(run: Long) = !closed.get() && revision.get() == run && active() &&
        budget?.active == true && ownership?.canContinue() != false && routeOwnership?.canContinue() != false &&
        (!accountAdmitted.get() || accountClient.get()?.current() == true) &&
        playbackAvailability.get()?.current(System.currentTimeMillis()) != false && slotPremiumCurrent(run) && slotChaseCurrent(run)
    private fun preparationCurrent(run: Long) = SystemClock.elapsedRealtime() < deadline && preparationAdmitted(run)
    private fun current(run: Long) = preparationCurrent(run) && routeCurrent.get()
    private fun workerOwned(): Boolean = ownership?.checkWorker() != false &&
        (routeOwnership == null || routeOwnership === ownership || routeOwnership.checkWorker()) &&
        (!accountAdmitted.get() || accountClient.get()?.checkWorker() == true) &&
        playbackAvailability.get()?.current(System.currentTimeMillis()) != false &&
        slotPremiumCurrent(revision.get()) && slotChaseCurrent(revision.get())
    private fun slotPremiumCurrent(run: Long): Boolean {
        return slotLinearAdmission.current {
            val receipt = slotPremiumReceipt.get()
            val scope = slotPremiumScope.get()
            receipt == null || scope != null && slotAuthorityCurrent(run, receipt, scope)
        }
    }
    private fun slotChaseCurrent(run: Long): Boolean = revision.get() == run && slotChaseAdmission.current()
    private fun slotChaseCandidateCurrent(run: Long): Boolean {
        val receipt = slotChaseReceipt.get() ?: return false
        val scope = slotChaseScope.get() ?: return false
        return slotAuthorityCurrent(run, receipt, scope)
    }
    private fun slotAuthorityCurrent(run: Long, receipt: AbemaSlotAuthorityReceipt,
        scope: AbemaSlotAuthorityScope): Boolean {
        return scope.client === accountClient.get() && scope.run == run && slotResource != null &&
            scope.instanceId == ownership?.instanceId && scope.ownerProof == ownership.ownerProof &&
            scope.channelId == slotChannelId && scope.slotId == slotId && scope.playbackDeadline == deadline &&
            receipt.current(scope, System.currentTimeMillis(), SystemClock.elapsedRealtime())
    }
    private fun openConnection(url: URL): HttpsURLConnection {
        if (!workerOwned()) throw java.io.IOException("Selected playback owner unavailable")
        return route?.open(url) ?: (url.openConnection() as HttpsURLConnection)
    }
    override fun canContinue(): Boolean = current(revision.get())
    override fun checkAuthorization(): Boolean {
        if (!closed.get() && accountAdmitted.get() &&
            (accountClient.get()?.current() != true || !slotPremiumCurrent(revision.get()) || !slotChaseCurrent(revision.get()))) {
            firstFailure.remember(PrototypeFeedFailure(PrototypeFailureReason.ABEMA_ACCOUNT_UNAVAILABLE))
            return false
        }
        return canContinue()
    }

    private fun monitorAccount(run: Long) {
        if (closed.get() || revision.get() != run) return
        if (active() && budget?.active == true && SystemClock.elapsedRealtime() < deadline &&
            accountAdmitted.get() && (accountClient.get()?.current() != true || !slotPremiumCurrent(run) || !slotChaseCurrent(run))) {
            fail(PrototypeFeedFailure(PrototypeFailureReason.ABEMA_ACCOUNT_UNAVAILABLE)); return
        }
        handler.postDelayed({ monitorAccount(run) }, 200)
    }

    private fun emit(event: PrototypeFeedEvent) {
        if (lastEvent == event) return
        lastEvent = event
        onEvent(event)
    }

    private fun record(category: String) {
        Log.d("TachiaiCachedAbema", "kind=${if (effectiveReplay) "REPLAY" else "LIVE"} $category")
    }

    @SuppressLint("MissingOnRenderProcessGone")
    private fun configureBrowser() {
        // Activity chooses its dedicated process/profile before construction.
        net.fstab.tachiai.platform.web.DeveloperAccess.configureWebInspection()
        view.configureSecureSettings()
        view.settings.domStorageEnabled = false
        view.settings.blockNetworkImage = true
        view.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        view.settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean = true
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onCreateWindow(view: WebView, dialog: Boolean, gesture: Boolean, result: android.os.Message): Boolean = false
            override fun onJsAlert(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean { result.cancel(); return true }
            override fun onJsConfirm(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean { result.cancel(); return true }
            override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean { result.cancel(); return true }
            override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String?, result: android.webkit.JsPromptResult): Boolean { result.cancel(); return true }
        }
        view.webViewClient = object : WebViewClient() {
            override fun onReceivedHttpAuthRequest(view: WebView, auth: HttpAuthHandler, host: String, realm: String) {
                // A generated local-proxy credential, never a provider login or
                // persistent browser credential. Reject every other challenger.
                if (current(revision.get()) && route?.matchesProxyChallenge(host, realm) == true)
                    auth.proceed(route.proxyUsername, route.proxyPassword)
                else auth.cancel()
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.isForMainFrame && allows(request.url.toString()) && !documentStarted) return false
                fail(PrototypeFeedFailure(PrototypeFailureReason.MEDIA_BLOCKED)); return true
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = runCatching { URI(request.url.toString()) }.getOrNull()
                val live = preparationCurrent(revision.get()) && workerOwned()
                val allowed = uri != null && live &&
                    AbemaNativeBootstrapPolicy.allows(uri, request.isForMainFrame, request.method, episodeResource?.identity,
                        channelResource?.identity, slotResource?.identity, modernChannelContext = modernChannelPreparation,
                        continuousSlotPlayback = continuousSlotPlayback)
                if (!allowed) {
                    val category = if (!live) "LIFECYCLE" else
                        AbemaNativeBootstrapPolicy.refusalCategory(uri, request.isForMainFrame, request.method)
                    handler.post { if (!closed.get()) {
                        record("resource=REFUSED category=$category")
                        fail(PrototypeFeedFailure(if (live) PrototypeFailureReason.MEDIA_BLOCKED else PrototypeFailureReason.PLAYBACK_LIMIT))
                    } }
                    return refusedResource()
                }
                val local = ownedResources[uri.path]
                if (uri.host == "abema.tv" && uri.path.startsWith("/_tachiai/native/")) {
                    if (local == null) {
                        handler.post { if (!closed.get()) fail() }
                        return refusedResource()
                    }
                    return WebResourceResponse(if (request.isForMainFrame) "text/html" else "application/javascript", "UTF-8",
                        200, "OK", mapOf("Content-Security-Policy" to AbemaNativeBootstrapPolicy.contentSecurityPolicy(episodeResource?.identity,
                            channelResource?.identity, slotResource?.identity, modernChannelContext = modernChannelPreparation,
                            continuousSlotPlayback = continuousSlotPlayback),
                            "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), ByteArrayInputStream(local))
                }
                return null // Only exact policy-approved provider API endpoints.
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (!documentStarted && (url == null || url == "about:blank")) return
                if (closed.get()) return
                if (!allows(url) || documentStarted) { fail(PrototypeFeedFailure(PrototypeFailureReason.MEDIA_BLOCKED)); return }
                documentStarted = true
                routeCurrent.set(true)
            }
            override fun onPageFinished(view: WebView, url: String?) {
                if (!allows(url) && documentStarted) fail(PrototypeFeedFailure(PrototypeFailureReason.MEDIA_BLOCKED))
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                if (!allows(url) && documentStarted) fail(PrototypeFeedFailure(PrototypeFailureReason.MEDIA_BLOCKED))
            }
            override fun onReceivedSslError(view: WebView, ssl: SslErrorHandler, error: SslError) {
                ssl.cancel(); fail(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED))
            }
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                fail(PrototypeFeedFailure(PrototypeFailureReason.PREPARATION_FAILED)); return true
            }
        }
    }

    private fun refusedResource() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden",
        emptyMap(), ByteArrayInputStream(byteArrayOf()))

    override fun prepare(budget: NativePlaybackBudget) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (started || closed.get()) return
        started = true
        this.budget = budget
        deadline = budget.deadlineAt(SystemClock.elapsedRealtime())
        if (!active() || !budget.active) { fail(); return }
        emit(PrototypeFeedEvent.PREPARING)
        val run = revision.incrementAndGet()
        val readinessDeadline = minOf(deadline, SystemClock.elapsedRealtime() + 45_000)
        if (budget.isTimeBounded) handler.postDelayed({ if (!closed.get() && revision.get() == run)
            fail(PrototypeFeedFailure(PrototypeFailureReason.PLAYBACK_LIMIT)) }, budget.remainingMs)
        handler.postDelayed({ if (!closed.get() && revision.get() == run && !preparingNative) {
            if (!preparationAdmitted(run)) fail(PrototypeFeedFailure(PrototypeFailureReason.STOPPED), admissionLost = true)
            else {
                val reached = SystemClock.elapsedRealtime() >= readinessDeadline
                fail(PrototypeFeedFailure(if (reached) PrototypeFailureReason.PREPARATION_TIMEOUT
                    else PrototypeFailureReason.STOPPED), readinessDeadline = reached)
            }
        } },
            maxOf(0L, readinessDeadline - SystemClock.elapsedRealtime()))
        worker.execute {
            fun cached(identity: AbemaPublicBundleIdentity): AbemaBundlePrepared {
                val http = AbemaPublicBundleHttp(
                    canRun = { preparationCurrent(run) && SystemClock.elapsedRealtime() < readinessDeadline },
                    identity = identity,
                    open = { url -> transportCleanup.allocate(10, { openConnection(url) }) { it.disconnect() } },
                    onFailure = { transportCleanup.failed(); cleanupFailed = true; diagnostics.report(FailureStage.BUNDLE_DISCONNECT, it) },
                    openInput = { request -> transportCleanup.input { request.inputStream } },
                )
                bundleTransport.set(http)
                return try {
                    AbemaPublicBundleCache(File(view.context.cacheDir, "abema-helper-bundle"),
                        { preparationCurrent(run) && SystemClock.elapsedRealtime() < readinessDeadline }, identity)
                        .prepare(http::fetch)
                } finally {
                    try { http.close(); bundleTransport.compareAndSet(http, null) }
                    catch (error: Throwable) { cleanupFailed = true; throw error }
                }
            }
            try {
                check(preparationCurrent(run) && workerOwned() && CookieHandler.getDefault() == null)
                var accountArgument = "undefined"
                ownership?.let { selectedOwner ->
                    val resource = checkNotNull(episodeResource ?: channelResource ?: slotResource)
                    val sourceBinding = "${selectedOwner.instanceId}|${selectedOwner.ownerProof}|native-playback|" +
                        "${resource.providerId.value}|${resource.kind}|${resource.intent.name}|${resource.identity}"
                    val proof = MessageDigest.getInstance("SHA-256").digest(sourceBinding.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it.toInt() and 255) }
                    val client = AndroidAbemaAccountAccessClient(application, selectedOwner.instanceId,
                        selectedOwner.ownerProof, proof,
                        if (budget.isTimeBounded) deadline else minOf(deadline, SystemClock.elapsedRealtime() + 30_000),
                        playback = true, continuousPlayback = !budget.isTimeBounded, canUse = {
                            !closed.get() && revision.get() == run && active() && budget.active &&
                                SystemClock.elapsedRealtime() < deadline && selectedOwner.canContinue()
                        })
                    synchronized(accountLock) {
                        if (closed.get()) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
                        accountClient.set(client); accountCleanupPending.set(true)
                    }
                    val access = client.acquire()
                    when (access.status) {
                        AbemaAccountAccessIpc.Status.ACCOUNT -> {
                            val grant = checkNotNull(access.grant)
                            val argument = JSONObject().put("userId", grant.userId).put("bearer", grant.bearer)
                                .put("expiresAtMs", grant.expiresAtMs).put("deviceId", checkNotNull(access.deviceId))
                            if (slotResource != null) {
                                val beganWall = System.currentTimeMillis()
                                val beganElapsed = SystemClock.elapsedRealtime()
                                val profile = AbemaBorrowedSlotProfile(::openConnection,
                                    { preparationCurrent(run) && client.checkWorker() && selectedOwner.checkWorker() },
                                    SystemClock::elapsedRealtime, System::currentTimeMillis, readinessDeadline)
                                synchronized(accountLock) {
                                    if (closed.get() || revision.get() != run)
                                        throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
                                    slotProfile.set(profile)
                                }
                                val subscription = try { profile.read(grant.userId, grant.bearer) }
                                finally { if (!profile.cleanupConfirmed) cleanupFailed = true }
                                if (subscription !is AbemaSubscriptionProfileResult.Identity || subscription.id != grant.userId ||
                                    !preparationCurrent(run) || !client.checkWorker() || !selectedOwner.checkWorker())
                                    throw AbemaAccountException(AbemaAccountFailure.NOT_VERIFIED)
                                subscription.premiumExpiresAtMs?.let { expiry ->
                                    val scope = AbemaSlotAuthorityScope(client, selectedOwner.instanceId,
                                        selectedOwner.ownerProof, proof, slotChannelId, slotId, run, deadline)
                                    val receipt = AbemaSlotAuthorityReceipt.publish(scope, expiry, beganWall, beganElapsed,
                                        readinessDeadline, System.currentTimeMillis(), SystemClock.elapsedRealtime())
                                    if (receipt == null && !continuousSlotPlayback)
                                        throw AbemaAccountException(AbemaAccountFailure.EXPIRED)
                                    // A candidate that cannot be published grants no authority.
                                    // Linear playback does not require Premium; ended selection
                                    // without a candidate must independently pass Free admission.
                                    if (receipt != null) {
                                        synchronized(accountLock) {
                                            if (closed.get() || revision.get() != run || accountClient.get() !== client)
                                                throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
                                            slotPremiumScope.set(scope); slotPremiumReceipt.set(receipt)
                                        }
                                        argument.put("slotPremium", JSONObject().put("channelId", slotChannelId)
                                            .put("slotId", slotId).put("expiresAtMs", expiry))
                                    }
                                }
                                val chase = subscription.chase
                                if (chase.state == AbemaChaseFieldState.VERIFIED && chase.claims.isNotEmpty()) {
                                    val expiry = chase.claims.minOf { it.expiresAtMs }
                                    val scope = AbemaSlotAuthorityScope(client, selectedOwner.instanceId,
                                        selectedOwner.ownerProof, proof, slotChannelId, slotId, run, deadline)
                                    val receipt = AbemaSlotAuthorityReceipt.publish(scope, expiry, beganWall, beganElapsed,
                                        readinessDeadline, System.currentTimeMillis(), SystemClock.elapsedRealtime())
                                    // An expired unused candidate cannot truncate an ordinary
                                    // broadcast. Only a fresh Chase source binding activates it.
                                    if (receipt != null) {
                                        synchronized(accountLock) {
                                            if (closed.get() || revision.get() != run || accountClient.get() !== client)
                                                throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
                                            slotChaseScope.set(scope); slotChaseReceipt.set(receipt)
                                        }
                                        argument.put("slotChase", JSONObject().put("channelId", slotChannelId)
                                            .put("slotId", slotId).put("expiresAtMs", expiry)
                                            .put("authorityIds", JSONArray(chase.claims.map { it.id }.distinct())))
                                    }
                                }
                            }
                            accountArgument = argument.toString()
                        }
                        AbemaAccountAccessIpc.Status.GUEST -> {
                            if (initialManifest != null || requireSavedAccount)
                                throw AbemaAccountException(AbemaAccountFailure.NOT_VERIFIED)
                        }
                        AbemaAccountAccessIpc.Status.FAILURE -> throw AbemaAccountException(checkNotNull(access.failure))
                    }
                    accountAdmitted.set(true)
                    accountStatus = access.status
                    handler.post { monitorAccount(run) }
                    if (!preparationCurrent(run) || !workerOwned() || SystemClock.elapsedRealtime() >= readinessDeadline)
                        throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
                }
                val prepared = cached(AbemaPublicBundleIdentity())
                val support = cached(AbemaPublicBundleIdentity.bootstrapSupport())
                val legacy = cached(AbemaPublicBundleIdentity.legacySource())
                val utilities = cached(AbemaPublicBundleIdentity.sourceUtilities())
                val arguments = mapOf("__NONCE__" to nonce, "__NATIVE_REPLAY__" to replay.toString(),
                    "__EPISODE_ARGUMENT__" to (episodeResource?.let { JSONObject.quote(it.identity) } ?: "undefined"),
                    "__CHANNEL_ARGUMENT__" to (channelResource?.let { JSONObject.quote(it.identity) } ?: "undefined"),
                    "__SLOT_ARGUMENT__" to (slotResource?.let { JSONObject.quote(it.identity) } ?: "undefined"),
                    "__ACCOUNT_ARGUMENT__" to accountArgument,
                    "__CHANNEL_DIAGNOSTIC__" to observeChannelPreparation.toString(),
                    "__MODERN_CHANNEL_CONTEXT__" to observeModernChannelContext.toString(),
                    "__CONTINUOUS_SLOT_PLAYBACK__" to continuousSlotPlayback.toString())
                val template = view.context.assets.open("abema/native-bootstrap-initialize.js")
                    .bufferedReader().use { it.readText() }
                val initialization = Regex("__[A-Z_]+__").replace(template) { checkNotNull(arguments[it.value]) }
                    .toByteArray(Charsets.UTF_8)
                privateInitialization.set(initialization)
                if (!preparationCurrent(run) || !workerOwned()) {
                    privateInitialization.getAndSet(null)?.fill(0)
                    throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
                }
                val resources = mapOf(
                    "/_tachiai/native/index.html" to view.context.assets.open("abema/native-bootstrap.html").use { it.readBytes() },
                    "/_tachiai/native/control.js" to view.context.assets.open("abema/native-bootstrap.js").use { it.readBytes() },
                    "/_tachiai/native/bundle.js" to prepared.bytes,
                    "/_tachiai/native/support.js" to support.bytes,
                    "/_tachiai/native/legacy.js" to legacy.bytes,
                    "/_tachiai/native/utilities.js" to utilities.bytes,
                    "/_tachiai/native/initialize.js" to initialization,
                )
                handler.post {
                    if (preparationCurrent(run) && SystemClock.elapsedRealtime() < readinessDeadline) {
                        record("cache=${prepared.origin.name} support=${support.origin.name} legacy=${legacy.origin.name} utilities=${utilities.origin.name}")
                        ownedResources = resources
                        view.loadUrl(AbemaNativeBootstrapPolicy.PAGE)
                        waitForHelper(run, readinessDeadline)
                    }
                }
            } catch (error: AbemaAccountException) {
                val reason = when (error.category) {
                    AbemaAccountFailure.TEMPORARY -> PrototypeFailureReason.NETWORK_FAILED
                    AbemaAccountFailure.TIME_LIMIT_REACHED -> PrototypeFailureReason.PREPARATION_TIMEOUT
                    AbemaAccountFailure.CLEANUP_FAILED -> PrototypeFailureReason.CLEANUP_FAILED
                    else -> PrototypeFailureReason.ABEMA_ACCOUNT_UNAVAILABLE
                }
                handler.post { if (!closed.get() && revision.get() == run) fail(PrototypeFeedFailure(reason)) }
            } catch (error: AbemaBundleException) {
                if (preparationCurrent(run) && error.category != AbemaBundleFailure.CANCELLED)
                    diagnostics.report(FailureStage.CACHED_ABEMA_BUNDLE_PREPARE, error)
                val reason = when (error.category) {
                    AbemaBundleFailure.NETWORK_FAILED -> PrototypeFailureReason.NETWORK_FAILED
                    AbemaBundleFailure.HTTP_REFUSED -> PrototypeFailureReason.HTTP_REJECTED
                    AbemaBundleFailure.TIME_LIMIT_REACHED -> PrototypeFailureReason.PREPARATION_TIMEOUT
                    AbemaBundleFailure.POLICY_REFUSED, AbemaBundleFailure.COOKIE_HANDLER_REFUSED -> PrototypeFailureReason.MEDIA_BLOCKED
                    AbemaBundleFailure.PIN_REFUSED, AbemaBundleFailure.SIZE_REFUSED, AbemaBundleFailure.ASSET_TYPE_REFUSED -> PrototypeFailureReason.UNSUPPORTED_MEDIA
                    else -> PrototypeFailureReason.PREPARATION_FAILED
                }
                handler.post { if (!closed.get() && revision.get() == run) fail(PrototypeFeedFailure(reason)) }
            } catch (error: Throwable) {
                // Cleanup Errors must remain a safe retained failure, never
                // escape this owned worker to Android's uncaught handler.
                if (preparationCurrent(run)) diagnostics.report(FailureStage.CACHED_ABEMA_BUNDLE_PREPARE, error)
                handler.post { if (!closed.get() && revision.get() == run) fail() }
            }
        }
    }

    private fun waitForHelper(run: Long, readinessDeadline: Long) {
        if (closed.get() || revision.get() != run) return
        if (!preparationAdmitted(run)) {
            fail(PrototypeFeedFailure(PrototypeFailureReason.STOPPED), admissionLost = true); return
        }
        if (SystemClock.elapsedRealtime() >= readinessDeadline) {
            fail(PrototypeFeedFailure(PrototypeFailureReason.PREPARATION_TIMEOUT), readinessDeadline = true); return
        }
        if (!allows(view.url) || !routeCurrent.get()) {
            handler.postDelayed({ waitForHelper(run, readinessDeadline) }, 100); return
        }
        view.evaluateJavascript("window['$name']?.status() || 'UNAVAILABLE'") { raw ->
            if (!current(run) || SystemClock.elapsedRealtime() >= readinessDeadline || !allows(view.url)) return@evaluateJavascript
            val state = quoted(raw, 100)?.takeIf { it in setOf("WAITING_SESSION", "WAITING_SOURCE", "READY",
                "WORKING", "RESPONSE", "FAILED", "STOPPED", "UNAVAILABLE") } ?: "FAILED"
            if (lastHelperState != state) { lastHelperState = state; record("helper=$state") }
            pollMetadata(run, readinessDeadline) { metadata ->
                if (helperEvidence.firstFailure != AbemaCachedHelperFailure.NONE) {
                    fail(helperFailure ?: PrototypeFeedFailure(PrototypeFailureReason.PREPARATION_FAILED)); return@pollMetadata
                }
                when (state) {
                    "READY" -> if (!helperEvidence.permitsPreparation(state, metadata)) fail()
                        else if (!preparingNative) { preparingNative = true; prepareNative(run) }
                    "WAITING_SESSION", "WAITING_SOURCE", "UNAVAILABLE" -> {
                        emit(PrototypeFeedEvent.WAITING_PROVIDER)
                        handler.postDelayed({ waitForHelper(run, readinessDeadline) }, 100)
                    }
                    else -> fail(helperFailure ?: PrototypeFeedFailure(PrototypeFailureReason.PREPARATION_FAILED))
                }
            }
        }
    }

    private fun pollMetadata(run: Long, readDeadline: Long, onRead: (AbemaCachedHelperMetadata?) -> Unit) {
        if (!current(run) || SystemClock.elapsedRealtime() >= readDeadline || !allows(view.url)) return
        view.evaluateJavascript("window['$name']?.metadata() || null") { raw ->
            if (!current(run) || SystemClock.elapsedRealtime() >= readDeadline || !allows(view.url)) return@evaluateJavascript
            if (raw == "null") { onRead(null); return@evaluateJavascript }
            val value = parseAbemaCachedHelperMetadataCallback(raw)
            if (value == null) {
                helperPreparationEvidence.refuse()
                record("metadata=REFUSED"); fail(); return@evaluateJavascript
            }
            helperPreparationEvidence.observe(value)
            val first = helperEvidence.observe(value)
            if (first) diagnostics.reportCategory(FailureStage.CACHED_ABEMA_SOURCE_PREPARE,
                value.failure.diagnosticCategory())
            val phase = value.phase
            val http = value.http
            val diagnostic = value.closedSummary()
            if (lastMetadata != diagnostic) { lastMetadata = diagnostic; record(diagnostic) }
            if (first || helperEvidence.firstFailure == AbemaCachedHelperFailure.NONE) helperFailure = when {
                value.failure == AbemaCachedHelperFailure.TIMER_EXPIRED -> PrototypeFeedFailure(PrototypeFailureReason.PREPARATION_TIMEOUT)
                value.failure == AbemaCachedHelperFailure.OWNED_CANCELLED -> PrototypeFeedFailure(PrototypeFailureReason.STOPPED)
                value.failure == AbemaCachedHelperFailure.FETCH_REJECTED_BEFORE_HTTP -> PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED)
                value.failure == AbemaCachedHelperFailure.HTTP_REFUSED -> PrototypeFeedFailure(if (http == 404) PrototypeFailureReason.MEDIA_NOT_FOUND
                    else PrototypeFailureReason.HTTP_REJECTED, httpStatus = http)
                http >= 400 -> PrototypeFeedFailure(if (http == 404) PrototypeFailureReason.MEDIA_NOT_FOUND
                    else PrototypeFailureReason.HTTP_REJECTED, httpStatus = http)
                phase == "SOURCE_SESSION_REQUIRED" || phase == "SOURCE_MODE_UNSUPPORTED" ||
                    phase.startsWith("REPLAY_") || phase.startsWith("SLOT_") ->
                    PrototypeFeedFailure(PrototypeFailureReason.UNSUPPORTED_MEDIA)
                phase == "LICENSE" -> PrototypeFeedFailure(PrototypeFailureReason.LICENSE_FAILED)
                else -> null
            }
            onRead(value)
        }
    }

    private fun prepareNative(run: Long) {
        if (!current(run) || !allows(view.url)) { fail(); return }
        nativePreparationEvidence.observe(AbemaNativePreparationPhase.WORKER_QUEUED, true)
        emit(PrototypeFeedEvent.PREPARING)
        worker.execute {
            try {
                check(current(run) && workerOwned() && CookieHandler.getDefault() == null)
                nativePreparationEvidence.observe(AbemaNativePreparationPhase.SOURCE_SELECT, true)
                val uri = selectedSource(run, minOf(deadline, SystemClock.elapsedRealtime() + 10_000))
                    ?: throw IllegalStateException()
                val requests = BoundedMediaRequests(checkNotNull(budget).child(), { current(run) && it == uri }, { event, code ->
                    record("manifest=${event.name} code=$code")
                    rememberMedia(event, code)
                }, canRequest = { current(run) },
                    openConnection = { url -> transportCleanup.allocate(10, { openConnection(url) }) { it.disconnect() } },
                    openInput = { request -> transportCleanup.input { request.inputStream } },
                    openErrorInput = { request -> transportCleanup.optionalInput { request.errorStream } },
                    onFailure = { stage, error ->
                        if (stage == NativeFailureStage.MEDIA_DISCONNECT) { transportCleanup.failed(); cleanupFailed = true }
                        diagnostics.nativeObserver()(stage, error)
                    })
                sourceRequests.set(requests)
                nativePreparationEvidence.observe(AbemaNativePreparationPhase.MANIFEST_READ, current(run))
                val source = try { readSource(uri, requests) }
                finally {
                    try { requests.close(); sourceRequests.compareAndSet(requests, null) }
                    catch (error: Throwable) { cleanupFailed = true; throw error }
                }
                nativePreparationEvidence.observe(AbemaNativePreparationPhase.SOURCE_RECHECK, current(run))
                check(current(run) && workerOwned() && selectedSource(run, minOf(deadline, SystemClock.elapsedRealtime() + 10_000)) == uri)
                nativePreparationEvidence.observe(AbemaNativePreparationPhase.NATIVE_DISPATCH, true)
                handler.post {
                    if (!current(run) || !allows(view.url)) return@post
                    // The diagnostic completes after the original source/transport
                    // close and exact binding recheck. It can never reach a player,
                    // CDM, license exchange or media segment request.
                    if (initialManifest != null) {
                        if (cleanupFailed || source !is AbemaChannelNativeSource || source.channelId != channelId ||
                            initialManifest.snapshot.reason != AbemaInitialManifestReason.OBSERVED) { fail(); return@post }
                        try { checkNotNull(onManifestOnlyPrepared)(initialManifest.snapshot) }
                        catch (_: Throwable) { fail() }
                        return@post
                    }
                    nativePreparationEvidence.observe(AbemaNativePreparationPhase.NATIVE_CREATE, true)
                    createNative(source, run)
                }
            } catch (error: Throwable) {
                if (current(run)) {
                    nativePreparationEvidence.fail(error, true)
                    diagnostics.report(FailureStage.CACHED_ABEMA_SOURCE_PREPARE, error)
                }
                handler.post { if (!closed.get() && revision.get() == run) fail() }
            }
        }
    }

    private fun readSource(uri: URI, requests: BoundedMediaRequests): AbemaNativeDashSource {
        if (slotLinearAdmission.linearSelected) return resolveAbemaChannelNativeSource(uri, requests, slotChannelId,
            modernChannelContext = modernChannelPreparation, allowModernPlayback = modernChannelPreparation)
        if (slotResource != null) return resolveAbemaSlotNativeSource(uri, requests, slotChannelId, slotId,
            onSlotSourceDiagnostic)
        if (replay) return resolveAbemaReplayNativeSource(uri, requests, AbemaReplayCdnScope.APPROVED_PROTOTYPE, episodeId)
        if (channelResource != null) return resolveAbemaChannelNativeSource(uri, requests, channelId, initialManifest,
            modernChannelContext = observeModernChannelContext)
        check(AbemaNativeBootstrapPolicy.allowsNewsSource(uri, approvedPrototype = true, channelId = channelId))
        val source = requests.create(C.DATA_TYPE_MANIFEST)
        return try {
            source.openUri(uri)
            val body = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count < 0) break
                check(body.size() + count <= 256 * 1024)
                body.write(buffer, 0, count)
            }
            val initialization = checkNotNull(parseAbemaRequestInitialization(body.toString("UTF-8")))
            AbemaNewsNativeSource(uri, initialization.kids)
        } finally { source.close() }
    }

    private fun createNative(source: AbemaNativeDashSource, run: Long) {
        if (initialManifest != null) { fail(); return }
        val slotSourceMatches = when {
            slotResource == null -> true
            slotLinearAdmission.linearSelected -> source is AbemaChannelNativeSource && source.channelId == slotChannelId
            else -> source is AbemaSlotNativeSource && source.channelId == slotChannelId && source.slotId == slotId
        }
        if (!slotSourceMatches) { fail(); return }
        if (replay && slotResource == null && (source !is AbemaReplayNativeSource || source.episodeId != episodeId)) { fail(); return }
        if (channelResource != null && (source !is AbemaChannelNativeSource || source.channelId != channelId)) { fail(); return }
        val policy = DeclaredDashMediaPolicy(source.uri) { uri ->
            reviewCdn(uri, AbemaCdnStage.DECLARED_MEDIA)
            if (slotLinearAdmission.linearSelected) {
                AbemaNativeBootstrapPolicy.allowsNewsCdn(uri, approvedPrototype = true, channelId = slotChannelId,
                    modernChannelContext = modernChannelPreparation)
            } else if (slotResource != null) {
                allowsAbemaSlotDeclaredMedia(uri)
            } else if (replay) {
                abemaReplayCdnUriPolicy(uri, AbemaReplayCdnScope.APPROVED_PROTOTYPE) == AbemaReplayUriPolicy.ALLOWED
            }
            else AbemaNativeBootstrapPolicy.allowsNewsCdn(uri, approvedPrototype = true, channelId = channelId)
        }
        val transitions = if (effectiveReplay) null else AbemaManifestTransitionPolicy(source.kids)
        val parser = BoundedDashManifestParser(source.uri, policy,
            preflight = { body -> transitions?.accepts(body) ?: (parseAbemaRequestInitialization(body)?.kids?.toSet() == source.kids.toSet()) },
            canRun = { current(run) }, onEvent = { event, _ ->
                if (event == DashManifestPolicyEvent.REFUSED) remember(PrototypeFeedFailure(PrototypeFailureReason.UNSUPPORTED_MEDIA))
            },
            validateModel = { parsed -> transitions?.requiresDeclarationFreeModel != true ||
                (0 until parsed.periodCount).all { period -> parsed.getPeriod(period).adaptationSets.all { adaptation ->
                    adaptation.representations.all { it.format.drmInitData == null }
                } } })
        try {
            val created = BoundedNativeDashPlayer(view.context, checkNotNull(budget), source.kids,
                allowedUri = policy::allows, manifestUri = source.uri, manifestParser = parser,
                exchange = { challenge -> broker(challenge, run, minOf(deadline, SystemClock.elapsedRealtime() + 30_000)) },
                onEvent = { event, code ->
                    rememberNative(event, code)
                    handler.post {
                        if (closed.get() || revision.get() != run || !active()) return@post
                        record("native=${event.name}")
                        when (event) {
                            NativeDashEvent.READY -> {
                                if ((slotChaseAdmission.chaseSelected || slotLinearAdmission.linearSelected) && !slotChaseTimelineAccepted.get()) {
                                    val snapshot = nativeHost?.timingSnapshot()
                                    if (!slotChaseAdmission.initialLiveStartAllowed(snapshot) ||
                                        !slotLinearAdmission.initialLiveStartAllowed(snapshot)) {
                                        fail(PrototypeFeedFailure(PrototypeFailureReason.UNSUPPORTED_MEDIA)); return@post
                                    }
                                    slotChaseTimelineAccepted.set(true)
                                }
                                emit(PrototypeFeedEvent.READY)
                            }
                            NativeDashEvent.DRM_REQUESTED -> emit(PrototypeFeedEvent.LICENSE_REQUESTED)
                            NativeDashEvent.DRM_KEYS_LOADED -> emit(PrototypeFeedEvent.LICENSE_READY)
                            NativeDashEvent.VIDEO_FRAME -> emit(PrototypeFeedEvent.VIDEO_FRAME)
                            NativeDashEvent.PLAYER_FAILED, NativeDashEvent.DRM_REFUSED, NativeDashEvent.DRM_FAILED,
                            NativeDashEvent.DRM_PREWARM_FAILED, NativeDashEvent.LIMIT_REACHED, NativeDashEvent.STOPPED -> fail()
                            else -> Unit
                        }
                    }
                }, onMediaEvent = { event, code ->
                    rememberMedia(event, code)
                    if (current(run)) record("media=${event.name} code=$code")
                },
                beforeRelease = { policy.close(); pending.getAndSet(null)?.complete(null) },
                keepDrmSessionForClearTransitions = !effectiveReplay,
                initialDrmFormat = if (effectiveReplay) null else Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264)
                    .setDrmInitData(DrmInitData(C.CENC_TYPE_cenc, DrmInitData.SchemeData(
                        C.COMMON_PSSH_UUID, MimeTypes.VIDEO_MP4, commonPssh(source.kids)))).build(),
                openConnection = ::openConnection, onFailure = diagnostics.nativeObserver(),
                onCleanupOwner = { nativeCleanup = it },
                onCleanupChanged = ::nativeCleanupChanged,
                onCleanupUnconfirmed = { cleanupFailed = true; onCleanupChanged() })
            nativeHost = created
            nativePreparationEvidence.observe(AbemaNativePreparationPhase.HOST_CREATED, true)
            created.start(source.uri, playWhenReady = false)
        } catch (error: Exception) {
            if (current(run)) diagnostics.report(FailureStage.ABEMA_PLAYER_CREATE, error)
            policy.close(); fail()
        }
    }

    private fun selectedSource(run: Long, sourceDeadline: Long): URI? {
        val completion = CompletableFuture<URI?>()
        check(sourcePending.compareAndSet(null, completion))
        handler.post {
            if (!current(run) || SystemClock.elapsedRealtime() >= sourceDeadline || !allows(view.url)) completion.complete(null)
            else view.evaluateJavascript("(() => { const h = window['$name']; return JSON.stringify({" +
                "binding: h?.${when { slotResource != null -> "slotBinding"; episodeResource != null -> "sourceBinding"; channelResource != null -> "channelBinding"; else -> "selectedSource" }}() || null," +
                "availability: h?.playbackAvailability() || null}); })()") { raw ->
                if (!current(run) || SystemClock.elapsedRealtime() >= sourceDeadline || !allows(view.url)) completion.complete(null)
                else {
                    val projection = runCatching { org.json.JSONObject(quoted(raw, 24_000) ?: "") }.getOrNull()
                    val selected = projection?.opt("binding") as? String
                    val slotBinding = if (slotResource != null)
                        parseAbemaSlotPlaybackBinding(selected, slotChannelId, slotId,
                            allowLinear = continuousSlotPlayback, modernChannelContext = modernChannelPreparation) else null
                    val uri = when {
                        slotResource != null -> slotBinding?.uri
                        episodeResource != null -> parseAbemaEpisodeSourceBinding(selected, episodeId)
                        channelResource != null -> parseAbemaChannelSourceBinding(selected, channelId,
                            modernChannelContext = observeModernChannelContext)
                        else -> runCatching { URI(selected ?: "") }.getOrNull()
                    }
                    if (uri != null) {
                        try { reviewCdn(uri, AbemaCdnStage.SELECTED_MANIFEST) }
                        catch (_: Exception) {
                            record("cdnReview=UNAVAILABLE"); completion.complete(null); return@evaluateJavascript
                        }
                    }
                    val availability = uri?.let { parseAbemaPlaybackAvailability(
                        projection?.opt("availability") as? String, it,
                        requireExpiry = replay && slotBinding?.linear != true, wallMs = System.currentTimeMillis()) }
                    val allowed = uri != null && availability != null && if (slotBinding?.linear == true)
                        AbemaNativeBootstrapPolicy.allowsNewsSource(uri, approvedPrototype = true, channelId = slotChannelId,
                            modernChannelContext = modernChannelPreparation)
                    else if (slotResource != null) allowsAbemaSlotSource(uri, slotChannelId, slotId)
                    else if (replay)
                        abemaReplaySelectedSourcePolicy(uri, AbemaReplayCdnScope.APPROVED_PROTOTYPE, episodeId) == AbemaReplayUriPolicy.ALLOWED
                    else AbemaNativeBootstrapPolicy.allowsNewsSource(uri, approvedPrototype = true, channelId = channelId,
                        modernChannelContext = observeModernChannelContext)
                    if (allowed && slotBinding != null) {
                        if ((slotBinding.chase && accountStatus != AbemaAccountAccessIpc.Status.ACCOUNT) ||
                            !slotLinearAdmission.select(slotBinding.linear) ||
                            !slotChaseAdmission.select(slotBinding.chase) || !slotPremiumCurrent(run)) {
                            remember(PrototypeFeedFailure(PrototypeFailureReason.ABEMA_ACCOUNT_UNAVAILABLE))
                            completion.complete(null); return@evaluateJavascript
                        }
                    }
                    record("source=${if (allowed) "ALLOWED" else "REFUSED"}")
                    if (!allowed && uri != null) remember(PrototypeFeedFailure(PrototypeFailureReason.MEDIA_BLOCKED))
                    if (!allowed && replay && slotResource == null) {
                        val category = uri?.let { abemaReplaySelectedSourcePolicy(it, AbemaReplayCdnScope.APPROVED_PROTOTYPE).name } ?: "INVALID_URI"
                        val shape = when {
                            uri == null -> "INVALID_URI"
                            uri.toString().length > 2048 -> "TOO_LONG"
                            uri.scheme != "https" -> "SCHEME"
                            uri.port !in listOf(-1, 443) -> "PORT"
                            uri.host == "ds-vod-abematv.akamaized.net" -> "DS_VOD_AKAMAI"
                            uri.host == "vod-abematv.akamaized.net" -> "VOD_AKAMAI"
                            uri.host == "vod-cf.p-c3-e.abema-tv.com" -> "VOD_CLOUDFRONT"
                            else -> "OTHER_HOST"
                        }
                        record("sourcePolicy=$category shape=$shape")
                    }
                    if (allowed) playbackAvailability.compareAndSet(null, availability)
                    completion.complete(uri?.takeIf { allowed && playbackAvailability.get()?.current(System.currentTimeMillis()) == true })
                }
            }
        }
        return try { completion.get(maxOf(1L, sourceDeadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS) }
        catch (_: Exception) { null }
        finally { sourcePending.compareAndSet(completion, null) }
    }

    private fun broker(challenge: ByteArray, run: Long, exchangeDeadline: Long): ByteArray? {
        if (!workerOwned()) return null
        val completion = CompletableFuture<ByteArray?>()
        check(pending.compareAndSet(null, completion))
        val encoded = Base64.encodeToString(challenge, Base64.NO_WRAP)
        handler.post {
            if (!current(run) || SystemClock.elapsedRealtime() >= exchangeDeadline || !allows(view.url)) completion.complete(null)
            else view.evaluateJavascript("window['$name']?.begin('$encoded') || 'REFUSED'") { raw ->
                if (!current(run) || SystemClock.elapsedRealtime() >= exchangeDeadline || !allows(view.url) || quoted(raw, 100) != "WORKING") completion.complete(null)
                else pollResponse(completion, run, exchangeDeadline)
            }
        }
        return try {
            awaitOpaqueResponse(completion, exchangeDeadline - SystemClock.elapsedRealtime())?.let { bytes ->
                if (workerOwned() && current(run)) bytes else { bytes.fill(0); null }
            }
        }
        finally { pending.compareAndSet(completion, null) }
    }

    private fun pollResponse(completion: CompletableFuture<ByteArray?>, run: Long, exchangeDeadline: Long) {
        if (completion.isDone) return
        if (!current(run) || !allows(view.url) || SystemClock.elapsedRealtime() >= exchangeDeadline) { completion.complete(null); return }
        view.evaluateJavascript("window['$name']?.status() || 'UNAVAILABLE'") { raw ->
            if (!current(run) || !allows(view.url) || SystemClock.elapsedRealtime() >= exchangeDeadline) completion.complete(null)
            else when (quoted(raw, 100)) {
                "WORKING" -> handler.postDelayed({ pollResponse(completion, run, exchangeDeadline) }, 100)
                "RESPONSE" -> view.evaluateJavascript("window['$name']?.take()") { opaque ->
                    val encoded = quoted(opaque, 90_000)
                    val response = try {
                        if (encoded == null || !encoded.matches(Regex("[A-Za-z0-9+/]+={0,2}"))) null
                        else Base64.decode(encoded, Base64.NO_WRAP).let { bytes ->
                            if (bytes.size in 1..65536 && Base64.encodeToString(bytes, Base64.NO_WRAP) == encoded) bytes
                            else { bytes.fill(0); null }
                        }
                    } catch (_: Exception) { null }
                    if (!current(run) || !allows(view.url) || SystemClock.elapsedRealtime() >= exchangeDeadline ||
                        !completion.complete(response)) response?.fill(0)
                    if (!current(run) || SystemClock.elapsedRealtime() >= exchangeDeadline) completion.complete(null)
                }
                // Collect the same bounded closed evidence before handing a
                // terminal helper failure back to the CDM/cleanup path. Never
                // read after close or extend the original exchange deadline.
                else -> pollMetadata(run, exchangeDeadline) {
                    remember(helperFailure ?: PrototypeFeedFailure(PrototypeFailureReason.LICENSE_FAILED))
                    completion.complete(null)
                }
            }
        }
    }

    override fun pauseOriginal(onResult: (Boolean) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        // No provider media element was created by this runtime.
        onResult(current(revision.get()) && allows(view.url) && nativeHost?.timingSnapshot()?.state == Player.STATE_READY)
    }

    private fun quoted(raw: String?, limit: Int): String? = try {
        if (raw == null || raw.length > limit) null else JSONTokener(raw).nextValue() as? String
    } catch (_: Exception) { null }

    private fun reviewCdn(uri: URI, stage: AbemaCdnStage) {
        val value = AbemaCdnApprovalPolicy.review(uri, stage, System.currentTimeMillis()) ?: return
        try { cdnJournal.record(value) }
        catch (_: Exception) { record("cdnReview=UNAVAILABLE"); throw IllegalStateException("CDN review unavailable") }
        if (value.decision == AbemaCdnDecision.PENDING) {
            networkReviewNeeded.set(true)
            remember(PrototypeFeedFailure(PrototypeFailureReason.MEDIA_APPROVAL_REQUIRED))
            record("cdnReview=PENDING stage=${stage.name}")
        }
    }

    private fun remember(value: PrototypeFeedFailure) {
        if (!closed.get() && active()) firstFailure.remember(value)
    }

    private fun rememberMedia(event: NativeMediaEvent, code: Int) {
        val reason = when (event) {
            NativeMediaEvent.HTTP_REJECTED -> if (code == 404) PrototypeFailureReason.MEDIA_NOT_FOUND else PrototypeFailureReason.HTTP_REJECTED
            NativeMediaEvent.SOURCE_NOT_ALLOWLISTED -> PrototypeFailureReason.MEDIA_BLOCKED
            NativeMediaEvent.REQUEST_FAILED -> PrototypeFailureReason.NETWORK_FAILED
            NativeMediaEvent.ENCRYPTION_UNSUPPORTED, NativeMediaEvent.PLAYLIST_UNSUPPORTED -> PrototypeFailureReason.UNSUPPORTED_MEDIA
            NativeMediaEvent.LIMIT_REACHED -> PrototypeFailureReason.PLAYBACK_LIMIT
            else -> return
        }
        remember(PrototypeFeedFailure(reason, httpStatus = if (event == NativeMediaEvent.HTTP_REJECTED)
            code.takeIf { it in 100..599 } else null))
    }

    private fun rememberNative(event: NativeDashEvent, code: Int) {
        val reason = when (event) {
            NativeDashEvent.DRM_REFUSED, NativeDashEvent.DRM_FAILED, NativeDashEvent.DRM_PREWARM_FAILED -> PrototypeFailureReason.LICENSE_FAILED
            NativeDashEvent.PLAYER_FAILED -> PrototypeFailureReason.PLAYER_FAILED
            NativeDashEvent.LIMIT_REACHED -> PrototypeFailureReason.PLAYBACK_LIMIT
            NativeDashEvent.STOPPED -> PrototypeFailureReason.STOPPED
            else -> return
        }
        remember(PrototypeFeedFailure(reason, playerCode = if (event == NativeDashEvent.PLAYER_FAILED)
            code.takeIf { it in 1000..9999 } else null))
    }

    private fun fail(value: PrototypeFeedFailure = PrototypeFeedFailure(PrototypeFailureReason.PREPARATION_FAILED),
        readinessDeadline: Boolean = false, admissionLost: Boolean = false) {
        val observed = if (networkReviewNeeded.get()) PrototypeFeedFailure(PrototypeFailureReason.MEDIA_APPROVAL_REQUIRED) else value
        // A current native readiness callback owns its STOPPED result even
        // after active admission is lost. Request/teardown callbacks keep
        // their existing active guard; an earlier cause always wins.
        if (admissionLost && !closed.get()) firstFailure.remember(observed) else remember(observed)
        // The existing atomic latch decides the first cause. A concurrent
        // unrelated failure cannot acquire this native deadline marker.
        if (readinessDeadline && observed.reason == PrototypeFailureReason.PREPARATION_TIMEOUT && failure === observed)
            nativeReadinessDeadlineReached = true
        finish(if (failure?.reason == PrototypeFailureReason.MEDIA_APPROVAL_REQUIRED)
            PrototypeFeedEvent.NETWORK_APPROVAL_REQUIRED else PrototypeFeedEvent.FAILED)
    }
    override fun close() {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { close() }; return }
        // This owner explicitly cancels an outstanding helper operation. Do
        // not read after close, delay cleanup, or classify ordinary successful
        // playback/take cleanup as cancellation.
        if (!closed.get() && documentStarted && (!preparingNative || pending.get()?.isDone == false) &&
            helperEvidence.remember(AbemaCachedHelperFailure.OWNED_CANCELLED))
            diagnostics.reportCategory(FailureStage.CACHED_ABEMA_SOURCE_PREPARE, FailureCategory.ABEMA_CACHED_OWNED_CANCELLED)
        finish(PrototypeFeedEvent.STOPPED)
    }

    private fun nativeCleanupChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (!handler.post(::nativeCleanupChanged)) { cleanupFailed = true; onCleanupChanged() }
            return
        }
        if (nativeCleanup?.confirmed == true) nativeHost = null
        finishTransportCleanup()
        finishHelperCleanup()
        onCleanupChanged()
    }

    private fun cancelTransports() {
        // Interrupt reads promptly, but this task is only a cancellation attempt.
        // The tracked preparation finally and final receipt still own confirmation.
        transportCancellationPending.set(true)
        try {
            cleanupExecutor.execute {
                try {
                    for ((stage, close) in listOf<Pair<FailureStage, () -> Unit>>(
                        FailureStage.BUNDLE_TRANSPORT_CLOSE to {
                            val owned = bundleTransport.get(); owned?.close(); bundleTransport.compareAndSet(owned, null)
                        },
                        FailureStage.SOURCE_REQUESTS_CLOSE to {
                            val owned = sourceRequests.get(); owned?.close(); sourceRequests.compareAndSet(owned, null)
                        },
                    )) {
                        if (!diagnostics.cleanupAll(stage, close)) cleanupFailed = true
                    }
                } finally {
                    transportCancellationPending.set(false)
                    finishTransportCleanup()
                    onCleanupChanged()
                }
            }
        } catch (error: Throwable) {
            cleanupFailed = true
            transportCancellationPending.set(false)
            diagnostics.report(FailureStage.SOURCE_REQUESTS_CLOSE, error)
        }
    }

    private fun finishTransportCleanup() {
        // A queued worker can settle synchronously during shutdownNow. Do not
        // finalize while main is still sealing/cancelling the other owners.
        if (!closed.get() || !cleanupSetupComplete.get() || worker.pending || accountCleanupPending.get() ||
            slotProfileCleanupPending.get() || transportCancellationPending.get() || nativeCleanup?.pending == true ||
            !transportFinalizing.compareAndSet(false, true)) return
        if (slotProfile.get()?.cleanupConfirmed == false) cleanupFailed = true
        try {
            transportCleanup.closeAsync(cleanupExecutor, Executor { task ->
                if (Looper.myLooper() == Looper.getMainLooper()) task.run()
                else check(handler.post(task)) { "ABEMA cleanup dispatch rejected" }
            }, onComplete = {
                transportFinalized.set(true)
                finishHelperCleanup()
                onCleanupChanged()
            }, onFailure = { error ->
                cleanupFailed = true
                diagnostics.report(FailureStage.SOURCE_REQUESTS_CLOSE, error)
            })
        } catch (error: Throwable) {
            cleanupFailed = true
            transportFinalized.set(true)
            diagnostics.report(FailureStage.SOURCE_REQUESTS_CLOSE, error)
            finishHelperCleanup()
            onCleanupChanged()
        }
    }

    private fun finishHelperCleanup() {
        if (!closed.get() || !cleanupSetupComplete.get() || !transportFinalized.get() ||
            nativeCleanup?.pending == true || helperFinalized.get()) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (!handler.post(::finishHelperCleanup)) { cleanupFailed = true; helperFinalized.set(true); onCleanupChanged() }
            return
        }
        try {
            if (!helperCleanup.destroy() || helperCleanup.cleanupFailed) cleanupFailed = true
        } finally { helperFinalized.set(true); onCleanupChanged() }
    }

    private fun finish(event: PrototypeFeedEvent) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { finish(event) }; return }
        if (!closed.compareAndSet(false, true)) return
        helperPreparationEvidence.seal()
        nativePreparationEvidence.seal(sourcePending.get()?.isDone == false, pending.get()?.isDone == false)
        synchronized(accountLock) {
            accountClient.get()?.close()
            slotPremiumReceipt.set(null); slotPremiumScope.set(null)
            slotChaseReceipt.set(null); slotChaseScope.set(null)
        }
        slotProfile.get()?.let { profile ->
            slotProfileCleanupPending.set(true)
            try {
                accountCleanup.execute {
                    try { profile.cancel() }
                    finally { slotProfileCleanupPending.set(false); finishTransportCleanup(); onCleanupChanged() }
                }
            } catch (error: Throwable) {
                cleanupFailed = true; slotProfileCleanupPending.set(false)
                diagnostics.report(FailureStage.CATALOG_AUTH_CLEANUP, error)
            }
        }
        accountClient.get()?.let { client ->
            try {
                accountCleanup.execute {
                    try { if (!client.awaitCleanup()) cleanupFailed = true }
                    finally { accountCleanupPending.set(false); finishTransportCleanup(); onCleanupChanged() }
                }
            } catch (error: Throwable) {
                // Continue every local teardown stage while retaining this
                // unconfirmed client with the failed session/coordinator.
                cleanupFailed = true; accountCleanupPending.set(false)
                diagnostics.report(FailureStage.CATALOG_AUTH_CLEANUP, error)
            }
        }
        transportCleanup.seal()
        revision.incrementAndGet()
        routeCurrent.set(false)
        budget?.stop()
        handler.removeCallbacksAndMessages(null)
        sourcePending.getAndSet(null)?.complete(null)
        pending.getAndSet(null)?.complete(null)
        cancelTransports()
        for ((stage, cleanup) in listOf<Pair<FailureStage, () -> Unit>>(
            FailureStage.HELPER_STOP to { if (allows(view.url)) view.evaluateJavascript("window['$name']?.stop()", null) },
            FailureStage.NATIVE_HOST_CLOSE to {
                val host = nativeHost
                if (host != null) host.close()
                else nativeCleanup?.closePlayer(handler, diagnostics.nativeObserver(), ::nativeCleanupChanged)
            },
            FailureStage.RESOURCES_CLEAR to { ownedResources = emptyMap(); privateInitialization.getAndSet(null)?.fill(0) },
            FailureStage.WORKER_SHUTDOWN to { worker.shutdownNow() },
        )) {
            if (!diagnostics.cleanupAll(stage, cleanup)) cleanupFailed = true
        }
        cleanupSetupComplete.set(true)
        finishTransportCleanup()
        emit(event)
    }
}
