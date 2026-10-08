package net.fstab.tachiai.platform.web

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Message
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.net.URI
import net.fstab.tachiai.provider.BrowserRequest
import net.fstab.tachiai.provider.BrowserCommand
import net.fstab.tachiai.provider.BrowserIdentity
import net.fstab.tachiai.provider.ProviderAdapter

@Composable
fun rememberBrowserPaneController(adapter: ProviderAdapter): BrowserPaneController =
    remember(adapter) { BrowserPaneController(adapter) }

@Composable
fun BrowserPane(
    adapter: ProviderAdapter,
    request: BrowserRequest,
    controller: BrowserPaneController,
    initiallyMuted: Boolean,
    browserIdentityOverride: BrowserIdentity? = null,
    modifier: Modifier = Modifier,
) {
    val initialUri = remember(request.url) { request.url.toJavaUri() }
    require(initialUri != null && adapter.isTopLevelNavigationAllowed(initialUri)) {
        "Provider browser requests must begin on an allowlisted top-level URL."
    }
    var generation by remember(adapter.id) { mutableIntStateOf(0) }
    DisposableEffect(controller) {
        controller.setRecreate { generation += 1 }
        onDispose { controller.setRecreate(null) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val popups = remember(controller) { RestrictedBrowserPopupHost() }
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> { controller.resume(); popups.resume() }
                Lifecycle.Event.ON_PAUSE -> { controller.pause(); popups.pause() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.pause()
            popups.close()
        }
    }

    val browserIdentity = browserIdentityFor(request, browserIdentityOverride)
    key(adapter, request.url, generation, browserIdentity, request.useWideViewport, request.loadWithOverviewMode,
        request.debugUseDefaultScriptDialogs) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                val assetLoader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                    .build()

                WebView(context).apply webView@{
                    WebView.setWebContentsDebuggingEnabled(false)
                    configureSecureSettings()
                    settings.useWideViewPort = request.useWideViewport
                    settings.loadWithOverviewMode = request.loadWithOverviewMode
                    settings.setSupportMultipleWindows(request.popupHttpsHosts.isNotEmpty())
                    settings.userAgentString = userAgentFor(browserIdentity, settings.userAgentString)
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(this@webView, request.acceptsThirdPartyCookies)
                    }
                    recordNativeSettings(BrowserSettingsRole.MAIN, request.logNativePageEvents)
                    val diagnostics = BrowserPageDiagnostics(request.logNativePageEvents)
                    diagnostics.recordHostPolicy(request.nativeProbeRole, suppressScriptDialogs(request))
                    addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                        diagnostics.recordGeometry(right - left, bottom - top)
                    }
                    webViewClient = TachiaiWebViewClient(
                        adapter = adapter,
                        assetLoader = assetLoader,
                        servePackagedAssets = request.isPackagedAsset,
                        packagedChildPaths = request.packagedChildPaths,
                        initialUrl = request.url,
                        requestedUri = initialUri,
                        allowsExternalNavigation = request.allowsExternalNavigation,
                        diagnostics = diagnostics,
                        onReady = {
                            controller.pageReady()
                            if (initiallyMuted) controller.execute(BrowserCommand.MUTE)
                        },
                        onFailure = controller::pageFailed,
                        onLoading = controller::pageLoading,
                        onRendererGone = {
                            popups.close()
                            controller.detach(this)
                            controller.rendererGone()
                            generation += 1
                        },
                    )
                    webChromeClient = TachiaiWebChromeClient(adapter, request, popups, diagnostics, controller::recordPopupEvent)
                    controller.attach(this, initialUri)
                    loadUrl(request.url)
                }
            },
            onRelease = { webView ->
                popups.close()
                controller.detach(webView)
                webView.stopLoading()
                webView.webChromeClient = null
                webView.destroy()
            },
        )
    }
}

internal fun browserIdentityFor(
    request: BrowserRequest,
    override: BrowserIdentity?,
): BrowserIdentity = override ?: request.browserIdentity

@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
internal fun WebView.configureSecureSettings() {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.mediaPlaybackRequiresUserGesture = true
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.allowFileAccessFromFileURLs = false
    settings.allowUniversalAccessFromFileURLs = false
    settings.setSupportMultipleWindows(false)
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.safeBrowsingEnabled = true
}

@SuppressLint("MissingOnRenderProcessGone")
private class TachiaiWebViewClient(
    private val adapter: ProviderAdapter,
    private val assetLoader: WebViewAssetLoader,
    private val servePackagedAssets: Boolean,
    private val packagedChildPaths: Set<String>,
    private val initialUrl: String,
    private val requestedUri: URI,
    private val allowsExternalNavigation: Boolean,
    private val diagnostics: BrowserPageDiagnostics,
    private val onReady: () -> Unit,
    private val onFailure: (String) -> Unit,
    private val onLoading: () -> Unit,
    private val onRendererGone: () -> Unit,
) : WebViewClient() {
    private var mainFrameFailed = false
    private val reportedSubresourceFailures = NativeDiagnosticBudget<Pair<PageEventKind, Int>>(8)

    private fun recordSubresourceFailure(kind: PageEventKind, code: Int) {
        if (reportedSubresourceFailures.admit(kind to code)) {
            diagnostics.record(kind, code)
        }
        if (reportedSubresourceFailures.takeLimitMarker()) diagnostics.record(PageEventKind.SUBRESOURCE_LIMIT_REACHED)
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url.toJavaUri() ?: return null
        if (PackagedAssetPolicy.isAssetHost(uri)) {
            if (request.method == "GET" &&
                PackagedAssetPolicy.allows(uri, servePackagedAssets, packagedChildPaths)
            ) {
                assetLoader.shouldInterceptRequest(request.url)?.let { return it }
            }
            return WebResourceResponse(
                "text/plain", "UTF-8", 404, "Not Found", emptyMap(),
                ByteArrayInputStream(byteArrayOf()),
            )
        }
        return super.shouldInterceptRequest(view, request)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        val uri = request.url.toJavaUri() ?: run {
            diagnostics.record(PageEventKind.NAVIGATION_BLOCKED)
            return true
        }
        if (adapter.isTopLevelNavigationAllowed(uri)) return false
        if (!allowsExternalNavigation) {
            diagnostics.record(PageEventKind.NAVIGATION_BLOCKED)
            onFailure("blocked navigation")
            return true
        }
        if (request.url.scheme == "https") {
            runCatching {
                view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
            }
        }
        return true
    }

    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
        diagnostics.record(PageEventKind.STARTED)
        onLoading()
        val uri = url.toJavaUri()
        if (uri == null || !adapter.isTopLevelNavigationAllowed(uri)) {
            diagnostics.record(PageEventKind.NAVIGATION_BLOCKED)
            view.stopLoading()
            onFailure("blocked top-level navigation")
            if (view.canGoBack()) view.goBack() else view.loadUrl(initialUrl)
        } else {
            mainFrameFailed = false
        }
    }

    override fun onPageCommitVisible(view: WebView, url: String) {
        val uri = url.toJavaUri()
        diagnostics.record(if (uri != null && adapter.isTopLevelNavigationAllowed(uri))
            PageEventKind.COMMIT_VISIBLE else PageEventKind.NAVIGATION_BLOCKED)
    }

    override fun onPageFinished(view: WebView, url: String) {
        diagnostics.record(PageEventKind.FINISHED)
        val uri = url.toJavaUri()
        if (!mainFrameFailed && uri != null && adapter.isTopLevelNavigationAllowed(uri)) {
            adapter.focusScriptFor(requestedUri, uri)?.let { script ->
                view.evaluateJavascript(script, null)
            }
            onReady()
        } else if (!mainFrameFailed) {
            diagnostics.record(PageEventKind.NAVIGATION_BLOCKED)
            onFailure("blocked top-level navigation")
        }
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (request.isForMainFrame) {
            diagnostics.record(PageEventKind.NETWORK_ERROR, error.errorCode)
            mainFrameFailed = true
            onFailure("network error ${error.errorCode}")
        } else recordSubresourceFailure(PageEventKind.SUBRESOURCE_NETWORK_ERROR, error.errorCode)
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        if (request.isForMainFrame && errorResponse.statusCode >= 400) {
            diagnostics.record(PageEventKind.HTTP_ERROR, errorResponse.statusCode)
            mainFrameFailed = true
            onFailure("HTTP ${errorResponse.statusCode}")
        } else if (!request.isForMainFrame && errorResponse.statusCode >= 400) {
            recordSubresourceFailure(PageEventKind.SUBRESOURCE_HTTP_ERROR, errorResponse.statusCode)
        }
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
        handler.cancel()
        // Android does not identify whether this is the main document or a resource.
        diagnostics.record(PageEventKind.TLS_RESOURCE_ERROR)
        onFailure("TLS resource error")
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        diagnostics.record(PageEventKind.RENDERER_GONE)
        onRendererGone()
        return true
    }
}

private class TachiaiWebChromeClient(
    private val adapter: ProviderAdapter,
    private val request: BrowserRequest,
    private val popups: RestrictedBrowserPopupHost,
    private val diagnostics: BrowserPageDiagnostics,
    private val onPopupEvent: (PopupDiagnosticEvent) -> Unit,
) : WebChromeClient() {
    private val reportedConsoleLevels = NativeDiagnosticBudget<android.webkit.ConsoleMessage.MessageLevel>(2)

    override fun onPermissionRequest(request: PermissionRequest) {
        val origin = request.origin.toJavaUri() ?: run {
            diagnostics.record(PageEventKind.PERMISSION_DENIED)
            request.deny()
            return
        }
        val granted = MediaPermissionPolicy.grantProtectedMediaOnly(
            adapter = adapter,
            origin = origin,
            requestedResources = request.resources,
            protectedMediaResource = PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID,
        )
        if (granted.isEmpty()) {
            diagnostics.record(PageEventKind.PERMISSION_DENIED)
            request.deny()
        } else {
            diagnostics.record(PageEventKind.PERMISSION_GRANTED)
            request.grant(granted)
        }
    }

    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message,
    ): Boolean {
        diagnostics.record(PageEventKind.WINDOW_REQUESTED)
        val current = view.url?.toJavaUri()
        if (current == null || !adapter.isTopLevelNavigationAllowed(current) || request.popupHttpsHosts.isEmpty()) {
            diagnostics.record(PageEventKind.WINDOW_DENIED_BY_POLICY)
            return false
        }
        return popups.open(view, request.popupHttpsHosts, request.popupDiagnosticAlternateHttpsHosts,
            isUserGesture, resultMsg, request.logNativePageEvents, adapter::diagnosticResourceCategory, onPopupEvent)
    }

    // Default JS dialogs use a separate window which does not inherit FLAG_SECURE.
    override fun onJsAlert(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean {
        if (!suppressScriptDialogs(request)) {
            diagnostics.record(PageEventKind.SCRIPT_ALERT_DELEGATED)
            return super.onJsAlert(view, url, message, result)
        }
        diagnostics.record(PageEventKind.SCRIPT_ALERT_CANCELLED)
        result.cancel()
        return true
    }

    override fun onJsConfirm(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean {
        if (!suppressScriptDialogs(request)) {
            diagnostics.record(PageEventKind.SCRIPT_CONFIRM_DELEGATED)
            return super.onJsConfirm(view, url, message, result)
        }
        diagnostics.record(PageEventKind.SCRIPT_CONFIRM_CANCELLED)
        result.cancel()
        return true
    }

    override fun onJsPrompt(view: WebView, url: String, message: String, defaultValue: String, result: android.webkit.JsPromptResult): Boolean {
        if (!suppressScriptDialogs(request)) {
            diagnostics.record(PageEventKind.SCRIPT_PROMPT_DELEGATED)
            return super.onJsPrompt(view, url, message, defaultValue, result)
        }
        diagnostics.record(PageEventKind.SCRIPT_PROMPT_CANCELLED)
        result.cancel()
        return true
    }

    override fun onJsBeforeUnload(view: WebView, url: String, message: String, result: android.webkit.JsResult): Boolean {
        if (!suppressScriptDialogs(request)) {
            diagnostics.record(PageEventKind.SCRIPT_BEFORE_UNLOAD_DELEGATED)
            return super.onJsBeforeUnload(view, url, message, result)
        }
        diagnostics.record(PageEventKind.SCRIPT_BEFORE_UNLOAD_CANCELLED)
        result.cancel()
        return true
    }

    override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
        if (!request.suppressScriptDialogsAndConsole) return super.onConsoleMessage(message)
        // Inspect only severity, never message/source/line. At most one marker
        // per severity per WebView avoids flooding away lifecycle evidence.
        val level = message.messageLevel()
        val event = when (level) {
            android.webkit.ConsoleMessage.MessageLevel.ERROR -> PageEventKind.CONSOLE_ERROR_SUPPRESSED
            android.webkit.ConsoleMessage.MessageLevel.WARNING -> PageEventKind.CONSOLE_WARNING_SUPPRESSED
            else -> null
        }
        if (event != null && reportedConsoleLevels.admit(level)) diagnostics.record(event)
        return true
    }
}

private fun Uri.toJavaUri(): URI? = runCatching { URI(toString()) }.getOrNull()

private fun String.toJavaUri(): URI? = runCatching { URI(this) }.getOrNull()
