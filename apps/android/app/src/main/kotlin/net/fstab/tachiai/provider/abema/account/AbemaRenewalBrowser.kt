package net.fstab.tachiai.provider.abema.account

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import android.os.Process
import java.util.concurrent.ConcurrentLinkedQueue

internal enum class AbemaRenewalBrowserResult {
    DELIVERED, CANCELLED, TEMPORARY_FAILURE, CLEANUP_UNCONFIRMED;
    val cleanupConfirmed get() = this != CLEANUP_UNCONFIRMED
}

internal interface AbemaRenewalBrowser {
    // Main-looper calls and completion; the owner releases only after completion.
    fun start(request: AbemaApprovalBrowserRequest, completed: (AbemaRenewalBrowserResult) -> Unit)
    fun cancel()
    fun release(): Boolean
}

// Only fixed, bounded account-page requests cross this channel. The existing
// protected handoff transports the candidate separately; no grant is an IPC field.
internal object AbemaRenewalBrowserProtocol {
    const val START = 1
    const val CANCEL = 2
    const val RESULT = 3
    const val LIMIT_MS = 90_000L
    private val requestKeys = setOf("instance", "proof", "nonce", "deadline")

    fun requestFields(request: AbemaApprovalBrowserRequest): Map<String, Any?> = mapOf(
        "instance" to request.instanceId, "proof" to request.ownerProof,
        "nonce" to request.emailNonce, "deadline" to request.deadlineElapsedMs)

    fun accepts(request: AbemaApprovalBrowserRequest, now: Long): Boolean =
        request.verificationUri == "https://abema.tv/account" && request.automaticReturn &&
            !request.resetBrowserSession && request.emailNonce != null &&
            decodeRequest(requestFields(request), 0, 0, now) != null

    fun decodeRequest(fields: Map<String, Any?>, sendingUid: Int, ownUid: Int, now: Long): AbemaApprovalBrowserRequest? =
        runCatching {
            if (ownUid < 0 || sendingUid != ownUid || fields.keys != requestKeys || now < 0) return null
            val deadline = fields["deadline"] as? Long ?: return null
            if (deadline <= now || deadline - now > LIMIT_MS) return null
            AbemaApprovalBrowserRequest(fields["instance"] as? String ?: return null,
                fields["proof"] as? String ?: return null, "https://abema.tv/account", deadline,
                fields["nonce"] as? String ?: return null, resetBrowserSession = false, automaticReturn = true)
        }.getOrNull()

    fun matches(fields: Map<String, Any?>, sendingUid: Int, ownUid: Int, request: AbemaApprovalBrowserRequest): Boolean =
        ownUid >= 0 && sendingUid == ownUid && fields == requestFields(request)

    fun decodeResult(fields: Map<String, Any?>, sendingUid: Int, ownUid: Int,
        request: AbemaApprovalBrowserRequest): AbemaRenewalBrowserResult? {
        if (fields.keys != requestKeys + "result" ||
            !matches(fields - "result", sendingUid, ownUid, request)) return null
        val name = fields["result"] as? String ?: return null
        return AbemaRenewalBrowserResult.entries.singleOrNull { it.name == name }
    }
}

internal class AndroidAbemaRenewalBrowser(context: Context) : AbemaRenewalBrowser, ServiceConnection {
    private companion object { val retained = ConcurrentLinkedQueue<AndroidAbemaRenewalBrowser>() }
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var request: AbemaApprovalBrowserRequest? = null
    private var completed: ((AbemaRenewalBrowserResult) -> Unit)? = null
    private var result: AbemaRenewalBrowserResult? = null
    private var remote: Messenger? = null
    private var bound = false
    private var sent = false
    private var cancelled = false
    private var expired = false
    private var cancellationWatchdog = false
    private var retainedOnce = false
    private val death = IBinder.DeathRecipient { main.post { finish(AbemaRenewalBrowserResult.CLEANUP_UNCONFIRMED) } }
    private val reply = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (result != null || message.what != AbemaRenewalBrowserProtocol.RESULT) return
            val captured = request ?: return
            val decoded = runCatching {
                AbemaRenewalBrowserProtocol.decodeResult(message.data.fields(), message.sendingUid, Process.myUid(), captured)
            }.getOrNull() ?: return
            finish(when {
                !decoded.cleanupConfirmed -> decoded
                expired -> AbemaRenewalBrowserResult.TEMPORARY_FAILURE
                cancelled -> AbemaRenewalBrowserResult.CANCELLED
                else -> decoded
            })
        }
    })

    override fun start(request: AbemaApprovalBrowserRequest, completed: (AbemaRenewalBrowserResult) -> Unit) {
        requireMain()
        check(this.request == null && result == null) { "A renewal browser is single use" }
        this.request = request; this.completed = completed
        if (!AbemaRenewalBrowserProtocol.accepts(request, SystemClock.elapsedRealtime())) {
            finish(AbemaRenewalBrowserResult.TEMPORARY_FAILURE); return
        }
        main.postDelayed({
            if (result == null) { expired = true; cancel() }
        }, request.deadlineElapsedMs - SystemClock.elapsedRealtime())
        // A lost result must not strand the operation, nor imply cleanup.
        main.postDelayed({ finish(AbemaRenewalBrowserResult.CLEANUP_UNCONFIRMED) },
            maxOf(0L, request.deadlineElapsedMs - SystemClock.elapsedRealtime()) + 7_000L)
        try {
            bound = context.bindService(Intent(context, AbemaRenewalBrowserService::class.java), this, Context.BIND_AUTO_CREATE)
            if (!bound) finish(AbemaRenewalBrowserResult.TEMPORARY_FAILURE)
        } catch (_: Exception) {
            finish(AbemaRenewalBrowserResult.CLEANUP_UNCONFIRMED)
        }
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        requireMain()
        if (result != null) return
        if (cancelled) { finish(if (expired) AbemaRenewalBrowserResult.TEMPORARY_FAILURE else AbemaRenewalBrowserResult.CANCELLED); return }
        val captured = request ?: return
        try {
            binder.linkToDeath(death, 0)
            remote = Messenger(binder)
            // Mark before send: an exception cannot prove that START was not delivered.
            sent = true
            remote!!.send(Message.obtain().apply {
                what = AbemaRenewalBrowserProtocol.START; replyTo = reply; data = captured.envelope()
            })
        } catch (_: Exception) { finish(AbemaRenewalBrowserResult.CLEANUP_UNCONFIRMED) }
    }

    override fun onServiceDisconnected(name: ComponentName) = disconnected()
    override fun onBindingDied(name: ComponentName) = disconnected()
    override fun onNullBinding(name: ComponentName) {
        requireMain()
        finish(if (sent) AbemaRenewalBrowserResult.CLEANUP_UNCONFIRMED else AbemaRenewalBrowserResult.TEMPORARY_FAILURE)
    }
    private fun disconnected() { requireMain(); finish(AbemaRenewalBrowserResult.CLEANUP_UNCONFIRMED) }

    override fun cancel() {
        requireMain()
        if (result != null) return
        cancelled = true
        if (!sent) {
            finish(if (expired) AbemaRenewalBrowserResult.TEMPORARY_FAILURE else AbemaRenewalBrowserResult.CANCELLED)
            return
        }
        if (!cancellationWatchdog) {
            cancellationWatchdog = true
            main.postDelayed({ finish(AbemaRenewalBrowserResult.CLEANUP_UNCONFIRMED) }, 7_000L)
        }
        try {
            remote?.send(Message.obtain().apply {
                what = AbemaRenewalBrowserProtocol.CANCEL; data = checkNotNull(request).envelope()
            }) ?: finish(AbemaRenewalBrowserResult.CLEANUP_UNCONFIRMED)
        } catch (_: Exception) { finish(AbemaRenewalBrowserResult.CLEANUP_UNCONFIRMED) }
    }

    override fun release(): Boolean {
        requireMain()
        if (request == null && !bound && remote == null && result == null) return true
        if (result?.cleanupConfirmed != true) { retain(); return false }
        try {
            if (bound) context.unbindService(this)
            bound = false
            remote?.binder?.let { runCatching { it.unlinkToDeath(death, 0) } }
            remote = null
            return true
        } catch (_: Exception) { retain(); return false }
    }

    private fun finish(value: AbemaRenewalBrowserResult) {
        requireMain()
        if (result != null) return
        result = value
        main.removeCallbacksAndMessages(null)
        if (!value.cleanupConfirmed) retain()
        val callback = completed; completed = null
        callback?.invoke(value)
    }
    private fun retain() { if (!retainedOnce) { retainedOnce = true; retained.add(this) } }
    private fun requireMain() { check(Looper.myLooper() == Looper.getMainLooper()) }
}

@Suppress("DEPRECATION")
internal fun Bundle.fields(): Map<String, Any?> = keySet().associateWith { get(it) }

internal fun AbemaApprovalBrowserRequest.envelope() = Bundle().apply {
    putString("instance", instanceId); putString("proof", ownerProof)
    putString("nonce", emailNonce); putLong("deadline", deadlineElapsedMs)
}
