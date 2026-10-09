package net.fstab.tachiai.platform.media

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import androidx.media3.exoplayer.trackselection.TrackSelector
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

// Provider-free SDK tests: synthetic formats/capabilities and bandwidth samples,
// no media downloads, decoder, provider account, route or DRM setup. These prove
// selection policy and meter isolation, not end-to-end provider adaptation.
@UnstableApi
@RunWith(AndroidJUnit4::class)
class NativePlaybackQualityTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun audioKeepsBestSupportedComparableTrackWhileVideoRemainsAdaptive() = onSelectorThread {
        val quality = NativePlayerQuality(context)
        try {
            val parameters = quality.trackSelector.parameters
            assertEquals(Int.MAX_VALUE, parameters.maxVideoWidth)
            assertEquals(Int.MAX_VALUE, parameters.maxVideoHeight)
            assertTrue(parameters.isViewportSizeLimitedByPhysicalDisplaySize)
            assertFalse(parameters.allowMultipleAdaptiveSelections)
            assertFalse(parameters.forceHighestSupportedBitrate)
            assertFalse(parameters.forceLowestBitrate)
            val result = selections(quality.trackSelector, ControlledMeter(quality.bandwidthMeter))
            val audio = checkNotNull(result.selections[1])
            assertTrue(audio is FixedTrackSelection)
            assertEquals(1, audio.length())
            assertEquals(192_000, audio.selectedFormat.bitrate)
            val video = checkNotNull(result.selections[0])
            assertTrue(video is AdaptiveTrackSelection)
            assertEquals(3, video.length())
            assertTrue((0 until video.length()).any { video.getFormat(it).height == 1080 })
            assertTrue((0 until video.length()).all { video.getFormat(it).height <= 1080 })
        } finally { quality.close(); quality.trackSelector.release() }
    }

    @Test fun videoFallsAndRecoversUnderControlledBandwidthWithoutChangingAudio() = onSelectorThread {
        val quality = NativePlayerQuality(context)
        try {
            val meter = ControlledMeter(quality.bandwidthMeter)
            val result = selections(quality.trackSelector, meter)
            val video = checkNotNull(result.selections[0])
            val audio = checkNotNull(result.selections[1])
            fun update(bufferUs: Long) {
                video.updateSelectedTrack(0, bufferUs, C.TIME_UNSET, emptyList(),
                    Array(video.length()) { MediaChunkIterator.EMPTY })
                assertEquals(192_000, audio.selectedFormat.bitrate)
            }
            meter.estimate = 20_000_000
            update(0)
            assertEquals(1080, video.selectedFormat.height)
            meter.estimate = 700_000
            update(0) // A low buffer allows the normal SDK downgrade hysteresis.
            assertEquals(360, video.selectedFormat.height)
            meter.estimate = 20_000_000
            update(0)
            assertEquals("Recovery waits for sufficient buffer", 360, video.selectedFormat.height)
            update(15_000_000)
            assertEquals(1080, video.selectedFormat.height)
        } finally { quality.close(); quality.trackSelector.release() }
    }

    @Test fun autoCanSelect4kWhenViewportAndBandwidthAllow() = onSelectorThread {
        val quality = NativePlayerQuality(context)
        try {
            val meter = ControlledMeter(quality.bandwidthMeter).apply { estimate = 40_000_000 }
            val result = selections(quality.trackSelector, meter, 3840, 2160)
            val video = checkNotNull(result.selections[0])
            assertTrue(video is AdaptiveTrackSelection)
            assertEquals(4, video.length())
            video.updateSelectedTrack(0, 0, C.TIME_UNSET, emptyList(),
                Array(video.length()) { MediaChunkIterator.EMPTY })
            assertEquals(2160, video.selectedFormat.height)
            assertEquals(192_000, checkNotNull(result.selections[1]).selectedFormat.bitrate)
        } finally { quality.close(); quality.trackSelector.release() }
    }

    @Test fun onePlayersTransferSampleDoesNotChangeTheOtherPlayersEstimate() {
        val first = NativePlayerQuality(context)
        val second = NativePlayerQuality(context)
        try {
            assertNotSame(first.bandwidthMeter, second.bandwidthMeter)
            assertNotSame(first.trackSelector, second.trackSelector)
            // Pin both meters before sampling: asynchronous SDK network-type
            // initialization may otherwise reset an untouched meter's estimate.
            first.bandwidthMeter.setNetworkTypeOverride(C.NETWORK_TYPE_WIFI)
            second.bandwidthMeter.setNetworkTypeOverride(C.NETWORK_TYPE_WIFI)
            val unchanged = second.bandwidthMeter.bitrateEstimate
            val before = first.bandwidthMeter.bitrateEstimate
            val listener = first.bandwidthMeter.transferListener
            val spec = DataSpec.Builder().setUri("https://fixture.invalid/media").build()
            listener.onTransferInitializing(unusedSource, spec, true)
            listener.onTransferStart(unusedSource, spec, true)
            listener.onBytesTransferred(unusedSource, spec, true, 512 * 1024)
            SystemClock.sleep(20) // Non-zero SDK sample duration; no network or playback wait.
            listener.onTransferEnd(unusedSource, spec, true)
            assertNotEquals(before, first.bandwidthMeter.bitrateEstimate)
            assertEquals(unchanged, second.bandwidthMeter.bitrateEstimate)
        } finally {
            first.close(); second.close()
            first.trackSelector.release(); second.trackSelector.release()
        }
    }

    @Test fun exactManualVideoKeepsAutoAudioAndManualAudioKeepsVideoAdaptive() = onSelectorThread {
        val quality = NativePlayerQuality(context)
        try {
            assertTrue(quality.trackSelector.parameters.isViewportSizeLimitedByPhysicalDisplaySize)
            val tracks = qualityTracks()
            val meter = ControlledMeter(quality.bandwidthMeter)
            val video = NativeQualityRequest(nativeQualityTrack(tracks.groups[0].getTrackFormat(1), C.TRACK_TYPE_VIDEO))
            val audio = NativeQualityRequest(nativeQualityTrack(tracks.groups[1].getTrackFormat(0), C.TRACK_TYPE_AUDIO))
            fun apply(preferences: NativeQualityPreferences) {
                val resolution = resolveNativeQuality(tracks, preferences)
                val parameters = quality.trackSelector.parameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO).clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                resolution.overrides.forEach { parameters.setOverrideForType(it) }
                quality.trackSelector.parameters = parameters.build()
            }
            apply(NativeQualityPreferences(video = video))
            val first = selections(quality.trackSelector, meter)
            assertTrue(first.selections[0] is FixedTrackSelection)
            assertEquals(720, checkNotNull(first.selections[0]).selectedFormat.height)
            assertEquals(192_000, checkNotNull(first.selections[1]).selectedFormat.bitrate)
            apply(NativeQualityPreferences(audio = audio))
            val second = selections(quality.trackSelector, meter, initialize = false)
            assertTrue(second.selections[0] is AdaptiveTrackSelection)
            assertEquals(64_000, checkNotNull(second.selections[1]).selectedFormat.bitrate)
            apply(NativeQualityPreferences())
            assertTrue(quality.trackSelector.parameters.overrides.isEmpty())
            val reset = selections(quality.trackSelector, meter, initialize = false)
            assertTrue(reset.selections[0] is AdaptiveTrackSelection)
            assertEquals(192_000, checkNotNull(reset.selections[1]).selectedFormat.bitrate)
            assertFalse(quality.trackSelector.parameters.allowMultipleAdaptiveSelections)
        } finally { quality.close(); quality.trackSelector.release() }
    }

    @Test fun changedGroupsReResolveDescriptorsAndMissingUnsupportedRequestsStayVisible() = onSelectorThread {
        val tracks = qualityTracks()
        val video = NativeQualityRequest(nativeQualityTrack(tracks.groups[0].getTrackFormat(1), C.TRACK_TYPE_VIDEO))
        val request = NativeQualityPreferences(video = video)
        val initial = resolveNativeQuality(tracks, request)
        assertEquals(NativeQualityOutcome.REQUESTED, initial.controls[NativeQualityKind.VIDEO]?.outcome)
        val changed = qualityTracks("replacement-video")
        val replacement = resolveNativeQuality(changed, request)
        assertEquals(changed.groups[0].mediaTrackGroup, replacement.overrides.single().mediaTrackGroup)
        assertNotEquals(initial.overrides.single().mediaTrackGroup, replacement.overrides.single().mediaTrackGroup)
        val absent = NativeQualityRequest(video.track!!.copy(height = 600))
        val missing = resolveNativeQuality(changed, NativeQualityPreferences(video = absent))
        assertTrue(missing.overrides.isEmpty())
        assertEquals(absent, missing.controls[NativeQualityKind.VIDEO]?.requested)
        assertEquals(NativeQualityOutcome.UNAVAILABLE, missing.controls[NativeQualityKind.VIDEO]?.outcome)
        val unsupported = NativeQualityRequest(nativeQualityTrack(tracks.groups[1].getTrackFormat(2), C.TRACK_TYPE_AUDIO))
        val refused = resolveNativeQuality(tracks, NativeQualityPreferences(audio = unsupported))
        assertTrue(refused.overrides.isEmpty())
        assertEquals(NativeQualityOutcome.UNAVAILABLE, refused.controls[NativeQualityKind.AUDIO]?.outcome)
        val pending = resolveNativeQuality(Tracks.EMPTY, request)
        assertEquals(NativeQualityOutcome.PENDING, pending.controls[NativeQualityKind.VIDEO]?.outcome)
    }

    @Test fun otherContentGroupsAndCoupledOrSingleTrackSourcesDoNotInventAlternatives() = onSelectorThread {
        val tracks = qualityTracks()
        val separateContent = Tracks.Group(TrackGroup("different-language", audio(128_000)), false,
            intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))
        val combined = Tracks(tracks.groups + separateContent)
        val other = NativeQualityRequest(nativeQualityTrack(separateContent.getTrackFormat(0), C.TRACK_TYPE_AUDIO))
        val resolution = resolveNativeQuality(combined, NativeQualityPreferences(audio = other))
        assertTrue(resolution.overrides.isEmpty())
        assertFalse(resolution.controls[NativeQualityKind.AUDIO]!!.options.contains(other))
        val single = Tracks(listOf(Tracks.Group(TrackGroup("single-muxed-audio", audio(192_000)), false,
            intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))))
        val control = resolveNativeQuality(single, NativeQualityPreferences()).controls[NativeQualityKind.AUDIO]!!
        assertEquals(1, control.options.size)
        assertTrue(control.capability.contains("bundled tracks may be coupled"))
        val ambiguous = Tracks(single.groups + Tracks.Group(TrackGroup("other-content", audio(192_000)), false,
            intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)))
        assertTrue(resolveNativeQuality(ambiguous, NativeQualityPreferences()).controls[NativeQualityKind.AUDIO]!!.options.isEmpty())
    }

    @Test fun duplicateNumericDescriptorsNeverChooseAnArbitrarySdkTrack() = onSelectorThread {
        val first = video(1280, 720, 2_000_000).buildUpon().setId("private-first-identity").build()
        val second = first.buildUpon().setId("private-second-identity").build()
        val tracks = Tracks(listOf(Tracks.Group(TrackGroup("private-group-identity", first, second), true,
            intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED), booleanArrayOf(true, true))))
        val request = NativeQualityRequest(nativeQualityTrack(first, C.TRACK_TYPE_VIDEO))
        assertEquals(request, NativeQualityRequest(nativeQualityTrack(second, C.TRACK_TYPE_VIDEO)))
        val result = resolveNativeQuality(tracks, NativeQualityPreferences(video = request))
        assertTrue(result.overrides.isEmpty())
        val control = result.controls[NativeQualityKind.VIDEO]!!
        assertEquals(request, control.requested)
        assertEquals(NativeQualityOutcome.UNAVAILABLE, control.outcome)
        assertTrue(control.options.isEmpty())
        assertFalse(control.toString().contains("private"))
    }

    @Test fun manualOverridesCannotBypassRetainedVideoAudioOrViewportLimits() = onSelectorThread {
        val quality = NativePlayerQuality(context)
        try {
            val tracks = qualityTracks()
            val large = NativeQualityRequest(nativeQualityTrack(tracks.groups[0].getTrackFormat(3), C.TRACK_TYPE_VIDEO))
            val limited = quality.trackSelector.parameters.buildUpon().setMaxAudioBitrate(128_000).build()
            val audio = NativeQualityRequest(nativeQualityTrack(tracks.groups[1].getTrackFormat(1), C.TRACK_TYPE_AUDIO))
            val result = resolveNativeQuality(tracks, NativeQualityPreferences(large, audio), limited, 1920, 1080)
            assertTrue(result.overrides.isEmpty())
            assertEquals(NativeQualityOutcome.UNAVAILABLE, result.controls[NativeQualityKind.VIDEO]?.outcome)
            assertEquals(NativeQualityOutcome.UNAVAILABLE, result.controls[NativeQualityKind.AUDIO]?.outcome)
        } finally { quality.close(); quality.trackSelector.release() }
    }

    @Test fun preferenceRequestsAreGuardedByPlayerThreadOwnershipAndCleanup() {
        lateinit var quality: NativePlayerQuality
        lateinit var player: ExoPlayer
        onSelectorThread {
            quality = NativePlayerQuality(context)
            player = quality.configure(ExoPlayer.Builder(context)).build()
            quality.bind(player)
            assertTrue(quality.request(player, NativeQualityPreferences()))
        }
        try {
            assertFalse(quality.request(player, NativeQualityPreferences())) // Runner has no player Looper.
            onSelectorThread {
                quality.close()
                assertFalse(quality.request(player, NativeQualityPreferences()))
            }
        } finally { onSelectorThread { quality.close(); player.release() } }
    }

    private fun qualityTracks(videoId: String = "fixture-video") = Tracks(listOf(
        Tracks.Group(TrackGroup(videoId, video(640, 360, 500_000), video(1280, 720, 2_000_000),
            video(1920, 1080, 8_000_000), video(3840, 2160, 16_000_000)), true,
            IntArray(4) { C.FORMAT_HANDLED }, BooleanArray(4) { true }),
        Tracks.Group(TrackGroup("fixture-audio", audio(64_000), audio(192_000), audio(384_000)), true,
            intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED, C.FORMAT_UNSUPPORTED_SUBTYPE),
            booleanArrayOf(false, true, false)),
    ))

    private fun selections(selector: DefaultTrackSelector, meter: BandwidthMeter,
        viewportWidth: Int = 1920, viewportHeight: Int = 1080, initialize: Boolean = true) = run {
        // Owned render targets make Auto tests deterministic on any test phone.
        // Production keeps the SDK's physical-display viewport default.
        selector.parameters = selector.parameters.buildUpon()
            .setViewportSize(viewportWidth, viewportHeight, false).build()
        if (initialize) selector.init(TrackSelector.InvalidationListener { selector.onParametersActivated(it) }, meter)
        selector.onParametersActivated(selector.parameters)
        val video = TrackGroup("fixture-video", video(640, 360, 500_000),
            video(1280, 720, 2_000_000), video(1920, 1080, 8_000_000), video(3840, 2160, 16_000_000))
        val audio = TrackGroup("fixture-audio", audio(64_000), audio(192_000), audio(384_000))
        selector.selectTracks(arrayOf(Capabilities(C.TRACK_TYPE_VIDEO), Capabilities(C.TRACK_TYPE_AUDIO)),
            TrackGroupArray(video, audio), MediaPeriodId(Any()), Timeline.EMPTY)
    }

    private fun onSelectorThread(block: () -> Unit) {
        // A real ExoPlayer calls selectTracks on its Looper-backed playback
        // thread. The selector's API 32+ spatializer listener requires a Looper;
        // the instrumentation runner worker has none. This synchronous owned
        // fixture uses the main Looper without changing device constraints.
        val failure = AtomicReference<Throwable>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            try { block() } catch (error: Throwable) { failure.set(error) }
        }
        failure.get()?.let { throw it }
    }

    private fun video(width: Int, height: Int, bitrate: Int) = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(width).setHeight(height)
        .setAverageBitrate(bitrate).build()
    private fun audio(bitrate: Int) = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC)
        .setChannelCount(2).setSampleRate(48_000).setAverageBitrate(bitrate).build()

    private class ControlledMeter(delegate: BandwidthMeter) : BandwidthMeter by delegate {
        var estimate = 10_000_000L
        override fun getBitrateEstimate() = estimate
    }

    private class Capabilities(private val type: Int) : RendererCapabilities {
        override fun getName() = "Owned quality fixture"
        override fun getTrackType() = type
        override fun supportsFormat(format: Format): Int = RendererCapabilities.create(
            when {
                MimeTypes.getTrackType(format.sampleMimeType) != type -> C.FORMAT_UNSUPPORTED_TYPE
                type == C.TRACK_TYPE_AUDIO && format.bitrate == 384_000 -> C.FORMAT_UNSUPPORTED_SUBTYPE
                else -> C.FORMAT_HANDLED
            }, RendererCapabilities.ADAPTIVE_SEAMLESS, RendererCapabilities.TUNNELING_NOT_SUPPORTED)
        override fun supportsMixedMimeTypeAdaptation() = RendererCapabilities.ADAPTIVE_SEAMLESS
    }

    private val unusedSource = object : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long = error("No transport in this fixture")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = error("No media in this fixture")
        override fun getUri(): Uri? = null
        override fun close() = Unit
    }
}
