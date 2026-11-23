package net.fstab.tachiai.presentation

import kotlin.random.Random

// What a feed is waiting for (#587). A channel can go live at any time; an
// occurrence (an exact scheduled broadcast) cannot start before its start time.
internal enum class FeedWaitingKind { CHANNEL, OCCURRENCE }

internal data class FeedWaitingSignal(val kind: FeedWaitingKind, val startEpochMs: Long? = null) {
    init { require(startEpochMs == null || startEpochMs in 1..MAX_SCHEDULED_START_EPOCH_MS) }
}

// Only a provider's own "not live yet" or "live show ended" signals lead to
// waiting. An occurrence (slot or replay) that ended keeps its ended state.
internal fun PrototypeFeedFailure.waitingSignal(channel: Boolean): FeedWaitingSignal? = when (reason) {
    PrototypeFailureReason.NOT_LIVE_YET ->
        FeedWaitingSignal(if (channel) FeedWaitingKind.CHANNEL else FeedWaitingKind.OCCURRENCE, scheduledStartEpochMs)
    PrototypeFailureReason.CHANNEL_NOT_AIRING -> FeedWaitingSignal(FeedWaitingKind.CHANNEL)
    PrototypeFailureReason.BROADCAST_ENDED -> FeedWaitingSignal(FeedWaitingKind.CHANNEL).takeIf { channel }
    else -> null
}

// Whether a failure observed during a waiting check is a passing problem
// that waiting may ride out. Account, login, route, policy, licensing and
// cleanup refusals need the user and leave waiting at once.
internal fun PrototypeFeedFailure.transientWhileWaiting(): Boolean {
    if (httpStatus in setOf(401, 403)) return false
    return when (reason) {
        PrototypeFailureReason.NETWORK_FAILED, PrototypeFailureReason.PREPARATION_TIMEOUT,
        PrototypeFailureReason.CATALOG_UNAVAILABLE, PrototypeFailureReason.CATALOG_RATE_LIMITED,
        PrototypeFailureReason.MEDIA_NOT_FOUND, PrototypeFailureReason.LIVE_POSITION_FAILED,
        PrototypeFailureReason.LIVE_WINDOW_EXPIRED, PrototypeFailureReason.PREPARATION_FAILED -> true
        PrototypeFailureReason.HTTP_REJECTED -> httpStatus == 408 || httpStatus == 429 || httpStatus?.let { it in 500..599 } == true
        else -> false
    }
}

internal fun PrototypeFeedFailure.rateLimited(): Boolean =
    reason == PrototypeFailureReason.CATALOG_RATE_LIMITED || httpStatus == 429

internal enum class FeedWaitingTransientResult { WAITING, GAVE_UP, NOT_WAITING }

internal class FeedWaitingCheck internal constructor(internal val dueMs: Long) {
    override fun toString() = "FeedWaitingCheck"
}

// Presentation-thread policy only (#587): when to check again, what is known
// about the start, and when to give up after repeated passing failures. The
// caller proves slot, lifecycle and playback ownership before each check.
// Elapsed time schedules checks; wall time only places a known start.
internal class FeedWaitingState(private val jitter: (Long) -> Long = { bound -> Random.nextLong(bound + 1) }) {
    companion object {
        const val CHECK_MS = 60_000L
        const val FAST_CHECK_MS = 15_000L
        const val START_WINDOW_MS = 120_000L
        const val TRANSIENT_BASE_MS = 30_000L
        const val TRANSIENT_LIMIT = 5
        const val MAX_BACKOFF_MS = 900_000L
    }

    var active = false
        private set
    var kind = FeedWaitingKind.CHANNEL
        private set
    var startEpochMs: Long? = null
        private set
    var lastCheckedEpochMs: Long? = null
        private set
    // A waiting channel's start from its schedule (#560), not a provider "not
    // live yet" signal: it only times checks like a signalled start, from the
    // next scheduling on, and leaves the pane's first line alone.
    var programStartEpochMs: Long? = null
        private set
    // A fresh waiting period may look up a schedule once; later checks don't.
    var startLookupNeeded = false
        private set
    // An account failure during waiting sends the next check through the
    // provider's account readiness once (#587, decision 14).
    var accountRecheck = false
        private set
    private var dueMs: Long? = null
    private var issued: FeedWaitingCheck? = null
    private var rateLimitStreak = 0
    private var transientStreak = 0
    // Consecutive failed checks of any passing kind; the give-up count.
    private var failedChecks = 0

    private fun schedule(nowElapsedMs: Long, nowEpochMs: Long) {
        val start = startEpochMs ?: programStartEpochMs.takeIf { kind == FeedWaitingKind.CHANNEL }
        var interval = when {
            start == null -> CHECK_MS
            // Approved: no checks before an occurrence's start.
            kind == FeedWaitingKind.OCCURRENCE && nowEpochMs < start -> start - nowEpochMs
            nowEpochMs >= start - START_WINDOW_MS && nowEpochMs <= start + START_WINDOW_MS -> FAST_CHECK_MS
            nowEpochMs < start - START_WINDOW_MS -> minOf(CHECK_MS, start - START_WINDOW_MS - nowEpochMs)
            kind == FeedWaitingKind.OCCURRENCE -> FAST_CHECK_MS
            else -> CHECK_MS
        }
        if (rateLimitStreak > 0) interval = maxOf(interval, backoff(CHECK_MS, rateLimitStreak))
        if (transientStreak > 0) interval = maxOf(interval, backoff(TRANSIENT_BASE_MS, transientStreak))
        // Jitter only adds, so an occurrence is never checked before its start.
        val spread = jitter(interval / 10).coerceIn(0, interval / 10)
        dueMs = nowElapsedMs + interval + spread
    }

    private fun backoff(base: Long, streak: Int): Long =
        (base shl (streak - 1).coerceIn(0, 10)).coerceAtMost(MAX_BACKOFF_MS)

    // A provider said the show isn't live: enter waiting, or stay in it.
    // lookedUp: the check that produced this signal already asked for a
    // schedule, so later checks in this waiting period don't.
    fun waiting(signal: FeedWaitingSignal, lookedUp: Boolean, nowElapsedMs: Long, nowEpochMs: Long) {
        if (nowElapsedMs < 0) return
        startLookupNeeded = if (active) startLookupNeeded && !lookedUp else !lookedUp
        active = true; kind = signal.kind
        signal.startEpochMs?.let { startEpochMs = it }
        issued = null; transientStreak = 0; rateLimitStreak = 0; failedChecks = 0; accountRecheck = false
        lastCheckedEpochMs = nowEpochMs
        schedule(nowElapsedMs, nowEpochMs)
    }

    // The single decision for any feed failure (#587): whether the feed waits.
    // A provider's "not live yet" or "live show ended" signal starts or keeps
    // waiting; during waiting a passing failure backs off (giving up after a
    // limit), an account failure is rechecked once through readiness, and a
    // failure that needs the user leaves waiting with its usual message.
    // transientAccount: the provider's account check failed temporarily (for
    // example a network blip during renewal), which waiting rides out.
    fun failed(failure: PrototypeFeedFailure, channel: Boolean, lookedUp: Boolean,
        nowElapsedMs: Long, nowEpochMs: Long, transientAccount: Boolean = false): Boolean {
        val signal = failure.waitingSignal(channel)
        return when {
            // A live show that ended didn't ask for the next show's schedule.
            signal != null -> {
                waiting(signal, lookedUp && failure.reason != PrototypeFailureReason.BROADCAST_ENDED, nowElapsedMs, nowEpochMs)
                true
            }
            !active -> false
            transientAccount && failure.reason == PrototypeFailureReason.ABEMA_ACCOUNT_UNAVAILABLE ->
                transientFailure(false, nowElapsedMs, nowEpochMs) == FeedWaitingTransientResult.WAITING
            failure.reason == PrototypeFailureReason.ABEMA_ACCOUNT_UNAVAILABLE && failure.httpStatus == null && !accountRecheck -> {
                accountRecheck = true; lastCheckedEpochMs = nowEpochMs; recheck(nowElapsedMs); true
            }
            failure.transientWhileWaiting() ->
                transientFailure(failure.rateLimited(), nowElapsedMs, nowEpochMs) == FeedWaitingTransientResult.WAITING
            else -> { cancel(); false }
        }
    }

    // A waiting check failed for a passing reason. GAVE_UP after the limit:
    // the caller then shows the failure as it would without waiting.
    fun transientFailure(rateLimited: Boolean, nowElapsedMs: Long, nowEpochMs: Long): FeedWaitingTransientResult {
        if (!active) return FeedWaitingTransientResult.NOT_WAITING
        issued = null
        lastCheckedEpochMs = nowEpochMs
        if (rateLimited) rateLimitStreak++ else { transientStreak++; rateLimitStreak = 0 }
        if (++failedChecks >= TRANSIENT_LIMIT) { cancel(); return FeedWaitingTransientResult.GAVE_UP }
        schedule(nowElapsedMs, nowEpochMs)
        return FeedWaitingTransientResult.WAITING
    }

    fun programStart(epochMs: Long?) {
        if (active) programStartEpochMs = epochMs?.takeIf { it in 1..MAX_SCHEDULED_START_EPOCH_MS }
    }

    fun takeDue(nowElapsedMs: Long): FeedWaitingCheck? {
        val due = dueMs ?: return null
        if (!active || issued != null || nowElapsedMs < due) return null
        return FeedWaitingCheck(due).also { issued = it; dueMs = null }
    }

    fun current(check: FeedWaitingCheck): Boolean = active && issued === check

    // A check was taken but could not run (for example lifecycle or admission
    // was not ready); try again shortly instead of losing the cadence.
    fun deferred(check: FeedWaitingCheck, nowElapsedMs: Long) {
        if (issued !== check) return
        issued = null; dueMs = nowElapsedMs + FAST_CHECK_MS
    }

    // Check again at once without resetting backoff, e.g. after an account
    // failure that readiness may now recover.
    fun recheck(nowElapsedMs: Long) {
        if (!active) return
        issued = null; dueMs = nowElapsedMs
    }

    // "Check now": the next ticker pass checks, and the cadence restarts.
    fun checkNow(nowElapsedMs: Long): Boolean {
        if (!active || issued != null) return false
        dueMs = nowElapsedMs; transientStreak = 0; rateLimitStreak = 0; failedChecks = 0
        return true
    }

    // The show is live and the feed prepared; the next waiting period may
    // look up a schedule again.
    fun started() = cancel()

    fun cancel() {
        active = false; startEpochMs = null; programStartEpochMs = null; lastCheckedEpochMs = null; startLookupNeeded = false
        dueMs = null; issued = null; rateLimitStreak = 0; transientStreak = 0; failedChecks = 0; accountRecheck = false
        kind = FeedWaitingKind.CHANNEL
    }
}
