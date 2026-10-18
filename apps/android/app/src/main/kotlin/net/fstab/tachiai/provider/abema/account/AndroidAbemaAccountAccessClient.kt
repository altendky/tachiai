package net.fstab.tachiai.provider.abema.account

import android.content.*
import android.os.*
import android.os.Process
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

// Worker acquisition/checks; main admission is RAM/clock only. The ordinary
// process owns the durable record. This client retains only its private lease.
internal class AndroidAbemaAccountAccessClient(context: Context, instanceId: String, ownerProof: String,
    sourceProof: String, deadlineElapsedMs: Long, playback: Boolean, continuousPlayback: Boolean = false,
    private val canUse: () -> Boolean) : AutoCloseable {
    companion object { private val retained = ConcurrentLinkedQueue<Any>() }
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
    private val lock = Any()
    private val checks = ReentrantLock()
    private var sequence = 1L
    private var operation: Operation? = null
    @Volatile private var credential: AbemaAccountGrant? = null
    private val minimumWall = AtomicLong()
    @Volatile private var cleanupConfirmed = false
    private var connection: Connection? = null // Main-owned.
    private class Operation(val sequence: Long, val expected: Int) {
        val done = CountDownLatch(1)
        @Volatile var fields: Map<String, Any>? = null
    }
    private fun live(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return !closed.get() && !revoked.get() && !poisoned.get() &&
            AbemaAccountAccessIpc.sessionCurrent(request, began, now, acquired.get()) &&
            runCatching(canUse).getOrDefault(false)
    }
    fun current(): Boolean {
        val wall = System.currentTimeMillis()
        val previous = minimumWall.getAndUpdate { minimum -> maxOf(minimum, wall) }
        val value = credential
        val okay = acquired.get() && live() && wall >= previous && (value == null || wall < value.expiresAtMs)
        if (!okay && acquired.get()) { revoked.set(true); close() }
        return okay
    }
    private val reply = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.sendingUid != Process.myUid()) return
            val fields = AbemaAccountAccessIpc.fields(message.data) ?: return
            message.data.clear()
            val seq = fields["sequence"] as? Long ?: return
            if (!AbemaAccountAccessIpc.matches(fields, request, seq)) return
            when (message.what) {
                AbemaAccountAccessIpc.READY -> if (seq == 1L && fields.keys == AbemaAccountAccessIpc.base(request, seq).keys && live()) ready.countDown()
                AbemaAccountAccessIpc.REVOKED -> if (fields.keys == AbemaAccountAccessIpc.base(request, seq).keys) { revoked.set(true); close() }
                AbemaAccountAccessIpc.CLOSED -> {
                    if (fields.keys != AbemaAccountAccessIpc.base(request, seq).keys + "confirmed" || fields["confirmed"] !is Boolean) return
                    val confirmed = fields["confirmed"] == true
                    if (!confirmed) poisoned.set(true)
                    finishConnection(confirmed)
                }
                else -> synchronized(lock) {
                    val pending = operation
                    if (pending != null && seq == pending.sequence && message.what == pending.expected && live()) {
                        pending.fields = fields; pending.done.countDown()
                    }
                }
            }
        }
    })
    private inner class Connection : ServiceConnection {
        var bound = false
        var remote: Messenger? = null
        var binder: IBinder? = null
        val death = IBinder.DeathRecipient { main.post { revoked.set(true); poisoned.set(true); finishConnection(false) } }
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (connection !== this || closed.get()) { finishConnection(remote == null); return }
            try {
                service.linkToDeath(death, 0); binder = service; remote = Messenger(service)
                send(AbemaAccountAccessIpc.OPEN, 1)
            } catch (_: Throwable) { poisoned.set(true); finishConnection(false) }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            if (connection === this) { poisoned.set(true); revoked.set(true); finishConnection(false) }
        }
        override fun onBindingDied(name: ComponentName) {
            if (connection === this) { poisoned.set(true); revoked.set(true); finishConnection(false) }
        }
        override fun onNullBinding(name: ComponentName) { if (connection === this) finishConnection(true) }
        fun send(what: Int, seq: Long) {
            val data = AbemaAccountAccessIpc.bundle(AbemaAccountAccessIpc.base(request, seq))
            try { (remote ?: throw IllegalStateException()).send(Message.obtain().apply {
                this.what = what; replyTo = reply; this.data = data
            }) } finally { data.clear() }
        }
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
        val fields = transact(AbemaAccountAccessIpc.ACQUIRE, AbemaAccountAccessIpc.RESULT, 30000)
            ?: return failure(AbemaAccountFailure.CANCELLED)
        val seq = fields["sequence"] as? Long ?: return failure(AbemaAccountFailure.REFUSED)
        val value = AbemaAccountAccessIpc.result(fields, request, seq, System.currentTimeMillis())
            ?: run { close(); return failure(AbemaAccountFailure.REFUSED) }
        if (value.status == AbemaAccountAccessIpc.Status.FAILURE) { close(); return value }
        if (!live()) { close(); return failure(AbemaAccountFailure.CANCELLED) }
        credential = value.grant
        minimumWall.accumulateAndGet(System.currentTimeMillis()) { minimum, now -> maxOf(minimum, now) }
        acquired.set(true)
        if (!current()) return failure(AbemaAccountFailure.CANCELLED)
        return value
    }
    private fun transact(what: Int, expected: Int, waitMs: Long): Map<String, Any>? {
        val pending = synchronized(lock) {
            if (operation != null || !live() || sequence == Long.MAX_VALUE) return null
            Operation(++sequence, expected).also { operation = it }
        }
        main.post {
            try { if (live()) connection?.send(what, pending.sequence) else pending.done.countDown() }
            catch (_: Throwable) { pending.done.countDown(); revoked.set(true); close() }
        }
        try {
            if (!await(pending.done, waitMs, true)) { revoked.set(true); close(); return null }
            return pending.fields.takeIf { live() }
        } finally { synchronized(lock) { if (operation === pending) operation = null }; pending.fields = null }
    }
    fun checkWorker(): Boolean {
        if (!worker() || !current()) return false
        val start = SystemClock.elapsedRealtime()
        val locked = try { checks.tryLock(AbemaAccountAccessIpc.IPC_MS, TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        if (!locked) { revoked.set(true); close(); return false }
        try {
            val now = SystemClock.elapsedRealtime()
            val left = AbemaAccountAccessIpc.IPC_MS - (now - start)
            val fields = if (now >= start && left > 0 && current())
                transact(AbemaAccountAccessIpc.CHECK, AbemaAccountAccessIpc.CHECKED, left) else null
            val seq = fields?.get("sequence") as? Long
            val okay = fields != null && seq != null && fields.keys == AbemaAccountAccessIpc.base(request, seq).keys + "current" &&
                fields["current"] == true && current()
            if (!okay) { revoked.set(true); close() }
            return okay
        } finally { checks.unlock() }
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        acquired.set(false); credential = null
        synchronized(lock) { operation?.done?.countDown() }
        main.post {
            val binding = connection
            if (binding?.remote == null) { finishConnection(true); return@post }
            val seq = synchronized(lock) { if (sequence == Long.MAX_VALUE) sequence else ++sequence }
            try { binding.send(AbemaAccountAccessIpc.CLOSE, seq) }
            catch (_: Throwable) { poisoned.set(true); finishConnection(false) }
        }
    }
    private fun finishConnection(confirmed: Boolean) {
        revoked.set(true); acquired.set(false); credential = null
        val binding = connection
        var released = true
        binding?.binder?.let { runCatching { it.unlinkToDeath(binding.death, 0) } }
        if (binding?.bound == true) {
            try { application.unbindService(binding); binding.bound = false }
            catch (_: Throwable) { released = false }
        }
        if (!confirmed || !released) poisoned.set(true)
        cleanupConfirmed = confirmed && released && !poisoned.get()
        if (cleanupConfirmed) { binding?.remote = null; binding?.binder = null; connection = null }
        ready.countDown(); synchronized(lock) { operation?.done?.countDown() }; cleanup.countDown()
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
