package net.fstab.tachiai.provider.twitch

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import net.fstab.tachiai.platform.media.NativeFailureStage
import net.fstab.tachiai.platform.media.NativePlaybackDiagnosticSnapshot
import net.fstab.tachiai.platform.media.NativePlaybackTransportFailure
import net.fstab.tachiai.platform.network.ProviderTransportEvidence
import net.fstab.tachiai.platform.network.ProviderTransportException
import net.fstab.tachiai.platform.network.ProviderTransportExceptionKind
import net.fstab.tachiai.platform.network.ProviderTransportFailure
import net.fstab.tachiai.platform.network.ProviderTransportPhase
import net.fstab.tachiai.presentation.FeedAutoRecoveryDecision
import net.fstab.tachiai.presentation.FeedAutoRecoveryState
import net.fstab.tachiai.presentation.FeedRecoveryAction
import net.fstab.tachiai.presentation.PrototypeFailureReason
import net.fstab.tachiai.presentation.PrototypeFeedFailure
import net.fstab.tachiai.presentation.recoveryAction
import net.fstab.tachiai.presentation.routeSwitchWanted
import org.junit.Assert.*
import org.junit.Test

// #271 (PR #732): Twitch preparation requests that never reached Twitch.
class TwitchPreparationTransportTest {
    private fun carrier(failure: ProviderTransportFailure, phase: ProviderTransportPhase = ProviderTransportPhase.UNKNOWN,
        cancelled: Boolean = false) = ProviderTransportException(ProviderTransportEvidence(failure, phase, cancelled,
            ProviderTransportExceptionKind.CONNECT))

    @Test fun `only pre-response route availability failures are classified`() {
        for (error in listOf<Throwable>(carrier(ProviderTransportFailure.CONNECT), carrier(ProviderTransportFailure.DNS),
            carrier(ProviderTransportFailure.TIMEOUT, ProviderTransportPhase.CONNECT), ConnectException(),
            UnknownHostException(), IllegalStateException("wrapped", carrier(ProviderTransportFailure.CONNECT))))
            assertNotNull(error.toString(), twitchPreparationTransport(error))
        for (error in listOf<Throwable>(IOException("Playback connection ended"), SSLHandshakeException("tls"),
            SocketTimeoutException(), carrier(ProviderTransportFailure.TIMEOUT, ProviderTransportPhase.RESPONSE),
            carrier(ProviderTransportFailure.CONNECT, cancelled = true), carrier(ProviderTransportFailure.TLS),
            IllegalStateException("Playback authorization ended")))
            assertNull(error.toString(), twitchPreparationTransport(error))
    }

    @Test fun `provider HTTP and sign-in refusals keep their own failure`() {
        assertTrue(twitchPreparationNetworkFailure(null))
        assertTrue(twitchPreparationNetworkFailure(PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED)))
        for (refusal in listOf(PrototypeFeedFailure(PrototypeFailureReason.LOGIN_REJECTED),
            PrototypeFeedFailure(PrototypeFailureReason.LOGIN_EXPIRED),
            PrototypeFeedFailure(PrototypeFailureReason.HTTP_REJECTED, httpStatus = 503),
            PrototypeFeedFailure(PrototypeFailureReason.MEDIA_NOT_FOUND, httpStatus = 404),
            PrototypeFeedFailure(PrototypeFailureReason.NOT_LIVE_YET),
            PrototypeFeedFailure(PrototypeFailureReason.PREPARATION_FAILED)))
            assertFalse(refusal.toString(), twitchPreparationNetworkFailure(refusal))
    }

    @Test fun `a dead route during Twitch retries continues the burst and then switches with Retry`() {
        val evidence = checkNotNull(twitchPreparationTransport(carrier(ProviderTransportFailure.CONNECT)))
        val transport = NativePlaybackTransportFailure(NativeFailureStage.MEDIA_REQUEST_OPEN, 0, evidence)
        val failure = PrototypeFeedFailure(PrototypeFailureReason.NETWORK_FAILED)
        assertEquals(FeedRecoveryAction.RETRY, failure.recoveryAction())
        val state = FeedAutoRecoveryState(); val member = Any()
        // The burst begins with the playing feed's own player failure.
        state.observe(member, NativePlaybackDiagnosticSnapshot(0, 0, false, true, true, 3, 0, null,
            20_000, true, 1, 0, 1, 0, null, null, null), 0)
        assertEquals(FeedAutoRecoveryDecision.SCHEDULED, state.failed(member,
            PrototypeFeedFailure(PrototypeFailureReason.PLAYER_FAILED, playerCode = 2000), transport, true, true, true, 1))
        var now = 1L
        repeat(3) { attempt ->
            now += 10_000; assertNotNull(state.takeDue(now)); now += 1
            // Each retry's preparation fails before Twitch answers: no member yet.
            val decision = state.failed(null, failure, transport, true, true, true, now)
            if (attempt < 2) assertEquals(FeedAutoRecoveryDecision.SCHEDULED, decision)
            else {
                assertEquals(FeedAutoRecoveryDecision.EXHAUSTED, decision)
                assertTrue(routeSwitchWanted(decision, failure, transport, configuredRoutes = 2))
            }
        }
    }
}
