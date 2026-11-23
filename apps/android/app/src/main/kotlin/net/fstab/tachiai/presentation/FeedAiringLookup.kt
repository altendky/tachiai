package net.fstab.tachiai.presentation

import net.fstab.tachiai.provider.catalog.CatalogAiring
import net.fstab.tachiai.provider.catalog.CatalogFailure
import net.fstab.tachiai.provider.catalog.CatalogResult

internal class FeedAiringToken internal constructor() {
    override fun toString() = "FeedAiringToken"
}

// A waiting channel's scheduled program (#560), from the provider's schedule.
// Presentation-thread policy only. The waiting pane never waits for it: the
// caller asks begin() on each refresh of the waiting state, runs at most one
// lookup at a time on its own worker, and hands the result to complete(),
// which drops any result but the latest lookup's. An answer is reused until
// it is stale or its program boundary has passed; failures back off. Lookups
// never run more often than once a minute, the waiting checks' own cadence.
internal class FeedAiringLookup {
    companion object {
        const val MIN_INTERVAL_MS = 60_000L
        const val FRESH_MS = 30 * 60_000L
        const val FAILURE_MS = 5 * 60_000L
        const val MAX_BACKOFF_MS = 60 * 60_000L
    }

    private var pending: FeedAiringToken? = null
    private var answer: CatalogAiring? = null
    private var startedElapsedMs: Long? = null
    private var dueElapsedMs: Long? = null
    // The first wall time at which the answer no longer describes now.
    private var boundaryEpochMs: Long? = null
    private var failures = 0
    // The provider has no schedule for this feed; never ask again.
    private var unsupported = false

    fun begin(nowElapsedMs: Long, nowEpochMs: Long): FeedAiringToken? {
        if (unsupported || pending != null) return null
        startedElapsedMs?.let { if (nowElapsedMs - it < MIN_INTERVAL_MS) return null }
        val boundaryPassed = boundaryEpochMs?.let { nowEpochMs >= it } == true
        dueElapsedMs?.let { if (nowElapsedMs < it && !boundaryPassed) return null }
        startedElapsedMs = nowElapsedMs
        return FeedAiringToken().also { pending = it }
    }

    // Whether this was the latest lookup; a superseded result changes nothing.
    fun complete(token: FeedAiringToken, result: CatalogResult<CatalogAiring>, nowElapsedMs: Long, nowEpochMs: Long): Boolean {
        if (token !== pending) return false
        pending = null
        when (result) {
            is CatalogResult.Value -> {
                failures = 0
                answer = result.value
                boundaryEpochMs = listOfNotNull(result.value.current?.endEpochMs, result.value.next?.startEpochMs)
                    .filter { it > nowEpochMs }.minOrNull()
                dueElapsedMs = nowElapsedMs + FRESH_MS
            }
            is CatalogResult.Failure -> when (result.reason) {
                CatalogFailure.UNSUPPORTED -> unsupported = true
                else -> {
                    // A previous answer stays shown only while it still applies.
                    boundaryEpochMs = null
                    val backoff = (FAILURE_MS shl failures.coerceAtMost(4)).coerceAtMost(MAX_BACKOFF_MS)
                    failures++
                    val limited = result.retryAtEpochMs?.let { (it - nowEpochMs).coerceIn(0, MAX_BACKOFF_MS) } ?: 0
                    dueElapsedMs = nowElapsedMs + maxOf(backoff, limited)
                }
            }
        }
        return true
    }

    // The scheduled program to show at nowEpochMs, if any: the one on now,
    // otherwise the next one that hasn't started.
    fun program(nowEpochMs: Long): FeedWaitingProgram? {
        val known = answer ?: return null
        known.current?.takeIf { nowEpochMs >= it.startEpochMs && nowEpochMs < it.endEpochMs }?.let {
            return FeedWaitingProgram(it.title, it.startEpochMs, it.endEpochMs, now = true)
        }
        return known.next?.takeIf { it.startEpochMs > nowEpochMs }?.let {
            FeedWaitingProgram(it.title, it.startEpochMs, it.endEpochMs, now = false)
        }
    }

    // The known start closest to now, which times the waiting checks.
    fun start(nowEpochMs: Long): Long? {
        val known = answer ?: return null
        return listOfNotNull(known.current?.startEpochMs, known.next?.startEpochMs)
            .minByOrNull { kotlin.math.abs(it - nowEpochMs) }
    }
}
