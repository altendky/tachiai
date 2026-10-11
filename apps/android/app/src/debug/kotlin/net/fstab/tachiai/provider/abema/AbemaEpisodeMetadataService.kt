package net.fstab.tachiai.provider.abema

import android.app.Service
import android.content.Intent
import android.os.*
import android.os.Process
import net.fstab.tachiai.BuildConfig

// Debug-only, non-exported, same-UID service in the dedicated guest process.
// Binding configures the isolated profile; only a valid explicit START can
// prepare a route or make a request. No Activity/window/account is launched.
class AbemaEpisodeMetadataService : Service() {
    private var runtime: AbemaGuestRuntimeCoordinator? = null
    private class Active(val start: AbemaEpisodeMetadataIpc.Start, val reply: Messenger,
        val lease: AbemaEpisodeRequestLease, val death: IBinder.DeathRecipient)
    private var active: Active? = null
    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.sendingUid != Process.myUid()) return
            val fields = runCatching { AbemaEpisodeMetadataIpc.fields(message.data) }.getOrNull() ?: return
            when (message.what) {
                AbemaEpisodeMetadataIpc.START -> {
                    val request = AbemaEpisodeMetadataIpc.decodeStart(fields, SystemClock.elapsedRealtime()) ?: return
                    val reply = message.replyTo ?: return
                    start(request, reply)
                }
                AbemaEpisodeMetadataIpc.CANCEL, AbemaEpisodeMetadataIpc.PULSE -> {
                    val nonce = AbemaEpisodeMetadataIpc.decodeNonce(fields) ?: return
                    val current = active?.takeIf { it.start.nonce == nonce && it.reply.binder == message.replyTo?.binder } ?: return
                    if (message.what == AbemaEpisodeMetadataIpc.CANCEL) {
                        current.lease.cancel(nonce); runtime?.cancel(nonce)
                    } else if (!current.lease.pulse(nonce)) runtime?.cancel(nonce)
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
    private fun start(request: AbemaEpisodeMetadataIpc.Start, reply: Messenger) {
        val coordinator = runtime ?: return
        if (active != null || coordinator.unavailable) { send(reply, AbemaEpisodeMetadataIpc.Result(request.nonce, "TEMPORARY")); return }
        val lease = AbemaEpisodeRequestLease(request.nonce, request.deadline, SystemClock::elapsedRealtime)
        val death = IBinder.DeathRecipient {
            lease.cancel(request.nonce)
            handler.post { if (active?.start === request) coordinator.cancel(request.nonce) }
        }
        try { reply.binder.linkToDeath(death, 0) } catch (_: Exception) { return }
        val owner = Active(request, reply, lease, death)
        active = owner
        val accepted = coordinator.start(AbemaGuestRuntimeRequest(request.instanceId, AbemaGuestBootstrapOperation.FETCH_EPISODE,
            request.episodeId, request.nonce, request.ownerProof, request.deadline, lease::current)) completed@{ result ->
            if (active !== owner) return@completed
            active = null
            runCatching { reply.binder.unlinkToDeath(death, 0) }
            val entry = result.entry
            val outcome = when (result.category) {
                "EXACT_EPISODE_METADATA" -> if (entry != null) "OK" else "TEMPORARY"
                "GUEST_EPISODE_HTTP_404", "GUEST_EPISODE_HTTP_410" -> "NOT_FOUND"
                "GUEST_EPISODE_HTTP_429" -> "RATE_LIMITED"
                else -> "TEMPORARY"
            }
            send(reply, AbemaEpisodeMetadataIpc.Result(request.nonce, outcome,
                entry?.resource?.identity.takeIf { outcome == "OK" }, entry?.title.takeIf { outcome == "OK" }))
        }
        if (!accepted) {
            active = null; lease.cancel(request.nonce); runCatching { reply.binder.unlinkToDeath(death, 0) }
            send(reply, AbemaEpisodeMetadataIpc.Result(request.nonce, "TEMPORARY"))
        }
    }
    private fun send(reply: Messenger, result: AbemaEpisodeMetadataIpc.Result) {
        try { reply.send(Message.obtain().apply { what = AbemaEpisodeMetadataIpc.RESULT; data = AbemaEpisodeMetadataIpc.result(result) }) }
        catch (_: Exception) { /* Closed category only; never log IPC payload. */ }
    }
    private fun cancel() {
        active?.let { it.lease.cancel(it.start.nonce); runtime?.cancel(it.start.nonce) }
    }
    override fun onUnbind(intent: Intent): Boolean { cancel(); return false }
    override fun onDestroy() { cancel(); super.onDestroy() }
}
