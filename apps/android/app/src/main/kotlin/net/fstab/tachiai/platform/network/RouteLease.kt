package net.fstab.tachiai.platform.network

import android.net.Network
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

// Closed RAM-only lease protocol between the playback process and the route
// owner in the ordinary process (#275). Configuration never crosses Binder:
// the owner re-reads the saved profile matching the digest and derives the
// allowed hosts from the closed purpose. Lease credentials cross once, in OPEN.
// A renewal job's OPEN (stage 5) also carries the job's Network and deadline,
// which the owner uses only to start a job-bound device when none is held.
internal object RouteLeaseIpc {
    const val OPEN = 1; const val CLOSE = 2; const val READY = 3; const val REVOKED = 4; const val CLOSED = 5
    const val IPC_MS = 5000L
    // A renewal job's whole operation limit.
    const val JOB_MS = 90_000L
    class Open(val nonce: String, val digest: String, val purpose: RoutePurpose, val username: String,
        val password: String, val realm: String, val deadline: Long, val job: RouteJobNetwork? = null) {
        override fun toString() = "RouteLeaseOpen(redacted)"
    }
    class Reply(val what: Int, val sequence: Long, val port: Int = 0, val confirmed: Boolean = false)
    private val base = setOf("nonce", "sequence")
    private val openKeys = base + setOf("digest", "purpose", "username", "password", "realm", "deadline")
    private val jobKeys = openKeys + setOf("network", "until")
    // Matches routebridge's local credential pattern.
    private val credential = Regex("[A-Za-z0-9_-]{16,128}")

    private val knownKeys = jobKeys + setOf("port", "confirmed")

    fun fields(values: Map<String, Any?>): Map<String, Any>? {
        if (values.size !in base.size..jobKeys.size) return null
        var size = 0
        return buildMap {
            for ((key, value) in values) {
                val typed = value is String || value is Long || value is Boolean || key == "network" && value is Network
                if (key !in knownKeys || !typed) return null
                if (value is String) { size += value.length; if (value.length > 128 || size > 1024) return null }
                put(key, checkNotNull(value))
            }
        }
    }
    fun open(fields: Map<String, Any>, now: Long): Open? {
        val job = if (fields.keys == jobKeys) {
            val until = fields["until"] as? Long ?: return null
            val network = fields["network"] as? Network ?: return null
            if (until <= now || until - now > JOB_MS) return null
            RouteJobNetwork(network, until)
        } else null
        if ((job == null && fields.keys != openKeys) || fields["sequence"] != 1L || now < 0) return null
        val nonce = fields["nonce"] as? String ?: return null
        val digest = fields["digest"] as? String ?: return null
        val purpose = RoutePurpose.entries.singleOrNull { it.name == fields["purpose"] } ?: return null
        val username = fields["username"] as? String ?: return null
        val password = fields["password"] as? String ?: return null
        val realm = fields["realm"] as? String ?: return null
        val deadline = fields["deadline"] as? Long ?: return null
        if (!nonce.matches(Regex("[a-f0-9]{32}")) || !digest.matches(Regex("[a-f0-9]{64}")) ||
            listOf(username, password, realm).any { !credential.matches(it) } ||
            deadline <= now || deadline - now > IPC_MS) return null
        return Open(nonce, digest, purpose, username, password, realm, deadline, job)
    }
    fun openFields(open: Open): Map<String, Any> = mapOf("nonce" to open.nonce, "sequence" to 1L, "digest" to open.digest,
        "purpose" to open.purpose.name, "username" to open.username, "password" to open.password,
        "realm" to open.realm, "deadline" to open.deadline) +
        (open.job?.let { mapOf("network" to it.network, "until" to it.deadlineElapsedMs) } ?: emptyMap())
    // Client CLOSE: the lease's nonce and a sequence after the last one seen.
    fun close(fields: Map<String, Any>, nonce: String, after: Long): Long? {
        val sequence = fields["sequence"] as? Long ?: return null
        return sequence.takeIf { fields.keys == base && fields["nonce"] == nonce && it > after }
    }
    fun reply(what: Int, fields: Map<String, Any>, nonce: String, after: Long): Reply? {
        val sequence = fields["sequence"] as? Long ?: return null
        if (fields["nonce"] != nonce || sequence <= after) return null
        return when (what) {
            READY -> (fields["port"] as? Long)?.takeIf { fields.keys == base + "port" && it in 1L..65535L }
                ?.let { Reply(what, sequence, port = it.toInt()) }
            REVOKED -> if (fields.keys == base) Reply(what, sequence) else null
            CLOSED -> (fields["confirmed"] as? Boolean)?.takeIf { fields.keys == base + "confirmed" }
                ?.let { Reply(what, sequence, confirmed = it) }
            else -> null
        }
    }
}

// Owner side: only a saved WireGuard profile with exactly this digest, with
// hosts derived from the purpose. Other protocols keep their local backends.
// A job OPEN takes the owner's job path: join, or start a job-bound device.
internal fun grantRouteLease(request: RouteLeaseIpc.Open, configured: (String) -> ConnectionProfile?,
    leaseForJob: (ConnectionProfile, RouteProxySecurity, RouteJobNetwork) -> RouteBackend = { _, _, _ ->
        throw IOException("Job route unavailable") },
    lease: (ConnectionProfile, RouteProxySecurity) -> RouteBackend): RouteBackend {
    val profile = configured(request.digest)?.takeIf {
        it.protocol === WireGuardRouteProtocol && routeConfigurationKey(it) == request.digest
    } ?: throw IOException("Route profile unavailable")
    val security = RouteProxySecurity(request.username, request.password, request.realm,
        request.purpose.allowedHosts, request.purpose)
    return request.job?.let { leaseForJob(profile, security, it) } ?: lease(profile, security)
}

// One owner-side lease per OPEN: at most one READY, then exactly one CLOSED
// with the bounded close outcome. A lease granted after the client left or
// past its deadline is closed at once. Sends carry an increasing sequence.
internal class RouteLeaseGrant(
    val request: RouteLeaseIpc.Open,
    private val grant: (RouteLeaseIpc.Open) -> RouteBackend,
    private val send: (Int, Map<String, Any>) -> Unit,
    private val execute: (Runnable) -> Unit,
    private val now: () -> Long,
    // Inside the client's IPC_MS wait for CLOSED, so a slow but completed
    // close is still reported as confirmed (#275 stage 5).
    private val closeMs: Long = RouteLeaseIpc.IPC_MS - 1000,
    private val closed: (Boolean) -> Unit = {},
    // Closed reason for an unconfirmed close; never a host or credential.
    private val log: (String) -> Unit = {},
) {
    private enum class State { GRANTING, READY, ENDING }
    private val lock = Any()
    private var state = State.GRANTING
    private var backend: RouteBackend? = null
    private var sequence = 0L

    fun start() = onWorker {
        val leased = if (synchronized(lock) { state != State.GRANTING } || now() >= request.deadline) null
            else runCatching { grant(request) }.getOrNull()
        val port = leased?.let { runCatching { it.proxyPort }.getOrNull() }?.takeIf { it in 1..65535 }
        val ready = synchronized(lock) {
            (port != null && state == State.GRANTING && now() < request.deadline).also { ready ->
                if (ready) { backend = leased; state = State.READY } else state = State.ENDING
            }
        }
        if (ready) reply(RouteLeaseIpc.READY, mapOf("port" to checkNotNull(port).toLong()))
        else finish(leased)
    }

    // Client CLOSE, client death or owner shutdown (revoke). A grant still in
    // progress finishes the close itself when it returns.
    fun end(revoke: Boolean = false) {
        val held = synchronized(lock) {
            val was = state
            if (was == State.ENDING) return
            state = State.ENDING
            if (was == State.GRANTING) return
            backend.also { backend = null }
        }
        if (revoke) reply(RouteLeaseIpc.REVOKED)
        onWorker { finish(held) }
    }

    private fun onWorker(action: () -> Unit) {
        try { execute(Runnable(action)) }
        catch (_: Throwable) {
            log("LEASE_CLOSE_UNCONFIRMED reason=REJECTED")
            reply(RouteLeaseIpc.CLOSED, mapOf("confirmed" to false)); closed(false)
        }
    }

    private fun finish(held: RouteBackend?) {
        val failure = if (held == null) null else try {
            val task = FutureTask { held.close() }
            execute(task)
            task.get(closeMs, TimeUnit.MILLISECONDS)
            null
        } catch (_: java.util.concurrent.TimeoutException) { "TIMEOUT" }
        catch (_: java.util.concurrent.ExecutionException) { "FAILED" }
        catch (error: Throwable) {
            if (error is InterruptedException) { Thread.currentThread().interrupt(); "INTERRUPTED" } else "REJECTED"
        }
        failure?.let { log("LEASE_CLOSE_UNCONFIRMED reason=$it") }
        reply(RouteLeaseIpc.CLOSED, mapOf("confirmed" to (failure == null)))
        closed(failure == null)
    }

    private fun reply(what: Int, extra: Map<String, Any> = emptyMap()) = synchronized(lock) {
        runCatching { send(what, mapOf("nonce" to request.nonce, "sequence" to ++sequence) + extra) }
    }

    override fun toString() = "RouteLeaseGrant(redacted)"
}

// One Binder binding per lease. Android binds the owner's Messenger; tests
// deliver owner replies directly.
internal interface RouteLeaseChannel {
    interface Events {
        fun connected()
        fun received(what: Int, fields: Map<String, Any>)
        // The owner process died or refused the binding.
        fun lost()
    }
    fun connect(events: Events): Boolean
    fun send(what: Int, fields: Map<String, Any>)
    fun release()
}

// Playback-process WireGuard lease from the ordinary process's route owner.
// There is no local or System fallback: an unavailable owner fails the route,
// and a lost owner leaves the lease's port closed so feeds fail into recovery.
internal class RemoteRouteLeaseBackend private constructor(
    private val channel: RouteLeaseChannel,
    private val request: RouteLeaseIpc.Open,
    private val now: () -> Long,
    private val waitMs: Long,
    // Closed reason for an unconfirmed close; never a host or credential.
    private val log: (String) -> Unit,
) : RouteBackend, RouteLeaseChannel.Events {
    companion object {
        private val retained = ConcurrentLinkedQueue<Any>()
        private val random = SecureRandom()

        fun open(channel: RouteLeaseChannel, profile: ConnectionProfile, security: RouteProxySecurity,
            preparation: RoutePreparation, now: () -> Long, waitMs: Long = RouteLeaseIpc.IPC_MS,
            job: RouteJobNetwork? = null, log: (String) -> Unit = {}): RouteBackend {
            preparation.checkActive()
            val purpose = checkNotNull(security.purpose)
            check(profile.protocol === WireGuardRouteProtocol && purpose.allowedHosts == security.allowedHosts)
            val nonce = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            val request = RouteLeaseIpc.Open(nonce, routeConfigurationKey(profile), purpose,
                security.username, security.password, security.realm, now() + waitMs, job)
            val lease = RemoteRouteLeaseBackend(channel, request, now, waitMs, log)
            var hook: AutoCloseable? = null
            try {
                hook = preparation.onCancel(lease::cancel)
                lease.awaitReady()
                hook.close(); hook = null
                preparation.checkActive()
                return lease
            } catch (error: Throwable) {
                runCatching { hook?.close() }
                if (runCatching { lease.close() }.isFailure) preparation.recordCleanupFailure()
                throw error
            }
        }
    }
    private val lock = Any()
    private val ready = CountDownLatch(1)
    private val done = CountDownLatch(1)
    private var port = 0
    private var opened = false
    private var closing = false
    private var revoked = false
    private var ownerGone = false
    private var closedConfirmed: Boolean? = null
    private var ownerSequence = 0L
    private val closeOnce = lazy { runCatching { finish() } }

    override val proxyPort get() = synchronized(lock) { port }

    private fun awaitReady() {
        if (!channel.connect(this)) throw IOException("Route owner unavailable")
        val left = request.deadline - now()
        val arrived = left > 0 && try { ready.await(left, TimeUnit.MILLISECONDS) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        synchronized(lock) { if (!arrived || port == 0 || revoked || closing) throw IOException("Route lease unavailable") }
    }

    private fun cancel() { synchronized(lock) { revoked = true }; ready.countDown() }

    override fun connected() {
        val send = synchronized(lock) { (!opened && !closing && !ownerGone && !revoked).also { if (it) opened = true } }
        if (!send) return
        try { channel.send(RouteLeaseIpc.OPEN, RouteLeaseIpc.openFields(request)) }
        catch (_: Throwable) { lost() }
    }

    override fun received(what: Int, fields: Map<String, Any>) {
        synchronized(lock) {
            val reply = RouteLeaseIpc.reply(what, fields, request.nonce, ownerSequence) ?: return
            if (!opened) return
            ownerSequence = reply.sequence
            when (reply.what) {
                RouteLeaseIpc.READY -> if (port == 0 && !revoked && !closing) port = reply.port
                RouteLeaseIpc.REVOKED -> revoked = true
                RouteLeaseIpc.CLOSED -> { revoked = true; closedConfirmed = reply.confirmed }
            }
            if (reply.what == RouteLeaseIpc.CLOSED) done.countDown()
        }
        ready.countDown()
    }

    // Binder death means the owner process, and with it this lease's device,
    // is gone. Nothing remains to release there.
    override fun lost() {
        synchronized(lock) { ownerGone = true; revoked = true }
        ready.countDown(); done.countDown()
    }

    // Close once; repeated calls report the first outcome. Uncertainty throws
    // so the route's existing receipts poison and retain it.
    override fun close() { closeOnce.value.getOrThrow() }

    private fun finish() {
        val send = synchronized(lock) {
            closing = true
            opened && closedConfirmed == null && !ownerGone
        }
        ready.countDown()
        if (send) {
            // A failed send still waits: an owner that died reports it as lost.
            runCatching { channel.send(RouteLeaseIpc.CLOSE, mapOf("nonce" to request.nonce, "sequence" to 2L)) }
            try { done.await(waitMs, TimeUnit.MILLISECONDS) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        val (confirmed, reply) = synchronized(lock) { (!opened || ownerGone || closedConfirmed == true) to closedConfirmed }
        val released = runCatching { channel.release() }.isSuccess
        if (!confirmed || !released) {
            log("LEASE_CLOSE_UNCONFIRMED reason=" + when {
                !confirmed && reply == false -> "OWNER_UNCONFIRMED"
                !confirmed -> "NO_REPLY"
                else -> "RELEASE_FAILED"
            })
            retained.add(this); throw IOException("Route lease cleanup unconfirmed")
        }
    }

    override fun toString() = "RemoteRouteLeaseBackend(secrets hidden)"
}

// The single process decision: the playback, ABEMA guest and approval-browser
// processes lease WireGuard devices from the ordinary process (#275 stage 4), so
// one key pair never runs two devices beside a playing feed. The renewal job's
// leases, including the approval browser's, come from the same owner (stage 5).
internal fun routeLeasesFromOwner(processName: String?, packageName: String) =
    processName in setOf("$packageName:prototype_cached_player", "$packageName:abema_guest_bootstrap",
        "$packageName:abema_approval_browser")
