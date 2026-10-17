package net.fstab.tachiai.provider.abema.account

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import androidx.activity.ComponentActivity
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import net.fstab.tachiai.presentation.validProviderInstanceId

internal enum class AbemaAccountReadinessResult { READY, HANDOFF, FAILURE, TEMPORARY_FAILURE, INTERACTION_REQUIRED, STORAGE_FAILURE }

internal data class AbemaReadinessTarget(val instanceId: String, val ownerProof: String) {
    override fun toString() = "AbemaReadinessTarget(redacted)"
}

internal fun normalizedAbemaReadinessTargets(targets: List<AbemaReadinessTarget>): List<AbemaReadinessTarget>? {
    if (targets.size > 32) return null
    val copied = targets.toList()
    if (copied.any { !validProviderInstanceId(it.instanceId) || !it.ownerProof.matches(Regex("[a-f0-9]{64}")) }) return null
    val distinct = copied.distinct()
    if (distinct.distinctBy { it.instanceId }.size != distinct.size) return null
    return distinct
}

// A resumed host owns a logical request only. Browser handoff invalidates this
// client; the host creates a new bounded operation when it resumes.
internal class AbemaAccountReadiness(private val activity: ComponentActivity) : AutoCloseable {
    companion object { private val retained = ConcurrentLinkedQueue<Any>() }
    private val main = Handler(Looper.getMainLooper())
    private var resumed = false
    private var closed = false
    private var generation = 0L
    private var pending: Check? = null
    private inner class Check(val targets: List<AbemaReadinessTarget>, val complete: (AbemaAccountReadinessResult) -> Unit,
        val run: Long, val began: Long, val deadline: Long) : ServiceConnection {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        var index = 0
        var sequence = 0L
        var remote: Messenger? = null
        private var lateSender: Messenger? = null
        private val cancellationDeadline = Handler(Looper.getMainLooper())
        private val forgetSender = Runnable { lateSender = null }
        var bound = false
        var ended = false
        val reply = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(message: Message) {
                if (message.what != AbemaReadinessIpc.RESULT) return
                val data = message.data
                try {
                    if (!current()) {
                        if (data.size() !in 3..4) return
                        @Suppress("DEPRECATION") val fields = data.keySet().associateWith { data.get(it) }
                        if (AbemaReadinessIpc.cancelStaleLaunch(fields, nonce, sequence, message.sendingUid,
                                android.os.Process.myUid(), ::cancelTicket)) clearLateSender()
                        return
                    }
                    if (message.sendingUid != android.os.Process.myUid() || data.size() !in 3..4) {
                        finish(AbemaAccountReadinessResult.FAILURE); return
                    }
                    @Suppress("DEPRECATION") val fields = data.keySet().associateWith { data.get(it) }
                    val decision = AbemaReadinessIpc.decodeResponse(fields, nonce, sequence)
                        ?: run { finish(AbemaAccountReadinessResult.FAILURE); return }
                    if (decision.status != AbemaRenewalReadinessCoordinator.Status.LAUNCH) clearLateSender()
                    when (decision.status) {
                        AbemaRenewalReadinessCoordinator.Status.READY -> { index++; if (index == targets.size) finish(AbemaAccountReadinessResult.READY) else send() }
                        AbemaRenewalReadinessCoordinator.Status.WAIT -> main.postDelayed({ if (current()) send() }, 250)
                        AbemaRenewalReadinessCoordinator.Status.FAILURE -> finish(AbemaAccountReadinessResult.FAILURE)
                        AbemaRenewalReadinessCoordinator.Status.TEMPORARY_FAILURE -> finish(AbemaAccountReadinessResult.TEMPORARY_FAILURE)
                        AbemaRenewalReadinessCoordinator.Status.INTERACTION_REQUIRED -> finish(AbemaAccountReadinessResult.INTERACTION_REQUIRED)
                        AbemaRenewalReadinessCoordinator.Status.STORAGE_FAILURE -> finish(AbemaAccountReadinessResult.STORAGE_FAILURE)
                        AbemaRenewalReadinessCoordinator.Status.LAUNCH -> {
                            val ticket = checkNotNull(decision.ticket)
                            val callback = complete
                            if (!runCatching { finish(AbemaAccountReadinessResult.HANDOFF) }.getOrDefault(false)) {
                                cancelTicket(ticket); clearLateSender(); return
                            }
                            // Host stores its logical request before Android pauses it.
                            if (!resumed || closed || generation != run || activity.isFinishing || activity.isDestroyed) {
                                cancelTicket(ticket); clearLateSender(); return
                            }
                            try { activity.startActivity(Intent(activity, AbemaAutomaticRenewalActivity::class.java)
                                .putExtra(AbemaAutomaticRenewalActivity.TICKET, ticket)) }
                            catch (_: Throwable) {
                                cancelTicket(ticket)
                                if (resumed && !closed && generation == run) callback(AbemaAccountReadinessResult.FAILURE)
                            }
                            clearLateSender()
                        }
                    }
                } catch (_: Throwable) { finish(AbemaAccountReadinessResult.FAILURE) }
            }
        })
        private fun clearLateSender() { lateSender = null; cancellationDeadline.removeCallbacks(forgetSender) }
        private fun cancelTicket(ticket: String) {
            try { lateSender?.send(Message.obtain().apply {
                what = AbemaReadinessIpc.CANCEL; data = Bundle().apply { putString("ticket", ticket) }
            }) } catch (_: Throwable) { }
        }
        fun current(): Boolean {
            val now = SystemClock.elapsedRealtime()
            return !closed && resumed && !ended && pending === this && generation == run &&
                !activity.isDestroyed && !activity.isFinishing && now >= began && now < deadline
        }
        fun send() {
            if (!current() || index !in targets.indices) return
            sequence++
            val sender = remote ?: return
            lateSender = sender
            cancellationDeadline.removeCallbacks(forgetSender)
            cancellationDeadline.postDelayed(forgetSender, maxOf(0L, deadline - SystemClock.elapsedRealtime()))
            try { sender.send(Message.obtain().apply {
                what = AbemaReadinessIpc.CHECK; replyTo = reply
                data = Bundle().apply { putString("nonce", nonce); putString("instance", targets[index].instanceId)
                    putString("proof", targets[index].ownerProof)
                    putLong("sequence", sequence); putLong("deadline", deadline) }
            }) } catch (_: Throwable) { finish(AbemaAccountReadinessResult.FAILURE) }
        }
        fun finish(result: AbemaAccountReadinessResult): Boolean {
            if (ended) return false
            val publish = pending === this && generation == run && resumed && !closed
            val confirmed = detach()
            if (publish) complete(if (confirmed) result else AbemaAccountReadinessResult.FAILURE)
            return publish && confirmed
        }
        fun detach(): Boolean {
            if (ended) return true
            ended = true
            if (pending === this) pending = null
            var confirmed = true
            if (bound) {
                bound = false
                try { activity.applicationContext.unbindService(this) }
                catch (_: Throwable) { confirmed = false; retained.add(this); closed = true }
            }
            remote = null
            return confirmed
        }
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!current()) { detach(); return }
            remote = Messenger(binder); send()
        }
        override fun onServiceDisconnected(name: ComponentName) { finish(AbemaAccountReadinessResult.FAILURE) }
        override fun onBindingDied(name: ComponentName) { finish(AbemaAccountReadinessResult.FAILURE) }
        override fun onNullBinding(name: ComponentName) { finish(AbemaAccountReadinessResult.FAILURE) }
    }
    fun onResume() { if (!closed) resumed = true }
    fun onPause() { resumed = false; generation++; pending?.detach(); main.removeCallbacksAndMessages(null) }
    fun ensure(targets: List<AbemaReadinessTarget>, complete: (AbemaAccountReadinessResult) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        pending?.detach(); generation++
        if (!resumed || closed) return
        val normalized = normalizedAbemaReadinessTargets(targets)
        if (normalized == null) { complete(AbemaAccountReadinessResult.FAILURE); return }
        if (normalized.isEmpty()) { complete(AbemaAccountReadinessResult.READY); return }
        val began = SystemClock.elapsedRealtime()
        if (began < 0 || began > Long.MAX_VALUE - AbemaReadinessIpc.LIMIT_MS) { complete(AbemaAccountReadinessResult.FAILURE); return }
        val check = Check(normalized, complete, generation, began, began + AbemaReadinessIpc.LIMIT_MS)
        pending = check
        main.postDelayed({ if (!check.ended) check.finish(AbemaAccountReadinessResult.TEMPORARY_FAILURE) }, AbemaReadinessIpc.LIMIT_MS)
        try {
            check.bound = activity.applicationContext.bindService(Intent(activity, AbemaAccountReadinessService::class.java),
                check, Context.BIND_AUTO_CREATE)
            if (!check.bound) check.finish(AbemaAccountReadinessResult.FAILURE)
        } catch (_: Throwable) { check.finish(AbemaAccountReadinessResult.FAILURE) }
    }
    override fun close() { onPause(); closed = true }
    override fun toString() = "AbemaAccountReadiness(redacted)"
}
