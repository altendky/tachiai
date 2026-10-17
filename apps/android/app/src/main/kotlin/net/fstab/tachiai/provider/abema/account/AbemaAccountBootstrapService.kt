package net.fstab.tachiai.provider.abema.account

import android.app.Service
import android.content.Intent
import android.os.*
import android.os.Process
import net.fstab.tachiai.provider.abema.*

class AbemaAccountBootstrapService : Service() {
    private var runtime: AbemaGuestRuntimeCoordinator? = null
    private class Active(val start: AbemaAccountBootstrapIpc.Start, val reply: Messenger,
        val lease: AbemaEpisodeRequestLease, val death: IBinder.DeathRecipient)
    private var active: Active? = null
    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.sendingUid != Process.myUid()) return
            val fields = AbemaAccountBootstrapIpc.fields(message.data) ?: return
            when (message.what) {
                AbemaAccountBootstrapIpc.START -> {
                    val request = AbemaAccountBootstrapIpc.readStart(fields, SystemClock.elapsedRealtime()) ?: return
                    start(request, message.replyTo ?: return)
                }
                AbemaAccountBootstrapIpc.CANCEL, AbemaAccountBootstrapIpc.PULSE -> {
                    val nonce = AbemaAccountBootstrapIpc.readNonce(fields) ?: return
                    val current = active?.takeIf { it.start.nonce == nonce && it.reply.binder == message.replyTo?.binder } ?: return
                    if (message.what == AbemaAccountBootstrapIpc.CANCEL) { current.lease.cancel(nonce); runtime?.cancel(nonce) }
                    else if (!current.lease.pulse(nonce)) runtime?.cancel(nonce)
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
    private fun start(request: AbemaAccountBootstrapIpc.Start, reply: Messenger) {
        val coordinator = runtime ?: return
        if (active != null || coordinator.unavailable) { send(reply, AbemaAccountBootstrapIpc.Result(request.nonce, null)); return }
        val lease = AbemaEpisodeRequestLease(request.nonce, request.deadline, SystemClock::elapsedRealtime)
        val death = IBinder.DeathRecipient { lease.cancel(request.nonce); handler.post { if (active?.start === request) coordinator.cancel(request.nonce) } }
        try { reply.binder.linkToDeath(death, 0) } catch (_: Exception) { return }
        val owner = Active(request, reply, lease, death); active = owner
        val operation = if (request.mode == AbemaAccountBootstrapIpc.Mode.VALIDATION)
            AbemaGuestBootstrapOperation.PREPARE_ACCOUNT_VALIDATION else AbemaGuestBootstrapOperation.PREPARE_ACCOUNT_LINK
        val accepted = coordinator.start(AbemaGuestRuntimeRequest(request.instanceId, operation,
            nonce = request.nonce, expectedOwnerProof = request.proof, deadlineElapsedMs = request.deadline, canRemainActive = lease::current)) completed@{ result ->
            if (active !== owner) return@completed
            active = null; runCatching { reply.binder.unlinkToDeath(death, 0) }
            send(reply, AbemaAccountBootstrapIpc.Result(request.nonce, result.accountChallenge.takeIf {
                request.mode == AbemaAccountBootstrapIpc.Mode.LINK && result.category == "ACCOUNT_LINK_READY" && lease.current()
            }, result.accountValidation.takeIf {
                request.mode == AbemaAccountBootstrapIpc.Mode.VALIDATION && result.category == "ACCOUNT_VALIDATION_READY" && lease.current() &&
                    it?.deadlineElapsedMs == request.deadline
            }))
        }
        if (!accepted) { active = null; lease.cancel(request.nonce); runCatching { reply.binder.unlinkToDeath(death, 0) }; send(reply, AbemaAccountBootstrapIpc.Result(request.nonce, null)) }
    }
    private fun send(reply: Messenger, result: AbemaAccountBootstrapIpc.Result) {
        try { reply.send(Message.obtain().apply { what = AbemaAccountBootstrapIpc.RESULT; data = AbemaAccountBootstrapIpc.result(result) }) }
        catch (_: Exception) { /* Private payload is never logged. */ }
    }
    private fun cancel() { active?.let { it.lease.cancel(it.start.nonce); runtime?.cancel(it.start.nonce) } }
    override fun onUnbind(intent: Intent): Boolean { cancel(); return false }
    override fun onDestroy() { cancel(); super.onDestroy() }
}
