package net.fstab.tachiai.provider.abema.account

import android.app.Application
import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.*
import android.os.Process
import android.view.View
import android.webkit.ServiceWorkerController
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.feature.connections.AbemaApprovalBrowserRuntime
import net.fstab.tachiai.feature.connections.AbemaApprovalObservation
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

// Opt-in feasibility only. No Activity, persistent work, grant adoption or
// scheduling. An application-owned binding can outlive the caller's UI pause.
class AbemaRenewalProbeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val observations = ConcurrentHashMap.newKeySet<AbemaApprovalObservation>()
    private var runtime: AbemaApprovalBrowserRuntime? = null
    private var request: AbemaApprovalBrowserRequest? = null
    private var reply: Messenger? = null
    private var sender: IBinder? = null
    @Volatile private var cancelled = false
    private var ended = false
    private var started = false
    private var timedOut = false
    private val isolationAttempted = AtomicBoolean()
    private val isolationConfirmed = AtomicBoolean()
    private val death = IBinder.DeathRecipient { main.post { cancel() } }

    private fun enabled() = Build.VERSION.SDK_INT >= 28 && !BuildConfig.LOCAL_INSPECTION &&
        applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
        applicationInfo.flags and ApplicationInfo.FLAG_TEST_ONLY == 0 &&
        Application.getProcessName() == "$packageName:abema_approval_browser"

    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (!enabled() || message.sendingUid != Process.myUid()) return
            if (message.what == AbemaRenewalProbeProtocol.CANCEL) {
                if (message.data.keySet() == setOf("nonce") && message.data.getString("nonce") == request?.emailNonce) cancel()
                return
            }
            if (message.what != AbemaRenewalProbeProtocol.START || started) return
            val response = message.replyTo ?: return
            @Suppress("DEPRECATION")
            val parsed = AbemaRenewalProbeProtocol.decode(message.data.keySet().associateWith { message.data.get(it) },
                message.sendingUid, Process.myUid(), SystemClock.elapsedRealtime()) ?: return
            started = true; request = parsed; reply = response
            try { response.binder.linkToDeath(death, 0); sender = response.binder }
            catch (_: Exception) { complete(false); return }
            runtime = AbemaApprovalBrowserRuntime(this@AbemaRenewalProbeService, parsed,
                operationCurrent = { !cancelled && !ended && sender?.isBinderAlive == true },
                status = {}, onBrowser = { browser ->
                    check(!browser.isAttachedToWindow && browser.windowToken == null)
                    val metrics = resources.displayMetrics
                    val width = metrics.widthPixels.coerceIn(1, 4096)
                    val height = metrics.heightPixels.coerceIn(1, 4096)
                    browser.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    browser.layout(0, 0, width, height)
                }, completed = ::complete, observation = { value ->
                    observations.add(value)
                    if (value == AbemaApprovalObservation.PAGE_STARTED && isolationAttempted.compareAndSet(false, true)) {
                        if (Looper.myLooper() == Looper.getMainLooper()) checkRejectedHostIsolation(parsed)
                        else main.post { checkRejectedHostIsolation(parsed) }
                    }
                })
            main.postDelayed({ timedOut = true; cancel() }, parsed.deadlineElapsedMs - SystemClock.elapsedRealtime())
            runtime!!.start()
            runtime?.resume()
        }
    })

    override fun onBind(intent: Intent): IBinder? = if (enabled()) messenger.binder else null
    override fun onUnbind(intent: Intent?): Boolean { cancel(); return false }
    override fun onDestroy() { cancel(); super.onDestroy() }

    // Exercise the actual process-wide claim and lifecycle guards while the
    // first host owns a running browser. The rejected host must never create a
    // WebView or pause the owner's process-global service-worker networking.
    private fun checkRejectedHostIsolation(parsed: AbemaApprovalBrowserRequest) {
        isolationConfirmed.set(false)
        if (cancelled || ended || runtime == null || sender?.isBinderAlive != true ||
            !parsed.current(SystemClock.elapsedRealtime())) return
        val unexpectedBrowser = AtomicBoolean()
        val rejectedResult = AtomicReference<Boolean?>()
        var rejected: AbemaApprovalBrowserRuntime? = null
        try {
            val settings = ServiceWorkerController.getInstance().serviceWorkerWebSettings
            if (settings.blockNetworkLoads) return
            rejected = AbemaApprovalBrowserRuntime(this, parsed,
                operationCurrent = { !cancelled && !ended && parsed.current(SystemClock.elapsedRealtime()) },
                status = {}, onBrowser = {
                    unexpectedBrowser.set(true)
                    error("Rejected probe host must not create a browser")
                },
                completed = { rejectedResult.set(it) })
            rejected.start()
            rejected.resume()
            rejected.pause()
            isolationConfirmed.set(rejectedResult.get() == false && !unexpectedBrowser.get() && !settings.blockNetworkLoads)
        } catch (_: Throwable) {
            // Runtime observers intentionally contain their exceptions. Keep an
            // explicit failed observation for the ordinary-process assertion.
            isolationConfirmed.set(false)
        } finally {
            rejected?.close(false)
        }
    }

    private fun cancel() {
        cancelled = true
        if (runtime == null) complete(false) else runtime?.close(false)
    }
    private fun complete(delivered: Boolean) {
        if (ended) return
        ended = true; main.removeCallbacksAndMessages(null)
        sender?.let { runCatching { it.unlinkToDeath(death, 0) } }; sender = null
        val result = when {
            AbemaApprovalObservation.CLEANUP_UNCONFIRMED in observations -> AbemaRenewalProbeResult.CLEANUP_UNCONFIRMED
            delivered -> AbemaRenewalProbeResult.DELIVERED
            timedOut -> AbemaRenewalProbeResult.DEADLINE
            cancelled -> AbemaRenewalProbeResult.CANCELLED
            AbemaApprovalObservation.ROUTE_APPLIED !in observations -> AbemaRenewalProbeResult.REFUSED
            else -> AbemaRenewalProbeResult.NO_CANDIDATE
        }
        val nonce = request?.emailNonce
        try { reply?.send(Message.obtain().apply {
            what = AbemaRenewalProbeProtocol.RESULT
            data = Bundle().apply {
                putString("nonce", nonce); putString("result", result.name)
                putStringArrayList("observations", ArrayList(observations.map { it.name }.sorted()))
                putBoolean("isolationConfirmed", isolationConfirmed.get())
            }
        }) } catch (_: Exception) { }
        reply = null; request = null; runtime = null
    }
}
