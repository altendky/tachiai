package net.fstab.tachiai.presentation

import org.junit.Assert.*
import org.junit.Test

class FeedWaitingStateTest {
    private val epoch = 1_800_000_000_000L
    private fun state(jitter: Long = 0) = FeedWaitingState { bound -> minOf(jitter, bound) }
    private fun FeedWaitingState.dueAt(now: Long): Long {
        var t = now
        while (takeDue(t) == null) { t += 1_000; check(t - now < 10_000_000) }
        return t
    }
    private val channel = FeedWaitingSignal(FeedWaitingKind.CHANNEL)

    @Test fun onlyNotLiveAndLiveShowEndedSignalsWait() {
        val notLive = PrototypeFeedFailure(PrototypeFailureReason.NOT_LIVE_YET, scheduledStartEpochMs = epoch)
        assertEquals(FeedWaitingSignal(FeedWaitingKind.CHANNEL, epoch), notLive.waitingSignal(channel = true))
        assertEquals(FeedWaitingSignal(FeedWaitingKind.OCCURRENCE, epoch), notLive.waitingSignal(channel = false))
        assertEquals(FeedWaitingSignal(FeedWaitingKind.CHANNEL),
            PrototypeFeedFailure(PrototypeFailureReason.CHANNEL_NOT_AIRING).waitingSignal(channel = true))
        // A live show ending waits for the next one; an occurrence that ended keeps its ended state.
        val ended = PrototypeFeedFailure(PrototypeFailureReason.BROADCAST_ENDED)
        assertEquals(FeedWaitingSignal(FeedWaitingKind.CHANNEL), ended.waitingSignal(channel = true))
        assertNull(ended.waitingSignal(channel = false))
        for (reason in PrototypeFailureReason.entries.filter { it !in setOf(PrototypeFailureReason.NOT_LIVE_YET,
                PrototypeFailureReason.CHANNEL_NOT_AIRING, PrototypeFailureReason.BROADCAST_ENDED) })
            assertNull(reason.name, PrototypeFeedFailure(reason).waitingSignal(channel = true))
    }

    @Test fun aScheduledStartIsOnlyCarriedByNotLiveYet() {
        assertThrows(IllegalArgumentException::class.java) {
            PrototypeFeedFailure(PrototypeFailureReason.MEDIA_NOT_FOUND, scheduledStartEpochMs = epoch)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrototypeFeedFailure(PrototypeFailureReason.NOT_LIVE_YET, scheduledStartEpochMs = 0)
        }
        assertEquals(FeedRecoveryAction.CHECK_NOW, PrototypeFeedFailure(PrototypeFailureReason.NOT_LIVE_YET).recoveryAction())
    }

    @Test fun aChannelWithoutAStartIsCheckedEveryMinute() {
        val s = state()
        s.waiting(channel, lookedUp = true, nowElapsedMs = 0, nowEpochMs = epoch)
        assertTrue(s.active)
        assertNull(s.takeDue(59_999))
        val check = checkNotNull(s.takeDue(60_000))
        assertTrue(s.current(check))
        // One check at a time.
        assertNull(s.takeDue(120_000))
        s.waiting(channel, lookedUp = false, nowElapsedMs = 61_000, nowEpochMs = epoch + 61_000)
        assertFalse(s.current(check))
        assertEquals(121_000L, s.dueAt(61_000))
    }

    @Test fun checksSpeedUpToFifteenSecondsWithinTwoMinutesOfAKnownStart() {
        val s = state()
        val start = epoch + 10 * 60_000
        s.waiting(FeedWaitingSignal(FeedWaitingKind.CHANNEL, start), lookedUp = true, nowElapsedMs = 0, nowEpochMs = epoch)
        assertEquals(60_000L, s.dueAt(0))
        // 2.5 min before: the minute step is clamped to land on the window.
        s.waiting(FeedWaitingSignal(FeedWaitingKind.CHANNEL, start), true, 0, start - 150_000)
        assertEquals(30_000L, s.dueAt(0))
        s.waiting(FeedWaitingSignal(FeedWaitingKind.CHANNEL, start), true, 0, start - 120_000)
        assertEquals(15_000L, s.dueAt(0))
        s.waiting(FeedWaitingSignal(FeedWaitingKind.CHANNEL, start), true, 0, start + 120_000)
        assertEquals(15_000L, s.dueAt(0))
        // Well past a channel's start, back to every minute.
        s.waiting(FeedWaitingSignal(FeedWaitingKind.CHANNEL, start), true, 0, start + 121_000)
        assertEquals(60_000L, s.dueAt(0))
    }

    @Test fun aScheduledProgramStartTimesAChannelsChecksFromTheNextScheduling() {
        val s = state()
        val start = epoch + 10 * 60_000
        s.programStart(start)
        assertNull("only while waiting", s.programStartEpochMs)
        s.waiting(channel, lookedUp = true, nowElapsedMs = 0, nowEpochMs = start - 120_000)
        // Applied after this scheduling: the current check stays a minute away.
        s.programStart(start)
        assertEquals(60_000L, s.dueAt(0))
        s.waiting(channel, true, 60_000, start - 60_000)
        assertEquals(75_000L, s.dueAt(60_000))
        // It never changes the signalled start or the pane's first line.
        assertNull(s.startEpochMs)
        // A provider's own start wins, and an occurrence ignores a program start.
        val signalled = state()
        signalled.waiting(FeedWaitingSignal(FeedWaitingKind.CHANNEL, start + 3_600_000), true, 0, start - 60_000)
        signalled.programStart(start)
        signalled.waiting(FeedWaitingSignal(FeedWaitingKind.CHANNEL), true, 0, start - 60_000)
        assertEquals(60_000L, signalled.dueAt(0))
        val occurrence = state()
        occurrence.waiting(FeedWaitingSignal(FeedWaitingKind.OCCURRENCE), true, 0, start - 60_000)
        occurrence.programStart(start)
        occurrence.waiting(FeedWaitingSignal(FeedWaitingKind.OCCURRENCE), true, 0, start - 60_000)
        assertEquals(60_000L, occurrence.dueAt(0))
        s.cancel(); assertNull(s.programStartEpochMs)
    }

    @Test fun anOccurrenceIsNotCheckedBeforeItsStartThenEveryFifteenSeconds() {
        val s = state(jitter = 5_000)
        val start = epoch + 30 * 60_000
        s.waiting(FeedWaitingSignal(FeedWaitingKind.OCCURRENCE, start), true, 0, epoch)
        // Jitter only delays, so the first check is never before the start.
        val first = s.dueAt(0)
        assertTrue(first >= 30 * 60_000L)
        s.waiting(FeedWaitingSignal(FeedWaitingKind.OCCURRENCE, start), true, 0, start + 600_000)
        // 15 s plus at most 1.5 s of jitter; dueAt steps in whole seconds.
        assertTrue(s.dueAt(0) in 15_000L..17_000L)
        // Approved: no checks before the start, however far away it is.
        val far = state()
        far.waiting(FeedWaitingSignal(FeedWaitingKind.OCCURRENCE, epoch + 5 * 3_600_000L), true, 0, epoch)
        assertNull(far.takeDue(5 * 3_600_000L - 1))
        assertNotNull(far.takeDue(5 * 3_600_000L))
    }

    @Test fun jitterAddsAtMostATenthOfTheInterval() {
        val s = FeedWaitingState { bound -> bound * 5 }
        s.waiting(channel, true, 0, epoch)
        assertEquals(66_000L, s.dueAt(0))
    }

    @Test fun passingFailuresBackOffAndGiveUpAfterFive() {
        val s = state()
        s.waiting(channel, true, 0, epoch)
        // dueAt takes the check that is due.
        var now = s.dueAt(0)
        val waits = mutableListOf<Long>()
        repeat(4) {
            assertEquals(FeedWaitingTransientResult.WAITING, s.transientFailure(false, now, epoch + now))
            val next = s.dueAt(now)
            waits += next - now; now = next
        }
        // 30 s, 60 s, 120 s, 240 s backoff never undercuts the minute cadence.
        assertEquals(listOf(60_000L, 60_000L, 120_000L, 240_000L), waits)
        assertEquals(FeedWaitingTransientResult.GAVE_UP, s.transientFailure(false, now, epoch + now))
        assertFalse(s.active)
        assertEquals(FeedWaitingTransientResult.NOT_WAITING, s.transientFailure(false, now, epoch + now))
    }

    @Test fun rateLimitsDoubleTheIntervalAndANotLiveAnswerResetsIt() {
        val s = state()
        s.waiting(channel, true, 0, epoch)
        s.takeDue(60_000)
        s.transientFailure(true, 60_000, epoch + 60_000)
        assertEquals(60_000L, s.dueAt(60_000) - 60_000)
        s.takeDue(120_000)
        s.transientFailure(true, 120_000, epoch + 120_000)
        assertEquals(120_000L, s.dueAt(120_000) - 120_000)
        s.waiting(channel, true, 240_000, epoch + 240_000)
        assertEquals(60_000L, s.dueAt(240_000) - 240_000)
    }

    @Test fun checkNowChecksImmediatelyAndRestartsTheCadence() {
        val s = state()
        s.waiting(channel, true, 0, epoch)
        assertTrue(s.checkNow(10_000))
        val check = checkNotNull(s.takeDue(10_000))
        // Not while a check is running.
        assertFalse(s.checkNow(10_500))
        s.deferred(check, 11_000)
        assertNull(s.takeDue(25_999))
        assertNotNull(s.takeDue(26_000))
    }

    @Test fun theScheduleIsLookedUpOncePerWaitingPeriod() {
        val s = state()
        s.waiting(channel, lookedUp = false, nowElapsedMs = 0, nowEpochMs = epoch)
        assertTrue(s.startLookupNeeded)
        s.waiting(FeedWaitingSignal(FeedWaitingKind.CHANNEL, epoch + 600_000), lookedUp = true, 60_000, epoch + 60_000)
        assertFalse(s.startLookupNeeded)
        assertEquals(epoch + 600_000, s.startEpochMs)
        // A later answer without a start keeps the known one.
        s.waiting(channel, lookedUp = false, 120_000, epoch + 120_000)
        assertFalse(s.startLookupNeeded)
        assertEquals(epoch + 600_000, s.startEpochMs)
        // Going live ends the period; the next one looks up again.
        s.started()
        assertFalse(s.active); assertNull(s.startEpochMs)
        s.waiting(channel, lookedUp = false, 200_000, epoch + 200_000)
        assertTrue(s.startLookupNeeded)
    }

    @Test fun theFailureDecisionEntersStaysRechecksTheAccountOnceAndLeaves() {
        val s = state()
        val notLive = PrototypeFeedFailure(PrototypeFailureReason.NOT_LIVE_YET)
        // Not waiting: ordinary failures stay ordinary.
        assertFalse(s.failed(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED), true, true, 0, epoch))
        assertFalse(s.active)
        assertTrue(s.failed(notLive, channel = true, lookedUp = true, nowElapsedMs = 0, nowEpochMs = epoch))
        assertTrue(s.active); assertEquals(FeedWaitingKind.CHANNEL, s.kind)
        // An account problem is rechecked at once, through readiness, one time.
        val account = PrototypeFeedFailure(PrototypeFailureReason.ABEMA_ACCOUNT_UNAVAILABLE)
        assertTrue(s.failed(account, true, true, 60_000, epoch + 60_000))
        assertTrue(s.accountRecheck)
        assertNotNull(s.takeDue(60_000))
        // A not-live answer clears it; a second account failure in a row leaves waiting.
        assertTrue(s.failed(notLive, true, true, 61_000, epoch + 61_000))
        assertFalse(s.accountRecheck)
        assertTrue(s.failed(account, true, true, 121_000, epoch + 121_000))
        assertFalse(s.failed(account, true, true, 122_000, epoch + 122_000))
        assertFalse(s.active)
        // A refusal that needs the user leaves waiting at once.
        assertTrue(s.failed(notLive, true, true, 200_000, epoch + 200_000))
        assertFalse(s.failed(PrototypeFeedFailure(PrototypeFailureReason.LICENSE_FAILED), true, true, 201_000, epoch + 201_000))
        assertFalse(s.active)
        // A passing failure while waiting keeps waiting.
        assertTrue(s.failed(notLive, true, true, 300_000, epoch + 300_000))
        assertTrue(s.failed(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED), true, true, 301_000, epoch + 301_000))
        assertTrue(s.active)
    }

    @Test fun anEndedOccurrenceDoesNotWaitButAnEndedChannelDoes() {
        val ended = PrototypeFeedFailure(PrototypeFailureReason.BROADCAST_ENDED)
        assertFalse(state().failed(ended, channel = false, lookedUp = true, nowElapsedMs = 0, nowEpochMs = epoch))
        val s = state()
        // Even though the attempt that played did look up, the next show needs its own schedule.
        assertTrue(s.failed(ended, channel = true, lookedUp = true, nowElapsedMs = 0, nowEpochMs = epoch))
        assertTrue(s.startLookupNeeded)
    }

    @Test fun aTemporaryAccountCheckFailureIsRiddenOut() {
        val s = state()
        assertTrue(s.failed(PrototypeFeedFailure(PrototypeFailureReason.NOT_LIVE_YET), true, true, 0, epoch))
        val account = PrototypeFeedFailure(PrototypeFailureReason.ABEMA_ACCOUNT_UNAVAILABLE)
        assertTrue(s.failed(account, true, true, 60_000, epoch + 60_000))
        assertTrue(s.accountRecheck)
        // The recheck's readiness failed temporarily: keep waiting, and keep rechecking the account.
        assertTrue(s.failed(account, true, true, 61_000, epoch + 61_000, transientAccount = true))
        assertTrue(s.active); assertTrue(s.accountRecheck)
    }

    @Test fun mixedPassingFailuresStillGiveUpAfterFive() {
        val s = state()
        s.waiting(channel, true, 0, epoch)
        val results = listOf(true, false, true, false, true).map { rate -> s.transientFailure(rate, 0, epoch) }
        assertEquals(List(4) { FeedWaitingTransientResult.WAITING } + FeedWaitingTransientResult.GAVE_UP, results)
    }

    @Test fun passingAndRefusingFailuresAreTold() {
        assertTrue(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED).transientWhileWaiting())
        assertTrue(PrototypeFeedFailure(PrototypeFailureReason.HTTP_REJECTED, httpStatus = 503).transientWhileWaiting())
        assertTrue(PrototypeFeedFailure(PrototypeFailureReason.CATALOG_RATE_LIMITED).rateLimited())
        assertFalse(PrototypeFeedFailure(PrototypeFailureReason.HTTP_REJECTED, httpStatus = 404).transientWhileWaiting())
        assertFalse(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED, httpStatus = 403).transientWhileWaiting())
        for (reason in listOf(PrototypeFailureReason.ABEMA_ACCOUNT_UNAVAILABLE, PrototypeFailureReason.LOGIN_EXPIRED,
                PrototypeFailureReason.ROUTE_FAILED, PrototypeFailureReason.LICENSE_FAILED,
                PrototypeFailureReason.MEDIA_BLOCKED, PrototypeFailureReason.CLEANUP_FAILED))
            assertFalse(reason.name, PrototypeFeedFailure(reason).transientWhileWaiting())
    }
}
