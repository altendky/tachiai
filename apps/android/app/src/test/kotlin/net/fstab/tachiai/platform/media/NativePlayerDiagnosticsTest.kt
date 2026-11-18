package net.fstab.tachiai.platform.media

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import java.lang.reflect.Proxy
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import net.fstab.tachiai.platform.network.ProviderTransportEvidence
import net.fstab.tachiai.platform.network.ProviderTransportExceptionKind
import net.fstab.tachiai.platform.network.ProviderTransportFailure
import net.fstab.tachiai.platform.network.ProviderTransportOperation
import net.fstab.tachiai.platform.network.ProviderTransportPhase
import org.junit.Assert.*
import org.junit.Test

@UnstableApi
class NativePlayerDiagnosticsTest {
    @Test fun `genuine protected failure after terminal error vetoes recovery through subsequent cleanup`() {
        val tls = NativeMediaRequestException(NativeMediaRequestFailure.TLS,
            ProviderTransportEvidence(ProviderTransportFailure.TLS))
        for (refusal in listOf(tls,
            NativeMediaRequestException(NativeMediaRequestFailure.HTTP_REJECTED, httpStatus = 401),
            NativeMediaRequestException(NativeMediaRequestFailure.HTTP_REJECTED, httpStatus = 403))) {
            var now = 100L
            val diagnostics = NativePlayerDiagnostics { now }
            capture(diagnostics)
            val network = NativeMediaRequestException(NativeMediaRequestFailure.TIMEOUT,
                ProviderTransportEvidence(ProviderTransportFailure.TIMEOUT))
            diagnostics.recordFailure(NativeFailureStage.MEDIA_REQUEST_OPEN, network)
            now++
            diagnostics.recordFailure(NativeFailureStage.PLAYER_ERROR, IOException("synthetic"))
            now++
            diagnostics.recordFailure(NativeFailureStage.MEDIA_REQUEST_OPEN, refusal)
            now++
            diagnostics.recordFailure(NativeFailureStage.MEDIA_REQUEST_READ,
                NativeMediaRequestException(NativeMediaRequestFailure.PLAYBACK_INACTIVE))
            diagnostics.recordFailure(NativeFailureStage.MEDIA_REQUEST_OPEN, network)
            val snapshot = checkNotNull(diagnostics.snapshot(true))
            if (refusal.httpStatus != null) assertEquals(refusal.httpStatus, snapshot.httpFailure?.status)
            else assertEquals(ProviderTransportFailure.TLS, snapshot.transportFailure?.evidence?.failure)
        }
    }

    @Test fun `terminal local refusal cannot acquire authority from later retryable callbacks`() {
        val diagnostics = NativePlayerDiagnostics { 100L }
        capture(diagnostics)
        diagnostics.recordFailure(NativeFailureStage.MEDIA_REQUEST_OPEN,
            NativeMediaRequestException(NativeMediaRequestFailure.REQUEST_ADMISSION_REFUSED))
        diagnostics.recordFailure(NativeFailureStage.PLAYER_ERROR, IOException("synthetic"))
        diagnostics.recordFailure(NativeFailureStage.MEDIA_REQUEST_OPEN,
            NativeMediaRequestException(NativeMediaRequestFailure.TIMEOUT,
                ProviderTransportEvidence(ProviderTransportFailure.TIMEOUT)))
        diagnostics.recordFailure(NativeFailureStage.MEDIA_REQUEST_OPEN,
            NativeMediaRequestException(NativeMediaRequestFailure.HTTP_REJECTED, httpStatus = 503))
        val snapshot = checkNotNull(diagnostics.snapshot(true))
        assertNull(snapshot.transportFailure)
        assertNull(snapshot.httpFailure)
    }

    @Test fun `concurrent protected request and terminal callbacks cannot lose the veto`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(25) {
                val diagnostics = NativePlayerDiagnostics { 100L }
                capture(diagnostics)
                diagnostics.recordFailure(NativeFailureStage.MEDIA_REQUEST_OPEN,
                    NativeMediaRequestException(NativeMediaRequestFailure.TIMEOUT,
                        ProviderTransportEvidence(ProviderTransportFailure.TIMEOUT)))
                val start = CountDownLatch(1)
                val terminal = executor.submit {
                    check(start.await(3, TimeUnit.SECONDS))
                    diagnostics.recordFailure(NativeFailureStage.PLAYER_ERROR, IOException("synthetic"))
                }
                val protected = executor.submit {
                    check(start.await(3, TimeUnit.SECONDS))
                    diagnostics.recordFailure(NativeFailureStage.MEDIA_REQUEST_OPEN,
                        NativeMediaRequestException(NativeMediaRequestFailure.TLS,
                            ProviderTransportEvidence(ProviderTransportFailure.TLS)))
                }
                start.countDown()
                terminal.get(3, TimeUnit.SECONDS); protected.get(3, TimeUnit.SECONDS)
                assertEquals(ProviderTransportFailure.TLS, diagnostics.snapshot(true)?.transportFailure?.evidence?.failure)
            }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test fun `terminal request proof survives consequential inactive request cleanup`() {
        for (http in listOf(false, true)) {
            var now = 100L
            val diagnostics = NativePlayerDiagnostics { now }
            capture(diagnostics)
            val cause = if (http) NativeMediaRequestException(NativeMediaRequestFailure.HTTP_REJECTED, httpStatus = 503)
                else NativeMediaRequestException(NativeMediaRequestFailure.TIMEOUT,
                    ProviderTransportEvidence(ProviderTransportFailure.TIMEOUT))
            diagnostics.recordFailure(NativeFailureStage.MEDIA_REQUEST_OPEN, cause)
            now++
            diagnostics.recordPlayerError(2000)
            diagnostics.recordFailure(NativeFailureStage.PLAYER_ERROR, IOException("synthetic"))
            now++
            diagnostics.recordFailure(NativeFailureStage.MEDIA_REQUEST_READ,
                NativeMediaRequestException(NativeMediaRequestFailure.PLAYBACK_INACTIVE))
            val stopped = checkNotNull(diagnostics.snapshot(true))
            if (http) {
                assertEquals(NativePlaybackHttpFailure(NativeFailureStage.MEDIA_REQUEST_OPEN, 2, 503), stopped.httpFailure)
                assertNull(stopped.transportFailure)
            } else {
                assertEquals(ProviderTransportFailure.TIMEOUT, stopped.transportFailure?.evidence?.failure)
                assertEquals(2L, stopped.transportFailure?.ageMs)
                assertNull(stopped.httpFailure)
            }
        }
    }

    @Test fun `HTTP status remains request linked and ages through terminal player error`() {
        var now = 100L
        val diagnostics = NativePlayerDiagnostics { now }
        capture(diagnostics)
        diagnostics.recordFailure(NativeFailureStage.MEDIA_REQUEST_OPEN,
            NativeMediaRequestException(NativeMediaRequestFailure.HTTP_REJECTED, httpStatus = 503))
        now = 120
        diagnostics.recordPlayerError(2000)
        diagnostics.recordFailure(NativeFailureStage.PLAYER_ERROR, IOException("synthetic"))
        val snapshot = checkNotNull(diagnostics.snapshot(false))
        assertEquals(NativePlaybackHttpFailure(NativeFailureStage.MEDIA_REQUEST_OPEN, 20, 503), snapshot.httpFailure)
        assertNull(snapshot.transportFailure)
        now = 5_200
        assertEquals(5_100L, diagnostics.snapshot(false)?.httpFailure?.ageMs)
    }

    @Test fun `a new media failure replaces HTTP and transport authority together`() {
        var now = 100L
        val diagnostics = NativePlayerDiagnostics { now }
        capture(diagnostics)
        val network = NativeMediaRequestException(NativeMediaRequestFailure.TIMEOUT,
            ProviderTransportEvidence(ProviderTransportFailure.TIMEOUT))
        diagnostics.recordFailure(NativeFailureStage.MEDIA_REQUEST_READ, network)
        assertNotNull(diagnostics.snapshot(false)?.transportFailure)
        now++
        diagnostics.recordFailure(NativeFailureStage.MEDIA_REQUEST_OPEN,
            NativeMediaRequestException(NativeMediaRequestFailure.HTTP_REJECTED, httpStatus = 403))
        assertEquals(403, diagnostics.snapshot(false)?.httpFailure?.status)
        assertNull(diagnostics.snapshot(false)?.transportFailure)
        now++
        diagnostics.recordFailure(NativeFailureStage.MEDIA_REQUEST_READ,
            NativeMediaRequestException(NativeMediaRequestFailure.REQUEST_ADMISSION_REFUSED))
        val refused = checkNotNull(diagnostics.snapshot(false))
        assertNull(refused.httpFailure)
        assertNull(refused.transportFailure)
        now++
        diagnostics.recordFailure(NativeFailureStage.MEDIA_REQUEST_OPEN, network)
        assertNotNull(diagnostics.snapshot(false)?.transportFailure)
        assertNull(diagnostics.snapshot(false)?.httpFailure)
    }

    private fun capture(diagnostics: NativePlayerDiagnostics): NativePlaybackDiagnosticSnapshot {
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, _ ->
            when (method.name) {
                "getVideoFormat", "getPlayerError" -> null
                "getPlayWhenReady", "isPlaying" -> true
                "getPlaybackState" -> 3
                "getPlaybackSuppressionReason" -> 0
                else -> error("Unexpected SDK read")
            }
        } as ExoPlayer
        return checkNotNull(diagnostics.capture(player, NativeTimingSnapshot(12_000, 60_000, 20_000,
            null, null, null, false, false, true, false, false, true, true, 3, 1f)))
    }

    @Test fun `player error after local admission failure cannot resurrect earlier network evidence`() {
        for (stage in listOf(NativeFailureStage.MEDIA_REQUEST_OPEN, NativeFailureStage.MEDIA_REQUEST_READ)) {
            var now = 100L
            val diagnostics = NativePlayerDiagnostics { now }
            capture(diagnostics)
            val evidence = ProviderTransportEvidence(ProviderTransportFailure.UNKNOWN,
                exceptionKind = ProviderTransportExceptionKind.IO)
            val early = NativePlaybackTransportFailure(NativeFailureStage.MEDIA_REQUEST_OPEN, 0, evidence)
            diagnostics.recordFailure(NativeFailureStage.MEDIA_REQUEST_OPEN,
                NativeMediaRequestException(NativeMediaRequestFailure.IO, evidence))
            assertEquals(evidence, diagnostics.snapshot(false)?.transportFailure?.evidence)
            now = 101
            diagnostics.recordFailure(stage, NativeMediaRequestException(NativeMediaRequestFailure.REQUEST_ADMISSION_REFUSED))
            assertNull(diagnostics.snapshot(false)?.transportFailure)
            now = 102
            diagnostics.recordPlayerError(2001)
            diagnostics.recordFailure(NativeFailureStage.PLAYER_ERROR, IOException("Synthetic player error"))
            val failed = checkNotNull(diagnostics.snapshot(false))
            assertEquals(NativeFailureStage.PLAYER_ERROR, failed.failureStage)
            assertEquals(2001, failed.errorCode)
            assertNull(failed.transportFailure)
            assertNull(latestNativePlaybackTransport(failed, early))
        }
    }

    @Test fun `recorded player failure retains its own transport even when early evidence appears fresher`() {
        var now = 100L
        val diagnostics = NativePlayerDiagnostics { now }
        capture(diagnostics)
        val ownEvidence = ProviderTransportEvidence(ProviderTransportFailure.TIMEOUT)
        diagnostics.recordFailure(NativeFailureStage.MEDIA_REQUEST_READ,
            NativeMediaRequestException(NativeMediaRequestFailure.TIMEOUT, ownEvidence))
        now = 120
        diagnostics.recordFailure(NativeFailureStage.PLAYER_ERROR, IOException("Synthetic player error"))
        val failed = checkNotNull(diagnostics.snapshot(false))
        val early = NativePlaybackTransportFailure(NativeFailureStage.MEDIA_REQUEST_OPEN, 0,
            ProviderTransportEvidence(ProviderTransportFailure.CONNECT))
        assertEquals(ownEvidence, latestNativePlaybackTransport(failed, early)?.evidence)
    }

    @Test fun `early evidence and freshest projection remain available before any recorded player failure`() {
        val diagnostics = NativePlayerDiagnostics { 100L }
        val healthy = capture(diagnostics)
        assertNull(healthy.failureStage)
        val early = NativePlaybackTransportFailure(NativeFailureStage.MEDIA_REQUEST_OPEN, 10,
            ProviderTransportEvidence(ProviderTransportFailure.CONNECT))
        assertEquals(early, latestNativePlaybackTransport(null, early))
        assertEquals(early, latestNativePlaybackTransport(healthy, early))
        assertNull(latestNativePlaybackTransport(healthy, null))
        val native = NativePlaybackTransportFailure(NativeFailureStage.MEDIA_REQUEST_READ, 20,
            ProviderTransportEvidence(ProviderTransportFailure.TIMEOUT))
        assertEquals(early, latestNativePlaybackTransport(healthy.copy(transportFailure = native), early))
        assertEquals(native, latestNativePlaybackTransport(healthy.copy(transportFailure = native), early.copy(ageMs = 30)))
    }

    @Test fun `cached released snapshot keeps numeric error and context without consulting SDK`() {
        var now = 100L
        val counters = DecoderCounters().apply { renderedOutputBufferCount = 20; droppedBufferCount = 2 }
        var forbidSdk = false
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, _ ->
            check(!forbidSdk) { "Released SDK must not be read" }
            when (method.name) {
                "getVideoFormat" -> Format.Builder().build()
                "getVideoDecoderCounters" -> counters
                "getPlayerError" -> null
                "getPlayWhenReady", "isPlaying" -> true
                "getPlaybackState" -> 3
                "getPlaybackSuppressionReason" -> 0
                else -> error("Unexpected SDK read")
            }
        } as ExoPlayer
        val diagnostics = NativePlayerDiagnostics { now }
        val timing = NativeTimingSnapshot(12_000, 60_000, 20_000, null, null, null,
            false, false, true, false, false, true, true, 3, 1f)
        val captured = checkNotNull(diagnostics.capture(player, timing))
        assertEquals(20L, captured.renderedFrames); assertEquals(2L, captured.droppedFrames)
        assertEquals(12_000L, captured.clockMs)
        diagnostics.recordPlayerError(2001)
        val evidence = ProviderTransportEvidence(ProviderTransportFailure.TIMEOUT, ProviderTransportPhase.RESPONSE,
            exceptionKind = ProviderTransportExceptionKind.SOCKET_TIMEOUT,
            operation = ProviderTransportOperation.RESPONSE_BODY, phaseAvailable = true, proxyPresent = true)
        diagnostics.recordFailure(NativeFailureStage.MEDIA_REQUEST_READ,
            NativeMediaRequestException(NativeMediaRequestFailure.TIMEOUT, evidence))
        now = 150
        diagnostics.recordFailure(NativeFailureStage.PLAYER_RELEASE, IOException("secret-provider-url-and-token"))
        forbidSdk = true
        now = 200
        assertEquals(12_000L, diagnostics.capture(player, null)?.positionMs)
        val stopped = checkNotNull(diagnostics.snapshot(released = true))
        assertTrue(stopped.released); assertEquals(100L, stopped.sampleAgeMs)
        assertEquals(100L, stopped.renderProgressAgeMs)
        assertEquals(2001, stopped.errorCode)
        assertEquals(12_000L, stopped.positionMs); assertEquals(20_000L, stopped.bufferedMs)
        assertEquals(NativeFailureStage.PLAYER_RELEASE, stopped.failureStage); assertEquals(50L, stopped.failureAgeMs)
        assertEquals(NativeFailureStage.MEDIA_REQUEST_READ, stopped.transportFailure?.stage)
        assertEquals(100L, stopped.transportFailure?.ageMs); assertEquals(evidence, stopped.transportFailure?.evidence)
        assertFalse(stopped.toString().contains("secret-provider"))
        now = 250
        assertEquals(150L, diagnostics.snapshot(released = true)?.sampleAgeMs)
        assertEquals(20L, diagnostics.snapshot(released = true)?.renderedFrames)
    }
}
