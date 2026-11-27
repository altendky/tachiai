package net.fstab.tachiai.provider.abema.account

import android.content.*
import android.os.*
import android.os.Process
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import net.fstab.tachiai.platform.diagnostics.OperationStats
import net.fstab.tachiai.platform.network.ProviderRouteOwnerReads

// Worker acquisition/checks; main admission is RAM/clock only. The ordinary
// process owns the durable record. This client retains only its private lease.
// Its exchanges never wait for the viewer's main looper (#770): a worker sends
// directly and replies arrive on a dedicated reply thread. Binding, unbinding
// and the connection object stay main-owned.
internal class AndroidAbemaAccountAccessClient(context: Context, instanceId: String, ownerProof: String,
    sourceProof: String, deadlineElapsedMs: Long, playback: Boolean, continuousPlayback: Boolean = false,
    private val canUse: () -> Boolean) : AutoCloseable {
    companion object {
        private val retained = ConcurrentLinkedQueue<Any>()
        // One process-wide thread receives every client's replies.
        private val replies: Looper by lazy { HandlerThread("abema-account-reply").apply { start() }.looper }
    }
    private val application = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val request = AbemaAccountAccessIpc.Request(UUID.randomUUID().toString().replace("-", ""),
        instanceId, ownerProof, sourceProof, deadlineElapsedMs, playback, continuousPlayback)
    private val began = SystemClock.elapsedRealtime()
    private val closed = AtomicBoolean()
    private val acquired = AtomicBoolean()
    private val started = AtomicBoolean()
    private val revoked = AtomicBoolean()
    private val poisoned = AtomicBoolean()
    private val retainedOnce = AtomicBoolean()
    private val ready = CountDownLatch(1)
    private val cleanup = CountDownLatch(1)
    private val checks = ReentrantLock()
    private val checkHistory = AbemaAccountCheckHistory()
    private val exchange = AbemaAccountExchange()
    @Volatile private var credential: AbemaAccountGrant? = null
    // A later expiry the service announced for this lease after a same-account renewal.
    @Volatile private var renewedExpiry = 0L
    private val wallClock = AbemaAccountWallClock(System::currentTimeMillis)
    @Volatile private var cleanupConfirmed = false
    // The first closed cause that ended an acquired lease (#729), logged once.
    private val lossCause = java.util.concurrent.atomic.AtomicReference<String?>()
    @Volatile private var acquiredAtMs = -1L
    private var connection: Connection? = null // Main-owned.
    private fun live(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return !closed.get() && !revoked.get() && !poisoned.get() &&
            AbemaAccountAccessIpc.sessionCurrent(request, began, now, acquired.get()) &&
            runCatching(canUse).getOrDefault(false)
    }
    // Names, once, why an acquired lease stopped. Logging never affects admission.
    private fun lose(cause: String) {
        if (!acquired.get() || !lossCause.compareAndSet(null, cause)) return
        runCatching {
            android.util.Log.d("TachiaiAbemaAccount", AbemaAccountAccessIpc.lossLine("client=LEASE_LOST", cause,
                request.playback, request.continuousPlayback, acquired = true,
                ageMs = acquiredAtMs.takeIf { it >= 0 }?.let { SystemClock.elapsedRealtime() - it }))
        }
    }
    private fun liveCause(wallSampled: Boolean = true) = abemaAccessClientLossCause(closed.get(), revoked.get(),
        poisoned.get(), AbemaAccountAccessIpc.sessionCurrent(request, began, SystemClock.elapsedRealtime(), true),
        runCatching(canUse).getOrDefault(false), wallSampled)
    fun current(): Boolean {
        val wall = wallClock.sample()
        val value = credential
        val okay = acquired.get() && live() && wall != null && (value == null || wall < maxOf(value.expiresAtMs, renewedExpiry))
        if (!okay && acquired.get()) { lose(liveCause(wall != null)); revoked.set(true); close() }
        return okay
    }
    private val reply: Messenger = Messenger(object : Handler(replies) {
        override fun handleMessage(message: Message) {
            if (message.sendingUid != Process.myUid()) return
            val fields = AbemaAccountAccessIpc.fields(message.data) ?: return
            message.data.clear()
            val seq = fields["sequence"] as? Long ?: return
            if (!AbemaAccountAccessIpc.matches(fields, request, seq)) return
            when (message.what) {
                AbemaAccountAccessIpc.READY -> if (seq == 1L && fields.keys == AbemaAccountAccessIpc.base(request, seq).keys && live()) ready.countDown()
                AbemaAccountAccessIpc.REVOKED -> if (fields.keys == AbemaAccountAccessIpc.base(request, seq).keys) {
                    lose("SERVICE_REVOKED"); revoked.set(true); close()
                }
                AbemaAccountAccessIpc.RENEWED -> {
                    if (fields.keys != AbemaAccountAccessIpc.base(request, seq).keys + "expiry") return
                    val expiry = fields["expiry"] as? Long ?: return
                    // It may arrive before acquire() publishes the credential.
                    if (!closed.get() && expiry > renewedExpiry && expiry <= 9_007_199_254_740_991L) renewedExpiry = expiry
                }
                AbemaAccountAccessIpc.CLOSED -> {
                    if (fields.keys != AbemaAccountAccessIpc.base(request, seq).keys + "confirmed" || fields["confirmed"] !is Boolean) return
                    val confirmed = fields["confirmed"] == true
                    if (!closed.get()) lose("SERVICE_CLOSED")
                    if (!confirmed) poisoned.set(true)
                    // Revoked at once, before any later reply, as on the main
                    // looper before; unbinding stays main-owned.
                    outbound.exclusive { revoked.set(true) }; exchange.wake()
                    main.post { finishConnection(confirmed) }
                }
                else -> {
                    // How long the reply waited for this reply thread (#752, #770).
                    val hop = (SystemClock.uptimeMillis() - message.`when`).coerceAtLeast(0L)
                    if (exchange.deliver(seq, message.what, fields, ::live, hop) != null &&
                        message.what == AbemaAccountAccessIpc.CHECKED) runCatching {
                        AbemaAccountCheckStats.client.time("replyHop", hop, SystemClock.elapsedRealtime())
                    }
                }
            }
        }
    })
    private inner class Connection : ServiceConnection {
        var bound = false
        var remote: Messenger? = null
        var binder: IBinder? = null
        val death = IBinder.DeathRecipient { main.post { lose("BINDER_DIED"); revoked.set(true); poisoned.set(true); finishConnection(false) } }
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (connection !== this || closed.get()) { finishConnection(remote == null); return }
            try {
                service.linkToDeath(death, 0); binder = service
                remote = Messenger(service).also(outbound::open)
                send(AbemaAccountAccessIpc.OPEN, 1)
            } catch (_: Throwable) { poisoned.set(true); finishConnection(false) }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            if (connection === this) { lose("SERVICE_DISCONNECTED"); poisoned.set(true); revoked.set(true); finishConnection(false) }
        }
        override fun onBindingDied(name: ComponentName) {
            if (connection === this) { lose("BINDING_DIED"); poisoned.set(true); revoked.set(true); finishConnection(false) }
        }
        override fun onNullBinding(name: ComponentName) { if (connection === this) finishConnection(true) }
        fun send(what: Int, seq: Long) { if (remote == null || !outbound.sendControl(what, seq)) throw IllegalStateException() }
    }
    private val outbound: AbemaAccountOutbound<Messenger> = AbemaAccountOutbound { remote, what, seq ->
        val data = AbemaAccountAccessIpc.bundle(AbemaAccountAccessIpc.base(request, seq))
        try { remote.send(Message.obtain().apply { this.what = what; replyTo = reply; this.data = data }) }
        finally { data.clear() }
    }
    private fun worker() = Looper.myLooper() != Looper.getMainLooper()
    private fun await(latch: CountDownLatch, limitMs: Long, requireLive: Boolean): Boolean {
        val start = SystemClock.elapsedRealtime()
        try {
            while (latch.count != 0L) {
                val now = SystemClock.elapsedRealtime()
                if (now < start || now - start >= limitMs || (requireLive && !live())) return false
                latch.await(minOf(100, limitMs - (now - start)), TimeUnit.MILLISECONDS)
            }
            return !requireLive || live()
        } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
    }
    fun acquire(): AbemaAccountAccessIpc.Result {
        fun failure(category: AbemaAccountFailure) = AbemaAccountAccessIpc.Result(
            AbemaAccountAccessIpc.Status.FAILURE, failure = category).also { close() }
        if (!worker() || Build.VERSION.SDK_INT < 28 || !live() || !started.compareAndSet(false, true) ||
            AbemaAccountAccessIpc.request(AbemaAccountAccessIpc.base(request, 1), began) == null) return failure(AbemaAccountFailure.REFUSED)
        main.post {
            if (connection != null || !live()) { ready.countDown(); return@post }
            val binding = Connection(); connection = binding
            try {
                binding.bound = application.bindService(Intent(application, AbemaAccountAccessService::class.java), binding, Context.BIND_AUTO_CREATE)
                if (!binding.bound) finishConnection(true)
            } catch (_: Throwable) { finishConnection(true) }
        }
        if (!await(ready, AbemaAccountAccessIpc.IPC_MS, true)) { close(); return failure(AbemaAccountFailure.TIME_LIMIT_REACHED) }
        android.util.Log.d("TachiaiAbemaAccount", "client=BOUND")
        val fields = transact(AbemaAccountAccessIpc.ACQUIRE, AbemaAccountAccessIpc.RESULT, 30000)
            ?: return failure(AbemaAccountFailure.CANCELLED)
        android.util.Log.d("TachiaiAbemaAccount", "client=RESULT")
        val seq = fields["sequence"] as? Long ?: return failure(AbemaAccountFailure.REFUSED)
        val value = AbemaAccountAccessIpc.result(fields, request, seq, System.currentTimeMillis())
            ?: run { close(); return failure(AbemaAccountFailure.REFUSED) }
        if (value.status == AbemaAccountAccessIpc.Status.FAILURE) { close(); return value }
        if (!live()) { close(); return failure(AbemaAccountFailure.CANCELLED) }
        credential = value.grant
        if (wallClock.sample() == null) return failure(AbemaAccountFailure.EXPIRED)
        acquiredAtMs = SystemClock.elapsedRealtime()
        acquired.set(true)
        if (!current()) return failure(AbemaAccountFailure.CANCELLED)
        return value
    }
    // Worker-only. The send leaves this thread directly (#770) instead of
    // through the main looper; sendHop is now the time until it has left.
    private fun transact(what: Int, expected: Int, waitMs: Long,
        checkWait: ((CountDownLatch) -> Boolean)? = null, sendHop: ((Long) -> Unit)? = null,
        replyHop: ((Long) -> Unit)? = null): Map<String, Any>? {
        val pending = exchange.begin(expected, ::live) ?: return null
        try {
            val sending = SystemClock.elapsedRealtime()
            try { if (!outbound.send(what, pending.sequence, ::live)) pending.done.countDown() }
            catch (_: Throwable) { pending.done.countDown(); lose("SEND_FAILED"); revoked.set(true); close() }
            sendHop?.let { runCatching { it(SystemClock.elapsedRealtime() - sending) } }
            val completed = checkWait?.invoke(pending.done) ?: await(pending.done, waitMs, true)
            if (!completed) { lose(if (live()) "REPLY_TIMEOUT" else liveCause()); revoked.set(true); close(); return null }
            val fields = pending.fields.takeIf { live() }
            if (fields != null) replyHop?.let { runCatching { it(pending.replyHopMs) } }
            return fields
        } finally { exchange.end(pending) }
    }
    fun checkWorker(): Boolean {
        if (!worker() || !current()) return false
        val started = SystemClock.elapsedRealtime()
        val stats = AbemaAccountCheckStats.client
        return performAbemaAccountCheck(checks, AbemaAccountAccessIpc.CHECK_REPLY_MS, SystemClock::elapsedRealtime, ::current,
            dispatch = { dispatched, replyMs ->
                var interrupted = false
                var sent = -1L
                var replied = -1L
                // Where a lease check's time goes (#752, #770): waiting for this
                // client's check lock, the send until it leaves, the whole
                // dispatched exchange until its reply wakes this worker, and
                // that exchange less both hops (remote).
                runCatching { stats.time("lockWait", dispatched - started, dispatched) }
                val fields = transact(AbemaAccountAccessIpc.CHECK, AbemaAccountAccessIpc.CHECKED, replyMs, { done ->
                    awaitAbemaAccountCheck(done, dispatched, dispatched + replyMs,
                        SystemClock::elapsedRealtime, ::live).also { interrupted = it.interrupted }.completed
                }, sendHop = { sent = it; stats.time("sendHop", it, SystemClock.elapsedRealtime()) },
                    replyHop = { replied = it })
                runCatching { SystemClock.elapsedRealtime().let { now ->
                    stats.time("dispatch", now - dispatched, now)
                    if (sent >= 0 && replied >= 0) stats.time("remote", now - dispatched - sent - replied, now)
                } }
                AbemaAccountCheckCompletion(abemaAccountCheckAccepted(fields, request), interrupted)
            }, revoke = { lose("CHECK_REFUSED"); revoked.set(true); close() }, history = checkHistory,
            observe = { stats.count(it.event, SystemClock.elapsedRealtime()) }).also { accepted ->
            AbemaAccountCheckStats.record(stats, "check", started, SystemClock.elapsedRealtime(), accepted)
            logAbemaCheckStats(stats)
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        acquired.set(false); credential = null
        exchange.wake()
        main.post {
            val binding = connection
            if (binding?.remote == null) { finishConnection(true); return@post }
            val seq = exchange.next()
            try { binding.send(AbemaAccountAccessIpc.CLOSE, seq) }
            catch (_: Throwable) { poisoned.set(true); finishConnection(false) }
        }
    }
    private fun finishConnection(confirmed: Boolean) {
        // A worker's live check and its send cannot straddle this revocation.
        outbound.exclusive { revoked.set(true) }; acquired.set(false); credential = null
        val binding = connection
        var released = true
        binding?.binder?.let { runCatching { it.unlinkToDeath(binding.death, 0) } }
        if (binding?.bound == true) {
            try { application.unbindService(binding); binding.bound = false }
            catch (_: Throwable) { released = false }
        }
        if (!confirmed || !released) poisoned.set(true)
        cleanupConfirmed = confirmed && released && !poisoned.get()
        if (cleanupConfirmed) { binding?.remote = null; binding?.binder = null; connection = null; outbound.clear() }
        ready.countDown(); exchange.wake(); cleanup.countDown()
    }
    fun awaitCleanup(): Boolean {
        if (!worker()) return false
        close()
        val confirmed = await(cleanup, AbemaAccountAccessIpc.IPC_MS, false) && cleanupConfirmed && !poisoned.get()
        if (!confirmed) { poisoned.set(true); if (retainedOnce.compareAndSet(false, true)) retained.add(this) }
        return confirmed
    }
    override fun toString() = "AndroidAbemaAccountAccessClient(redacted)"
}

// Logs whichever closed check and store-read lines are due (#745).
internal fun logAbemaCheckStats(stats: OperationStats, tag: String = "TachiaiAbemaAccount") {
    runCatching {
        stats.drain(SystemClock.elapsedRealtime())?.let { android.util.Log.d(tag, it) }
        ProviderRouteOwnerReads.drainStats().forEach { android.util.Log.d("TachiaiStoreReads", it) }
    }
}

// The closed cause of an acquired lease that is no longer current (#729).
internal fun abemaAccessClientLossCause(closed: Boolean, revoked: Boolean, poisoned: Boolean,
    sessionCurrent: Boolean, scopeCurrent: Boolean, wallSampled: Boolean): String = when {
    closed -> "CLOSED"
    revoked -> "REVOKED"
    poisoned -> "POISONED"
    !sessionCurrent -> "SESSION_EXPIRED"
    !scopeCurrent -> "SCOPE_LOST"
    !wallSampled -> "CLOCK_ROLLBACK"
    else -> "GRANT_EXPIRED"
}
