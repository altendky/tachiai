package net.fstab.tachiai.provider.abema

import android.app.Service
import android.content.Intent
import android.os.*
import android.os.Process

// Non-exported, same-UID service in the dedicated guest process.
// Binding configures the isolated profile; only a valid explicit START can
// prepare a route or make a request. No Activity/window/account is launched.
class AbemaEpisodeMetadataService : Service() {
    private var runtime: AbemaGuestRuntimeCoordinator? = null
    private class Request(val episode: AbemaEpisodeMetadataIpc.Start? = null, val slot: AbemaSlotMetadataIpc.Start? = null) {
        init { require((episode == null) != (slot == null)) }
        val nonce get() = episode?.nonce ?: checkNotNull(slot).nonce
        val instanceId get() = episode?.instanceId ?: checkNotNull(slot).instanceId
        val proof get() = episode?.ownerProof ?: checkNotNull(slot).ownerProof
        val deadline get() = episode?.deadline ?: checkNotNull(slot).deadline
    }
    private class Active(val start: Request, val reply: Messenger,
        val lease: AbemaEpisodeRequestLease, val death: IBinder.DeathRecipient)
    private var active: Active? = null
    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.sendingUid != Process.myUid()) return
            val slotMessage = message.what in setOf(AbemaSlotMetadataIpc.START, AbemaSlotMetadataIpc.CANCEL, AbemaSlotMetadataIpc.PULSE)
            val fields = runCatching { if (slotMessage) AbemaSlotMetadataIpc.fields(message.data)
                else AbemaEpisodeMetadataIpc.fields(message.data) }.getOrNull() ?: return
            when (message.what) {
                AbemaEpisodeMetadataIpc.START -> {
                    val request = AbemaEpisodeMetadataIpc.decodeStart(fields, SystemClock.elapsedRealtime()) ?: return
                    val reply = message.replyTo ?: return
                    start(Request(episode = request), reply)
                }
                AbemaSlotMetadataIpc.START -> {
                    val request = AbemaSlotMetadataIpc.decodeStart(fields, SystemClock.elapsedRealtime()) ?: return
                    start(Request(slot = request), message.replyTo ?: return)
                }
                AbemaEpisodeMetadataIpc.CANCEL, AbemaEpisodeMetadataIpc.PULSE,
                AbemaSlotMetadataIpc.CANCEL, AbemaSlotMetadataIpc.PULSE -> {
                    val nonce = AbemaEpisodeMetadataIpc.decodeNonce(fields) ?: return
                    val current = active?.takeIf { it.start.nonce == nonce && (it.start.slot != null) == slotMessage &&
                        it.reply.binder == message.replyTo?.binder } ?: return
                    if (message.what in setOf(AbemaEpisodeMetadataIpc.CANCEL, AbemaSlotMetadataIpc.CANCEL)) {
                        current.lease.cancel(nonce); runtime?.cancel(nonce)
                    } else if (!current.lease.pulse(nonce)) runtime?.cancel(nonce)
                }
            }
        }
    }
    private val messenger = Messenger(handler)
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 28) runtime = AbemaGuestRuntimeProcess.get(this)
    }
    override fun onBind(intent: Intent): IBinder? = if (runtime != null) messenger.binder else null
    private fun start(request: Request, reply: Messenger) {
        val coordinator = runtime ?: return
        if (active != null || coordinator.unavailable) { send(reply, request, "TEMPORARY"); return }
        val lease = AbemaEpisodeRequestLease(request.nonce, request.deadline, SystemClock::elapsedRealtime)
        val death = IBinder.DeathRecipient {
            lease.cancel(request.nonce)
            handler.post { if (active?.start === request) coordinator.cancel(request.nonce) }
        }
        try { reply.binder.linkToDeath(death, 0) } catch (_: Exception) { return }
        val owner = Active(request, reply, lease, death)
        active = owner
        val accepted = coordinator.start(AbemaGuestRuntimeRequest(request.instanceId,
            if (request.slot == null) AbemaGuestBootstrapOperation.FETCH_EPISODE else AbemaGuestBootstrapOperation.FETCH_SLOT,
            request.episode?.episodeId, request.nonce, request.proof, request.deadline,
            slotRequest = request.slot?.slot, canRemainActive = lease::current)) completed@{ result ->
            if (active !== owner) return@completed
            active = null
            runCatching { reply.binder.unlinkToDeath(death, 0) }
            val entry = result.entry
            val outcome = when (result.category) {
                "EXACT_EPISODE_METADATA" -> if (request.episode != null && entry != null) "OK" else "TEMPORARY"
                "EXACT_SLOT_METADATA" -> if (request.slot != null && entry?.resource == request.slot.slot.resource) "OK" else "TEMPORARY"
                "GUEST_EPISODE_HTTP_404", "GUEST_EPISODE_HTTP_410" -> if (request.episode != null) "NOT_FOUND" else "TEMPORARY"
                "GUEST_EPISODE_HTTP_429" -> if (request.episode != null) "RATE_LIMITED" else "TEMPORARY"
                "GUEST_SLOT_HTTP_404", "GUEST_SLOT_HTTP_410" -> if (request.slot != null) "NOT_FOUND" else "TEMPORARY"
                "GUEST_SLOT_HTTP_429" -> if (request.slot != null) "RATE_LIMITED" else "TEMPORARY"
                else -> "TEMPORARY"
            }
            send(reply, request, outcome, entry?.title.takeIf { outcome == "OK" })
        }
        if (!accepted) {
            active = null; lease.cancel(request.nonce); runCatching { reply.binder.unlinkToDeath(death, 0) }
            send(reply, request, "TEMPORARY")
        }
    }
    private fun send(reply: Messenger, request: Request, outcome: String, title: String? = null) {
        try { reply.send(Message.obtain().apply {
            if (request.slot == null) {
                what = AbemaEpisodeMetadataIpc.RESULT
                data = AbemaEpisodeMetadataIpc.result(AbemaEpisodeMetadataIpc.Result(request.nonce, outcome,
                    request.episode?.episodeId.takeIf { outcome == "OK" }, title))
            } else {
                what = AbemaSlotMetadataIpc.RESULT
                data = AbemaSlotMetadataIpc.result(AbemaSlotMetadataIpc.Result(request.nonce, outcome,
                    request.slot.slot.channelId.takeIf { outcome == "OK" }, request.slot.slot.slotId.takeIf { outcome == "OK" }, title))
            }
        }) }
        catch (_: Exception) { /* Closed category only; never log IPC payload. */ }
    }
    private fun cancel() {
        active?.let { it.lease.cancel(it.start.nonce); runtime?.cancel(it.start.nonce) }
    }
    override fun onUnbind(intent: Intent): Boolean { cancel(); return false }
    override fun onDestroy() { cancel(); super.onDestroy() }
}
