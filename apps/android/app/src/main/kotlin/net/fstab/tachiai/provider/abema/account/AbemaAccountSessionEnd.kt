package net.fstab.tachiai.provider.abema.account

import java.util.concurrent.Executors

// How a lease session's end in the ordinary process resolved (#783), for one
// closed line. Anything but CONFIRMED poisons the instance until that process ends.
internal enum class AbemaSessionEnd { CONFIRMED, JOBS_RUNNING, ATTACHMENT_UNCONFIRMED, INTERRUPTED, CLOCK, REJECTED, DEBUG_FAULT }

// Waits for a lease session's own work and its owner attachment to confirm
// cleanup. A Stop during acquisition cancels a validation that may still be
// in a store read or a route teardown, so this allows up to [limitMs]
// (AbemaAccountAccessIpc.END_MS), within the client's longer CLOSED wait.
// [close] is repeated because an attachment can be created after cancellation.
internal fun awaitAbemaSessionEnd(limitMs: Long, nowMs: () -> Long, pause: () -> Unit, close: () -> Unit,
    idle: () -> Boolean, confirmed: () -> Boolean): AbemaSessionEnd {
    val began = nowMs()
    while (true) {
        close()
        val jobsIdle = idle()
        if (jobsIdle && confirmed()) return AbemaSessionEnd.CONFIRMED
        val now = nowMs()
        if (now < began) return AbemaSessionEnd.CLOCK
        if (now - began >= limitMs) return if (jobsIdle) AbemaSessionEnd.ATTACHMENT_UNCONFIRMED else AbemaSessionEnd.JOBS_RUNNING
        try { pause() } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return AbemaSessionEnd.INTERRUPTED }
    }
}

// Session ends wait on their own threads (#783). On the shared cancellation
// pool, two feeds' ends and a readiness cancellation could queue behind each
// other until the client's wait for CLOSED had passed.
internal object AbemaSessionEndWorkers {
    val executor = Executors.newCachedThreadPool { task -> Thread(task, "abema-account-end").apply { isDaemon = true } }
}

internal fun abemaSessionEndLine(result: AbemaSessionEnd, waitedMs: Long) =
    "owner=SESSION_END result=${result.name} waitedMs=${waitedMs.coerceIn(-1L, 86_400_000L)}"
