package net.fstab.tachiai.provider.abema.account

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.*
import android.os.Process
import android.view.View
import net.fstab.tachiai.feature.connections.AbemaApprovalBrowserRuntime
import net.fstab.tachiai.feature.connections.AbemaApprovalObservation

// Application-owned, unattached browser. This service never starts an Activity,
// adopts a grant, resets the remembered profile or collects user credentials.
class AbemaRenewalBrowserService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var runtime: AbemaApprovalBrowserRuntime? = null
    private var request: AbemaApprovalBrowserRequest? = null
    private var reply: Messenger? = null
    private var sender: IBinder? = null
    @Volatile private var cancelled = false
    @Volatile private var ended = false
    private var started = false
    private var timedOut = false
    private var cleanupUnconfirmed = false
    private val death = IBinder.DeathRecipient { main.post { cancel() } }
    private fun enabled() = Build.VERSION.SDK_INT >= 28 &&
        Application.getProcessName() == "$packageName:abema_approval_browser"

    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (!enabled() || message.sendingUid != Process.myUid()) return
            val fields = runCatching { message.data.fields() }.getOrNull() ?: return
            if (message.what == AbemaRenewalBrowserProtocol.CANCEL) {
                val captured = request ?: return
                if (AbemaRenewalBrowserProtocol.matches(fields, message.sendingUid, Process.myUid(), captured)) cancel()
                return
            }
            if (message.what != AbemaRenewalBrowserProtocol.START || started) return
            val response = message.replyTo ?: return
            val parsed = AbemaRenewalBrowserProtocol.decodeRequest(fields,
                message.sendingUid, Process.myUid(), SystemClock.elapsedRealtime()) ?: return
            started = true; request = parsed; reply = response
            try { response.binder.linkToDeath(death, 0); sender = response.binder }
            catch (_: Exception) { complete(false); return }
            runtime = AbemaApprovalBrowserRuntime(this@AbemaRenewalBrowserService, parsed,
                operationCurrent = { !cancelled && !ended && sender?.isBinderAlive == true &&
                    parsed.current(SystemClock.elapsedRealtime()) },
                status = {}, onBrowser = { browser ->
                    check(!browser.isAttachedToWindow && browser.windowToken == null)
                    val metrics = resources.displayMetrics
                    val width = metrics.widthPixels.coerceIn(1, 4096)
                    val height = metrics.heightPixels.coerceIn(1, 4096)
                    browser.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    browser.layout(0, 0, width, height)
                }, completed = ::complete, observation = {
                    if (it == AbemaApprovalObservation.CLEANUP_UNCONFIRMED) cleanupUnconfirmed = true
                })
            main.postDelayed({ timedOut = true; cancel() }, maxOf(0L, parsed.deadlineElapsedMs - SystemClock.elapsedRealtime()))
            runtime!!.start()
            runtime?.resume()
        }
    })

    override fun onBind(intent: Intent): IBinder? = if (enabled()) messenger.binder else null
    override fun onUnbind(intent: Intent?): Boolean { cancel(); return false }
    override fun onDestroy() { cancel(); super.onDestroy() }

    private fun cancel() {
        if (ended) return
        cancelled = true
        if (runtime == null) complete(false) else runtime?.close(false)
    }

    private fun complete(delivered: Boolean) {
        if (ended) return
        ended = true; main.removeCallbacksAndMessages(null)
        sender?.let { runCatching { it.unlinkToDeath(death, 0) } }; sender = null
        val result = when {
            cleanupUnconfirmed -> AbemaRenewalBrowserResult.CLEANUP_UNCONFIRMED
            timedOut -> AbemaRenewalBrowserResult.TEMPORARY_FAILURE
            cancelled -> AbemaRenewalBrowserResult.CANCELLED
            delivered -> AbemaRenewalBrowserResult.DELIVERED
            else -> AbemaRenewalBrowserResult.TEMPORARY_FAILURE
        }
        val captured = request
        try {
            if (captured != null) reply?.send(Message.obtain().apply {
                what = AbemaRenewalBrowserProtocol.RESULT
                data = captured.envelope().apply { putString("result", result.name) }
            })
        } catch (_: Exception) { }
        reply = null; request = null; runtime = null
    }
}
