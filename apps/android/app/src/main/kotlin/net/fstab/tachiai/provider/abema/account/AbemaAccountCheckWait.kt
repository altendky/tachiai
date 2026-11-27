package net.fstab.tachiai.provider.abema.account

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import net.fstab.tachiai.platform.diagnostics.OperationStats

internal enum class AbemaAccountCheckLock { ACQUIRED, INTERRUPTED, TIMED_OUT }

// Cancellation before dispatch owns no remote CHECK and cannot revoke a lease.
internal fun acquireAbemaAccountCheckLock(lock: ReentrantLock, timeoutMs: Long): AbemaAccountCheckLock {
    if (Thread.currentThread().isInterrupted) return AbemaAccountCheckLock.INTERRUPTED
    return try {
        if (lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) AbemaAccountCheckLock.ACQUIRED else AbemaAccountCheckLock.TIMED_OUT
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        AbemaAccountCheckLock.INTERRUPTED
    }
}

internal data class AbemaAccountCheckWait(val completed: Boolean, val interrupted: Boolean)
internal data class AbemaAccountCheckCompletion(val accepted: Boolean, val interrupted: Boolean)

// Serialized checks of one lease. A caller that queued behind other checks may
// adopt an accepted CHECK dispatched after it began (#729): that service-side
// verification read the protected state no earlier than the caller's own
// CHECK would have, so the coalesced caller is admitted on equal evidence.
internal class AbemaAccountCheckHistory {
    private val tickets = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var accepted = 0L
    fun ticket(): Long = tickets.incrementAndGet()
    fun accept(ticket: Long) { if (ticket > accepted) accepted = ticket }
    // An accepted CHECK was dispatched after the caller holding [ticket] began.
    fun covers(ticket: Long) = accepted > ticket
}

// How a caller's check was answered, for closed counters only (#745).
internal enum class AbemaAccountCheckPath(val event: String) { DISPATCHED("dispatched"), COALESCED("coalesced") }

// Per-process account check costs (#745): fixed names, counts and milliseconds.
// The viewer counts media request admissions and its lease checks; the
// ordinary process counts the durable checks it serves.
internal object AbemaAccountCheckStats {
    val media = OperationStats("media=ADMISSION_STATS")
    val client = OperationStats("client=CHECK_STATS")
    val owner = OperationStats("owner=CHECK_STATS")
    fun record(stats: OperationStats, event: String, startedMs: Long, nowMs: Long, accepted: Boolean) {
        runCatching {
            stats.time(event, nowMs - startedMs, nowMs)
            if (!accepted) stats.count("refused", nowMs)
        }
    }
}

// The Android client retains IPC dispatch, exact reply validation and lease
// validity. This owns their serialized operation and cleanup boundary.
// queueMs bounds the wait for the serialized lock; each dispatched CHECK then
// has its own replyMs, so time spent queued never shortens a remote reply.
internal fun performAbemaAccountCheck(
    lock: ReentrantLock,
    queueMs: Long,
    nowMs: () -> Long,
    current: () -> Boolean,
    dispatch: (dispatchedMs: Long, replyMs: Long) -> AbemaAccountCheckCompletion,
    revoke: () -> Unit,
    replyMs: Long = queueMs,
    history: AbemaAccountCheckHistory? = null,
    observe: (AbemaAccountCheckPath) -> Unit = {},
): Boolean {
    val start = nowMs()
    val ticket = history?.ticket()
    when (acquireAbemaAccountCheckLock(lock, queueMs)) {
        AbemaAccountCheckLock.INTERRUPTED -> return false
        AbemaAccountCheckLock.TIMED_OUT -> return false
        AbemaAccountCheckLock.ACQUIRED -> Unit
    }
    try {
        if (Thread.currentThread().isInterrupted) return false
        val now = nowMs()
        if (now < start) { revoke(); return false }
        // Queueing can consume this caller's budget without creating a remote
        // operation. Only dispatched uncertainty or lost validity owns revoke.
        if (queueMs - (now - start) <= 0) return false
        if (!current()) { revoke(); return false }
        if (ticket != null && history.covers(ticket)) {
            runCatching { observe(AbemaAccountCheckPath.COALESCED) }
            return current() && !Thread.currentThread().isInterrupted
        }
        val dispatched = history?.ticket()
        runCatching { observe(AbemaAccountCheckPath.DISPATCHED) }
        val completion = dispatch(now, replyMs)
        val okay = completion.accepted && current()
        if (!okay) revoke() else if (dispatched != null) history.accept(dispatched)
        return okay && !completion.interrupted && !Thread.currentThread().isInterrupted
    } finally { lock.unlock() }
}

// The client's one in-flight lease exchange (#770). Replies complete it from a
// dedicated reply thread rather than the viewer's main looper, so this owns the
// sequence and the single pending operation under one lock. A reply applies only
// to the exact pending sequence and kind while the lease is live; one arriving
// after the feed stopped, or after the caller gave up, changes nothing.
internal class AbemaAccountExchange {
    class Operation(val sequence: Long, val expected: Int) {
        val done = CountDownLatch(1)
        @Volatile var fields: Map<String, Any>? = null
        // How long the applied reply waited for the reply thread.
        @Volatile var replyHopMs = 0L
    }
    private val lock = Any()
    private var sequence = 1L
    private var operation: Operation? = null
    fun begin(expected: Int, live: () -> Boolean): Operation? = synchronized(lock) {
        if (operation != null || !live() || sequence == Long.MAX_VALUE) return null
        Operation(++sequence, expected).also { operation = it }
    }
    fun deliver(sequence: Long, what: Int, fields: Map<String, Any>, live: () -> Boolean,
        replyHopMs: Long = 0L): Operation? = synchronized(lock) {
        val pending = operation
        if (pending == null || sequence != pending.sequence || what != pending.expected || !live()) return null
        pending.replyHopMs = replyHopMs; pending.fields = fields; pending.done.countDown()
        pending
    }
    fun end(pending: Operation) { synchronized(lock) { if (operation === pending) operation = null }; pending.fields = null }
    // Wakes the waiting caller, which then sees the lease is no longer live.
    fun wake() { synchronized(lock) { operation?.done?.countDown() } }
    fun next(): Long = synchronized(lock) { if (sequence == Long.MAX_VALUE) sequence else ++sequence }
}

// Sends to the lease service from whichever thread asks (#770). A worker's
// live check and its send are one step under this lock, as is any state change
// passed to exclusive(), such as revocation before unbinding. A CLOSE is sent
// only after the client closed, so no operation reaches the service after the
// CLOSE that ends its lease. The target mirrors the main-owned connection.
internal class AbemaAccountOutbound<T : Any>(private val deliver: (T, Int, Long) -> Unit) {
    private val lock = Any()
    private var target: T? = null
    fun open(remote: T) { synchronized(lock) { target = remote } }
    fun clear() { synchronized(lock) { target = null } }
    fun <R> exclusive(block: () -> R): R = synchronized(lock) { block() }
    // False when nothing was sent. A delivery failure propagates to the caller.
    fun send(what: Int, sequence: Long, live: () -> Boolean): Boolean = synchronized(lock) {
        val remote = target
        if (remote == null || !live()) return false
        deliver(remote, what, sequence); true
    }
    // OPEN and CLOSE need only a connected service, not a live lease.
    fun sendControl(what: Int, sequence: Long): Boolean = send(what, sequence) { true }
}

internal fun abemaAccountCheckAccepted(fields: Map<String, Any>?, request: AbemaAccountAccessIpc.Request): Boolean {
    val sequence = fields?.get("sequence") as? Long ?: return false
    return sequence >= 1 && AbemaAccountAccessIpc.matches(fields, request, sequence) &&
        fields.keys == AbemaAccountAccessIpc.base(request, sequence).keys + "current" && fields["current"] == true
}

// Once dispatched, the service remains busy until this exact CHECK replies.
// Drain it while retaining the caller's checks lock: interruption refuses that
// caller, but cannot abandon an operation and let a new CHECK end the lease.
// The original absolute deadline is never renewed by another interruption.
internal fun awaitAbemaAccountCheck(
    done: CountDownLatch,
    beganMs: Long,
    deadlineMs: Long,
    nowMs: () -> Long,
    current: () -> Boolean,
): AbemaAccountCheckWait {
    var interrupted = Thread.interrupted()
    try {
        while (true) {
            val now = nowMs()
            if (now < beganMs || now >= deadlineMs || !current())
                return AbemaAccountCheckWait(false, interrupted)
            if (done.count == 0L) {
                // An interrupt can race the final reply without entering await.
                interrupted = Thread.interrupted() || interrupted
                return AbemaAccountCheckWait(true, interrupted)
            }
            try { done.await(minOf(100L, deadlineMs - now), TimeUnit.MILLISECONDS) }
            catch (_: InterruptedException) { interrupted = true }
        }
    } finally { if (interrupted) Thread.currentThread().interrupt() }
}
