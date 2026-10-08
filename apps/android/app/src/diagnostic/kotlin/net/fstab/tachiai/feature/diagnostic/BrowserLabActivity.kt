package net.fstab.tachiai.feature.diagnostic

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.net.URI
import net.fstab.tachiai.platform.web.configureSecureSettings

// Compiled/registered only in the separate diagnostic APK, never debug/release.
class BrowserLabActivity : ComponentActivity() {
    @SuppressLint("MissingOnRenderProcessGone")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Deliberately no FLAG_SECURE: screenshots of this fixture are allowed.
        WebView.setWebContentsDebuggingEnabled(true)
        Log.d("TachiaiLab", "mode=FIXTURE_ONLY inspection=true screenshots=true")
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                var short by remember { mutableStateOf(false) }
                var measured by remember { mutableStateOf("Browser measurement pending") }
                val browser = remember {
                    val assets = WebViewAssetLoader.Builder()
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
                        .build()
                    WebView(this).apply {
                        configureSecureSettings()
                        CookieManager.getInstance().setAcceptCookie(false)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        // Default dialogs are intentional here, not cancelled by Tachiai.
                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                !runCatching { BrowserLabPolicy.allowsNavigation(URI(request.url.toString())) }.getOrDefault(false)

                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                                val allowed = runCatching {
                                    BrowserLabPolicy.allowsResource(URI(request.url.toString()), request.method)
                                }.getOrDefault(false)
                                if (allowed) assets.shouldInterceptRequest(request.url)?.let { return it }
                                return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(),
                                    ByteArrayInputStream(byteArrayOf()))
                            }

                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                view.destroy()
                                finish()
                                return true
                            }
                        }
                        addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                            measured = "Native browser: ${right - left} × ${bottom - top} px"
                            Log.d("TachiaiLab", "width=${right - left} height=${bottom - top}")
                        }
                        loadUrl(BROWSER_LAB_URL)
                    }
                }
                DisposableEffect(browser) {
                    onDispose {
                        browser.stopLoading()
                        browser.webChromeClient = null
                        browser.destroy()
                    }
                }
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Text("INSPECTABLE BROWSER LAB · fixture only · no accounts")
                    Text(measured)
                    Row {
                        Button(onClick = { short = !short }) { Text(if (short) "Full height" else "Short height") }
                        Button(onClick = { browser.reload() }) { Text("Reload fixture") }
                    }
                    AndroidView(factory = { browser }, modifier =
                        if (short) Modifier.fillMaxWidth().height(100.dp) else Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}
