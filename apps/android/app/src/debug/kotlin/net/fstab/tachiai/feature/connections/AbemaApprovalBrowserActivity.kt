package net.fstab.tachiai.feature.connections

import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.platform.web.configureSecureSettings
import net.fstab.tachiai.provider.abema.readAbemaCatalogRouteOwner
import net.fstab.tachiai.provider.abema.account.*
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// Provider-controlled pages only. This process/profile never hosts a guest
// helper, playback, native bridge or injected script and never reads cookies.
class AbemaApprovalBrowserActivity : ComponentActivity() {
    companion object {
        internal const val INSTANCE_ID = "PROVIDER_INSTANCE_ID"
        internal const val OWNER_PROOF = "OWNER_PROOF"
        internal const val VERIFICATION_URI = "VERIFICATION_URI"
        internal const val DEADLINE = "DEADLINE_ELAPSED_MS"
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
        fun closeConfirmed(): Boolean {
            if (!prepared.await(1, TimeUnit.SECONDS)) return false
            val closed = routeClose?.await() ?: (backend?.await() ?: true)
            return closed && preparation.cleanupConfirmed
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var request: AbemaApprovalBrowserRequest
    private lateinit var status: TextView
    private var view: WebView? = null
    private var owned: Owned? = null
    @Volatile private var resumed = false
    @Volatile private var ready = false
    @Volatile private var ending = false
    private var finishedAfterCleanup = false
    private var claimed = false
    private var checkPending = false
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        // A recreated Activity cannot recover the native controller's private
        // challenge. Do not reload or extend a request after process death.
        if (!BuildConfig.DEBUG || Build.VERSION.SDK_INT < 28 || savedInstanceState != null ||
            Application.getProcessName() != "$packageName:abema_approval_browser" || poisoned.get()) {
            finishDirect(); return
        }
        val parsed = runCatching { AbemaApprovalBrowserRequest(
            checkNotNull(intent.getStringExtra(INSTANCE_ID)), checkNotNull(intent.getStringExtra(OWNER_PROOF)),
            checkNotNull(intent.getStringExtra(VERIFICATION_URI)), intent.getLongExtra(DEADLINE, -1)) }.getOrNull()
        intent.removeExtra(VERIFICATION_URI)
        if (parsed == null || !parsed.current(SystemClock.elapsedRealtime()) || !occupied.compareAndSet(false, true)) {
            finishDirect(); return
        }
        claimed = true; request = parsed
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { closeBrowser(true) }
        })
        try {
            if (!profileConfigured) {
                WebView.setDataDirectorySuffix(AbemaApprovalBrowserPolicy.PROFILE_SUFFIX)
                profileConfigured = true
            }
            WebView.setWebContentsDebuggingEnabled(false)
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            status = TextView(this).apply { text = "Preparing ABEMA’s browser through the saved route…"; setPadding(16, 16, 16, 16) }
            column.addView(status)
            column.addView(Button(this).apply {
                text = "Return to Tachiai"
                setOnClickListener { closeBrowser(true) }
            })
            val browser = WebView(this)
            view = browser
            browser.configureSecureSettings()
            browser.settings.blockNetworkLoads = true
            browser.settings.saveFormData = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false)
            browser.webViewClient = client()
            browser.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage?) = true
                override fun onPermissionRequest(permission: PermissionRequest) { permission.deny() }
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
            browser.setDownloadListener { _, _, _, _, _ -> status.text = "Downloads are unavailable in account approval." }
            val serviceWorkers = ServiceWorkerController.getInstance()
            serviceWorkers.serviceWorkerWebSettings.apply {
                allowFileAccess = false; allowContentAccess = false; blockNetworkLoads = true
            }
            serviceWorkers.setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(resource: WebResourceRequest): WebResourceResponse? =
                    if (allowed(resource) && currentOnWorker()) null else blocked()
            })
            column.addView(browser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            setContentView(column)
            prepareRoute()
            watchOwner()
        } catch (_: Throwable) { closeBrowser(false) }
    }

    private fun sameOwner() = request.ownerProof == readAbemaCatalogRouteOwner(request.instanceId,
        { providerInstanceStore(applicationContext).read { legacyProviderSettings(applicationContext) } },
        { connectionProfileStore(applicationContext).selected(it) }).proof()
    private fun current() = !ending && ready && resumed && !poisoned.get() && request.current(SystemClock.elapsedRealtime())
    // WebView interception is on Chromium's worker. Protected owner reads never
    // occur in navigation, proxy-authentication or main-thread UI callbacks.
    private fun currentOnWorker(): Boolean {
        if (!current()) return false
        val matches = runCatching(::sameOwner).getOrDefault(false)
        if (!matches) main.post { closeBrowser(false) }
        return matches && current()
    }
    private fun prepareRoute() {
        val handle = Owned(); owned = handle
        main.postDelayed({ if (!ending && !ready) closeBrowser(false) }, 45000)
        workers.execute {
            var valid = false
            var savedRouteRequired = false
            try {
                if (ending || !request.current(SystemClock.elapsedRealtime())) return@execute
                val owner = readAbemaCatalogRouteOwner(request.instanceId,
                    { providerInstanceStore(applicationContext).read { legacyProviderSettings(applicationContext) } },
                    { connectionProfileStore(applicationContext).selected(it) })
                check(owner.proof() == request.ownerProof)
                if (owner.profile == null) { savedRouteRequired = true; return@execute }
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
                valid = !ending && request.current(SystemClock.elapsedRealtime()) && sameOwner()
            } catch (_: Throwable) { valid = false }
            finally {
                handle.prepared.countDown()
                main.post {
                    if (ending) return@post
                    if (savedRouteRequired) {
                        status.text = "Choose a saved connection in Providers to use routed ABEMA approval."
                        return@post
                    }
                    if (!valid) { closeBrowser(false); return@post }
                    proxy.install(checkNotNull(handle.route), onComplete = { applied ->
                        if (ending) return@install
                        if (!applied || !request.current(SystemClock.elapsedRealtime())) { closeBrowser(false); return@install }
                        ready = true
                        if (resumed) resumeBrowser()
                        status.text = "Complete approval on ABEMA’s pages, then return to Tachiai."
                    })
                }
            }
        }
    }
    private fun watchOwner() {
        if (ending) return
        if (!request.current(SystemClock.elapsedRealtime())) { closeBrowser(false); return }
        if (!checkPending) {
            checkPending = true
            workers.execute {
                val matches = runCatching(::sameOwner).getOrDefault(false)
                main.post { checkPending = false; if (!matches) closeBrowser(false) }
            }
        }
        main.postDelayed(::watchOwner, 1000)
    }
    private fun client() = object : WebViewClient() {
        override fun onPageStarted(browser: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            if (!current() || url == null || !AbemaApprovalBrowserPolicy.allowedNavigation(url)) closeBrowser(false)
        }
        override fun onPageCommitVisible(browser: WebView, url: String?) {
            if (!current() || url == null || !AbemaApprovalBrowserPolicy.allowedNavigation(url)) closeBrowser(false)
        }
        override fun shouldOverrideUrlLoading(browser: WebView, resource: WebResourceRequest): Boolean {
            val refused = !current() || !AbemaApprovalBrowserPolicy.allowedNavigation(resource.url.toString())
            if (refused && current()) status.text = "This destination is unavailable in ABEMA account approval."
            return refused
        }
        override fun shouldInterceptRequest(browser: WebView, resource: WebResourceRequest): WebResourceResponse? =
            if (allowed(resource) && currentOnWorker()) null else blocked()
        override fun onReceivedSslError(browser: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
            handler.cancel(); closeBrowser(false)
        }
        override fun onReceivedHttpAuthRequest(browser: WebView, handler: HttpAuthHandler, host: String, realm: String) {
            val route = owned?.route
            if (current() && route?.matchesProxyChallenge(host, realm) == true)
                handler.proceed(route.proxyUsername, route.proxyPassword)
            else handler.cancel()
        }
        override fun onReceivedError(browser: WebView, resource: WebResourceRequest, error: WebResourceError) {
            if (resource.isForMainFrame) status.text = "ABEMA’s page could not be loaded through this route. Return to Tachiai to retry."
        }
        override fun onReceivedHttpError(browser: WebView, resource: WebResourceRequest, response: WebResourceResponse) {
            if (resource.isForMainFrame) status.text = "ABEMA returned an error page. Return to Tachiai to retry."
        }
        override fun onRenderProcessGone(browser: WebView, detail: RenderProcessGoneDetail): Boolean {
            closeBrowser(false); return true
        }
    }
    private fun allowed(resource: WebResourceRequest) = if (resource.isForMainFrame)
        AbemaApprovalBrowserPolicy.allowedNavigation(resource.url.toString())
        else AbemaApprovalBrowserPolicy.allowedResource(resource.url.toString())
    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", 403, "Unavailable", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    private fun resumeBrowser() {
        if (!current()) return
        view?.settings?.blockNetworkLoads = false
        ServiceWorkerController.getInstance().serviceWorkerWebSettings.blockNetworkLoads = false
        view?.onResume(); view?.resumeTimers()
        if (!loaded) { loaded = true; view?.loadUrl(request.verificationUri) }
    }
    override fun onResume() {
        super.onResume(); resumed = true
        if (::request.isInitialized && ready && !ending) {
            workers.execute {
                val matches = runCatching(::sameOwner).getOrDefault(false)
                main.post { if (matches && current()) resumeBrowser() else if (!ending) closeBrowser(false) }
            }
        }
    }
    override fun onPause() {
        resumed = false
        view?.settings?.blockNetworkLoads = true
        view?.stopLoading(); view?.onPause(); view?.pauseTimers()
        if (profileConfigured) ServiceWorkerController.getInstance().serviceWorkerWebSettings.blockNetworkLoads = true
        super.onPause()
    }
    private fun closeBrowser(returnRequested: Boolean) {
        if (ending || !claimed) return
        ending = true; ready = false
        main.removeCallbacksAndMessages(null)
        val handle = owned
        val browser = view
        var destroyed = true
        try {
            handle?.preparation?.cancel()
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
            poisoned.set(true)
            synchronized(retained) { retained.addAll(listOfNotNull(handle, browser)) }
            finishDirect()
        }
        if (!destroyed) { unconfirmed(); return }
        val clearDeadline = Runnable { if (!finishedAfterCleanup) unconfirmed() }
        main.postDelayed(clearDeadline, 5000)
        proxy.clear {
            cleanup.execute {
                val confirmed = runCatching { handle?.closeConfirmed() != false }.getOrDefault(false)
                main.post {
                    if (finishedAfterCleanup) return@post
                    main.removeCallbacks(clearDeadline)
                    if (!confirmed || poisoned.get()) { unconfirmed(); return@post }
                    occupied.set(false)
                    if (returnRequested && loaded && request.current(SystemClock.elapsedRealtime())) setResult(RESULT_OK)
                    finishDirect()
                }
            }
        }
    }
    private fun finishDirect() { finishedAfterCleanup = true; super.finish() }
    override fun finish() { if (finishedAfterCleanup || !claimed) finishDirect() else closeBrowser(false) }
    override fun onDestroy() {
        if (claimed && !ending) closeBrowser(false)
        super.onDestroy()
    }
}
