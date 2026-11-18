package net.fstab.tachiai.presentation

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import net.fstab.tachiai.platform.media.NativeFailureStage
import net.fstab.tachiai.platform.media.NativePlaybackDiagnosticSnapshot
import net.fstab.tachiai.platform.media.NativePlaybackTransportFailure
import net.fstab.tachiai.platform.network.ProviderTransportEvidence
import net.fstab.tachiai.platform.network.ProviderTransportExceptionKind
import net.fstab.tachiai.platform.network.ProviderTransportFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrototypeFeedFailureTest {
    @Test fun `current terminal account failure supersedes an earlier bare network observation`() {
        val latch = PrototypeFeedFailureLatch()
        val account = PrototypeFeedFailure(PrototypeFailureReason.ABEMA_ACCOUNT_UNAVAILABLE)
        latch.remember(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED))
        latch.rememberTerminalFailure(account)
        assertEquals(account, latch.failure)
    }

    @Test fun `each verified-refresh terminal reason supersedes only the old bare network observation`() {
        val network = PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED)
        for (account in refreshFailures()) {
            val latch = PrototypeFeedFailureLatch()
            latch.remember(network)
            latch.remember(account)
            assertEquals(network, latch.failure) // An observation alone is not a terminal receipt.
            latch.rememberTerminalFailure(account)
            assertEquals(account, latch.failure)
            latch.remember(network)
            assertEquals(account, latch.failure)
        }
    }

    @Test fun `hard refusal around account terminal cannot become verified refresh authority`() {
        for (account in refreshFailures()) for (hard in hardRefusals()) for (before in listOf(false, true)) {
            val latch = PrototypeFeedFailureLatch()
            latch.remember(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED))
            latch.remember(account) // A duplicate weak observation cannot pin priority.
            if (before) latch.remember(hard)
            latch.rememberTerminalFailure(account)
            if (!before) latch.remember(hard)
            assertEquals(hard, latch.failure)
            assertEquals(FeedAutoRecoveryDecision.SKIPPED, recover(checkNotNull(latch.failure), transport(),
                authorizationRefreshSupported = true))
        }
    }

    @Test fun `native license and playback limit terminals replace stale network without granting recovery`() {
        for (reason in listOf(PrototypeFailureReason.LICENSE_FAILED, PrototypeFailureReason.PLAYBACK_LIMIT)) {
            val latch = PrototypeFeedFailureLatch()
            val terminal = PrototypeFeedFailure(reason)
            latch.remember(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED))
            latch.rememberTerminalFailure(terminal)
            assertEquals(terminal, latch.failure)
            assertEquals(FeedAutoRecoveryDecision.SKIPPED, recover(checkNotNull(latch.failure), transport(),
                authorizationRefreshSupported = true))
        }
    }

    @Test fun `first equally hard refusal wins across generic and explicit terminal channels`() {
        val license = PrototypeFeedFailure(PrototypeFailureReason.LICENSE_FAILED)
        val denied = PrototypeFeedFailure(PrototypeFailureReason.HTTP_REJECTED, httpStatus = 403)
        for ((first, second) in listOf(license to denied, denied to license)) {
            for (terminalFirst in listOf(false, true)) {
                val latch = PrototypeFeedFailureLatch()
                latch.remember(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED))
                if (terminalFirst) {
                    latch.rememberTerminalFailure(first)
                    latch.remember(second)
                } else {
                    latch.remember(first)
                    latch.rememberTerminalFailure(second)
                }
                assertEquals(first, latch.failure)
            }
        }
    }

    @Test fun `generic cleanup stop is consequential only after an attributable terminal receipt`() {
        val stopped = PrototypeFeedFailure(PrototypeFailureReason.STOPPED)
        val terminals = refreshFailures() + listOf(
            PrototypeFeedFailure(PrototypeFailureReason.LICENSE_FAILED),
            PrototypeFeedFailure(PrototypeFailureReason.PLAYBACK_LIMIT))
        for (terminal in terminals) for (before in listOf(false, true)) {
            val latch = PrototypeFeedFailureLatch()
            latch.remember(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED))
            if (before) latch.remember(stopped)
            latch.rememberTerminalFailure(terminal)
            if (!before) latch.remember(stopped)
            assertEquals(if (before) stopped else terminal, latch.failure)
        }
        for (account in refreshFailures()) {
            val latch = PrototypeFeedFailureLatch()
            latch.remember(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED))
            latch.rememberTerminalFailure(account)
            latch.rememberTerminalFailure(stopped) // Explicit refusal is not cleanup noise.
            assertEquals(stopped, latch.failure)
        }
    }

    @Test fun `account terminal with stale transport requires verified refresh and exact playing ownership`() {
        for (account in refreshFailures()) {
            val latch = PrototypeFeedFailureLatch()
            latch.remember(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED))
            latch.rememberTerminalFailure(account)
            val failure = checkNotNull(latch.failure)
            val stale = transport(ageMs = 320_228)
            assertEquals(FeedAutoRecoveryDecision.SKIPPED, recover(failure, stale))
            assertEquals(FeedAutoRecoveryDecision.SCHEDULED, recover(failure, stale,
                authorizationRefreshSupported = true))
            assertEquals(FeedAutoRecoveryDecision.SKIPPED, recover(failure, stale, observedPlaying = false,
                authorizationRefreshSupported = true))
            assertEquals(FeedAutoRecoveryDecision.SKIPPED, recover(failure, stale, sameMember = false,
                authorizationRefreshSupported = true))
            assertEquals(FeedAutoRecoveryDecision.SKIPPED, recover(failure, stale, allowed = false,
                authorizationRefreshSupported = true))
            for (evidence in listOf(
                ProviderTransportEvidence(ProviderTransportFailure.TLS),
                ProviderTransportEvidence(ProviderTransportFailure.CANCELLED),
                ProviderTransportEvidence(ProviderTransportFailure.UNKNOWN, localCancellationRequested = true),
                ProviderTransportEvidence(ProviderTransportFailure.UNKNOWN,
                    exceptionKind = ProviderTransportExceptionKind.INTERRUPTED_IO))) {
                assertEquals(FeedAutoRecoveryDecision.SKIPPED, recover(failure,
                    NativePlaybackTransportFailure(NativeFailureStage.MEDIA_REQUEST_OPEN, 0, evidence),
                    authorizationRefreshSupported = true))
            }
        }
    }

    @Test fun `fresh behind-live player failure supersedes an earlier bare network failure`() {
        val latch = PrototypeFeedFailureLatch()
        latch.remember(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED))
        latch.rememberPlayerFailure(1002)
        assertEquals(PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = 1002), latch.failure)
    }

    @Test fun `first specific reason HTTP metadata or known player code always survives a later behind-live error`() {
        val initialFailures = PrototypeFailureReason.entries.filter { it != PrototypeFailureReason.NETWORK_FAILED }
            .map { PrototypeFeedFailure(it) } + listOf(
                PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED, httpStatus = 401),
                PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED, httpStatus = 403),
                PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED, httpStatus = 503),
                PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED, playerCode = 2001),
                PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED, playerCode = 1002))
        for (initial in initialFailures) {
            val latch = PrototypeFeedFailureLatch()
            latch.remember(initial)
            latch.remember(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED))
            latch.rememberPlayerFailure(1002)
            assertEquals(initial, latch.failure)
        }
    }

    @Test fun `specific refusal before or after terminal behind-live supersedes the old bare network observation`() {
        for (hard in hardRefusals()) for (before in listOf(false, true)) {
            val latch = PrototypeFeedFailureLatch()
            latch.remember(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED))
            if (before) latch.remember(hard)
            latch.rememberPlayerFailure(1002)
            if (!before) latch.remember(hard)
            assertEquals(hard, latch.failure)
            latch.remember(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED))
            latch.rememberPlayerFailure(1002)
            assertEquals(hard, latch.failure)
        }
    }

    @Test fun `consequential stop after terminal behind-live cannot hide it but an earlier stop remains binding`() {
        val stopped = PrototypeFeedFailure(PrototypeFailureReason.STOPPED)
        for (before in listOf(false, true)) {
            val latch = PrototypeFeedFailureLatch()
            latch.remember(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED))
            if (before) latch.remember(stopped)
            latch.rememberPlayerFailure(1002)
            if (!before) latch.remember(stopped)
            assertEquals(if (before) stopped else PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = 1002),
                latch.failure)
        }
    }

    @Test fun `duplicate terminal observation and consequential stop cannot mask a later license refusal`() {
        val latch = PrototypeFeedFailureLatch()
        val terminal = PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = 1002)
        val license = PrototypeFeedFailure(PrototypeFailureReason.LICENSE_FAILED)
        latch.remember(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED))
        latch.rememberPlayerFailure(1002)
        latch.remember(terminal) // A separate equal value is still the same observation.
        latch.remember(PrototypeFeedFailure(PrototypeFailureReason.STOPPED))
        assertEquals(terminal, latch.failure)
        latch.remember(license)
        assertEquals(license, latch.failure)
    }

    @Test fun `soft availability observations cannot mask hard refusal before or after terminal behind-live`() {
        val hard = listOf(PrototypeFeedFailure(PrototypeFailureReason.HTTP_REJECTED, httpStatus = 401),
            PrototypeFeedFailure(PrototypeFailureReason.LICENSE_FAILED))
        for (soft in softAvailability()) for (refusal in hard) for (before in listOf(false, true)) {
            val latch = PrototypeFeedFailureLatch()
            latch.remember(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED))
            latch.remember(soft)
            if (before) latch.remember(refusal)
            latch.rememberPlayerFailure(1002)
            if (!before) latch.remember(refusal)
            assertEquals(refusal, latch.failure)
            assertEquals(FeedAutoRecoveryDecision.SKIPPED, recover(checkNotNull(latch.failure), transport()))
        }
    }

    @Test fun `soft availability followed by terminal behind-live retains its live-only recovery gate`() {
        val terminal = PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = 1002)
        for (soft in softAvailability()) {
            val latch = PrototypeFeedFailureLatch()
            latch.remember(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED))
            latch.remember(soft)
            latch.rememberPlayerFailure(1002)
            assertEquals(terminal, latch.failure)
            assertEquals(FeedAutoRecoveryDecision.SKIPPED, recover(checkNotNull(latch.failure), transport(), live = false))
            assertEquals(FeedAutoRecoveryDecision.SCHEDULED, recover(checkNotNull(latch.failure), transport(ageMs = 320_228)))
            latch.remember(soft)
            assertEquals(terminal, latch.failure)
            assertEquals(FeedAutoRecoveryDecision.SKIPPED, recover(checkNotNull(latch.failure), transport(), live = false))
        }
    }

    @Test fun `authentication metadata and unknown player codes cannot masquerade as soft availability`() {
        val hard = listOf(
            PrototypeFeedFailure(PrototypeFailureReason.LIVE_WINDOW_EXPIRED, httpStatus = 403),
            PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, httpStatus = 403, playerCode = 1002),
            PrototypeFeedFailure(PrototypeFailureReason.HTTP_REJECTED, httpStatus = 503, playerCode = 6001),
            PrototypeFeedFailure(PrototypeFailureReason.MEDIA_NOT_FOUND, httpStatus = 404, playerCode = 6001),
            PrototypeFeedFailure(PrototypeFailureReason.LIVE_POSITION_FAILED, playerCode = 6001))
        for (refusal in hard) {
            val latch = PrototypeFeedFailureLatch()
            latch.remember(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED))
            latch.remember(PrototypeFeedFailure(PrototypeFailureReason.HTTP_REJECTED, httpStatus = 503))
            latch.remember(refusal)
            latch.rememberPlayerFailure(1002)
            assertEquals(refusal, latch.failure)
            assertEquals(FeedAutoRecoveryDecision.SKIPPED, recover(checkNotNull(latch.failure), transport()))
        }
    }

    @Test fun `generic remember and a first unknown terminal code cannot authorize a later behind-live promotion`() {
        val network = PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED)
        val generic = PrototypeFeedFailureLatch()
        generic.remember(network)
        generic.remember(PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = 1002))
        assertEquals(network, generic.failure)
        for (code in listOf(null, 1000, 2000, 2001, 6001)) {
            val latch = PrototypeFeedFailureLatch()
            latch.remember(network)
            latch.rememberPlayerFailure(code)
            latch.rememberPlayerFailure(1002)
            assertEquals(network, latch.failure)
            val empty = PrototypeFeedFailureLatch()
            empty.rememberPlayerFailure(code)
            assertEquals(PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = code), empty.failure)
        }
    }

    @Test fun `terminal behind-live following stale transport is eligible only for an exact previously playing live member`() {
        val latch = PrototypeFeedFailureLatch()
        latch.remember(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED))
        val stale = transport(ageMs = 320_228)
        assertEquals(FeedAutoRecoveryDecision.SKIPPED, recover(checkNotNull(latch.failure), stale))
        latch.rememberPlayerFailure(1002)
        val terminal = checkNotNull(latch.failure)
        assertEquals(FeedAutoRecoveryDecision.SCHEDULED, recover(terminal, stale))
        assertEquals(FeedAutoRecoveryDecision.SKIPPED, recover(terminal, stale, observedPlaying = false))
        assertEquals(FeedAutoRecoveryDecision.SKIPPED, recover(terminal, stale, sameMember = false))
        assertEquals(FeedAutoRecoveryDecision.SKIPPED, recover(terminal, stale, live = false))
        assertEquals(FeedAutoRecoveryDecision.SKIPPED, recover(terminal, stale, allowed = false))
    }

    @Test fun `fresh IO cannot bypass a specific refusal and terminal behind-live cannot bypass TLS or cancellation`() {
        for (hard in hardRefusals()) for (before in listOf(false, true)) {
            val latch = PrototypeFeedFailureLatch()
            latch.remember(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED))
            if (before) latch.remember(hard)
            latch.rememberPlayerFailure(1002)
            if (!before) latch.remember(hard)
            assertEquals(FeedAutoRecoveryDecision.SKIPPED, recover(checkNotNull(latch.failure), transport()))
        }
        val terminal = PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = 1002)
        for (evidence in listOf(
            ProviderTransportEvidence(ProviderTransportFailure.TLS, exceptionKind = ProviderTransportExceptionKind.TLS_HANDSHAKE),
            ProviderTransportEvidence(ProviderTransportFailure.CANCELLED),
            ProviderTransportEvidence(ProviderTransportFailure.UNKNOWN, localCancellationRequested = true),
            ProviderTransportEvidence(ProviderTransportFailure.UNKNOWN, exceptionKind = ProviderTransportExceptionKind.INTERRUPTED_IO))) {
            assertEquals(FeedAutoRecoveryDecision.SKIPPED,
                recover(terminal, NativePlaybackTransportFailure(NativeFailureStage.MEDIA_REQUEST_OPEN, 0, evidence)))
        }
    }

    @Test fun `concurrent terminal and specific refusal observations cannot lose the refusal`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(50) {
                val latch = PrototypeFeedFailureLatch()
                val hard = PrototypeFeedFailure(PrototypeFailureReason.HTTP_REJECTED, httpStatus = 403)
                latch.remember(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED))
                val start = CountDownLatch(1)
                val player = executor.submit { check(start.await(3, TimeUnit.SECONDS)); latch.rememberPlayerFailure(1002) }
                val refusal = executor.submit { check(start.await(3, TimeUnit.SECONDS)); latch.remember(hard) }
                start.countDown()
                player.get(3, TimeUnit.SECONDS); refusal.get(3, TimeUnit.SECONDS)
                assertEquals(hard, latch.failure)
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    private fun refreshFailures() = listOf(PrototypeFailureReason.ABEMA_ACCOUNT_UNAVAILABLE,
        PrototypeFailureReason.LOGIN_EXPIRED, PrototypeFailureReason.LOGIN_UNAVAILABLE).map { PrototypeFeedFailure(it) }

    private fun hardRefusals() = listOf(
        PrototypeFeedFailure(PrototypeFailureReason.HTTP_REJECTED, httpStatus = 401),
        PrototypeFeedFailure(PrototypeFailureReason.HTTP_REJECTED, httpStatus = 403),
        PrototypeFeedFailure(PrototypeFailureReason.LICENSE_FAILED),
        PrototypeFeedFailure(PrototypeFailureReason.MEDIA_BLOCKED),
        PrototypeFeedFailure(PrototypeFailureReason.MEDIA_APPROVAL_REQUIRED),
        PrototypeFeedFailure(PrototypeFailureReason.CLEANUP_FAILED),
        PrototypeFeedFailure(PrototypeFailureReason.PREPARATION_FAILED),
        PrototypeFeedFailure(PrototypeFailureReason.PREPARATION_TIMEOUT),
        PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = 6001))

    private fun softAvailability() = listOf(
        PrototypeFeedFailure(PrototypeFailureReason.HTTP_REJECTED, httpStatus = 408),
        PrototypeFeedFailure(PrototypeFailureReason.HTTP_REJECTED, httpStatus = 429),
        PrototypeFeedFailure(PrototypeFailureReason.HTTP_REJECTED, httpStatus = 503),
        PrototypeFeedFailure(PrototypeFailureReason.MEDIA_NOT_FOUND),
        PrototypeFeedFailure(PrototypeFailureReason.MEDIA_NOT_FOUND, httpStatus = 404),
        PrototypeFeedFailure(PrototypeFailureReason.LIVE_WINDOW_EXPIRED),
        PrototypeFeedFailure(PrototypeFailureReason.LIVE_POSITION_FAILED),
        PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = 1002))

    private fun transport(ageMs: Long = 0) = NativePlaybackTransportFailure(NativeFailureStage.MEDIA_REQUEST_OPEN,
        ageMs, ProviderTransportEvidence(ProviderTransportFailure.UNKNOWN, exceptionKind = ProviderTransportExceptionKind.IO))

    private fun recover(failure: PrototypeFeedFailure, transport: NativePlaybackTransportFailure?,
        observedPlaying: Boolean = true, sameMember: Boolean = true, live: Boolean = true,
        allowed: Boolean = true, authorizationRefreshSupported: Boolean = false): FeedAutoRecoveryDecision {
        val state = FeedAutoRecoveryState(); val member = Any()
        if (observedPlaying) state.observe(member, NativePlaybackDiagnosticSnapshot(1_000, 0, false, true,
            true, 3, 0, null, 20_000, true, 30, 0, 1, 0, null, null, null), 1_000)
        return state.failed(if (sameMember) member else Any(), failure, transport, live, allowed,
            restoreOffset = true, nowMs = 1_100, authorizationRefreshSupported = authorizationRefreshSupported)
    }

    @Test fun `catalog access failures direct users to the shared Twitch account`() {
        val catalog = PrototypeFeedFailure(PrototypeFailureReason.CATALOG_CONNECTION_REQUIRED).message
        assertTrue(catalog.contains("Twitch account"))
        assertFalse(catalog.contains("saved Twitch login"))
        assertTrue(PrototypeFeedFailure(PrototypeFailureReason.CATALOG_RATE_LIMITED).message.contains("Try again later"))
        assertFalse(PrototypeFeedFailure(PrototypeFailureReason.LOGIN_EXPIRED).message.contains("catalog account"))
    }

    @Test fun `initial HTTP failure survives subsequent player and cleanup events`() {
        val latch = PrototypeFeedFailureLatch()
        assertNull(latch.failure)
        val initial = PrototypeFeedFailure(PrototypeFailureReason.MEDIA_NOT_FOUND, httpStatus = 404)
        latch.remember(initial)
        latch.remember(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED))
        latch.remember(PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = 2000))
        latch.remember(PrototypeFeedFailure(PrototypeFailureReason.STOPPED))
        latch.remember(PrototypeFeedFailure(PrototypeFailureReason.CLEANUP_FAILED))
        assertEquals(initial, latch.failure)
    }

    @Test fun `not found does not claim an offline channel or rejected login`() {
        val message = PrototypeFeedFailure(PrototypeFailureReason.MEDIA_NOT_FOUND, httpStatus = 404).message
        assertTrue(message.contains("may be offline"))
        assertTrue(message.contains("HTTP 404"))
        assertFalse(message.contains("Reconnect"))
    }

    @Test fun `failure diagnostics accept only bounded numeric values`() {
        for (status in listOf(-1, 0, 99, 600, Int.MAX_VALUE)) {
            try {
                PrototypeFeedFailure(PrototypeFailureReason.HTTP_REJECTED, httpStatus = status)
                throw AssertionError("Unbounded status accepted")
            } catch (_: IllegalArgumentException) { }
        }
        for (code in listOf(-1, 0, 999, 10000, Int.MAX_VALUE)) {
            try {
                PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = code)
                throw AssertionError("Unbounded player code accepted")
            } catch (_: IllegalArgumentException) { }
        }
        assertTrue(PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = 2004)
            .message.contains("Player error 2004"))
    }

    @Test fun `all reasons supply fixed source independent text`() {
        for (reason in PrototypeFailureReason.entries) {
            val message = PrototypeFeedFailure(reason).message
            assertTrue(message.isNotBlank())
            assertFalse(message.contains("https://"))
            assertFalse(message.contains("token="))
        }
    }
}
