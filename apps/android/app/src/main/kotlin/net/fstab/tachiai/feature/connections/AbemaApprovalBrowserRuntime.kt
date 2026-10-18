package net.fstab.tachiai.feature.connections

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.*
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.platform.web.configureSecureSettings
import net.fstab.tachiai.presentation.SourceRouteMode
import net.fstab.tachiai.provider.abema.captureAbemaCatalogRouteOwner
import net.fstab.tachiai.provider.abema.account.*
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// Shared only inside the approval process. The visible Activity retains its
// foreground policy; an operation host supplies its own bounded admission.
internal enum class AbemaApprovalObservation {
    ROUTE_APPLIED, PAGE_STARTED, PAGE_COMMITTED, CANDIDATE_CAPTURED,
    REGION_ALLOWED, REGION_REJECTED, NETWORK_ERROR, HTTP_ERROR, TLS_REFUSED,
    RENDERER_GONE, CLEANUP_CONFIRMED, CLEANUP_UNCONFIRMED,
}

internal class AbemaApprovalBrowserRuntime(
    private val context: Context,
    private val request: AbemaApprovalBrowserRequest,
    private val operationCurrent: () -> Boolean,
    private val status: (String) -> Unit,
    private val onBrowser: (WebView) -> Unit,
    private val completed: (Boolean) -> Unit,
    private val observation: (AbemaApprovalObservation) -> Unit = {},
) {
    private val applicationContext get() = context.applicationContext
    private val packageName get() = context.packageName
    companion object {
        private const val LOG_TAG = "TachiaiAbemaApproval"
        private val occupied = AtomicBoolean()
        private val poisoned = AtomicBoolean()
        private var profileConfigured = false
        private val proxy = AbemaWebViewRoute()
        private val workers = Executors.newSingleThreadExecutor { Thread(it, "abema-approval").apply { isDaemon = true } }
        private val cleanup = Executors.newSingleThreadExecutor { Thread(it, "abema-approval-close").apply { isDaemon = true } }
        private val retained = mutableListOf<Any>()
    }
    private class Owned {
        val preparation = RoutePreparation()
        val prepared = CountDownLatch(1)
        @Volatile var backend: AbemaAccountCleanupReceipt<RouteBackend>? = null
        @Volatile var route: RouteSession? = null
        @Volatile var routeClose: AbemaAccountCleanupReceipt<RouteSession>? = null
        @Volatile var regionCheck: AbemaApprovalRegionCheck? = null
        fun closeConfirmed(): Boolean {
            if (!prepared.await(1, TimeUnit.SECONDS)) return false
            if (regionCheck?.closeConfirmed() == false) return false
            val closed = routeClose?.await() ?: (backend?.await() ?: true)
            return closed && preparation.cleanupConfirmed
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private var view: WebView? = null
    private var owned: Owned? = null
    private var sessionReset: AbemaApprovalSessionReset? = null
    @Volatile private var active = false
    private var successful = false
    @Volatile private var ready = false
    @Volatile private var ending = false
    private var finishedAfterCleanup = false
    private var claimed = false
    private var checkPending = false
    private var loaded = false
    @Volatile private var trustedEmailPage = false
    @Volatile private var emailCandidate: AbemaAccountGrant? = null
    private var automaticCandidate: AbemaAccountGrant? = null
    private val automaticReturn = AbemaApprovalAutomaticReturn()

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (Build.VERSION.SDK_INT < 28 ||
            Application.getProcessName() != "$packageName:abema_approval_browser" || poisoned.get() ||
            !request.current(SystemClock.elapsedRealtime()) || !operationCurrent() ||
            !occupied.compareAndSet(false, true)) {
            finishDirect(); return
        }
        claimed = true
        try {
            if (!profileConfigured) {
                WebView.setDataDirectorySuffix(AbemaApprovalBrowserPolicy.PROFILE_SUFFIX)
                profileConfigured = true
            }
            net.fstab.tachiai.platform.web.DeveloperAccess.configureWebInspection()
            val browser = WebView(context)
            view = browser
            browser.configureSecureSettings()
            browser.settings.blockNetworkLoads = true
            browser.settings.saveFormData = false
            browser.settings.setGeolocationEnabled(false)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false)
            browser.webViewClient = client()
            browser.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage?) = true
                override fun onPermissionRequest(permission: PermissionRequest) { permission.deny() }
                override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                    callback?.invoke(origin, false, false)
                }
                // Default script dialogs do not inherit the secure window and
                // must not present provider text or credential prompts there.
                override fun onJsAlert(browser: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                    result.cancel(); return true
                }
                override fun onJsConfirm(browser: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                    result.cancel(); return true
                }
                override fun onJsBeforeUnload(browser: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                    result.cancel(); return true
                }
                override fun onJsPrompt(browser: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
                    result.cancel(); return true
                }
                override fun onShowFileChooser(webView: WebView?, callback: android.webkit.ValueCallback<Array<android.net.Uri>>?, parameters: FileChooserParams?): Boolean {
                    callback?.onReceiveValue(null); return true
                }
            }
            browser.setDownloadListener { _, _, _, _, _ -> status("Downloads are unavailable in account approval.") }
            val serviceWorkers = ServiceWorkerController.getInstance()
            serviceWorkers.serviceWorkerWebSettings.apply {
                allowFileAccess = false; allowContentAccess = false; blockNetworkLoads = true
            }
            serviceWorkers.setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(resource: WebResourceRequest) = intercept(resource)
            })
            onBrowser(browser)
            if (request.resetBrowserSession) prepareFreshSession() else prepareRoute()
            watchOwner()
        } catch (_: Throwable) { close(false) }
    }

    private fun sameOwner() = request.ownerProof == captureAbemaCatalogRouteOwner(request.instanceId,
        { providerInstanceStore(applicationContext).read { legacyProviderSettings(applicationContext) } },
        { connectionProfileStore(applicationContext).snapshot(it) }, request.ownerProof).proof()
    private fun current() = !ending && ready && active && operationCurrent() && !poisoned.get() && request.current(SystemClock.elapsedRealtime())
    // WebView interception is on Chromium's worker. Protected owner reads never
    // occur in navigation, proxy-authentication or main-thread UI callbacks.
    private fun currentOnWorker(): Boolean {
        if (!current()) return false
        val matches = runCatching(::sameOwner).getOrDefault(false)
        if (!matches) main.post { close(false) }
        return matches && current()
    }
    private fun prepareFreshSession() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
            status("Fresh sign-in is unavailable with this WebView. Return to Tachiai.")
            return
        }
        status("Clearing ABEMA’s remembered browser login…")
        val began = SystemClock.elapsedRealtime()
        fun eligible() = !ending && request.current(SystemClock.elapsedRealtime()) &&
            SystemClock.elapsedRealtime() - began in 0 until 5000
        val timeout = Runnable { if (!ending) close(false) }
        val reset = AbemaApprovalSessionReset(
            supported = { true },
            deleteBrowsingData = { done -> WebStorageCompat.deleteBrowsingData(WebStorage.getInstance(), Runnable(done)) },
            canContinue = ::eligible,
            completed = { cleared -> main.post completion@{
                main.removeCallbacks(timeout)
                if (ending) return@completion
                if (!cleared || !eligible()) { close(false); return@completion }
                workers.execute {
                    val matches = runCatching(::sameOwner).getOrDefault(false)
                    main.post ownership@{
                        if (ending) return@ownership
                        if (!matches || !eligible() || sessionReset?.completionConfirmed != true) { close(false); return@ownership }
                        Log.i(LOG_TAG, "BROWSER_SESSION_RESET_CONFIRMED")
                        prepareRoute()
                    }
                }
            } }, cleanupWaitMs = 5000,
        )
        sessionReset = reset // Retain before dispatch or any owner-check callback.
        main.postDelayed(timeout, 5000)
        workers.execute {
            val matches = runCatching(::sameOwner).getOrDefault(false)
            main.post {
                if (ending) return@post
                if (!matches || !eligible()) { close(false); return@post }
                reset.begin()
            }
        }
    }
    private fun prepareRoute() {
        val handle = Owned(); owned = handle
        main.postDelayed({ if (!ending && !ready) close(false) }, 45000)
        workers.execute {
            var valid = false
            try {
                if (ending || !request.current(SystemClock.elapsedRealtime())) return@execute
                val owner = captureAbemaCatalogRouteOwner(request.instanceId,
                    { providerInstanceStore(applicationContext).read { legacyProviderSettings(applicationContext) } },
                    { connectionProfileStore(applicationContext).snapshot(it) }, request.ownerProof)
                check(owner.proof() == request.ownerProof)
                check((owner.profile == null) == (owner.instance.setup.route?.mode == SourceRouteMode.SYSTEM))
                if (BuildConfig.LOCAL_INSPECTION) Log.i("TachiaiInspect", "ABEMA_ROUTE mode=${owner.instance.setup.route?.mode?.name} " +
                    "kind=${owner.profile?.kind?.id ?: "SYSTEM"} " +
                    "windscribeNamed=${owner.instance.setup.route?.connectionName?.contains("windscribe", ignoreCase = true) == true}")
                handle.route = RouteSession.createAbemaApprovalBrowser(owner.profile, handle.preparation,
                    ownBackend = { backend ->
                        val receipt = AbemaAccountCleanupReceipt(backend, RouteBackend::close)
                        handle.backend = receipt
                        object : RouteBackend {
                            override val proxyPort get() = backend.proxyPort
                            override fun close() { check(receipt.await()) }
                        }
                    })
                handle.routeClose = AbemaAccountCleanupReceipt(checkNotNull(handle.route), RouteSession::close)
                handle.regionCheck = AbemaApprovalRegionCheck(checkNotNull(handle.route), ::currentOnWorker,
                    SystemClock::elapsedRealtime, request.deadlineElapsedMs)
                valid = !ending && request.current(SystemClock.elapsedRealtime()) && sameOwner()
            } catch (_: Throwable) { valid = false }
            finally {
                handle.prepared.countDown()
                main.post {
                    if (ending) return@post
                    if (!valid) { close(false); return@post }
                    proxy.install(checkNotNull(handle.route), onComplete = { applied ->
                        if (ending) return@install
                        if (!applied || !request.current(SystemClock.elapsedRealtime())) { close(false); return@install }
                        ready = true
                        Log.i(LOG_TAG, "APPROVAL_ROUTE_PROXY_APPLIED"); observed(AbemaApprovalObservation.ROUTE_APPLIED)
                        if (active) resumeBrowser()
                        status(if (request.automaticReturn)
                            "ABEMA will return to Tachiai automatically after account confirmation. Sign in here if requested."
                            else if (request.emailNonce != null)
                            "If already signed in, stay on Account management and return to Tachiai. Otherwise choose Switch account → Email address and sign in, then return."
                            else "Complete approval on ABEMA’s pages, then return to Tachiai.")
                    })
                }
            }
        }
    }
    private fun watchOwner() {
        if (ending) return
        if (!request.current(SystemClock.elapsedRealtime()) || !operationCurrent()) { close(false); return }
        if (!checkPending) {
            checkPending = true
            workers.execute {
                val matches = runCatching(::sameOwner).getOrDefault(false)
                main.post { checkPending = false; if (!matches) close(false) }
            }
        }
        main.postDelayed(::watchOwner, 1000)
    }
    private fun client() = object : WebViewClient() {
        override fun onPageStarted(browser: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            Log.i(LOG_TAG, "PAGE_STARTED"); observed(AbemaApprovalObservation.PAGE_STARTED)
            trustedEmailPage = request.emailNonce != null && url?.let {
                runCatching { java.net.URI(it).host == "abema.tv" && AbemaApprovalBrowserPolicy.allowedNavigation(it) }.getOrDefault(false)
            } == true
            if (!current() || url == null || !AbemaApprovalBrowserPolicy.allowedNavigation(url)) close(false)
        }
        override fun onPageCommitVisible(browser: WebView, url: String?) {
            Log.i(LOG_TAG, "PAGE_COMMITTED"); observed(AbemaApprovalObservation.PAGE_COMMITTED)
            if (!current() || url == null || !AbemaApprovalBrowserPolicy.allowedNavigation(url)) close(false)
        }
        override fun onPageFinished(browser: WebView, url: String?) { Log.i(LOG_TAG, "PAGE_FINISHED") }
        override fun shouldOverrideUrlLoading(browser: WebView, resource: WebResourceRequest): Boolean {
            val refused = !current() || !AbemaApprovalBrowserPolicy.allowedNavigation(resource.url.toString())
            if (refused && current()) status("This destination is unavailable in ABEMA account approval.")
            return refused
        }
        override fun shouldInterceptRequest(browser: WebView, resource: WebResourceRequest) = intercept(resource)
        override fun onReceivedSslError(browser: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
            Log.i(LOG_TAG, "TLS_ERROR_REFUSED"); observed(AbemaApprovalObservation.TLS_REFUSED)
            handler.cancel(); close(false)
        }
        override fun onReceivedHttpAuthRequest(browser: WebView, handler: HttpAuthHandler, host: String, realm: String) {
            val route = owned?.route
            if (current() && route?.matchesProxyChallenge(host, realm) == true) {
                Log.i(LOG_TAG, "APPROVAL_ROUTE_PROXY_AUTH_ACCEPTED")
                handler.proceed(route.proxyUsername, route.proxyPassword)
            } else { Log.i(LOG_TAG, "HTTP_AUTH_REFUSED"); handler.cancel() }
        }
        override fun onReceivedError(browser: WebView, resource: WebResourceRequest, error: WebResourceError) {
            observed(AbemaApprovalObservation.NETWORK_ERROR)
            inspectResource("NETWORK_ERROR", resource, error.errorCode.coerceIn(-16, 0))
            if (request.automaticReturn && !ending && fatalAutomaticResource(resource)) { close(false); return }
            if (resource.url.host == AbemaApprovalBrowserPolicy.GATEWAY_HOST)
                Log.i(LOG_TAG, "GATEWAY_NETWORK_ERROR_${error.errorCode.coerceIn(-16, 0)}")
            if (resource.isForMainFrame) {
                Log.i(LOG_TAG, "MAIN_FRAME_NETWORK_ERROR_${error.errorCode.coerceIn(-16, 0)}")
                status("ABEMA’s page could not be loaded through this route. Return to Tachiai to retry.")
            }
        }
        override fun onReceivedHttpError(browser: WebView, resource: WebResourceRequest, response: WebResourceResponse) {
            observed(AbemaApprovalObservation.HTTP_ERROR)
            inspectResource("HTTP_ERROR", resource, response.statusCode.coerceIn(100, 599))
            if (request.automaticReturn && !ending && fatalAutomaticResource(resource)) { close(false); return }
            if (resource.url.host == AbemaApprovalBrowserPolicy.GATEWAY_HOST)
                Log.i(LOG_TAG, "GATEWAY_HTTP_ERROR_${response.statusCode.coerceIn(100, 599)}")
            if (resource.isForMainFrame) {
                Log.i(LOG_TAG, "MAIN_FRAME_HTTP_${response.statusCode.coerceIn(100, 599)}")
                status("ABEMA returned an error page. Return to Tachiai to retry.")
            }
        }
        override fun onRenderProcessGone(browser: WebView, detail: RenderProcessGoneDetail): Boolean {
            observed(AbemaApprovalObservation.RENDERER_GONE)
            close(false); return true
        }
    }
    private fun allowed(resource: WebResourceRequest) = if (resource.isForMainFrame)
        AbemaApprovalBrowserPolicy.allowedNavigation(resource.url.toString())
        else AbemaApprovalBrowserPolicy.allowedResource(resource.url.toString(), resource.method)
    private fun fatalAutomaticResource(resource: WebResourceRequest): Boolean {
        return abemaAutomaticReturnFatalResource(resource.isForMainFrame, resource.url.toString(),
            resource.method, trustedEmailPage, emailCandidate?.userId)
    }
    private fun intercept(resource: WebResourceRequest): WebResourceResponse? {
        if (!allowed(resource)) {
            inspectResource("POLICY_REFUSED", resource)
            if (resource.url.host == AbemaApprovalBrowserPolicy.GATEWAY_HOST) Log.i(LOG_TAG, "GATEWAY_RESOURCE_REFUSED")
            return blocked()
        }
        if (!currentOnWorker()) return blocked()
        if (resource.url.host == AbemaApprovalBrowserPolicy.GATEWAY_HOST)
            Log.i(LOG_TAG, "GATEWAY_RESOURCE_ADMITTED")
        if (resource.isForMainFrame) Log.i(LOG_TAG, "MAIN_FRAME_REQUEST_ADMITTED")
        if (request.emailNonce != null && trustedEmailPage && abemaEmailRegistrationId(resource.url.toString()) != null) {
            val origins = resource.requestHeaders.filterKeys { it.equals("Origin", true) }.values
            if (origins.size <= 1 && origins.all { it == "https://abema.tv" }) {
                val candidate = parseAbemaEmailCandidate(resource.url.toString(), resource.method,
                    resource.requestHeaders, System.currentTimeMillis())
                val admitted = currentOnWorker()
                if (admitted && trustedEmailPage) {
                    if (request.automaticReturn) {
                        // Worker owner checks occur outside this monitor. Only
                        // scalar admission and the first candidate are captured
                        // here, so parallel identical requests cannot replace it.
                        val queued = synchronized(automaticReturn) {
                            if (automaticReturn.queue(current(), trustedEmailPage, candidate != null)) {
                                emailCandidate = candidate
                                true
                            } else false
                        }
                        if (!queued) return null
                        observed(AbemaApprovalObservation.CANDIDATE_CAPTURED)
                        if (!main.post {
                            val accepted = synchronized(automaticReturn) {
                                automaticReturn.claim(current(), trustedEmailPage, candidate != null && emailCandidate === candidate)
                                    .also { if (it) automaticCandidate = candidate }
                            }
                            close(accepted)
                        }) automaticReturn.cancel()
                    } else emailCandidate = candidate
                }
            }
        }
        if (resource.url.host !in AbemaApprovalBrowserPolicy.REGION_HOSTS) return null
        val origins = resource.requestHeaders.filterKeys { it.equals("Origin", true) }.values
        if (origins.size > 1 || resource.requestHeaders.keys.any {
            it.equals("Cookie", true) || it.equals("Authorization", true) || it.equals("Proxy-Authorization", true)
        }) {
            main.post { close(false) }
            return blocked()
        }
        Log.i(LOG_TAG, if (resource.url.host == AbemaApprovalBrowserPolicy.REGION_HOST)
            "REGION_RESOURCE_GLOBAL" else "REGION_RESOURCE_LINEAR")
        val result = owned?.regionCheck?.check(origins.singleOrNull(), resource.url.toString())
        if (!currentOnWorker()) return blocked()
        return when (result) {
            is AbemaApprovalRegionResult.Reply -> {
                Log.i(LOG_TAG, when (result.status) { 200 -> "REGION_HTTP_200"; 403 -> "REGION_HTTP_403"; else -> "REGION_HTTP_OTHER" })
                if (result.status == 200) observed(AbemaApprovalObservation.REGION_ALLOWED)
                if (result.status == 403) observed(AbemaApprovalObservation.REGION_REJECTED)
                if (result.status == 403) main.post {
                    if (request.automaticReturn) close(false)
                    else if (current()) status("ABEMA rejected this route’s region. Return to Tachiai.")
                }
                WebResourceResponse("text/plain", "UTF-8", result.status, "Provider region response",
                    result.headers, ByteArrayInputStream(ByteArray(0)))
            }
            is AbemaApprovalRegionResult.Failure -> {
                Log.i(LOG_TAG, "REGION_FAILURE_${result.category.name}")
                main.post { close(false) }
                blocked()
            }
            null -> {
                main.post { close(false) }
                blocked()
            }
        }
    }
    // Host only: paths, queries, headers, bodies and credentials stay out of logcat.
    private fun inspectResource(event: String, resource: WebResourceRequest, code: Int = 0) {
        if (!BuildConfig.LOCAL_INSPECTION) return
        val host = resource.url.host.orEmpty().takeIf {
            it.length in 1..253 && it.matches(Regex("[A-Za-z0-9.-]+"))
        } ?: "OTHER"
        Log.i("TachiaiInspect", "$event host=$host main=${resource.isForMainFrame} code=$code")
    }
    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Unavailable", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    private fun resumeBrowser() {
        if (!current()) return
        view?.settings?.blockNetworkLoads = false
        ServiceWorkerController.getInstance().serviceWorkerWebSettings.blockNetworkLoads = false
        view?.onResume(); view?.resumeTimers()
        if (!loaded) { loaded = true; view?.loadUrl(request.verificationUri) }
        if (request.automaticReturn) status("ABEMA will return to Tachiai automatically after account confirmation. Sign in here if requested.")
    }
    fun resume() {
        if (!claimed || ending || finishedAfterCleanup) return
        active = true
        if (ready && !ending) {
            workers.execute {
                val matches = runCatching(::sameOwner).getOrDefault(false)
                main.post { if (matches && current()) resumeBrowser() else if (!ending) close(false) }
            }
        }
    }
    fun pause() {
        active = false
        // A rejected or completed host must not pause another owner's
        // process-wide WebView timers or service-worker network access.
        if (!claimed || ending || finishedAfterCleanup) return
        view?.settings?.blockNetworkLoads = true
        view?.stopLoading(); view?.onPause(); view?.pauseTimers()
        if (profileConfigured) ServiceWorkerController.getInstance().serviceWorkerWebSettings.blockNetworkLoads = true
    }
    fun close(returnRequested: Boolean) {
        if (!returnRequested) automaticReturn.cancel()
        if (ending || !claimed) return
        ending = true; ready = false
        trustedEmailPage = false
        main.removeCallbacksAndMessages(null)
        val handle = owned
        val reset = sessionReset
        reset?.cancel()
        val browser = view
        var destroyed = true
        try {
            handle?.preparation?.cancel()
            handle?.regionCheck?.cancel()
            browser?.settings?.blockNetworkLoads = true
            browser?.stopLoading(); browser?.onPause(); browser?.pauseTimers(); browser?.destroy()
            if (profileConfigured) {
                ServiceWorkerController.getInstance().serviceWorkerWebSettings.blockNetworkLoads = true
                ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                    override fun shouldInterceptRequest(resource: WebResourceRequest) = blocked()
                })
            }
            view = null
        } catch (_: Throwable) { destroyed = false }
        fun unconfirmed() {
            observed(AbemaApprovalObservation.CLEANUP_UNCONFIRMED)
            poisoned.set(true)
            synchronized(retained) { retained.addAll(listOfNotNull(handle, browser, reset)) }
            finishDirect()
        }
        if (!destroyed) { unconfirmed(); return }
        val clearDeadline = Runnable { if (!finishedAfterCleanup) unconfirmed() }
        main.postDelayed(clearDeadline, 5000)
        proxy.clear {
            cleanup.execute {
                val resetConfirmed = runCatching { reset?.awaitCleanup() != false }.getOrDefault(false)
                val routeConfirmed = runCatching { handle?.closeConfirmed() != false }.getOrDefault(false)
                val confirmed = resetConfirmed && routeConfirmed
                main.post {
                    if (finishedAfterCleanup) return@post
                    main.removeCallbacks(clearDeadline)
                    if (!confirmed || poisoned.get()) { unconfirmed(); return@post }
                    observed(AbemaApprovalObservation.CLEANUP_CONFIRMED)
                    occupied.set(false)
                    val returning = returnRequested && loaded && request.current(SystemClock.elapsedRealtime()) &&
                        (!request.automaticReturn || automaticReturn.deliveryAllowed(confirmed))
                    val nonce = request.emailNonce
                    val candidate = if (request.automaticReturn) automaticCandidate else emailCandidate
                    automaticCandidate = null
                    emailCandidate = null
                    if (returning && nonce != null && candidate != null) {
                        status("Returning the protected ABEMA account connection…")
                        AndroidAbemaEmailGrantHandoffClient(applicationContext).deliver(nonce, request.instanceId,
                            request.ownerProof, request.deadlineElapsedMs, candidate) { delivered ->
                            if (delivered && (!request.automaticReturn || !finishedAfterCleanup &&
                                request.current(SystemClock.elapsedRealtime()) && automaticReturn.deliveryAllowed(confirmed))) successful = true
                            finishDirect()
                        }
                    } else {
                        if (returning && nonce == null) successful = true
                        finishDirect()
                    }
                }
            }
        }
    }
    private fun finishDirect() {
        if (finishedAfterCleanup) return
        automaticReturn.cancel(); automaticCandidate = null; emailCandidate = null
        trustedEmailPage = false; finishedAfterCleanup = true
        completed(successful)
    }
    private fun observed(value: AbemaApprovalObservation) { runCatching { observation(value) } }
}
