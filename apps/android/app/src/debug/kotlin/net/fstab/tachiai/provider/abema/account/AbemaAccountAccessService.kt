package net.fstab.tachiai.provider.abema.account

import android.app.Application
import android.app.Service
import android.content.Intent
import android.content.Context
import android.os.*
import android.os.Process
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.presentation.validProviderInstanceId

// Closed RAM-only protocol. Bundle keys are unique; no JSON parsing or generic
// Parcelable/Serializable values are admitted. Credentials never enter Intents.
internal object AbemaAccountAccessIpc {
    const val OPEN = 1; const val ACQUIRE = 2; const val CHECK = 3; const val CLOSE = 4
    const val READY = 5; const val RESULT = 6; const val CHECKED = 7; const val CLOSED = 8; const val REVOKED = 9
    const val IPC_MS = 5000L
    enum class Status { ACCOUNT, GUEST, FAILURE }
    class Request(val nonce: String, val instance: String, val proof: String, val source: String,
        val deadline: Long, val playback: Boolean) {
        override fun toString() = "AbemaAccountAccessRequest(redacted)"
    }
    class Result(val status: Status, val grant: AbemaAccountGrant? = null, val deviceId: String? = null,
        val failure: AbemaAccountFailure? = null) {
        override fun toString() = "AbemaAccountAccessResult(${status.name})"
    }
    private val keys = setOf("nonce", "instance", "proof", "source", "deadline", "playback", "sequence")
    fun fields(bundle: Bundle): Map<String, Any>? = runCatching {
        var size = 0
        buildMap {
            if (bundle.size() !in 7..12) return null
            for (key in bundle.keySet()) {
                if (key.length > 16) return null
                @Suppress("DEPRECATION") val value = bundle.get(key)
                if (value !is String && value !is Long && value !is Boolean) return null
                if (value is String) { size += value.length; if (value.length > 6144) return null }
                if (size > 8192) return null
                put(key, value)
            }
        }
    }.getOrNull()
    fun request(fields: Map<String, Any>, now: Long): Request? {
        if (fields.keys != keys || now < 0 || fields["sequence"] !is Long || (fields["sequence"] as Long) < 1) return null
        val nonce = fields["nonce"] as? String ?: return null
        val instance = fields["instance"] as? String ?: return null
        val proof = fields["proof"] as? String ?: return null
        val source = fields["source"] as? String ?: return null
        val deadline = fields["deadline"] as? Long ?: return null
        val playback = fields["playback"] as? Boolean ?: return null
        if (!nonce.matches(Regex("[a-f0-9]{32}")) || !validProviderInstanceId(instance) ||
            !proof.matches(Regex("[a-f0-9]{64}")) || !source.matches(Regex("[a-f0-9]{64}")) ||
            deadline <= now || deadline - now > 300000) return null
        return Request(nonce, instance, proof, source, deadline, playback)
    }
    fun base(request: Request, sequence: Long): Map<String, Any> = mapOf("nonce" to request.nonce,
        "instance" to request.instance, "proof" to request.proof, "source" to request.source,
        "deadline" to request.deadline, "playback" to request.playback, "sequence" to sequence)
    fun matches(fields: Map<String, Any>, request: Request, sequence: Long) =
        keys.all { fields[it] == base(request, sequence)[it] }
    fun result(fields: Map<String, Any>, request: Request, sequence: Long, wall: Long): Result? = runCatching {
        if (!matches(fields, request, sequence)) return null
        val status = Status.valueOf(fields["status"] as? String ?: return null)
        when (status) {
            Status.GUEST -> if (fields.keys == keys + "status") Result(status) else null
            Status.FAILURE -> if (fields.keys == keys + setOf("status", "failure"))
                Result(status, failure = AbemaAccountFailure.valueOf(fields["failure"] as? String ?: return null)) else null
            Status.ACCOUNT -> {
                if (fields.keys != keys + setOf("status", "userId", "bearer", "expiry") +
                    (if (request.playback) setOf("deviceId") else emptySet())) return null
                val grant = decodedAbemaGrant(fields["bearer"] as? String ?: return null,
                    fields["userId"] as? String ?: return null, wall)
                if (fields["expiry"] != grant.expiresAtMs) return null
                val device = if (request.playback) abemaPlaybackDeviceId(grant, wall) else null
                if (request.playback && fields["deviceId"] != device) return null
                Result(status, grant, device)
            }
        }
    }.getOrNull()
    fun bundle(fields: Map<String, Any>) = Bundle().apply {
        for ((key, value) in fields) when (value) {
            is String -> putString(key, value); is Long -> putLong(key, value); is Boolean -> putBoolean(key, value)
            else -> error("Unsupported private field")
        }
    }
}

// Validation is shared only for the exact captured candidate proof. Another
// configured route cannot inherit this candidate's accepted profile response.
internal class AbemaAccountAccessOwnerRegistry {
    private class Entry(val owner: AbemaAccountAccessOwner, val routeCurrent: () -> Boolean) {
        val retired = AtomicBoolean()
        @Volatile var revoked = false
    }
    private val shared = ConcurrentHashMap<String, Entry>()
    private val retired = ConcurrentHashMap.newKeySet<AbemaAccountAccessOwner>()
    internal val retiredCount get() = retired.size
    fun owner(instanceId: String, ownerProof: String, routeCurrent: () -> Boolean = { true },
        create: () -> AbemaAccountAccessOwner): AbemaAccountAccessOwner {
        val key = "$instanceId|$ownerProof"
        while (true) {
            if (!runCatching(routeCurrent).getOrDefault(false)) accountRefused()
            val existing = shared[key]
            if (existing != null) {
                if (!existing.retired.get() && runCatching(existing.routeCurrent).getOrDefault(false)) return existing.owner
                if (existing.retired.compareAndSet(false, true)) {
                    retired.add(existing.owner)
                    existing.owner.close() // Revoke old cheap leases before allowing replacement.
                    existing.revoked = true
                    existing.owner.whenRetiredIdle {
                        retired.remove(existing.owner); shared.remove(key, existing)
                    }
                }
                // Pending or uncertain old cleanup cannot start another backend.
                if (!existing.revoked || !existing.owner.cleanupConfirmed) throw AbemaAccountException(AbemaAccountFailure.CANCELLED)
                shared.remove(key, existing)
                continue
            }
            val created = Entry(create(), routeCurrent)
            if (shared.putIfAbsent(key, created) == null) return created.owner
            created.owner.close() // Unattached factory allocation owns no transport.
        }
    }
    fun invalidate(instanceId: String, ownerProof: String) { shared["$instanceId|$ownerProof"]?.owner?.invalidate() }
}

// Only the ordinary process constructs stores/route owners. Cleanup poison is
// retained per instance, including across candidate/pool proof changes.
internal object AndroidAbemaAccountAccessOwners {
    private val owners = AbemaAccountAccessOwnerRegistry()
    private val blocked = ConcurrentHashMap.newKeySet<String>()
    fun invalidate(instanceId: String, ownerProof: String) {
        // A durably renewed account needs a fresh profile proof for surviving
        // consumers. Retain the owner and any cleanup poison; never replace it.
        owners.invalidate(instanceId, ownerProof)
    }
    fun poison(request: AbemaAccountAccessIpc.Request) {
        blocked.add(request.instance)
        AbemaAccountWorkers.poisoned(request.instance).set(true)
    }
    fun attach(service: Service, request: AbemaAccountAccessIpc.Request): AbemaAccountAccessOwner.Attachment {
        return attach(service, request.instance, request.proof)
    }
    fun attach(context: Context, instanceId: String, ownerProof: String): AbemaAccountAccessOwner.Attachment {
        if (instanceId in blocked || AbemaAccountWorkers.poisoned(instanceId).get())
            throw AbemaAccountException(AbemaAccountFailure.CLEANUP_FAILED)
        val binding = androidAbemaAccountConnectionBinding(context, instanceId, ownerProof)
        val captured = binding.owner()
        if (captured.proof() != ownerProof || !binding.sameOwner(captured)) accountRefused()
        if (instanceId in blocked || AbemaAccountWorkers.poisoned(instanceId).get())
            throw AbemaAccountException(AbemaAccountFailure.CLEANUP_FAILED)
        val owner = owners.owner(instanceId, ownerProof, routeCurrent = { binding.routeCurrent }) {
            AbemaAccountAccessOwner(instanceId, ownerProof, binding.store, System::currentTimeMillis,
                SystemClock::elapsedRealtime, { binding.sameOwner(captured) },
                { gate -> binding.transport(captured, gate) },
                { transport -> ((transport as? OwnedAbemaAccountTransport)?.cleanupConfirmed == true).also { confirmed ->
                    if (!confirmed) { blocked.add(instanceId); AbemaAccountWorkers.poisoned(instanceId).set(true) }
                } })
        }
        // A reused registry owner does not replace the fresh caller's scope
        // check. Durable acquire/lease checks still validate the shared grant
        // revision and exact stored record against the captured route owner.
        return attachAbemaAccountAccessOwner(owner,
            freshScopeCurrent = { binding.sameOwner(captured) && instanceId !in blocked && !AbemaAccountWorkers.poisoned(instanceId).get() },
            cleanupFailed = { blocked.add(instanceId); AbemaAccountWorkers.poisoned(instanceId).set(true) })
    }
}

// Worker-only seam: cached owners must not substitute their original caller's
// scope for the current attach request. No protected work occurs under a lock.
internal fun attachAbemaAccountAccessOwner(owner: AbemaAccountAccessOwner, freshScopeCurrent: () -> Boolean,
    cleanupFailed: () -> Unit): AbemaAccountAccessOwner.Attachment {
    if (!runCatching(freshScopeCurrent).getOrDefault(false)) accountRefused()
    val attachment = owner.attach()
    if (!runCatching(freshScopeCurrent).getOrDefault(false)) {
        attachment.close()
        if (!attachment.cleanupConfirmed) cleanupFailed()
        accountRefused()
    }
    return attachment
}

class AbemaAccountAccessService : Service() {
    companion object {
        // Independent waiters can attach to the core-owned shared flight. The
        // account mutation executor remains separately serialized.
        private val workers = ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(32), { task -> Thread(task, "abema-account-access").apply { isDaemon = true } })
    }
    private val main = Handler(Looper.getMainLooper())
    private val sessions = mutableMapOf<String, Session>() // Main-owned.
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.sendingUid != Process.myUid()) return
            val reply = message.replyTo ?: return
            val fields = AbemaAccountAccessIpc.fields(message.data) ?: return
            message.data.clear()
            val nonce = fields["nonce"] as? String ?: return
            val sequence = fields["sequence"] as? Long ?: return
            if (message.what == AbemaAccountAccessIpc.OPEN) {
                val request = AbemaAccountAccessIpc.request(fields, SystemClock.elapsedRealtime()) ?: return
                if (sequence != 1L || sessions.containsKey(nonce)) return
                val session = Session(request, reply)
                sessions[nonce] = session
                session.open(); return
            }
            val session = sessions[nonce] ?: return
            if (message.what !in setOf(AbemaAccountAccessIpc.ACQUIRE, AbemaAccountAccessIpc.CHECK, AbemaAccountAccessIpc.CLOSE)) return
            if (!AbemaAccountAccessIpc.matches(fields, session.request, sequence) || fields.size != 7 ||
                reply.binder != session.reply.binder || sequence <= session.sequence) return
            session.sequence = sequence
            when (message.what) {
                AbemaAccountAccessIpc.ACQUIRE -> session.acquire(sequence)
                AbemaAccountAccessIpc.CHECK -> session.check(sequence)
                AbemaAccountAccessIpc.CLOSE -> session.end(sequence)
            }
        }
    })
    private inner class Session(val request: AbemaAccountAccessIpc.Request, val reply: Messenger) {
        var sequence = 1L
        private val ended = AtomicBoolean()
        private val busy = AtomicBoolean()
        private val jobs = AtomicInteger()
        @Volatile private var attachment: AbemaAccountAccessOwner.Attachment? = null
        @Volatile private var lease: AbemaAccountAccessLease? = null
        private var acquireStarted = false
        private val death = IBinder.DeathRecipient { main.post { end(sequence) } }
        private fun current(): Boolean {
            val now = SystemClock.elapsedRealtime()
            return !ended.get() && now >= 0 && now < request.deadline && lease?.current() != false
        }
        fun open() {
            try { reply.binder.linkToDeath(death, 0) }
            catch (_: Throwable) { end(sequence); return }
            send(AbemaAccountAccessIpc.READY, 1)
            pulse()
        }
        private fun pulse() {
            if (!current()) { send(AbemaAccountAccessIpc.REVOKED, sequence); end(sequence); return }
            main.postAtTime({ pulse() }, this, SystemClock.uptimeMillis() + 100)
        }
        private fun worker(action: () -> Unit) {
            jobs.incrementAndGet()
            try { workers.execute {
                try { action() } catch (_: Throwable) { main.post { end(sequence) } }
                finally { jobs.decrementAndGet() }
            } } catch (_: Throwable) { jobs.decrementAndGet(); busy.set(false); main.post { end(sequence) } }
        }
        fun acquire(seq: Long) {
            if (acquireStarted || !current() || !busy.compareAndSet(false, true)) { end(seq); return }
            acquireStarted = true
            val began = SystemClock.elapsedRealtime()
            if (began < 0 || began > Long.MAX_VALUE - 30000) { busy.set(false); end(seq); return }
            val deadline = minOf(request.deadline, began + 30000)
            worker {
                var result = AbemaAccountAccessIpc.Result(AbemaAccountAccessIpc.Status.FAILURE, failure = AbemaAccountFailure.CANCELLED)
                try {
                    if (!current()) accountRefused()
                    val attached = AndroidAbemaAccountAccessOwners.attach(this@AbemaAccountAccessService, request)
                    attachment = attached
                    if (!current()) { attached.close(); accountRefused() }
                    when (val access = attached.acquire(began, deadline)) {
                        is AbemaAccountAccessResult.Account -> {
                            val grant = access.lease.grant ?: accountRefused()
                            val device = if (request.playback) abemaPlaybackDeviceId(grant, System.currentTimeMillis()) else null
                            if (!current() || !access.lease.checkWorker() || !current()) accountRefused()
                            lease = access.lease
                            result = AbemaAccountAccessIpc.Result(AbemaAccountAccessIpc.Status.ACCOUNT, grant, device)
                        }
                        is AbemaAccountAccessResult.Guest -> {
                            if (!current() || !access.lease.checkWorker() || !current()) accountRefused()
                            lease = access.lease; result = AbemaAccountAccessIpc.Result(AbemaAccountAccessIpc.Status.GUEST)
                        }
                        is AbemaAccountAccessResult.Failed -> result = AbemaAccountAccessIpc.Result(
                            AbemaAccountAccessIpc.Status.FAILURE, failure = access.category)
                    }
                } catch (error: AbemaAccountException) {
                    result = AbemaAccountAccessIpc.Result(AbemaAccountAccessIpc.Status.FAILURE, failure = error.category)
                } catch (_: Throwable) { result = AbemaAccountAccessIpc.Result(
                    AbemaAccountAccessIpc.Status.FAILURE, failure = AbemaAccountFailure.TEMPORARY) }
                val observed = result
                main.post {
                    busy.set(false)
                    if (ended.get()) return@post
                    val values = mutableMapOf<String, Any>("status" to observed.status.name)
                    observed.grant?.let { values["userId"] = it.userId; values["bearer"] = it.bearer; values["expiry"] = it.expiresAtMs }
                    observed.deviceId?.let { values["deviceId"] = it }
                    observed.failure?.let { values["failure"] = it.name }
                    if (observed.status != AbemaAccountAccessIpc.Status.FAILURE && !current()) { end(seq); return@post }
                    send(AbemaAccountAccessIpc.RESULT, seq, values)
                }
            }
        }
        fun check(seq: Long) {
            if (!current() || lease == null || !busy.compareAndSet(false, true)) { end(seq); return }
            worker {
                val okay = current() && runCatching { lease?.checkWorker() == true }.getOrDefault(false) && current()
                main.post {
                    busy.set(false)
                    if (ended.get()) return@post
                    send(AbemaAccountAccessIpc.CHECKED, seq, mapOf("current" to (okay && current())))
                    if (!okay) end(seq)
                }
            }
        }
        private fun send(what: Int, seq: Long, extra: Map<String, Any> = emptyMap()) {
            val data = AbemaAccountAccessIpc.bundle(AbemaAccountAccessIpc.base(request, seq) + extra)
            try { reply.send(Message.obtain().apply { this.what = what; this.data = data }) }
            catch (_: Throwable) { main.post { end(sequence) } }
            finally { data.clear() }
        }
        fun end(seq: Long) {
            if (!ended.compareAndSet(false, true)) return
            main.removeCallbacksAndMessages(this)
            attachment?.close(); lease = null
            try { AbemaAccountWorkers.cancellation.execute {
                val began = SystemClock.elapsedRealtime()
                var confirmed = false
                while (SystemClock.elapsedRealtime() - began in 0 until AbemaAccountAccessIpc.IPC_MS) {
                    // Creation can finish after cancellation; close that exact attachment too.
                    attachment?.close()
                    if (jobs.get() == 0 && (attachment?.cleanupConfirmed != false)) { confirmed = true; break }
                    try { Thread.sleep(10) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
                }
                val receipt = confirmed
                if (!receipt) AndroidAbemaAccountAccessOwners.poison(request)
                main.post {
                    send(AbemaAccountAccessIpc.CLOSED, seq, mapOf("confirmed" to receipt))
                    if (receipt) {
                        runCatching { reply.binder.unlinkToDeath(death, 0) }
                        if (sessions[request.nonce] === this) sessions.remove(request.nonce)
                        attachment = null; lease = null
                    } // Uncertainty retains the exact session/owner until process restart.
                }
            } } catch (_: Throwable) {
                AndroidAbemaAccountAccessOwners.poison(request)
                send(AbemaAccountAccessIpc.CLOSED, seq, mapOf("confirmed" to false))
            }
        }
    }
    override fun onBind(intent: Intent): IBinder? = if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 28 &&
        Application.getProcessName() == packageName) messenger.binder else null
    override fun onDestroy() { sessions.values.toList().forEach { it.end(it.sequence) }; super.onDestroy() }
}
