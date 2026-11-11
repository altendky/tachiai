package net.fstab.tachiai.provider.abema.account

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

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
