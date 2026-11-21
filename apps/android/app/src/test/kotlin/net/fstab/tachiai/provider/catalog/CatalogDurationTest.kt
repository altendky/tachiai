package net.fstab.tachiai.provider.catalog

import net.fstab.tachiai.presentation.ProviderId
import org.junit.Assert.*
import org.junit.Test

class CatalogDurationTest {
    private val resource = CatalogResource(ProviderId("abema"), "episode", "episode-A", CatalogIntent.VIDEO)

    @Test fun formatsHoursMinutesAndShortSeconds() {
        assertEquals("1s", formatCatalogDuration(1))
        assertEquals("40s", formatCatalogDuration(40))
        assertEquals("59s", formatCatalogDuration(59))
        assertEquals("1m", formatCatalogDuration(60))
        assertEquals("1m", formatCatalogDuration(89))
        assertEquals("2m", formatCatalogDuration(90))
        assertEquals("10m", formatCatalogDuration(584))
        assertEquals("45m", formatCatalogDuration(45 * 60))
        assertEquals("1h", formatCatalogDuration(3600))
        assertEquals("1h 58m", formatCatalogDuration(7080))
        assertEquals("1h 58m", formatCatalogDuration(7109))
        assertEquals("2h", formatCatalogDuration(7170))
        assertEquals("168h", formatCatalogDuration(MAX_CATALOG_DURATION_SECONDS))
    }

    @Test fun entriesAcceptOnlyAPositiveBoundedDurationAndDefaultToUnknown() {
        assertNull(CatalogEntry(resource, "Day one").durationSeconds)
        assertEquals(584L, CatalogEntry(resource, "Day one", durationSeconds = 584).durationSeconds)
        for (invalid in listOf(0L, -1L, MAX_CATALOG_DURATION_SECONDS + 1)) {
            assertThrows(IllegalArgumentException::class.java) { CatalogEntry(resource, "Day one", durationSeconds = invalid) }
            assertThrows(IllegalArgumentException::class.java) { formatCatalogDuration(invalid) }
        }
    }
}
