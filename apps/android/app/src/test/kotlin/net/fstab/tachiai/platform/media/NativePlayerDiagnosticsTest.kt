package net.fstab.tachiai.platform.media

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import java.lang.reflect.Proxy
import java.io.IOException
import net.fstab.tachiai.platform.network.ProviderTransportEvidence
import net.fstab.tachiai.platform.network.ProviderTransportExceptionKind
import net.fstab.tachiai.platform.network.ProviderTransportFailure
import net.fstab.tachiai.platform.network.ProviderTransportOperation
import net.fstab.tachiai.platform.network.ProviderTransportPhase
import org.junit.Assert.*
import org.junit.Test

@UnstableApi
class NativePlayerDiagnosticsTest {
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
