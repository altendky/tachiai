package net.fstab.tachiai.provider.abema.account

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Network
import android.os.*
import android.os.Process
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import net.fstab.tachiai.presentation.validProviderInstanceId
import net.fstab.tachiai.provider.abema.AbemaCatalogRouteOwner
import net.fstab.tachiai.platform.network.AndroidNetworkEgress
import net.fstab.tachiai.platform.network.RouteNetworkEgress

// RAM-only capability captured once for an operation. Each resource gets a
// fresh scope, while all scopes retain this exact Network and original deadline.
internal class AbemaRenewalNetwork(val network: Network, val deadlineElapsedMs: Long) {
    private val deadlineNanos = Math.multiplyExact(deadlineElapsedMs, 1_000_000L)
    init { require(deadlineElapsedMs > 0) }

    fun egressFactory(create: (Network, Long) -> RouteNetworkEgress = { selected, deadline ->
        AndroidNetworkEgress(selected, deadline)
    }): () -> RouteNetworkEgress = { create(network, deadlineNanos) }

    override fun toString() = "AbemaRenewalNetwork(private capability hidden)"
}

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
        val expected: AbemaAccountSnapshot, val beganWall: Long, val deadline: Long,
        val network: AbemaRenewalNetwork? = null) {
        init { require(network == null || network.deadlineElapsedMs == deadline) }
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
    private val ownedAdmission = AbemaRenewalOwnedAdmission(coordinator, operations,
        validate = ::workCurrent, dispatch = { action -> main.post(action) },
        elapsed = SystemClock::elapsedRealtime,
        schedulePending = { deadline, action -> main.postDelayed(action, maxOf(0L, deadline - SystemClock.elapsedRealtime())) },
        authoritative = { work, outcome ->
            if (outcome.result == AbemaAutomaticRenewalResult.SUCCESS)
                AndroidAbemaAccountAccessOwners.invalidate(work.owner.instance.id, work.owner.proof())
        })

    fun check(service: Service, target: AbemaReadinessTarget, deadline: Long,
        network: Network? = null): AbemaRenewalReadinessCoordinator.Decision {
        val reservation = reserve(service, target, deadline, network)
        val status = when (reservation) {
            is AbemaRenewalReservation.Owned -> {
                reservation.lease.activate()
                AbemaRenewalReadinessCoordinator.Status.WAIT
            }
            is AbemaRenewalReservation.Observed -> reservation.status
            is AbemaRenewalReservation.NoWork -> reservation.status
            AbemaRenewalReservation.Deferred -> AbemaRenewalReadinessCoordinator.Status.WAIT
        }
        return AbemaRenewalReadinessCoordinator.Decision(status)
    }

    // The owner must store the returned lease before explicitly activating it.
    // Observers never receive a ticket, operation, transport or cancellation right.
    fun reserve(context: Context, target: AbemaReadinessTarget, deadline: Long,
        network: Network? = null, admitted: () -> Boolean = { true },
        notify: (AbemaRenewalOperationOutcome) -> Unit = {},
    ): AbemaRenewalReservation {
        val instance = target.instanceId
        fun permitted() = runCatching(admitted).getOrDefault(false)
        fun invalidateTarget() = coordinator.invalidate(instance) { it.owner.proof() == target.ownerProof }
        fun failed(status: AbemaRenewalReadinessCoordinator.Status = AbemaRenewalReadinessCoordinator.Status.FAILURE): AbemaRenewalReadinessCoordinator.Decision {
            invalidateTarget()
            return AbemaRenewalReadinessCoordinator.Decision(status)
        }
        return try {
            ownedAdmission.retireExpiredPending()
            if (!permitted()) return AbemaRenewalReservation.Deferred
            coordinator.observeClaimed(instance) { it.owner.proof() == target.ownerProof }?.let {
                return AbemaRenewalReservation.Observed(it.status)
            }
            val capturedNetwork = network?.let { AbemaRenewalNetwork(it, deadline) }
            val binding = androidAbemaAccountConnectionBinding(context, instance, target.ownerProof,
                renewalEgressFactory = capturedNetwork?.egressFactory())
            val owner = binding.owner()
            val snapshot = binding.store.readSnapshot()
            val wall = System.currentTimeMillis()
            if (!permitted()) return AbemaRenewalReservation.Deferred
            var observed = false
            abemaReadinessRecordAdmission(
                ownerCurrent = { owner.instance.id == instance && owner.proof() == target.ownerProof && binding.sameOwner(owner) },
                claimed = { coordinator.observeClaimed(instance) { it.owner.proof() == target.ownerProof }
                    ?.also { observed = true } },
                recordCurrent = { binding.store.usable(snapshot.revision) }, refused = { failed() })?.let {
                return if (observed) AbemaRenewalReservation.Observed(it.status) else AbemaRenewalReservation.NoWork(it.status)
            }
            if (!permitted()) return AbemaRenewalReservation.Deferred
            val record = snapshot.record
            if (record == null) {
                invalidateTarget()
                return AbemaRenewalReservation.NoWork(AbemaRenewalReadinessCoordinator.Status.READY)
            }
            if (wall < record.acceptedWallMs || decodedAbemaGrant(record.grant.bearer, record.grant.userId,
                    record.acceptedWallMs).expiresAtMs != record.grant.expiresAtMs)
                return AbemaRenewalReservation.NoWork(failed().status)
            if (wall < record.grant.expiresAtMs) {
                invalidateTarget()
                return AbemaRenewalReservation.NoWork(AbemaRenewalReadinessCoordinator.Status.READY)
            }
            if (!abemaRenewalSnapshotMatches(snapshot, snapshot, wall)) return AbemaRenewalReservation.NoWork(failed().status)
            // WAIT observes existing work; it never replaces that work's
            // capability/deadline or grants control to this observer.
            ownedAdmission.reserve(instance, Scope(owner.proof(), snapshot),
                Work(binding, owner, snapshot, wall, deadline, capturedNetwork), deadline, admitted,
                construct = { ticket, work, completed -> createOperation(context.applicationContext, ticket, work, completed) },
                notify = notify)
        } catch (error: AbemaAccountException) {
            if (!permitted()) return AbemaRenewalReservation.Deferred
            AbemaRenewalReservation.NoWork(failed(when (error.category) {
                AbemaAccountFailure.STORAGE_FAILED -> AbemaRenewalReadinessCoordinator.Status.STORAGE_FAILURE
                AbemaAccountFailure.CANCELLED -> AbemaRenewalReadinessCoordinator.Status.FAILURE
                else -> AbemaRenewalReadinessCoordinator.Status.TEMPORARY_FAILURE
            }).status)
        } catch (_: Throwable) {
            if (!permitted()) AbemaRenewalReservation.Deferred
            else AbemaRenewalReservation.NoWork(failed(AbemaRenewalReadinessCoordinator.Status.TEMPORARY_FAILURE).status)
        }
    }
    private fun workCurrent(ticket: String, work: Work): Boolean =
        runCatching { work.binding.sameOwner(work.owner) && work.binding.store.usable(work.expected.revision) &&
            System.currentTimeMillis() >= work.beganWall && abemaRenewalSnapshotMatches(work.expected,
                work.binding.store.readSnapshot(), System.currentTimeMillis()) && coordinator.current(ticket) }.getOrDefault(false)
    fun cancelUnclaimed(ticket: String) = coordinator.cancelUnclaimed(ticket)

    // In-process, closed observation only: no readiness request or grant leaves
    // this process, and a different owner/predecessor cannot satisfy the probe.
    fun completion(instance: String, ownerProof: String, expected: AbemaAccountSnapshot): AbemaRenewalReadinessCoordinator.Completion {
        return evidence(instance, ownerProof, expected).completion
    }
    fun evidence(instance: String, ownerProof: String, expected: AbemaAccountSnapshot): AbemaRenewalReadinessCoordinator.Evidence {
        val scope = Scope(ownerProof, expected)
        return coordinator.evidence(instance) { Scope(it.owner.proof(), it.expected) == scope }
    }

    private fun createOperation(context: Context, ticket: String, work: Work,
        completed: (AbemaRenewalOperationOutcome) -> Unit): AbemaAccountRenewalOperation =
        AbemaAccountRenewalOperation(work.binding, work.owner, work.expected, work.deadline,
            AndroidAbemaRenewalBrowser(context, work.network?.network), AndroidAbemaEmailGrantHandoff.inbox,
            AbemaAccountWorkers.worker, AbemaAccountWorkers.cancellation,
            { action -> check(main.post(action)) }, { delay, action -> check(main.postDelayed(action, delay)) },
            SystemClock::elapsedRealtime, System::currentTimeMillis, { coordinator.current(ticket) },
            beforeApproval = { id, deadline, gate ->
                net.fstab.tachiai.platform.network.ProviderStartupScopeCleanup.awaitReady(id, deadline, gate)
            }, completed = completed,
            completionUnavailable = completed)
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
