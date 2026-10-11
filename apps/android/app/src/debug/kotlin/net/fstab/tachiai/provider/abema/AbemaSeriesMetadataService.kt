package net.fstab.tachiai.provider.abema

import android.app.Service
import android.content.Intent
import android.os.*
import android.os.Process
import net.fstab.tachiai.BuildConfig

// Same-UID, nonexported, no work on bind. The existing process coordinator
// exclusively owns routes, runtime admission and uncertain-cleanup retention.
class AbemaSeriesMetadataService : Service() {
    private var runtime: AbemaGuestRuntimeCoordinator? = null
    private class Active(val start: AbemaSeriesMetadataIpc.Start, val reply: Messenger,
        val lease: AbemaEpisodeRequestLease, val death: IBinder.DeathRecipient)
    private var active: Active? = null
    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.sendingUid != Process.myUid()) return
            val fields = runCatching { AbemaSeriesMetadataIpc.fields(message.data) }.getOrNull() ?: return
            when (message.what) {
                AbemaSeriesMetadataIpc.START -> {
                    val request = AbemaSeriesMetadataIpc.decodeStart(fields, SystemClock.elapsedRealtime()) ?: return
                    start(request, message.replyTo ?: return)
                }
                AbemaSeriesMetadataIpc.CANCEL, AbemaSeriesMetadataIpc.PULSE -> {
                    val nonce = AbemaEpisodeMetadataIpc.decodeNonce(fields) ?: return
                    val owner = active?.takeIf { it.start.nonce == nonce && it.reply.binder == message.replyTo?.binder } ?: return
                    if (message.what == AbemaSeriesMetadataIpc.CANCEL || !owner.lease.pulse(nonce)) {
                        owner.lease.cancel(nonce); runtime?.cancel(nonce)
                    }
                }
            }
        }
    }
    private val messenger = Messenger(handler)
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 28) runtime = AbemaGuestRuntimeProcess.get(this)
    }
    override fun onBind(intent: Intent): IBinder? = if (runtime != null) messenger.binder else null
    private fun start(request: AbemaSeriesMetadataIpc.Start, reply: Messenger) {
        val coordinator = runtime ?: return
        if (active != null || coordinator.unavailable) { send(reply, AbemaSeriesMetadataIpc.Result(request.nonce, "TEMPORARY"), request.page.seriesId); return }
        val lease = AbemaEpisodeRequestLease(request.nonce, request.deadline, SystemClock::elapsedRealtime)
        val death = IBinder.DeathRecipient { lease.cancel(request.nonce); handler.post {
            if (active?.start === request) coordinator.cancel(request.nonce)
        } }
        try { reply.binder.linkToDeath(death, 0) } catch (_: Exception) { return }
        val owner = Active(request, reply, lease, death); active = owner
        val accepted = coordinator.start(AbemaGuestRuntimeRequest(request.instanceId, AbemaGuestBootstrapOperation.FETCH_SERIES,
            nonce = request.nonce, expectedOwnerProof = request.ownerProof, deadlineElapsedMs = request.deadline,
            seriesRequest = request.page, canRemainActive = lease::current)) completed@{ result ->
            if (active !== owner) return@completed
            active = null; runCatching { reply.binder.unlinkToDeath(death, 0) }
            val outcome = when (result.category) {
                "EXACT_SERIES_PAGE" -> if (result.seriesPage != null) "OK" else "TEMPORARY"
                "GUEST_SERIES_HTTP_404", "GUEST_SERIES_HTTP_410" -> "NOT_FOUND"
                "GUEST_SERIES_HTTP_429" -> "RATE_LIMITED"
                else -> "TEMPORARY"
            }
            send(reply, AbemaSeriesMetadataIpc.Result(request.nonce, outcome, result.seriesPage.takeIf { outcome == "OK" }), request.page.seriesId)
        }
        if (!accepted) {
            active = null; lease.cancel(request.nonce); runCatching { reply.binder.unlinkToDeath(death, 0) }
            send(reply, AbemaSeriesMetadataIpc.Result(request.nonce, "TEMPORARY"), request.page.seriesId)
        }
    }
    private fun send(reply: Messenger, result: AbemaSeriesMetadataIpc.Result, parent: String) {
        try { reply.send(Message.obtain().apply { what = AbemaSeriesMetadataIpc.RESULT; data = AbemaSeriesMetadataIpc.result(result, parent) }) }
        catch (_: Exception) { /* No response/identity/error text logging. */ }
    }
    private fun cancel() { active?.let { it.lease.cancel(it.start.nonce); runtime?.cancel(it.start.nonce) } }
    override fun onUnbind(intent: Intent): Boolean { cancel(); return false }
    override fun onDestroy() { cancel(); super.onDestroy() }
}
