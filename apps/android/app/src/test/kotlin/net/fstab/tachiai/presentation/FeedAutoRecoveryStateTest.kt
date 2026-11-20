package net.fstab.tachiai.presentation

import net.fstab.tachiai.platform.media.NativeFailureStage
import net.fstab.tachiai.platform.media.NativePlaybackDiagnosticSnapshot
import net.fstab.tachiai.platform.media.NativePlaybackHttpFailure
import net.fstab.tachiai.platform.media.NativePlaybackTransportFailure
import net.fstab.tachiai.platform.media.latestNativePlaybackTransport
import net.fstab.tachiai.platform.network.ProviderTransportEvidence
import net.fstab.tachiai.platform.network.ProviderTransportExceptionKind
import net.fstab.tachiai.platform.network.ProviderTransportFailure
import org.junit.Assert.*
import org.junit.Test

class FeedAutoRecoveryStateTest {
    @Test fun `terminal unspecified IO with fresh typed network evidence retains network recovery`() {
        val state = FeedAutoRecoveryState(); val member = Any()
        state.observe(member, sample(0), 0)
        assertEquals(FeedAutoRecoveryDecision.SCHEDULED, fail(state, member, 1,
            PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = 2000)))
        assertNotNull(state.pending)
    }

    @Test fun `unspecified IO accepts only current typed media network or retryable HTTP evidence`() {
        val network = listOf(transport(ProviderTransportFailure.DNS), transport(ProviderTransportFailure.CONNECT),
            transport(ProviderTransportFailure.TIMEOUT), transport(), transport(stage = NativeFailureStage.MEDIA_REQUEST_READ))
        for (live in listOf(false, true)) {
            for (evidence in network) {
                val state = FeedAutoRecoveryState(); val member = Any()
                state.observe(member, sample(0).copy(liveClock = live), 0)
                assertEquals(FeedAutoRecoveryDecision.SCHEDULED, fail(state, member, 1,
                    PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = 2000), evidence, live = live))
            }
            for (status in listOf(408, 429, 500, 503, 599, 404)) {
                for (stage in listOf(NativeFailureStage.MEDIA_REQUEST_OPEN, NativeFailureStage.MEDIA_REQUEST_READ)) {
                    val state = FeedAutoRecoveryState(); val member = Any()
                    state.observe(member, sample(0).copy(liveClock = live), 0)
                    assertEquals("live=$live status=$status stage=$stage",
                        if (status == 404 && !live) FeedAutoRecoveryDecision.SKIPPED else FeedAutoRecoveryDecision.SCHEDULED,
                        fail(state, member, 1, PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = 2000),
                            evidence = null, live = live, httpFailure = NativePlaybackHttpFailure(stage, 0, status)))
                }
            }
        }
    }

    @Test fun `unspecified IO refuses stale missing local policy and untyped network evidence`() {
        val member = Any()
        val localFailure = sample(0).copy(failureStage = NativeFailureStage.MEDIA_REQUEST_OPEN, failureAgeMs = 0)
        val refused = listOf(null, transport(age = -1), transport(age = 5_001),
            transport(stage = NativeFailureStage.MEDIA_DISCONNECT),
            transport(kind = ProviderTransportExceptionKind.UNKNOWN),
            transport(kind = ProviderTransportExceptionKind.PROTOCOL),
            transport(kind = ProviderTransportExceptionKind.OTHER),
            latestNativePlaybackTransport(localFailure, transport()))
        for (evidence in refused) {
            val state = FeedAutoRecoveryState(); state.observe(member, sample(0), 0)
            assertEquals(FeedAutoRecoveryDecision.SKIPPED, fail(state, member, 1,
                PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = 2000), evidence))
        }
        for (http in listOf(
            NativePlaybackHttpFailure(NativeFailureStage.MEDIA_REQUEST_OPEN, -1, 503),
            NativePlaybackHttpFailure(NativeFailureStage.MEDIA_REQUEST_OPEN, 5_001, 503),
            NativePlaybackHttpFailure(NativeFailureStage.MEDIA_DISCONNECT, 0, 503),
            NativePlaybackHttpFailure(NativeFailureStage.MEDIA_REQUEST_OPEN, 0, 400),
            NativePlaybackHttpFailure(NativeFailureStage.MEDIA_REQUEST_READ, 0, 418))) {
            val state = FeedAutoRecoveryState(); state.observe(member, sample(0), 0)
            assertEquals(FeedAutoRecoveryDecision.SKIPPED, fail(state, member, 1,
                PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = 2000), evidence = null, httpFailure = http))
        }
    }

    @Test fun `fresh HTTP authorization denial blocks every older recovery reason and verified refresh`() {
        val failures = listOf(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED),
            PrototypeFeedFailure(PrototypeFailureReason.HTTP_REJECTED, httpStatus = 503),
            PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = 2000),
            PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = 1002),
            PrototypeFeedFailure(PrototypeFailureReason.ABEMA_ACCOUNT_UNAVAILABLE))
        for (status in listOf(401, 403)) for (failure in failures) {
            val state = FeedAutoRecoveryState(); val member = Any()
            state.observe(member, sample(0), 0)
            assertEquals(FeedAutoRecoveryDecision.SKIPPED, state.failed(member, failure, transport(), true, true, true, 1,
                authorizationRefreshSupported = true,
                httpFailure = NativePlaybackHttpFailure(NativeFailureStage.MEDIA_REQUEST_OPEN, 0, status)))
            assertNull(state.pending)
        }
    }

    @Test fun `fresh retryable HTTP cannot bypass TLS cancellation hard reasons or other player codes`() {
        val http = NativePlaybackHttpFailure(NativeFailureStage.MEDIA_REQUEST_OPEN, 0, 503)
        val terminal = PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = 2000)
        for (evidence in listOf(transport(ProviderTransportFailure.TLS), transport(ProviderTransportFailure.CANCELLED),
            transport(cancelled = true), transport(kind = ProviderTransportExceptionKind.INTERRUPTED_IO),
            transport(kind = ProviderTransportExceptionKind.TLS_HANDSHAKE),
            transport(kind = ProviderTransportExceptionKind.TLS_PEER), transport(kind = ProviderTransportExceptionKind.TLS_OTHER))) {
            val state = FeedAutoRecoveryState(); val member = Any(); state.observe(member, sample(0), 0)
            assertEquals(FeedAutoRecoveryDecision.SKIPPED, fail(state, member, 1, terminal, evidence, httpFailure = http))
        }
        val refused = listOf(PrototypeFailureReason.LICENSE_FAILED, PrototypeFailureReason.MEDIA_BLOCKED,
            PrototypeFailureReason.MEDIA_APPROVAL_REQUIRED, PrototypeFailureReason.CLEANUP_FAILED,
            PrototypeFailureReason.STOPPED, PrototypeFailureReason.PLAYBACK_LIMIT,
            PrototypeFailureReason.NETWORK_SECURITY_FAILED).map { PrototypeFeedFailure(it) } +
            listOf(null, 1000, 2001, 2002, 6001).map { PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = it) } +
            listOf(401, 403, 503).map { terminal.copy(httpStatus = it) }
        for (failure in refused) {
            val state = FeedAutoRecoveryState(); val member = Any(); state.observe(member, sample(0), 0)
            assertEquals("failure=$failure", FeedAutoRecoveryDecision.SKIPPED,
                fail(state, member, 1, failure, httpFailure = http))
        }
    }

    @Test fun `typed unspecified IO cannot bypass missing exact playback intent or lifecycle permission`() {
        for (mode in 0..4) {
            val state = FeedAutoRecoveryState(); val member = Any()
            if (mode != 0) state.observe(member, sample(0), 0)
            if (mode == 2) state.cancel()
            if (mode == 3) state.observe(member, sample(1).copy(playing = false, playWhenReady = false), 1)
            assertEquals("mode=$mode", FeedAutoRecoveryDecision.SKIPPED,
                fail(state, if (mode == 1) Any() else member, 1,
                    PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = 2000), allowed = mode != 4,
                    httpFailure = NativePlaybackHttpFailure(NativeFailureStage.MEDIA_REQUEST_OPEN, 0, 503)))
        }
    }

    @Test fun `verified provider renewal capability allows only one account refresh per incident`() {
        val state = FeedAutoRecoveryState(); val member = Any()
        state.observe(member, sample(0), 0)
        val failure = PrototypeFeedFailure(PrototypeFailureReason.ABEMA_ACCOUNT_UNAVAILABLE)
        assertEquals(FeedAutoRecoveryDecision.SCHEDULED,
            state.failed(member, failure, null, true, true, true, 0, authorizationRefreshSupported = true))
        assertNotNull(state.takeDue(1_000))
        assertEquals(FeedAutoRecoveryDecision.SKIPPED,
            state.failed(null, failure, null, true, true, true, 1_001, authorizationRefreshSupported = true))
        assertNull(state.pending)
        assertFalse(state.automaticAttemptActive)
        state.cancel(); state.observe(member, sample(2_000), 2_000)
        assertEquals(FeedAutoRecoveryDecision.SKIPPED,
            state.failed(member, failure, null, true, true, true, 2_000))
        for (status in listOf(401, 403)) {
            val blocked = FeedAutoRecoveryState(); blocked.observe(member, sample(0), 0)
            assertEquals(FeedAutoRecoveryDecision.SKIPPED, blocked.failed(member, failure.copy(httpStatus = status),
                null, true, true, true, 0, authorizationRefreshSupported = true))
        }
        val tls = FeedAutoRecoveryState(); tls.observe(member, sample(0), 0)
        assertEquals(FeedAutoRecoveryDecision.SKIPPED, tls.failed(member, failure,
            transport(ProviderTransportFailure.TLS, ProviderTransportExceptionKind.TLS_HANDSHAKE),
            true, true, true, 0, authorizationRefreshSupported = true))
    }

    @Test fun `transient account readiness inside an active incident continues bounded backoff while refusal stops`() {
        val account = PrototypeFeedFailure(PrototypeFailureReason.ABEMA_ACCOUNT_UNAVAILABLE); val member = Any()
        val state = FeedAutoRecoveryState(); firstAttempt(state, member)
        // The retry's readiness check has no member or transport evidence.
        assertEquals(FeedAutoRecoveryDecision.SCHEDULED, state.failed(null, account, null, true, true, true, 1_500,
            transientAccountReadiness = true))
        assertEquals(2, checkNotNull(state.pending).attempt)
        assertNull(state.takeDue(4_499)); assertNotNull(state.takeDue(4_500))
        assertEquals(FeedAutoRecoveryDecision.SCHEDULED, state.failed(null, account, null, true, true, true, 5_000,
            transientAccountReadiness = true))
        assertNotNull(state.takeDue(15_000))
        assertEquals(FeedAutoRecoveryDecision.EXHAUSTED, state.failed(null, account, null, true, true, true, 15_500,
            transientAccountReadiness = true))
        val refused = FeedAutoRecoveryState(); firstAttempt(refused, member)
        assertEquals(FeedAutoRecoveryDecision.SKIPPED, refused.failed(null, account, null, true, true, true, 1_500))
        assertNull(refused.pending); assertFalse(refused.automaticAttemptActive)
        val fresh = FeedAutoRecoveryState(); fresh.observe(member, sample(0), 0)
        assertEquals(FeedAutoRecoveryDecision.SKIPPED, fresh.failed(member, account, null, true, true, true, 1,
            transientAccountReadiness = true))
        for (status in listOf(401, 403)) {
            val denied = FeedAutoRecoveryState(); firstAttempt(denied, member)
            assertEquals(FeedAutoRecoveryDecision.SKIPPED, denied.failed(null, account.copy(httpStatus = status),
                null, true, true, true, 1_500, transientAccountReadiness = true))
        }
        val other = FeedAutoRecoveryState(); firstAttempt(other, member)
        assertEquals(FeedAutoRecoveryDecision.SKIPPED, other.failed(null,
            PrototypeFeedFailure(PrototypeFailureReason.LICENSE_FAILED), null, true, true, true, 1_500,
            transientAccountReadiness = true))
    }

    @Test fun `a transient preparation failure continues an active incident but never starts one`() {
        val member = Any()
        val transient = PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED, transientPreparation = true)
        val state = FeedAutoRecoveryState(); firstAttempt(state, member)
        // Observed on the SHIELD: the retry's account check failed temporarily
        // with no media transport evidence, and recovery stopped for good.
        assertEquals(FeedAutoRecoveryDecision.SCHEDULED, state.failed(null, transient, null, true, true, true, 1_500))
        assertEquals(2, checkNotNull(state.pending).attempt)
        val plain = FeedAutoRecoveryState(); firstAttempt(plain, member)
        assertEquals(FeedAutoRecoveryDecision.SKIPPED, plain.failed(null,
            PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED), null, true, true, true, 1_500))
        val fresh = FeedAutoRecoveryState(); fresh.observe(member, sample(0), 0)
        assertEquals(FeedAutoRecoveryDecision.SKIPPED, fresh.failed(member, transient, null, true, true, true, 1))
        val denied = FeedAutoRecoveryState(); firstAttempt(denied, member)
        assertEquals(FeedAutoRecoveryDecision.SKIPPED, denied.failed(null, transient.copy(httpStatus = 403),
            null, true, true, true, 1_500))
    }

    @Test fun `an ended feed never schedules automatic recovery from a played state`() {
        // Same played live state in which an offline live source would be retried (#553).
        for ((reason, expected) in listOf(PrototypeFailureReason.MEDIA_NOT_FOUND to FeedAutoRecoveryDecision.SCHEDULED,
            PrototypeFailureReason.BROADCAST_ENDED to FeedAutoRecoveryDecision.SKIPPED,
            PrototypeFailureReason.REPLAY_ENDED to FeedAutoRecoveryDecision.SKIPPED)) {
            val state = FeedAutoRecoveryState(); val member = Any()
            state.observe(member, sample(0), 0)
            assertEquals(reason.name, expected, fail(state, member, 1, PrototypeFeedFailure(reason), evidence = null))
        }
    }

    private fun sample(now: Long, clock: Long = 20_000 + now, frames: Long? = now / 10 + 1) =
        NativePlaybackDiagnosticSnapshot(now, 0, false, true, true, 3, 0, null,
            clock, true, frames, 0, 1, 0, null, null, null)
    private fun transport(failure: ProviderTransportFailure = ProviderTransportFailure.UNKNOWN,
        kind: ProviderTransportExceptionKind = ProviderTransportExceptionKind.IO, age: Long = 0,
        stage: NativeFailureStage = NativeFailureStage.MEDIA_REQUEST_OPEN, cancelled: Boolean = false) =
        NativePlaybackTransportFailure(stage, age, ProviderTransportEvidence(failure,
            localCancellationRequested = cancelled, exceptionKind = kind))
    private fun fail(state: FeedAutoRecoveryState, member: Any?, now: Long,
        failure: PrototypeFeedFailure = PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED),
        evidence: NativePlaybackTransportFailure? = transport(), live: Boolean = true,
        allowed: Boolean = true, restore: Boolean = true, httpFailure: NativePlaybackHttpFailure? = null) =
        state.failed(member, failure, evidence, live, allowed, restore, now, httpFailure = httpFailure)
    private fun firstAttempt(state: FeedAutoRecoveryState, member: Any, now: Long = 0): FeedAutoRecoveryRequest {
        state.observe(member, sample(now), now)
        assertEquals(FeedAutoRecoveryDecision.SCHEDULED, fail(state, member, now))
        return checkNotNull(state.takeDue(now + 1_000))
    }

    @Test fun `only a recent actual playing observation of the exact member admits an initial retry`() {
        val state = FeedAutoRecoveryState(); val member = Any()
        assertEquals(FeedAutoRecoveryDecision.SKIPPED, fail(state, member, 0))
        state.observe(member, sample(1), 1)
        assertEquals(FeedAutoRecoveryDecision.SKIPPED, fail(state, Any(), 1))
        state.observe(member, sample(2), 2)
        assertEquals(FeedAutoRecoveryDecision.SKIPPED, fail(state, member, 5_003))
        state.observe(member, sample(5_004), 5_004)
        assertEquals(FeedAutoRecoveryDecision.SCHEDULED, fail(state, member, 5_004))
        assertEquals(0, state.attemptCount)
        assertFalse(state.automaticAttemptActive)
    }

    @Test fun `fresh continuous buffering preserves previously playing intent until a terminal network failure`() {
        val state = FeedAutoRecoveryState(); val member = Any()
        state.observe(member, sample(0), 0)
        state.observe(member, sample(1_000), 1_000)
        for (now in 2_000L..12_000L step 1_000L) {
            state.observe(member, sample(now, clock = 21_000, frames = 101).copy(
                state = 2, playing = false), now)
        }
        state.observe(member, sample(12_100).copy(state = 1, playing = false, errorCode = 2001), 12_100)
        assertEquals(FeedAutoRecoveryDecision.SCHEDULED, fail(state, member, 12_100))
        assertEquals(0, state.attemptCount)
        assertNotNull(state.pending)
    }

    @Test fun `buffering alone cannot establish actual playing provenance`() {
        val state = FeedAutoRecoveryState(); val member = Any()
        for (now in 0L..12_000L step 1_000L) {
            state.observe(member, sample(now).copy(state = 2, playing = false), now)
        }
        assertEquals(FeedAutoRecoveryDecision.SKIPPED, fail(state, member, 12_000))
        assertNull(state.pending)
    }

    @Test fun `unobserved buffering gap cannot be bridged by later fresh buffering samples`() {
        val state = FeedAutoRecoveryState(); val member = Any()
        state.observe(member, sample(0), 0)
        state.observe(member, sample(1_000).copy(state = 2, playing = false), 1_000)
        for (now in 6_001L..12_001L step 1_000L) {
            state.observe(member, sample(now).copy(state = 2, playing = false), now)
        }
        assertEquals(FeedAutoRecoveryDecision.SKIPPED, fail(state, member, 12_001))
    }

    @Test fun `invalid buffering observation breaks playing intent even when fresh buffering returns`() {
        for (mode in 0..9) {
            val state = FeedAutoRecoveryState(); val member = Any(); val replacement = Any()
            state.observe(member, sample(0), 0)
            state.observe(member, sample(1_000).copy(state = 2, playing = false), 1_000)
            val buffering = sample(2_000).copy(state = 2, playing = false)
            val invalid = when (mode) {
                0 -> buffering.copy(sampleAgeMs = 1)
                1 -> buffering.copy(observedAtElapsedMs = 0)
                2 -> buffering.copy(released = true)
                3 -> buffering.copy(playWhenReady = false)
                4 -> buffering.copy(suppressionReason = 1)
                5 -> sample(2_000).copy(playing = false, playWhenReady = false)
                6 -> null
                else -> buffering
            }
            val current = if (mode == 9) replacement else member
            state.observe(current, invalid, 2_000, blocked = mode == 7, playingAd = mode == 8)
            state.observe(current, sample(3_000).copy(state = 2, playing = false), 3_000)
            assertEquals("mode=$mode", FeedAutoRecoveryDecision.SKIPPED, fail(state, current, 3_000))
            assertNull(state.pending)
        }
    }

    @Test fun `buffering continuity does not renew transport evidence or bypass explicit cancellation`() {
        for (cancel in listOf(false, true)) {
            val state = FeedAutoRecoveryState(); val member = Any()
            state.observe(member, sample(0), 0)
            for (now in 1_000L..8_000L step 1_000L) {
                state.observe(member, sample(now).copy(state = 2, playing = false), now)
            }
            if (cancel) state.cancel()
            assertEquals(FeedAutoRecoveryDecision.SKIPPED,
                fail(state, member, 8_000, evidence = if (cancel) transport() else transport(age = 5_001)))
            assertNull(state.pending)
        }
    }

    @Test fun `failure idle snapshot preserves previous playing proof and duplicate delivery cannot replace pending retry`() {
        val state = FeedAutoRecoveryState(); val member = Any()
        state.observe(member, sample(0), 0)
        state.observe(member, sample(100).copy(state = 1, playing = false, playWhenReady = false), 100)
        assertEquals(FeedAutoRecoveryDecision.SCHEDULED, fail(state, member, 100))
        val pending = state.pending
        assertEquals(FeedAutoRecoveryDecision.SKIPPED, fail(state, member, 101))
        assertSame(pending, state.pending)
        assertNull(state.takeDue(1_099)); assertEquals(0, state.attemptCount)
        val request = checkNotNull(state.takeDue(1_100))
        assertSame(pending, request); assertTrue(state.current(request)); assertNull(state.pending)
        assertTrue(state.automaticAttemptActive); assertEquals(1, state.attemptCount)
        assertNull(state.takeDue(1_101)); assertEquals(1, state.attemptCount)
    }

    @Test fun `three attempts use bounded backoff and retain initial offset intent across failed replacements`() {
        val state = FeedAutoRecoveryState(); val member = Any()
        val first = firstAttempt(state, member)
        assertEquals(1, first.attempt); assertTrue(first.restoreOffset)
        val timeout = PrototypeFeedFailure(PrototypeFailureReason.PREPARATION_TIMEOUT)
        assertEquals(FeedAutoRecoveryDecision.SCHEDULED, fail(state, null, 1_001, timeout, restore = false))
        assertFalse(state.current(first)); assertNull(state.takeDue(4_000))
        val second = checkNotNull(state.takeDue(4_001))
        assertEquals(2, second.attempt); assertTrue(second.restoreOffset)
        assertEquals(FeedAutoRecoveryDecision.SCHEDULED, fail(state, null, 4_002, restore = false))
        assertNull(state.takeDue(14_001))
        val third = checkNotNull(state.takeDue(14_002))
        assertEquals(3, third.attempt); assertTrue(third.restoreOffset)
        assertEquals(FeedAutoRecoveryDecision.EXHAUSTED, fail(state, null, 14_003, timeout))
        assertTrue(state.exhausted); assertEquals(3, state.attemptCount)
        assertFalse(state.current(third)); assertFalse(state.automaticAttemptActive); assertNull(state.pending)
    }

    @Test fun `an initial preparation timeout is not a playback recovery even with earlier playing evidence`() {
        val state = FeedAutoRecoveryState(); val member = Any()
        state.observe(member, sample(0), 0)
        assertEquals(FeedAutoRecoveryDecision.SKIPPED,
            fail(state, member, 0, PrototypeFeedFailure(PrototypeFailureReason.PREPARATION_TIMEOUT)))
        assertNull(state.pending)
    }

    @Test fun `cancel invalidates scheduled and issued receipts without transferring authority to newer requests`() {
        val state = FeedAutoRecoveryState(); val member = Any()
        state.observe(member, sample(0), 0); fail(state, member, 0)
        val scheduled = checkNotNull(state.pending)
        state.cancel(); assertNull(state.takeDue(1_000)); assertFalse(state.current(scheduled))
        val issued = firstAttempt(state, member, 1_001)
        state.cancel()
        assertFalse(state.current(issued)); assertEquals(0, state.attemptCount)
        assertEquals(FeedAutoRecoveryDecision.SKIPPED, fail(state, member, 2_002))
        val replacement = firstAttempt(state, member, 2_003)
        assertFalse(state.current(issued)); assertTrue(state.current(replacement))
    }

    @Test fun `six dispatched attempts remain capped across explicit cancellations until the rolling window expires`() {
        val state = FeedAutoRecoveryState(); val member = Any()
        var now = 0L
        repeat(6) {
            firstAttempt(state, member, now)
            state.cancel()
            now += 1_001
        }
        state.observe(member, sample(now), now)
        assertEquals(FeedAutoRecoveryDecision.EXHAUSTED, fail(state, member, now))
        assertTrue(state.exhausted); assertNull(state.pending)
        state.cancel(); now += 600_000
        state.observe(member, sample(now), now)
        assertEquals(FeedAutoRecoveryDecision.SCHEDULED, fail(state, member, now))
    }

    @Test fun `sixty seconds of same member advancing clock and renderer resets only consecutive attempts`() {
        val state = FeedAutoRecoveryState(); val member = Any()
        val receipt = firstAttempt(state, member)
        for (now in 1_001L..60_001L step 1_000) state.observe(member, sample(now), now)
        assertEquals(1, state.attemptCount)
        state.observe(member, sample(61_001), 61_001)
        assertEquals(0, state.attemptCount); assertFalse(state.current(receipt))
        assertFalse(state.automaticAttemptActive); assertFalse(state.exhausted)
        // Five more new bursts reach six despite the sustained-health reset.
        var now = 61_002L
        repeat(5) { firstAttempt(state, member, now); state.cancel(); now += 1_001 }
        state.observe(member, sample(now), now)
        assertEquals(FeedAutoRecoveryDecision.EXHAUSTED, fail(state, member, now))
    }

    @Test fun `ready alone or unchanging clocks or known renderer cannot replenish retries`() {
        for (mode in 0..3) {
            val state = FeedAutoRecoveryState(); val member = Any()
            firstAttempt(state, member)
            for (now in 1_001L..121_001L step 1_000) {
                val value = when (mode) {
                    0 -> sample(now).copy(playing = false, playWhenReady = false)
                    1 -> sample(now, clock = 20_000)
                    2 -> sample(now, frames = 1)
                    else -> sample(now).copy(state = 2)
                }
                state.observe(member, value, now)
            }
            assertEquals("mode=$mode", 1, state.attemptCount)
        }
    }

    @Test fun `unknown renderer allows clock proof and short repeated polls need not advance every sample`() {
        val state = FeedAutoRecoveryState(); val member = Any()
        firstAttempt(state, member)
        for (now in 1_001L..61_001L step 250) {
            val clock = 20_000 + now / 1_000 * 1_000
            state.observe(member, sample(now, clock, frames = null), now)
        }
        assertEquals(0, state.attemptCount)
    }

    @Test fun `member decoder and own clock discontinuities restart sustained health measurement`() {
        for (mode in 0..3) {
            val state = FeedAutoRecoveryState(); val member = Any(); val nextMember = Any()
            firstAttempt(state, member)
            for (now in 1_001L..80_001L step 1_000) {
                val changed = now >= 31_001
                val value = when {
                    !changed -> sample(now)
                    mode == 1 -> sample(now).copy(decoderGeneration = 2)
                    mode == 2 -> sample(now).copy(liveClock = false)
                    mode == 3 -> sample(now, clock = now - 31_000)
                    else -> sample(now)
                }
                state.observe(if (changed && mode == 0) nextMember else member, value, now)
            }
            assertEquals("mode=$mode", 1, state.attemptCount)
        }
    }

    @Test fun `paused blocked ads stale released suppressed and errored samples do not admit initial recovery`() {
        for (mode in 0..7) {
            val state = FeedAutoRecoveryState(); val member = Any()
            val value = when (mode) {
                0 -> sample(0).copy(playWhenReady = false, playing = false)
                1 -> sample(0).copy(sampleAgeMs = 1)
                2 -> sample(0).copy(released = true)
                3 -> sample(0).copy(suppressionReason = 1)
                4 -> sample(0).copy(errorCode = 2001)
                5 -> sample(0).copy(playing = false)
                else -> sample(0)
            }
            state.observe(member, value, 0, blocked = mode == 6, playingAd = mode == 7)
            assertEquals("mode=$mode", FeedAutoRecoveryDecision.SKIPPED, fail(state, member, 0))
        }
        val state = FeedAutoRecoveryState(); val member = Any()
        state.observe(member, sample(0), 0)
        state.observe(member, sample(1).copy(playing = false, playWhenReady = false), 1)
        assertEquals(FeedAutoRecoveryDecision.SKIPPED, fail(state, member, 1))
    }

    @Test fun `network failures need recent open or read evidence and deny TLS cancellation and unknown kinds`() {
        val admitted = listOf(transport(ProviderTransportFailure.DNS), transport(ProviderTransportFailure.CONNECT),
            transport(ProviderTransportFailure.TIMEOUT), transport(), transport(stage = NativeFailureStage.MEDIA_REQUEST_READ))
        val refused = listOf(null, transport(age = 5_001), transport(age = -1),
            transport(kind = ProviderTransportExceptionKind.UNKNOWN), transport(kind = ProviderTransportExceptionKind.SOCKET),
            transport(kind = ProviderTransportExceptionKind.TLS_HANDSHAKE),
            transport(kind = ProviderTransportExceptionKind.INTERRUPTED_IO),
            transport(failure = ProviderTransportFailure.TLS), transport(failure = ProviderTransportFailure.CANCELLED),
            transport(cancelled = true), transport(stage = NativeFailureStage.MEDIA_DISCONNECT))
        for ((evidence, expected) in admitted.map { it to FeedAutoRecoveryDecision.SCHEDULED } +
            refused.map { it to FeedAutoRecoveryDecision.SKIPPED }) {
            val state = FeedAutoRecoveryState(); val member = Any()
            state.observe(member, sample(0), 0)
            assertEquals("evidence=$evidence", expected, fail(state, member, 0, evidence = evidence))
        }
    }

    @Test fun `only explicit availability and live window outcomes are eligible while all other reasons stop an incident`() {
        val reasons = setOf(PrototypeFailureReason.HTTP_REJECTED, PrototypeFailureReason.NETWORK_FAILED,
            PrototypeFailureReason.MEDIA_NOT_FOUND, PrototypeFailureReason.LIVE_WINDOW_EXPIRED,
            PrototypeFailureReason.LIVE_POSITION_FAILED, PrototypeFailureReason.PLAYER_FAILED,
            PrototypeFailureReason.PREPARATION_TIMEOUT)
        for (reason in PrototypeFailureReason.entries.filter { it !in reasons }) {
            val state = FeedAutoRecoveryState(); val member = Any()
            val receipt = firstAttempt(state, member)
            assertEquals(reason.name, FeedAutoRecoveryDecision.SKIPPED,
                fail(state, null, 1_001, PrototypeFeedFailure(reason)))
            assertFalse(state.current(receipt)); assertNull(state.pending)
        }
        for (status in listOf(400, 401, 403, 404, 408, 429, 500, 503, 599)) {
            val state = FeedAutoRecoveryState(); val member = Any()
            state.observe(member, sample(0), 0)
            assertEquals(if (status in listOf(408, 429, 500, 503, 599)) FeedAutoRecoveryDecision.SCHEDULED else FeedAutoRecoveryDecision.SKIPPED,
                fail(state, member, 0, PrototypeFeedFailure(PrototypeFailureReason.HTTP_REJECTED, httpStatus = status)))
        }
        for (live in listOf(false, true)) for (reason in listOf(PrototypeFailureReason.MEDIA_NOT_FOUND,
            PrototypeFailureReason.LIVE_WINDOW_EXPIRED, PrototypeFailureReason.LIVE_POSITION_FAILED)) {
            val state = FeedAutoRecoveryState(); val member = Any()
            state.observe(member, sample(0), 0)
            assertEquals(if (live) FeedAutoRecoveryDecision.SCHEDULED else FeedAutoRecoveryDecision.SKIPPED,
                fail(state, member, 0, PrototypeFeedFailure(reason), live = live))
        }
        for (code in listOf(null, 1000, 1002, 2001, 6001)) {
            val state = FeedAutoRecoveryState(); val member = Any()
            state.observe(member, sample(0), 0)
            assertEquals(if (code == 1002) FeedAutoRecoveryDecision.SCHEDULED else FeedAutoRecoveryDecision.SKIPPED,
                fail(state, member, 0, PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = code)))
        }
    }

    @Test fun `permission denial revokes an active incident and does not borrow sibling observation`() {
        val a = FeedAutoRecoveryState(); val b = FeedAutoRecoveryState(); val member = Any()
        firstAttempt(a, member)
        assertEquals(FeedAutoRecoveryDecision.SKIPPED, fail(b, member, 1_000))
        assertEquals(FeedAutoRecoveryDecision.SKIPPED, fail(a, null, 1_001, allowed = false))
        assertFalse(a.automaticAttemptActive); assertNull(a.pending)
        assertEquals(1, a.attemptCount); assertEquals(0, b.attemptCount)
    }

    @Test fun `negative rollback stale snapshot and deadline overflow fail closed without issuing a request`() {
        val state = FeedAutoRecoveryState(); val member = Any()
        val request = firstAttempt(state, member)
        assertNull(state.takeDue(-1)); assertFalse(state.current(request))
        state.observe(member, sample(1_001), 1_001)
        assertEquals(FeedAutoRecoveryDecision.SKIPPED, fail(state, member, 1_000))
        assertNull(state.pending)
        state.observe(member, sample(1_001), 4_000)
        assertEquals(FeedAutoRecoveryDecision.SKIPPED, fail(state, member, 4_000))
        state.observe(member, sample(Long.MAX_VALUE, clock = 1), Long.MAX_VALUE)
        assertEquals(FeedAutoRecoveryDecision.SKIPPED, fail(state, member, Long.MAX_VALUE))
        assertNull(state.pending)
    }
}
