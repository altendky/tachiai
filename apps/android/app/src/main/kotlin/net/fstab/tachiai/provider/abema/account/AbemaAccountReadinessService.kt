package net.fstab.tachiai.provider.abema.account

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.*
import android.os.Process
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import net.fstab.tachiai.presentation.validProviderInstanceId
import net.fstab.tachiai.provider.abema.AbemaCatalogRouteOwner

internal object AbemaReadinessIpc {
    const val CHECK = 1
    const val RESULT = 2
    const val CANCEL = 3
    const val LIMIT_MS = 90_000L
    class Request(val nonce: String, val instance: String, val ownerProof: String, val sequence: Long, val deadline: Long) {
        override fun toString() = "AbemaReadinessRequest(redacted)"
    }
    fun request(data: Bundle, sendingUid: Int, ownUid: Int, now: Long): Request? = runCatching {
        if (data.size() != 5) return null
        @Suppress("DEPRECATION") val fields = data.keySet().associateWith { data.get(it) }
        decode(fields, sendingUid, ownUid, now)
    }.getOrNull()
    fun decode(fields: Map<String, Any?>, sendingUid: Int, ownUid: Int, now: Long): Request? {
        if (sendingUid < 0 || ownUid < 0 || sendingUid != ownUid ||
            fields.keys != setOf("nonce", "instance", "proof", "sequence", "deadline")) return null
        val nonce = fields["nonce"] as? String ?: return null
        val instance = fields["instance"] as? String ?: return null
        val proof = fields["proof"] as? String ?: return null
        val sequence = fields["sequence"] as? Long ?: return null
        val deadline = fields["deadline"] as? Long ?: return null
        if (!nonce.matches(Regex("[a-f0-9]{32}")) || !validProviderInstanceId(instance) ||
            !proof.matches(Regex("[a-f0-9]{64}")) || sequence < 1 ||
            now < 0 || deadline <= now || deadline - now > LIMIT_MS) return null
        return Request(nonce, instance, proof, sequence, deadline)
    }
    fun decodeResponse(fields: Map<String, Any?>, nonce: String, sequence: Long): AbemaRenewalReadinessCoordinator.Decision? {
        if (fields["nonce"] != nonce || fields["sequence"] != sequence) return null
        val status = (fields["status"] as? String)?.let {
            AbemaRenewalReadinessCoordinator.Status.entries.singleOrNull { value -> value.name == it }
        } ?: return null
        if (fields.keys != if (status == AbemaRenewalReadinessCoordinator.Status.LAUNCH)
                setOf("nonce", "sequence", "status", "ticket") else setOf("nonce", "sequence", "status")) return null
        val ticket = if (status == AbemaRenewalReadinessCoordinator.Status.LAUNCH)
            (fields["ticket"] as? String)?.takeIf { it.matches(Regex("[a-f0-9]{32}")) } ?: return null else null
        return AbemaRenewalReadinessCoordinator.Decision(status, ticket)
    }
    fun cancelStaleLaunch(fields: Map<String, Any?>, nonce: String, sequence: Long,
        sendingUid: Int, ownUid: Int, cancel: (String) -> Unit): Boolean {
        if (sendingUid < 0 || ownUid < 0 || sendingUid != ownUid) return false
        val decision = decodeResponse(fields, nonce, sequence) ?: return false
        if (decision.status != AbemaRenewalReadinessCoordinator.Status.LAUNCH) return false
        cancel(checkNotNull(decision.ticket))
        return true
    }
    fun response(request: Request, decision: AbemaRenewalReadinessCoordinator.Decision) = Bundle().apply {
        putString("nonce", request.nonce); putLong("sequence", request.sequence)
        putString("status", decision.status.name)
        decision.ticket?.let { putString("ticket", it) }
    }
}

internal object AndroidAbemaRenewalReadiness {
    class Work(val binding: AbemaAccountConnectionBinding, val owner: AbemaCatalogRouteOwner,
        val expected: AbemaAccountSnapshot, val beganWall: Long, val deadline: Long) {
        override fun toString() = "AbemaRenewalWork(redacted)"
    }
    private class Scope(val owner: String, val snapshot: AbemaAccountSnapshot) {
        override fun equals(other: Any?): Boolean {
            if (other !is Scope || owner != other.owner || snapshot.revision != other.snapshot.revision) return false
            val a = snapshot.record ?: return false
            val b = other.snapshot.record ?: return false
            return a.grant.userId == b.grant.userId && a.grant.bearer == b.grant.bearer &&
                a.grant.expiresAtMs == b.grant.expiresAtMs && a.acceptedWallMs == b.acceptedWallMs && a.generation == b.generation
        }
        override fun hashCode() = owner.hashCode() * 31 + snapshot.revision.hashCode()
        override fun toString() = "AbemaRenewalScope(redacted)"
    }
    private val coordinator = AbemaRenewalReadinessCoordinator<Work>(SystemClock::elapsedRealtime,
        { UUID.randomUUID().toString().replace("-", "") })
    private val main = Handler(Looper.getMainLooper())
    // Application-owned operations survive individual readiness Service/Activity
    // observers. Unconfirmed cleanup keeps the actual operation and reservation.
    private val operations = ConcurrentHashMap<String, AbemaAccountRenewalOperation>()
    fun check(service: Service, target: AbemaReadinessTarget, deadline: Long): AbemaRenewalReadinessCoordinator.Decision {
        val instance = target.instanceId
        fun invalidateTarget() = coordinator.invalidate(instance) { it.owner.proof() == target.ownerProof }
        fun failed(status: AbemaRenewalReadinessCoordinator.Status = AbemaRenewalReadinessCoordinator.Status.FAILURE): AbemaRenewalReadinessCoordinator.Decision {
            invalidateTarget()
            return AbemaRenewalReadinessCoordinator.Decision(status)
        }
        return try {
            coordinator.observeClaimed(instance) { it.owner.proof() == target.ownerProof }?.let { return it }
            val binding = androidAbemaAccountConnectionBinding(service, instance, target.ownerProof)
            val owner = binding.owner()
            val snapshot = binding.store.readSnapshot()
            val wall = System.currentTimeMillis()
            abemaReadinessRecordAdmission(
                ownerCurrent = { owner.instance.id == instance && owner.proof() == target.ownerProof && binding.sameOwner(owner) },
                claimed = { coordinator.observeClaimed(instance) { it.owner.proof() == target.ownerProof } },
                recordCurrent = { binding.store.usable(snapshot.revision) }, refused = { failed() })?.let { return it }
            val record = snapshot.record
            if (record == null) {
                invalidateTarget()
                return AbemaRenewalReadinessCoordinator.Decision(AbemaRenewalReadinessCoordinator.Status.READY)
            }
            if (wall < record.acceptedWallMs || decodedAbemaGrant(record.grant.bearer, record.grant.userId,
                    record.acceptedWallMs).expiresAtMs != record.grant.expiresAtMs) return failed()
            if (wall < record.grant.expiresAtMs) {
                invalidateTarget()
                return AbemaRenewalReadinessCoordinator.Decision(AbemaRenewalReadinessCoordinator.Status.READY)
            }
            if (!abemaRenewalSnapshotMatches(snapshot, snapshot, wall)) return failed()
            val decision = coordinator.check(instance, Scope(owner.proof(), snapshot), Work(binding, owner, snapshot, wall, deadline), deadline)
            if (decision.status == AbemaRenewalReadinessCoordinator.Status.LAUNCH) {
                start(service.applicationContext, checkNotNull(decision.ticket))
                AbemaRenewalReadinessCoordinator.Decision(AbemaRenewalReadinessCoordinator.Status.WAIT)
            } else decision
        } catch (error: AbemaAccountException) {
            failed(when (error.category) {
                AbemaAccountFailure.STORAGE_FAILED -> AbemaRenewalReadinessCoordinator.Status.STORAGE_FAILURE
                AbemaAccountFailure.CANCELLED -> AbemaRenewalReadinessCoordinator.Status.FAILURE
                else -> AbemaRenewalReadinessCoordinator.Status.TEMPORARY_FAILURE
            })
        } catch (_: Throwable) { failed(AbemaRenewalReadinessCoordinator.Status.TEMPORARY_FAILURE) }
    }
    fun claim(ticket: String): Work? = coordinator.claim(ticket)?.takeIf { work ->
        runCatching { work.binding.sameOwner(work.owner) && work.binding.store.usable(work.expected.revision) &&
            System.currentTimeMillis() >= work.beganWall && abemaRenewalSnapshotMatches(work.expected,
                work.binding.store.readSnapshot(), System.currentTimeMillis()) && coordinator.current(ticket) }.getOrDefault(false)
    }
    fun current(ticket: String) = coordinator.current(ticket)
    fun finish(ticket: String, success: Boolean) = coordinator.finish(ticket, success)
    fun finish(ticket: String, result: AbemaRenewalReadinessCoordinator.Status) = coordinator.finish(ticket, result)
    fun cancelUnclaimed(ticket: String) = coordinator.cancelUnclaimed(ticket)

    private fun start(context: Context, ticket: String) {
        val work = claim(ticket)
        if (work == null) { coordinator.finish(ticket, false); return }
        val operation = try { AbemaAccountRenewalOperation(work.binding, work.owner, work.expected, work.deadline,
            AndroidAbemaRenewalBrowser(context), AndroidAbemaEmailGrantHandoff.inbox,
            AbemaAccountWorkers.worker, AbemaAccountWorkers.cancellation,
            { action -> main.post(action) }, { delay, action -> main.postDelayed(action, delay) },
            SystemClock::elapsedRealtime, System::currentTimeMillis, { coordinator.current(ticket) },
            beforeApproval = { id, deadline, gate ->
                net.fstab.tachiai.platform.network.ProviderStartupScopeCleanup.awaitReady(id, deadline, gate) ==
                    net.fstab.tachiai.platform.network.ProviderStartupCleanupAdmission.READY
            }, completed = { outcome ->
                if (outcome.result == AbemaAutomaticRenewalResult.SUCCESS)
                    AndroidAbemaAccountAccessOwners.invalidate(work.owner.instance.id, work.owner.proof())
                val status = when (outcome.result) {
                    AbemaAutomaticRenewalResult.SUCCESS -> AbemaRenewalReadinessCoordinator.Status.READY
                    AbemaAutomaticRenewalResult.INTERACTION_REQUIRED -> AbemaRenewalReadinessCoordinator.Status.INTERACTION_REQUIRED
                    AbemaAutomaticRenewalResult.STORAGE_FAILURE -> AbemaRenewalReadinessCoordinator.Status.STORAGE_FAILURE
                    AbemaAutomaticRenewalResult.TEMPORARY_FAILURE -> AbemaRenewalReadinessCoordinator.Status.TEMPORARY_FAILURE
                    AbemaAutomaticRenewalResult.CANCELLED -> AbemaRenewalReadinessCoordinator.Status.FAILURE
                }
                coordinator.finish(ticket, status, outcome.cleanupConfirmed)
                if (outcome.cleanupConfirmed) operations.remove(ticket)
            }) } catch (_: Throwable) {
                // Construction has not bound or allocated browser resources.
                coordinator.finish(ticket, false); return
            }
        operations[ticket] = operation
        if (!main.post(operation::start)) {
            operations.remove(ticket)
            coordinator.finish(ticket, false)
        }
    }
}

// Same-UID observers initiate/join application-owned renewal. No grant fields
// enter readiness IPC and no Activity is launched automatically.
class AbemaAccountReadinessService : Service() {
    companion object {
        private val workers = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(32), { task -> Thread(task, "abema-readiness").apply { isDaemon = true } })
    }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var destroyed = false
    private val delivery = AbemaReadinessTicketDelivery(AndroidAbemaRenewalReadiness::cancelUnclaimed)
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.sendingUid != Process.myUid()) return
            if (message.what == AbemaReadinessIpc.CANCEL) {
                val data = message.data
                if (data.keySet() != setOf("ticket")) return
                @Suppress("DEPRECATION") val ticket = data.get("ticket") as? String ?: return
                if (ticket.matches(Regex("[a-f0-9]{32}"))) AndroidAbemaRenewalReadiness.cancelUnclaimed(ticket)
                return
            }
            if (destroyed || message.what != AbemaReadinessIpc.CHECK) return
            val request = AbemaReadinessIpc.request(message.data, message.sendingUid, Process.myUid(), SystemClock.elapsedRealtime()) ?: return
            val reply = message.replyTo ?: return
            try { workers.execute {
                val decision = if (destroyed || SystemClock.elapsedRealtime() >= request.deadline)
                    AbemaRenewalReadinessCoordinator.Decision(AbemaRenewalReadinessCoordinator.Status.FAILURE)
                else AndroidAbemaRenewalReadiness.check(this@AbemaAccountReadinessService,
                    AbemaReadinessTarget(request.instance, request.ownerProof), request.deadline)
                if (!delivery.created(decision)) return@execute
                main.post {
                    if (destroyed || SystemClock.elapsedRealtime() >= request.deadline) {
                        delivery.drop(decision); return@post
                    }
                    try { reply.send(Message.obtain().apply {
                        what = AbemaReadinessIpc.RESULT; data = AbemaReadinessIpc.response(request, decision)
                    }); delivery.delivered(decision) } catch (_: Throwable) { delivery.drop(decision) }
                }
            } } catch (_: Throwable) { }
        }
    })
    override fun onBind(intent: Intent?) = messenger.binder
    override fun onDestroy() { destroyed = true; delivery.close(); main.removeCallbacksAndMessages(null); super.onDestroy() }
}
