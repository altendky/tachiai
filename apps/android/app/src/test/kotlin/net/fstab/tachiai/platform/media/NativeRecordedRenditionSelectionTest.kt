package net.fstab.tachiai.platform.media

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter
import java.net.URI
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// #711: a live ABEMA feed behind its provider window stays on the rendition
// its rewind buffer recorded, whatever the bandwidth estimate does.
@androidx.media3.common.util.UnstableApi
class NativeRecordedRenditionSelectionTest {
    private class Meter(var bitrate: Long) : BandwidthMeter {
        override fun getBitrateEstimate() = bitrate
        override fun getTransferListener(): TransferListener? = null
        override fun addEventListener(eventHandler: Handler, eventListener: BandwidthMeter.EventListener) = Unit
        override fun removeEventListener(eventListener: BandwidthMeter.EventListener) = Unit
    }

    private class InlineExecutor : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() = Unit
        override fun shutdownNow(): List<Runnable> = emptyList()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    private fun video(id: String, bitrate: Int) = Format.Builder().setId(id).setAverageBitrate(bitrate).setPeakBitrate(bitrate)
        .setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(bitrate / 1_000).setHeight(bitrate / 2_000).build()
    private val group = TrackGroup(video("1080", 6_000_000), video("720", 3_000_000), video("360", 800_000))

    private fun selected(recorded: NativeRecordedRendition?, meter: Meter): ExoTrackSelection {
        val selection = NativeRecordedRenditionSelectionFactory { type -> recorded.takeIf { type == C.TRACK_TYPE_VIDEO } }
            .createTrackSelections(arrayOf(ExoTrackSelection.Definition(group, 0, 1, 2)), meter,
                androidx.media3.exoplayer.source.MediaSource.MediaPeriodId(Any()), androidx.media3.common.Timeline.EMPTY)
            .single()!!
        update(selection)
        return selection
    }

    private fun update(selection: ExoTrackSelection) = selection.updateSelectedTrack(0, 30_000_000, C.TIME_UNSET,
        emptyList(), Array(selection.length()) { MediaChunkIterator.EMPTY })

    @Test fun `adaptive selection is free near live`() {
        assertEquals("1080", selected(null, Meter(100_000_000)).selectedFormat.id)
        assertEquals("360", selected(null, Meter(500_000)).selectedFormat.id)
    }

    @Test fun `behind the window the recorded rendition holds through bandwidth changes`() {
        val meter = Meter(500_000)
        val selection = selected(NativeRecordedRendition("720", 3_000_000), meter)
        assertEquals("720", selection.selectedFormat.id)
        // A bandwidth jump (or drop) would otherwise switch to a rendition with
        // no recorded history, which is no longer declared.
        for (bitrate in listOf(100_000_000L, 200_000L, 50_000_000L)) {
            meter.bitrate = bitrate
            update(selection)
            assertEquals("720", selection.selectedFormat.id)
        }
    }

    @Test fun `another period's rendition is matched by the closest bandwidth`() {
        val ad = listOf(video("ad-hi", 5_000_000), video("ad-mid", 2_500_000), video("ad-lo", 700_000))
        assertEquals("ad-mid", nativeRecordedFormat(ad, NativeRecordedRendition("720", 3_000_000))?.id)
        assertEquals("ad-lo", nativeRecordedFormat(ad, NativeRecordedRendition("360", 800_000))?.id)
        assertNull(nativeRecordedFormat(emptyList(), NativeRecordedRendition("720", 3_000_000)))
        val selection = selected(NativeRecordedRendition("ad-mid-elsewhere", 2_400_000), Meter(100_000_000))
        assertEquals("720", selection.selectedFormat.id)
    }

    @Test fun `the cache pins only while the feed is older than the provider window`() {
        val cache = NativeLiveSegmentCache(executor = InlineExecutor())
        val segment = URI("https://cdn.example/p1/v720/1.m4s")
        cache.publish(mapOf(segment to NativeLiveSegmentCache.Segment("p1", 0, C.TRACK_TYPE_VIDEO, "720", 3_000_000,
            0, 0, 5_000, false)), 60_000)
        cache.notePlayerRequest(segment)
        cache.setBehindLiveMs(20_000)
        assertNull(cache.recordedRendition(C.TRACK_TYPE_VIDEO))
        // Within 10 s of the provider window's start, and beyond it.
        cache.setBehindLiveMs(51_000)
        assertEquals(NativeRecordedRendition("720", 3_000_000), cache.recordedRendition(C.TRACK_TYPE_VIDEO))
        cache.setBehindLiveMs(240_000)
        assertEquals(NativeRecordedRendition("720", 3_000_000), cache.recordedRendition(C.TRACK_TYPE_VIDEO))
        assertNull(cache.recordedRendition(C.TRACK_TYPE_AUDIO))
        cache.close()
        assertNull(cache.recordedRendition(C.TRACK_TYPE_VIDEO))
    }
}
