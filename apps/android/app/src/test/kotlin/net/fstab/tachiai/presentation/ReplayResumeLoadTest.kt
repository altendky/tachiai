package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.media.NativeFailureStage
import net.fstab.tachiai.platform.media.NativePlaybackDiagnosticSnapshot
import net.fstab.tachiai.platform.media.NativePlaybackTransportFailure
import net.fstab.tachiai.platform.media.NativeTimingSnapshot
import net.fstab.tachiai.platform.network.ProviderTransportEvidence
import net.fstab.tachiai.platform.network.ProviderTransportExceptionKind
import net.fstab.tachiai.platform.network.ProviderTransportFailure
import org.junit.Assert.*
import org.junit.Test

// A replay's wait for its resume position is measured from loading progress
// (#747), the same way for an initial start, a Retry and automatic recovery.
class ReplayResumeLoadTest {
    private val target = 5_335_000L // about 1:28:55 into a 3:24:00 episode
    private fun timing(state: Int = 2, position: Long? = target, buffered: Long? = target) =
        NativeTimingSnapshot(position, 12_240_000, buffered, null, null, null, false, false, true, true,
            false, false, false, state, 1f)

    // Samples every 200 ms like the viewer tick; buffered grows by step each sample until stopAtMs.
    private fun run(load: ReplayResumeLoad, untilMs: Long, readyAtMs: Long? = null, stopAtMs: Long = Long.MAX_VALUE,
        step: Long = 50): Pair<ReplayResumeStatus, Long> {
        var buffered = target
        var now = 0L
        while (now <= untilMs) {
            if (now < stopAtMs) buffered += step
            val ready = readyAtMs != null && now >= readyAtMs
            val status = load.observe(now, timing(if (ready) 3 else 2, buffered = buffered))
            if (status != ReplayResumeStatus.LOADING) return status to now
            now += 200
        }
        return ReplayResumeStatus.LOADING to now
    }

    private enum class Start { INITIAL, RETRY, AUTOMATIC }

    private fun diagnostic(now: Long) = NativePlaybackDiagnosticSnapshot(now, 0, false, true, true, 3, 0, null,
        20_000 + now, true, now / 10 + 1, 0, 1, 0, null, null, null)
    private fun transport() = NativePlaybackTransportFailure(NativeFailureStage.MEDIA_REQUEST_OPEN, 0,
        ProviderTransportEvidence(ProviderTransportFailure.UNKNOWN, exceptionKind = ProviderTransportExceptionKind.IO))

    // The recovery state and resume target as each start reaches a fresh player.
    private fun start(kind: Start): Pair<FeedAutoRecoveryState, Long?> {
        val recovery = FeedAutoRecoveryState()
        val saved = target
        val retryPosition = target + 60_000
        return when (kind) {
            Start.INITIAL -> recovery to replayResumeTargetMs(false, null, saved)
            Start.RETRY -> recovery to replayResumeTargetMs(true, retryPosition, null)
            Start.AUTOMATIC -> {
                val member = Any()
                recovery.observe(member, diagnostic(0), 0)
                assertEquals(FeedAutoRecoveryDecision.SCHEDULED, recovery.failed(member,
                    PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED), transport(), false, true, false, 0))
                checkNotNull(recovery.takeDue(1_000))
                assertTrue(recovery.automaticAttemptActive)
                recovery to replayResumeTargetMs(true, retryPosition, null)
            }
        }
    }

    @Test fun `a slow resume that keeps loading reaches its position for every start`() {
        for (kind in Start.entries) {
            val (_, resume) = start(kind)
            val load = ReplayResumeLoad(checkNotNull(resume), 0)
            var buffered = resume
            var now = 0L
            var status = ReplayResumeStatus.LOADING
            // Loads for 9.2 s, past the former fixed 8 s deadline.
            while (status == ReplayResumeStatus.LOADING) {
                buffered += 50
                status = load.observe(now, NativeTimingSnapshot(resume, 12_240_000, buffered, null, null, null,
                    false, false, true, true, false, false, false, if (now >= 9_200) 3 else 2, 1f))
                now += 200
            }
            assertEquals("start=$kind", ReplayResumeStatus.AT_TARGET, status)
            assertTrue("start=$kind", now > ReplayResumeLoad.STALL_MS)
        }
    }

    @Test fun `a stalled resume ends with an accurate bounded failure for every start`() {
        for (kind in Start.entries) {
            val (recovery, resume) = start(kind)
            val load = ReplayResumeLoad(checkNotNull(resume), 1_000)
            var now = 1_000L
            var status: ReplayResumeStatus
            do {
                status = load.observe(now, timing(position = resume, buffered = resume + 2_000))
                now += 200
            } while (status == ReplayResumeStatus.LOADING)
            assertEquals("start=$kind", ReplayResumeStatus.STALLED, status)
            assertEquals("start=$kind", 1_000 + ReplayResumeLoad.STALL_MS, now - 200)
            val failure = load.failure()
            assertEquals(PrototypeFailureReason.PREPARATION_TIMEOUT, failure.reason)
            assertNotEquals(PrototypeFailureReason.PLAYER_FAILED.message, failure.viewerMessage)
            assertEquals(FeedRecoveryAction.RETRY, failure.recoveryAction())
            // Existing rules: only an active automatic recovery incident continues
            // its bounded backoff; a first start or a manual Retry offers Retry.
            val decision = recovery.failed(Any(), failure, null, false, true, false, now)
            assertEquals("start=$kind", if (kind == Start.AUTOMATIC) FeedAutoRecoveryDecision.SCHEDULED
                else FeedAutoRecoveryDecision.SKIPPED, decision)
        }
    }

    @Test fun `no loading progress since the seek stalls at the former deadline`() {
        val load = ReplayResumeLoad(target, 0)
        assertEquals(ReplayResumeStatus.STALLED to ReplayResumeLoad.STALL_MS, run(load, 60_000, step = 0))
    }

    @Test fun `the stall window restarts with each progress and ends after the last`() {
        val load = ReplayResumeLoad(target, 0)
        val (status, at) = run(load, 60_000, stopAtMs = 6_000)
        assertEquals(ReplayResumeStatus.STALLED, status)
        // The last increase was sampled at 5.8 s.
        assertEquals(5_800 + ReplayResumeLoad.STALL_MS, at)
    }

    @Test fun `progress that never reaches the position is bounded`() {
        val load = ReplayResumeLoad(target, 0)
        assertEquals(ReplayResumeStatus.TIMED_OUT to ReplayResumeLoad.LIMIT_MS, run(load, 60_000))
    }

    @Test fun `ready at the position wins even at the bound`() {
        val load = ReplayResumeLoad(target, 0)
        assertEquals(ReplayResumeStatus.AT_TARGET,
            load.observe(ReplayResumeLoad.LIMIT_MS + 1, timing(state = 3, position = target + 1_000)))
    }

    @Test fun `buffering at the position or ready elsewhere is still loading`() {
        val load = ReplayResumeLoad(target, 0)
        assertEquals(ReplayResumeStatus.LOADING, load.observe(0, timing(state = 2)))
        assertEquals(ReplayResumeStatus.LOADING, load.observe(200, timing(state = 3, position = target + 1_001)))
        assertEquals(ReplayResumeStatus.LOADING, load.observe(400, timing(state = 3, position = null)))
        assertEquals(ReplayResumeStatus.LOADING, load.observe(600, timing(state = 3, position = target - 1_001)))
        assertEquals(ReplayResumeStatus.AT_TARGET, load.observe(800, timing(state = 3, position = target - 1_000)))
    }

    @Test fun `the first sample is only a baseline and a missing player is no progress`() {
        val load = ReplayResumeLoad(target, 0)
        // A large buffered value at the first sample is not progress.
        assertEquals(ReplayResumeStatus.LOADING, load.observe(7_000, timing(buffered = target + 50_000)))
        assertEquals(ReplayResumeStatus.STALLED, load.observe(8_000, null))
        val unknown = ReplayResumeLoad(target, 0)
        assertEquals(ReplayResumeStatus.LOADING, unknown.observe(1_000, timing(buffered = null)))
        assertEquals(ReplayResumeStatus.LOADING, unknown.observe(2_000, timing(buffered = -1)))
        assertEquals(ReplayResumeStatus.STALLED, unknown.observe(8_000, timing(buffered = null)))
    }

    @Test fun `a buffer reset by the seek counts once it grows again`() {
        val load = ReplayResumeLoad(target, 0)
        load.observe(0, timing(buffered = target + 30_000))
        load.observe(1_000, timing(buffered = target))
        assertEquals(ReplayResumeStatus.LOADING, load.observe(7_000, timing(buffered = target + 10)))
        assertEquals(ReplayResumeStatus.LOADING, load.observe(14_000, timing(buffered = target + 10)))
        assertEquals(ReplayResumeStatus.STALLED, load.observe(15_000, timing(buffered = target + 10)))
    }

    @Test fun `a clock that ran backwards fails closed`() {
        val load = ReplayResumeLoad(target, 10_000)
        assertEquals(ReplayResumeStatus.TIMED_OUT, load.observe(9_999, timing()))
    }

    @Test fun `the summary has only closed fields`() {
        val load = ReplayResumeLoad(target, 0)
        load.observe(0, timing(buffered = target))
        load.observe(1_000, timing(buffered = target + 500))
        assertEquals("elapsedMs=3000 progressAgeMs=2000 progress=1", load.summary(3_000))
        assertFalse(load.summary(3_000).contains(target.toString()))
    }
}
