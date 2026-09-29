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
import java.net.URI
import net.fstab.tachiai.provider.BrowserRequest
import net.fstab.tachiai.provider.BrowserCommand
import net.fstab.tachiai.provider.BrowserIdentity
import net.fstab.tachiai.provider.ProviderAdapter

@Composable
fun rememberBrowserPaneController(adapter: ProviderAdapter): BrowserPaneController =
    remember(adapter.id) { BrowserPaneController(adapter) }

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
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.resume()
                Lifecycle.Event.ON_PAUSE -> controller.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.pause()
        }
    }

    val browserIdentity = browserIdentityFor(request, browserIdentityOverride)
    key(generation, browserIdentity) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                val assetLoader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                    .build()

                WebView(context).apply webView@{
                    WebView.setWebContentsDebuggingEnabled(false)
                    configureSecureSettings()
                    settings.userAgentString = userAgentFor(browserIdentity, settings.userAgentString)
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(this@webView, request.acceptsThirdPartyCookies)
                    }
                    webViewClient = TachiaiWebViewClient(
                        adapter = adapter,
                        assetLoader = assetLoader,
                        servePackagedAssets = request.isPackagedAsset,
                        initialUrl = request.url,
                        requestedUri = initialUri,
                        onReady = {
                            controller.pageReady()
                            if (initiallyMuted) controller.execute(BrowserCommand.MUTE)
                        },
                        onFailure = controller::pageFailed,
                        onRendererGone = {
                            controller.detach(this)
                            controller.rendererGone()
                            generation += 1
                        },
                    )
                    webChromeClient = TachiaiWebChromeClient(adapter)
                    controller.attach(this, initialUri)
                    loadUrl(request.url)
                }
            },
            onRelease = { webView ->
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
private fun WebView.configureSecureSettings() {
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
    private val initialUrl: String,
    private val requestedUri: URI,
    private val onReady: () -> Unit,
    private val onFailure: (String) -> Unit,
    private val onRendererGone: () -> Unit,
) : WebViewClient() {
    private var mainFrameFailed = false

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        if (servePackagedAssets) {
            assetLoader.shouldInterceptRequest(request.url) ?: super.shouldInterceptRequest(view, request)
        } else {
            super.shouldInterceptRequest(view, request)
        }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        val uri = request.url.toJavaUri() ?: return true
        if (adapter.isTopLevelNavigationAllowed(uri)) return false
        if (request.url.scheme == "https") {
            runCatching {
                view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
            }
        }
        return true
    }

    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
        val uri = url.toJavaUri()
        if (uri == null || !adapter.isTopLevelNavigationAllowed(uri)) {
            view.stopLoading()
            onFailure("blocked top-level navigation")
            if (view.canGoBack()) view.goBack() else view.loadUrl(initialUrl)
        } else {
            mainFrameFailed = false
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        val uri = url.toJavaUri()
        if (!mainFrameFailed && uri != null && adapter.isTopLevelNavigationAllowed(uri)) {
            adapter.focusScriptFor(requestedUri, uri)?.let { script ->
                view.evaluateJavascript(script, null)
            }
            onReady()
        } else if (!mainFrameFailed) {
            onFailure("blocked top-level navigation")
        }
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (request.isForMainFrame) {
            mainFrameFailed = true
            onFailure("network error ${error.errorCode}")
        }
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        if (request.isForMainFrame && errorResponse.statusCode >= 400) {
            mainFrameFailed = true
            onFailure("HTTP ${errorResponse.statusCode}")
        }
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
        handler.cancel()
        onFailure("TLS resource error")
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onRendererGone()
        return true
    }
}

private class TachiaiWebChromeClient(
    private val adapter: ProviderAdapter,
) : WebChromeClient() {
    override fun onPermissionRequest(request: PermissionRequest) {
        val origin = request.origin.toJavaUri() ?: run {
            request.deny()
            return
        }
        val granted = MediaPermissionPolicy.grantProtectedMediaOnly(
            adapter = adapter,
            origin = origin,
            requestedResources = request.resources,
            protectedMediaResource = PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID,
        )
        if (granted.isEmpty()) request.deny() else request.grant(granted)
    }

    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message,
    ): Boolean = false
}

private fun Uri.toJavaUri(): URI? = runCatching { URI(toString()) }.getOrNull()

private fun String.toJavaUri(): URI? = runCatching { URI(this) }.getOrNull()
