package net.fstab.tachiai.platform.media

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.*
import org.junit.Test

@UnstableApi
class NativeQualityDiagnosticsTest {
    @Test fun numericVideoAndAudioDetailsNeverExposeFormatIdentityOrMetadata() {
        val format = Format.Builder().setId("secret-format-id").setLabel("secret-label")
            .setSampleMimeType("video/avc").setWidth(1280).setHeight(720)
            .setAverageBitrate(2_000_000).build()
        val video = nativeQualityTrack(format, C.TRACK_TYPE_VIDEO)
        val audio = nativeQualityTrack(Format.Builder().setSampleMimeType("audio/mp4a-latm")
            .setAverageBitrate(192_000).setChannelCount(2).setSampleRate(48000).build(), C.TRACK_TYPE_AUDIO)
        val snapshot = NativeQualitySnapshot(video, audio, 3_000_000,
            NativeBandwidthSample(1000, 375000, 3_000_000), listOf(NativeQualityOption(video, true, true)), 1)
        val text = snapshot.details()
        assertTrue(text.contains("1280×720 AVC 2000 kb/s"))
        assertTrue(text.contains("AAC 192 kb/s 2 ch 48000 Hz"))
        assertTrue(text.contains("3000 kb/s (samples observed)"))
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("Format("))
    }

    @Test fun unknownOrUntrustedFieldsBecomeClosedCodecAndUnknownNumbers() {
        val format = Format.Builder().setSampleMimeType("secret-provider-value")
            .setWidth(999999).setHeight(-1).setChannelCount(999).setSampleRate(999999).build()
        val video = nativeQualityTrack(format, C.TRACK_TYPE_VIDEO)
        assertEquals(NativeQualityCodec.OTHER, video.codec)
        assertNull(video.width); assertNull(video.height); assertNull(video.bitrateBps)
        val audio = nativeQualityTrack(format, C.TRACK_TYPE_AUDIO)
        assertNull(audio.channelCount); assertNull(audio.sampleRateHz)
        assertFalse(audio.summary().contains("secret"))
    }

    @Test fun initialEstimateAndUnavailableTracksAreNotPresentedAsMeasurements() {
        val snapshot = NativeQualitySnapshot(null, null, 1_000_000, null, emptyList(), 0)
        assertTrue(snapshot.summary().contains("initial/reset"))
        assertTrue(snapshot.summary().contains("Video: unavailable; audio: unavailable"))
        assertFalse(snapshot.summary().contains("samples observed"))
    }

    @Test fun availableSelectionsAreDistinctFromConsumedFormatAndOverflowIsExplicit() {
        val decoded = NativeQualityTrack(NativeQualityKind.VIDEO, NativeQualityCodec.AVC, 500000, 640, 360)
        val eligible = NativeQualityTrack(NativeQualityKind.VIDEO, NativeQualityCodec.AVC, 2000000, 1280, 720)
        val snapshot = NativeQualitySnapshot(decoded, null, 1_000_000, null,
            listOf(NativeQualityOption(eligible, true, true)), 3)
        assertTrue(snapshot.summary().contains("640×360"))
        assertFalse(snapshot.summary().contains("1280×720"))
        assertTrue(snapshot.details().contains("1280×720"))
        assertTrue(snapshot.details().contains("eligible/selected"))
        assertTrue(snapshot.details().contains("+2 omitted"))
    }
}
