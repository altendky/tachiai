package net.fstab.tachiai.provider.abema.account

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Network
import android.os.*
import android.os.Process
import java.util.concurrent.ConcurrentLinkedQueue
import net.fstab.tachiai.platform.network.AndroidNetworkEgress
import net.fstab.tachiai.platform.network.RouteNetworkEgress

internal enum class AbemaRenewalBrowserResult {
    DELIVERED, CANCELLED, TEMPORARY_FAILURE, CLEANUP_UNCONFIRMED;
    val cleanupConfirmed get() = this != CLEANUP_UNCONFIRMED
}

internal interface AbemaRenewalBrowser {
    val diagnostic: AbemaRenewalDiagnostic get() = AbemaRenewalDiagnostic()
    val producerDiagnostic: AbemaRenewalDiagnostic? get() = null
    // Main-looper calls and completion; the owner releases only after completion.
    fun start(request: AbemaApprovalBrowserRequest, completed: (AbemaRenewalBrowserResult) -> Unit)
    fun cancel()
    fun release(): Boolean
}

// START alone carries this immutable, RAM-only capability. Replies and CANCEL
// keep the scalar owner/nonce/deadline identity and cannot replace this Network.
internal class AbemaRenewalBrowserStart(val request: AbemaApprovalBrowserRequest, val network: Network? = null) {
    private val networkContext = network?.let { AbemaRenewalNetwork(it, request.deadlineElapsedMs) }
    fun egressFactory(create: (Network, Long) -> RouteNetworkEgress = { value, deadline ->
        AndroidNetworkEgress(value, deadline)
    }): (() -> RouteNetworkEgress)? = networkContext?.egressFactory(create)
    override fun toString() = "AbemaRenewalBrowserStart(redacted)"
}

// Only fixed, bounded account-page requests cross this channel. The existing
// protected handoff transports the candidate separately; no grant is an IPC field.
internal object AbemaRenewalBrowserProtocol {
    data class Outcome(val result: AbemaRenewalBrowserResult, val diagnostic: AbemaRenewalDiagnostic)
    const val START = 1
    const val CANCEL = 2
    const val RESULT = 3
    const val LIMIT_MS = 90_000L
    private val requestKeys = setOf("instance", "proof", "nonce", "deadline")

    fun requestFields(request: AbemaApprovalBrowserRequest): Map<String, Any?> = mapOf(
        "instance" to request.instanceId, "proof" to request.ownerProof,
        "nonce" to request.emailNonce, "deadline" to request.deadlineElapsedMs)

    fun startFields(start: AbemaRenewalBrowserStart): Map<String, Any?> = requestFields(start.request) +
        (start.network?.let { mapOf("bound" to true, "network" to it) } ?: emptyMap())

    fun decodeStart(fields: Map<String, Any?>, sendingUid: Int, ownUid: Int, now: Long): AbemaRenewalBrowserStart? =
        runCatching {
            val network = when (fields.keys) {
                requestKeys -> null
                requestKeys + setOf("bound", "network") -> {
                    if (fields["bound"] !is Boolean || fields["bound"] != true) return null
                    fields["network"] as? Network ?: return null
                }
                else -> return null
            }
            val request = decodeRequest(fields.filterKeys { it in requestKeys }, sendingUid, ownUid, now) ?: return null
            AbemaRenewalBrowserStart(request, network)
        }.getOrNull()

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
        request: AbemaApprovalBrowserRequest): AbemaRenewalBrowserResult? = decodeOutcome(fields, sendingUid, ownUid, request)?.result

    fun resultFields(request: AbemaApprovalBrowserRequest, result: AbemaRenewalBrowserResult,
        diagnostic: AbemaRenewalDiagnostic): Map<String, Any?> = requestFields(request) + ("result" to result.name) + diagnostic.fields()

    fun decodeOutcome(fields: Map<String, Any?>, sendingUid: Int, ownUid: Int,
        request: AbemaApprovalBrowserRequest): Outcome? {
        if (fields.keys != requestKeys + "result" + AbemaRenewalDiagnostic.keys ||
            !matches(fields.filterKeys { it in requestKeys }, sendingUid, ownUid, request)) return null
        val name = fields["result"] as? String ?: return null
        val result = AbemaRenewalBrowserResult.entries.singleOrNull { it.name == name } ?: return null
        val diagnostic = AbemaRenewalDiagnostic.decode(fields.filterKeys { it in AbemaRenewalDiagnostic.keys }) ?: return null
        val cleanup = if (result.cleanupConfirmed) AbemaRenewalDiagnostic.Cleanup.CONFIRMED else AbemaRenewalDiagnostic.Cleanup.UNCONFIRMED
        if (diagnostic.cleanup != cleanup ||
            result == AbemaRenewalBrowserResult.DELIVERED && diagnostic.reason != AbemaRenewalDiagnostic.Reason.NONE) return null
        return Outcome(result, diagnostic)
    }

    fun projectResult(result: AbemaRenewalBrowserResult, expired: Boolean, cancelled: Boolean): AbemaRenewalBrowserResult = when {
        !result.cleanupConfirmed -> result
        expired -> AbemaRenewalBrowserResult.TEMPORARY_FAILURE
        cancelled -> AbemaRenewalBrowserResult.CANCELLED
        else -> result
    }
}

// The real client handler uses this exact receipt/retirement path. Local guards
// and the producer's first closed receipt remain separate: IPC arrival cannot
// establish chronological ordering across owners. This is not a second receiver
// lifetime; completion permanently refuses all later diagnostic envelopes too.
internal class AbemaRenewalBrowserEvidence(private val diagnostics: AbemaRenewalDiagnostics) {
    private var retired = false
    private var producer: AbemaRenewalDiagnostic? = null
    @Synchronized fun producer() = producer
    @Synchronized fun receive(fields: Map<String, Any?>, sendingUid: Int, ownUid: Int,
        request: AbemaApprovalBrowserRequest): AbemaRenewalBrowserProtocol.Outcome? {
        if (retired) return null
        val decoded = AbemaRenewalBrowserProtocol.decodeOutcome(fields, sendingUid, ownUid, request) ?: return null
        if (producer == null) producer = decoded.diagnostic
        diagnostics.absorb(decoded.diagnostic)
        return decoded
    }
    @Synchronized fun finish(result: AbemaRenewalBrowserResult) {
        if (retired) return
        retired = true
        diagnostics.cleanup(result.cleanupConfirmed)
        diagnostics.freeze()
    }
}

internal class AndroidAbemaRenewalBrowser(context: Context, private val network: Network? = null) : AbemaRenewalBrowser, ServiceConnection {
    private val diagnostics = AbemaRenewalDiagnostics()
    private val evidence = AbemaRenewalBrowserEvidence(diagnostics)
    override val diagnostic get() = diagnostics.snapshot()
    override val producerDiagnostic get() = evidence.producer()
    private companion object { val retained = ConcurrentLinkedQueue<AndroidAbemaRenewalBrowser>() }
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var request: AbemaApprovalBrowserRequest? = null
    private var startCarrier: AbemaRenewalBrowserStart? = null
    private var completed: ((AbemaRenewalBrowserResult) -> Unit)? = null
    private var result: AbemaRenewalBrowserResult? = null
    private var remote: Messenger? = null
    private var bound = false
    private var sent = false
    private var cancelled = false
    private var expired = false
    private var cancellationWatchdog = false
    private var retainedOnce = false
    private val death = IBinder.DeathRecipient { main.post { disconnected() } }
    private val reply = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (result != null || message.what != AbemaRenewalBrowserProtocol.RESULT) return
            val captured = request ?: return
            val decoded = runCatching {
                evidence.receive(message.data.fields(), message.sendingUid, Process.myUid(), captured)
            }.getOrNull() ?: return
            finish(AbemaRenewalBrowserProtocol.projectResult(decoded.result, expired, cancelled))
        }
    })

    override fun start(request: AbemaApprovalBrowserRequest, completed: (AbemaRenewalBrowserResult) -> Unit) {
        requireMain()
        check(this.request == null && result == null) { "A renewal browser is single use" }
        this.request = request; this.completed = completed
        if (!AbemaRenewalBrowserProtocol.accepts(request, SystemClock.elapsedRealtime())) {
            diagnostics.refuse(AbemaRenewalDiagnostic.Phase.BROWSER_BIND, AbemaRenewalDiagnostic.Reason.REFUSED)
            finish(AbemaRenewalBrowserResult.TEMPORARY_FAILURE); return
        }
        startCarrier = runCatching { AbemaRenewalBrowserStart(request, network) }.getOrNull()
        if (startCarrier == null) {
            diagnostics.refuse(AbemaRenewalDiagnostic.Phase.BROWSER_BIND, AbemaRenewalDiagnostic.Reason.REFUSED)
            finish(AbemaRenewalBrowserResult.TEMPORARY_FAILURE); return
        }
        main.postDelayed({
            if (result == null) {
                diagnostics.refuse(AbemaRenewalDiagnostic.Phase.LIFETIME, AbemaRenewalDiagnostic.Reason.DEADLINE)
                expired = true; cancel()
            }
        }, request.deadlineElapsedMs - SystemClock.elapsedRealtime())
        // A lost result must not strand the operation, nor imply cleanup.
        main.postDelayed({ finish(AbemaRenewalBrowserResult.CLEANUP_UNCONFIRMED) },
            maxOf(0L, request.deadlineElapsedMs - SystemClock.elapsedRealtime()) + 7_000L)
        try {
            bound = context.bindService(Intent(context, AbemaRenewalBrowserService::class.java), this, Context.BIND_AUTO_CREATE)
            if (!bound) {
                diagnostics.refuse(AbemaRenewalDiagnostic.Phase.BROWSER_BIND, AbemaRenewalDiagnostic.Reason.BIND_REFUSED)
                finish(AbemaRenewalBrowserResult.TEMPORARY_FAILURE)
            }
        } catch (_: Exception) {
            diagnostics.refuse(AbemaRenewalDiagnostic.Phase.BROWSER_BIND, AbemaRenewalDiagnostic.Reason.EXCEPTION)
            finish(AbemaRenewalBrowserResult.CLEANUP_UNCONFIRMED)
        }
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        requireMain()
        if (result != null) return
        if (cancelled) { finish(if (expired) AbemaRenewalBrowserResult.TEMPORARY_FAILURE else AbemaRenewalBrowserResult.CANCELLED); return }
        val captured = startCarrier ?: return
        try {
            binder.linkToDeath(death, 0)
            diagnostics.progress(AbemaRenewalDiagnostic.Progress.BROWSER_BOUND)
            remote = Messenger(binder)
            // Mark before send: an exception cannot prove that START was not delivered.
            sent = true
            remote!!.send(Message.obtain().apply {
                what = AbemaRenewalBrowserProtocol.START; replyTo = reply; data = captured.startEnvelope()
            })
        } catch (_: Exception) {
            diagnostics.refuse(AbemaRenewalDiagnostic.Phase.BROWSER_BIND, AbemaRenewalDiagnostic.Reason.EXCEPTION)
            finish(AbemaRenewalBrowserResult.CLEANUP_UNCONFIRMED)
        }
    }

    override fun onServiceDisconnected(name: ComponentName) = disconnected()
    override fun onBindingDied(name: ComponentName) = disconnected()
    override fun onNullBinding(name: ComponentName) {
        requireMain()
        diagnostics.refuse(AbemaRenewalDiagnostic.Phase.BROWSER_BIND, AbemaRenewalDiagnostic.Reason.BIND_REFUSED)
        finish(if (sent) AbemaRenewalBrowserResult.CLEANUP_UNCONFIRMED else AbemaRenewalBrowserResult.TEMPORARY_FAILURE)
    }
    private fun disconnected() {
        requireMain()
        if (result == null) diagnostics.refuse(AbemaRenewalDiagnostic.Phase.BROWSER_BIND, AbemaRenewalDiagnostic.Reason.BROKER_LOST)
        finish(AbemaRenewalBrowserResult.CLEANUP_UNCONFIRMED)
    }

    override fun cancel() {
        requireMain()
        if (result != null) return
        diagnostics.refuse(AbemaRenewalDiagnostic.Phase.BROWSER_BIND,
            if (expired) AbemaRenewalDiagnostic.Reason.DEADLINE else AbemaRenewalDiagnostic.Reason.CANCELLED)
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
        evidence.finish(value)
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

internal fun AbemaRenewalBrowserStart.startEnvelope() = request.envelope().apply {
    network?.let { putBoolean("bound", true); putParcelable("network", it) }
}
