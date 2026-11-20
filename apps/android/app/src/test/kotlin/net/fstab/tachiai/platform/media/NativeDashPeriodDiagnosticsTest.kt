package net.fstab.tachiai.platform.media

import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.manifest.AdaptationSet
import androidx.media3.exoplayer.dash.manifest.BaseUrl
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.Period
import androidx.media3.exoplayer.dash.manifest.Representation
import androidx.media3.exoplayer.dash.manifest.SegmentBase
import org.junit.Assert.*
import org.junit.Test

@UnstableApi
class NativeDashPeriodDiagnosticsTest {
    private fun representation(drm: Boolean) = Representation.newInstance(0,
        Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).apply {
            if (drm) setDrmInitData(DrmInitData(DrmInitData.SchemeData(C.WIDEVINE_UUID, MimeTypes.VIDEO_MP4, byteArrayOf(1))))
        }.build(), listOf(BaseUrl("https://media.invalid/")), SegmentBase.SingleSegmentBase())
    private fun period(index: Int, drm: Boolean) = Period("p$index", index * 30_000L, listOf(
        AdaptationSet(index.toLong(), C.TRACK_TYPE_VIDEO, listOf(representation(drm)), emptyList(), emptyList(), emptyList())))
    private fun manifest(vararg drm: Boolean, dynamic: Boolean = true) = DashManifest(0, C.TIME_UNSET, 2_000, dynamic,
        C.TIME_UNSET, 60_000, C.TIME_UNSET, C.TIME_UNSET, null, null, null, null, drm.mapIndexed { index, value -> period(index, value) })

    @Test fun manifestShapeCountsPeriodsCarryingDrmDataWithoutIdentifiers() {
        // A protected program with an unencrypted break period between protected ones.
        val shape = nativeDashManifestShape(manifest(true, false, true), "SAME_PROTECTION")
        assertEquals(NativeDashManifestShape(3, 2, true, "SAME_PROTECTION"), shape)
        assertEquals("manifestShape periods=3 drmPeriods=2 dynamic=true transition=SAME_PROTECTION", shape.closedSummary())
        // As observed before a broadcast: only unencrypted periods.
        assertEquals("manifestShape periods=10 drmPeriods=0 dynamic=false transition=NONE",
            nativeDashManifestShape(manifest(*BooleanArray(10), dynamic = false), null).closedSummary())
        assertEquals(true, nativeDashPeriodHasDrm(manifest(true, false), 0))
        assertEquals(false, nativeDashPeriodHasDrm(manifest(true, false), 1))
        assertNull(nativeDashPeriodHasDrm(manifest(true, false), 2))
        assertThrows(IllegalArgumentException::class.java) { NativeDashManifestShape(1, 2, true, null) }
    }

    @Test fun periodChangesUseClosedReasonsAndExplicitUnknowns() {
        assertEquals("AUTO_TRANSITION", nativeDiscontinuityReason(Player.DISCONTINUITY_REASON_AUTO_TRANSITION))
        assertEquals("SEEK_ADJUSTMENT", nativeDiscontinuityReason(Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT))
        assertEquals("REMOVE", nativeDiscontinuityReason(Player.DISCONTINUITY_REASON_REMOVE))
        assertEquals("OTHER", nativeDiscontinuityReason(99))
        assertEquals("periodChange reason=AUTO_TRANSITION from=0 to=1 of=3 jumpMs=0 ad=false drm=false",
            NativeDashPeriodChange("AUTO_TRANSITION", 0, 1, 3, 0, false, false).closedSummary())
        assertEquals("periodChange reason=REMOVE from=2 to=0 of=1 jumpMs=UNKNOWN ad=false drm=UNKNOWN",
            NativeDashPeriodChange("REMOVE", 2, 0, 1, null, false, null).closedSummary())
    }
}
