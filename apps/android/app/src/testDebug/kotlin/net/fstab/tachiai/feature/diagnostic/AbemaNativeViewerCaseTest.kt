package net.fstab.tachiai.feature.diagnostic

import org.junit.Assert.*
import org.junit.Test

class AbemaNativeViewerCaseTest {
    @Test fun `viewer accepts only the four existing relative cases without changing policy`() {
        val accepted = listOf("LIVE_RELATIVE", "REPLAY_RELATIVE", "LIVE_REPLAY_RELATIVE", "REPLAY_LIVE_RELATIVE")
        assertEquals(accepted, AbemaNativePairCase.entries.mapNotNull { abemaNativeViewerCase(it.name)?.name })
        for (name in accepted) assertSame(abemaNativePairCase(name), abemaNativeViewerCase(name))
    }
    @Test fun `older diagnostics remain valid but cannot become viewer cases`() {
        for (mode in AbemaNativePairCase.entries.filterNot { it.relativeControls }) {
            assertNotNull(abemaNativePairCase(mode.name))
            assertNull(abemaNativeViewerCase(mode.name))
        }
    }
    @Test fun `unrecognized or arbitrary modes refuse`() {
        for (name in listOf("", "live_relative", "LIVE_RELATIVE ", "https://abema.tv/", "CUSTOM"))
            assertNull(abemaNativeViewerCase(name))
    }
}
