package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.platform.net.AccessProbeOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class AbemaNativeAccessProbeTest {
    @Test fun `denied manifest does not diagnose DRM region or authentication`() {
        assertEquals(AccessProbeOutcome.HTTP_REJECTED, abemaDashClassification(403, ""))
        assertEquals(AccessProbeOutcome.HTTP_REJECTED, abemaDashClassification(302, "<MPD>"))
    }

    @Test fun `manifest markers distinguish protection hints without requesting a license`() {
        assertEquals(AccessProbeOutcome.DASH_MARKERS_PRESENT, abemaDashClassification(200, "<MPD xmlns='urn:mpeg:dash:schema:mpd:2011'></MPD>"))
        assertEquals(AccessProbeOutcome.DASH_PROTECTION_MARKERS_PRESENT,
            abemaDashClassification(200, "<dash:MPD><dash:ContentProtection schemeIdUri='urn:uuid:test'/></dash:MPD>"))
        assertEquals(AccessProbeOutcome.INVALID_RESPONSE, abemaDashClassification(200, "<html>not a manifest</html>"))
    }
}
