package net.fstab.tachiai.platform.network

import android.os.Looper
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executor

// This override belongs only to the cached-player process. Both anonymous
// ABEMA helpers use the same provider route; native Twitch sockets do not use it.
// Callers destroy/cancel all helper views before clear, and await its callback
// before a subsequent run. Never use this for historical/login WebViews.
internal class AbemaWebViewRoute(
    private val backend: AbemaWebViewProxyBackend = AndroidAbemaWebViewProxyBackend,
    private val assertThread: () -> Unit = {
        check(Looper.myLooper() == Looper.getMainLooper())
    },
) {
    var busy: Boolean = false
        private set
    var currentGeneration: Long = 0
        private set
    private var overrideMayExist = false
    private var pendingInstall: ((Boolean) -> Unit)? = null
    private val clearWaiters = mutableListOf<() -> Unit>()
    private var clearing = false

    fun install(route: RouteSession, onComplete: (Boolean) -> Unit) =
        installProxy(if (route.isSystem) null else route.proxyPort, onComplete)

    // Android-free backend seam for asynchronous ordering/failure tests.
    internal fun installProxy(port: Int?, onComplete: (Boolean) -> Unit) {
        assertThread()
        if (busy || (port != null && port !in 1..65535)) { onComplete(false); return }
        if (!backend.supported) {
            onComplete(port == null && !overrideMayExist)
            return
        }
        busy = true
        val generation = ++currentGeneration
        pendingInstall = onComplete
        // Mark before dispatch: a thrown setter is not proof that no change
        // reached Chromium, so cleanup must still clear before another run.
        if (port != null) overrideMayExist = true
        try {
            val applied = {
                assertThread()
                if (currentGeneration == generation && !clearing) {
                    if (port == null) overrideMayExist = false
                    busy = false
                    val completion = pendingInstall
                    pendingInstall = null
                    completion?.invoke(true)
                }
            }
            if (port == null) backend.clear(applied) else backend.installLocalProxy(port, applied)
        } catch (_: Exception) {
            if (currentGeneration == generation && !clearing) {
                busy = false
                val completion = pendingInstall
                pendingInstall = null
                completion?.invoke(false)
            }
        }
    }

    fun clear(onComplete: () -> Unit) {
        assertThread()
        clearWaiters += onComplete
        if (clearing) return
        clearing = true
        busy = true
        val generation = ++currentGeneration
        val cancelled = pendingInstall
        pendingInstall = null
        cancelled?.invoke(false)
        fun finish() {
            assertThread()
            if (currentGeneration != generation || !clearing) return
            overrideMayExist = false
            clearing = false
            busy = false
            val callbacks = clearWaiters.toList()
            clearWaiters.clear()
            callbacks.forEach { it() }
        }
        if (!backend.supported && !overrideMayExist) { finish(); return }
        // On cleanup failure remain busy, blocking future helpers. The caller's
        // bounded lifecycle timeout reports the failure; never load directly.
        try { backend.clear(::finish) } catch (_: Exception) { }
    }
}

internal interface AbemaWebViewProxyBackend {
    val supported: Boolean
    fun installLocalProxy(port: Int, onApplied: () -> Unit)
    fun clear(onApplied: () -> Unit)
}

private object AndroidAbemaWebViewProxyBackend : AbemaWebViewProxyBackend {
    override val supported get() = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
    private val callbackExecutor = Executor { command ->
        android.os.Handler(Looper.getMainLooper()).post(command)
    }
    override fun installLocalProxy(port: Int, onApplied: () -> Unit) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            throw UnsupportedOperationException("WebView proxy override is unavailable")
        }
        val config = ProxyConfig.Builder().addProxyRule("http://127.0.0.1:$port")
            .removeImplicitRules().build()
        // Exactly one proxy; no direct alternative, destination bypass or URL
        // credentials. Ephemeral proxy auth is handled by each owned helper.
        ProxyController.getInstance().setProxyOverride(config, callbackExecutor, Runnable { onApplied() })
    }
    override fun clear(onApplied: () -> Unit) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            throw UnsupportedOperationException("WebView proxy override is unavailable")
        }
        ProxyController.getInstance().clearProxyOverride(callbackExecutor, Runnable { onApplied() })
    }
}
