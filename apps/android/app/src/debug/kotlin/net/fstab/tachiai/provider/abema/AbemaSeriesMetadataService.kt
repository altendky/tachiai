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
    private class Active(val nonce: String, val reply: Messenger, val cancelCode: Int,
        val lease: AbemaEpisodeRequestLease, val death: IBinder.DeathRecipient)
    private var active: Active? = null
    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.sendingUid != Process.myUid()) return
            val schedule = message.what in setOf(AbemaScheduleMetadataIpc.START, AbemaScheduleMetadataIpc.CANCEL, AbemaScheduleMetadataIpc.PULSE)
            val search = message.what in setOf(AbemaSearchMetadataIpc.START, AbemaSearchMetadataIpc.CANCEL, AbemaSearchMetadataIpc.PULSE)
            val fields = runCatching { when {
                search -> AbemaSearchMetadataIpc.fields(message.data)
                schedule -> AbemaScheduleMetadataIpc.fields(message.data)
                else -> AbemaSeriesMetadataIpc.fields(message.data)
            } }.getOrNull() ?: return
            when (message.what) {
                AbemaSeriesMetadataIpc.START -> {
                    val request = AbemaSeriesMetadataIpc.decodeStart(fields, SystemClock.elapsedRealtime()) ?: return
                    start(request, message.replyTo ?: return)
                }
                AbemaScheduleMetadataIpc.START -> {
                    val request = AbemaScheduleMetadataIpc.decodeStart(fields, SystemClock.elapsedRealtime()) ?: return
                    start(request, message.replyTo ?: return)
                }
                AbemaSearchMetadataIpc.START -> {
                    val request = AbemaSearchMetadataIpc.decodeStart(fields, SystemClock.elapsedRealtime()) ?: return
                    start(request, message.replyTo ?: return)
                }
                AbemaSeriesMetadataIpc.CANCEL, AbemaSeriesMetadataIpc.PULSE,
                AbemaScheduleMetadataIpc.CANCEL, AbemaScheduleMetadataIpc.PULSE,
                AbemaSearchMetadataIpc.CANCEL, AbemaSearchMetadataIpc.PULSE -> {
                    val nonce = AbemaEpisodeMetadataIpc.decodeNonce(fields) ?: return
                    val cancelCode = when { search -> AbemaSearchMetadataIpc.CANCEL; schedule -> AbemaScheduleMetadataIpc.CANCEL; else -> AbemaSeriesMetadataIpc.CANCEL }
                    val owner = active?.takeIf { it.nonce == nonce && it.cancelCode == cancelCode && it.reply.binder == message.replyTo?.binder } ?: return
                    if (message.what == cancelCode || !owner.lease.pulse(nonce)) {
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
        start(request.nonce, request.deadline, reply, AbemaSeriesMetadataIpc.CANCEL, { lease ->
            AbemaGuestRuntimeRequest(request.instanceId, AbemaGuestBootstrapOperation.FETCH_SERIES,
                nonce = request.nonce, expectedOwnerProof = request.ownerProof, deadlineElapsedMs = request.deadline,
                seriesRequest = request.page, canRemainActive = lease::current)
        }) { result ->
            val outcome = when (result.category) {
                "EXACT_SERIES_PAGE" -> if (result.seriesPage != null) "OK" else "TEMPORARY"
                "GUEST_SERIES_HTTP_404", "GUEST_SERIES_HTTP_410" -> "NOT_FOUND"
                "GUEST_SERIES_HTTP_429" -> "RATE_LIMITED"
                else -> "TEMPORARY"
            }
            send(reply, AbemaSeriesMetadataIpc.Result(request.nonce, outcome, result.seriesPage.takeIf { outcome == "OK" }), request.page.seriesId)
        }
    }
    private fun start(request: AbemaScheduleMetadataIpc.Start, reply: Messenger) {
        start(request.nonce, request.deadline, reply, AbemaScheduleMetadataIpc.CANCEL, { lease ->
            AbemaGuestRuntimeRequest(request.instanceId, AbemaGuestBootstrapOperation.FETCH_SCHEDULE,
                nonce = request.nonce, expectedOwnerProof = request.ownerProof, deadlineElapsedMs = request.deadline,
                canRemainActive = lease::current)
        }) { result ->
            val outcome = when (result.category) {
                "SCHEDULE_SNAPSHOT" -> if (result.scheduleSnapshot != null) "OK" else "TEMPORARY"
                "GUEST_SCHEDULE_HTTP_404", "GUEST_SCHEDULE_HTTP_410" -> "NOT_FOUND"
                "GUEST_SCHEDULE_HTTP_429" -> "RATE_LIMITED"
                else -> "TEMPORARY"
            }
            try { reply.send(Message.obtain().apply {
                what = AbemaScheduleMetadataIpc.RESULT
                data = AbemaScheduleMetadataIpc.result(AbemaScheduleMetadataIpc.Result(request.nonce, outcome,
                    result.scheduleSnapshot.takeIf { outcome == "OK" }))
            }) } catch (_: Exception) { /* Closed failure only. */ }
        }
    }
    private fun start(request: AbemaSearchMetadataIpc.Start, reply: Messenger) {
        start(request.nonce, request.deadline, reply, AbemaSearchMetadataIpc.CANCEL, { lease ->
            AbemaGuestRuntimeRequest(request.instanceId, AbemaGuestBootstrapOperation.FETCH_SEARCH,
                nonce = request.nonce, expectedOwnerProof = request.ownerProof, deadlineElapsedMs = request.deadline,
                searchRequest = request.search, canRemainActive = lease::current)
        }) { result ->
            val outcome = when (result.category) {
                "SEARCH_PAGE" -> if (result.searchReply != null) "OK" else "TEMPORARY"
                "GUEST_SEARCH_HTTP_404", "GUEST_SEARCH_HTTP_410" -> "NOT_FOUND"
                "GUEST_SEARCH_HTTP_429" -> "RATE_LIMITED"
                else -> "TEMPORARY"
            }
            try { reply.send(Message.obtain().apply {
                what = AbemaSearchMetadataIpc.RESULT
                data = AbemaSearchMetadataIpc.result(AbemaSearchMetadataIpc.Result(request.nonce, outcome,
                    result.searchReply.takeIf { outcome == "OK" }))
            }) } catch (_: Exception) { /* Closed failure only. */ }
        }
    }
    private fun start(nonce: String, deadline: Long, reply: Messenger, cancelCode: Int,
        request: (AbemaEpisodeRequestLease) -> AbemaGuestRuntimeRequest, completed: (AbemaGuestRuntimeResult) -> Unit) {
        val coordinator = runtime ?: return
        if (active != null || coordinator.unavailable) { completed(AbemaGuestRuntimeResult("TEMPORARY")); return }
        val lease = AbemaEpisodeRequestLease(nonce, deadline, SystemClock::elapsedRealtime)
        var owner: Active? = null
        val death = IBinder.DeathRecipient { lease.cancel(nonce); handler.post {
            if (owner != null && active === owner) coordinator.cancel(nonce)
        } }
        try { reply.binder.linkToDeath(death, 0) } catch (_: Exception) { return }
        owner = Active(nonce, reply, cancelCode, lease, death); active = owner
        val accepted = coordinator.start(request(lease)) finished@{ result ->
            if (active !== owner) return@finished
            active = null; runCatching { reply.binder.unlinkToDeath(death, 0) }; completed(result)
        }
        if (!accepted) {
            active = null; lease.cancel(nonce); runCatching { reply.binder.unlinkToDeath(death, 0) }
            completed(AbemaGuestRuntimeResult("TEMPORARY"))
        }
    }
    private fun send(reply: Messenger, result: AbemaSeriesMetadataIpc.Result, parent: String) {
        try { reply.send(Message.obtain().apply { what = AbemaSeriesMetadataIpc.RESULT; data = AbemaSeriesMetadataIpc.result(result, parent) }) }
        catch (_: Exception) { /* No response/identity/error text logging. */ }
    }
    private fun cancel() { active?.let { it.lease.cancel(it.nonce); runtime?.cancel(it.nonce) } }
    override fun onUnbind(intent: Intent): Boolean { cancel(); return false }
    override fun onDestroy() { cancel(); super.onDestroy() }
}
