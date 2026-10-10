package net.fstab.tachiai.feature.connections

import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class SharedCatalogPreviewHandoffTest {
    private val owner = ProviderInstance("12345678-1234-1234-1234-123456789abc", PrototypeService.ABEMA, "Chosen instance")
    private val entry = CatalogEntry(CatalogResource(ProviderId("abema"), "episode", "FutureEpisode", CatalogIntent.VIDEO),
        "Public episode", CatalogAvailability.UNKNOWN)

    @Test fun normalizedEntryCanBeTakenOnceOnlyByItsExplicitlyChosenProviderInstance() {
        val handoff = SharedCatalogPreviewHandoff.create(owner.id, entry)
        assertTrue(handoff.matches(owner))
        assertSame(entry, handoff.take(owner))
        assertNull(handoff.take(owner))
        assertFalse(handoff.toString().contains("FutureEpisode"))
    }

    @Test fun staleSelectionAndChangedServiceConsumeThePayloadWithoutDefaultFallback() {
        val other = ProviderInstance("22345678-1234-1234-1234-123456789abc", PrototypeService.ABEMA, "Other instance")
        val wrongService = owner.copy(service = PrototypeService.TWITCH)
        listOf(other, wrongService).forEach { selected ->
            val handoff = SharedCatalogPreviewHandoff.create(owner.id, entry)
            assertFalse(handoff.matches(selected))
            assertNull(handoff.take(selected))
            assertNull(handoff.take(owner))
        }
    }

    @Test fun lifecycleDiscardCannotBeReplayedWhenTheOwnerBecomesReadyLater() {
        val handoff = SharedCatalogPreviewHandoff.create(owner.id, entry)
        handoff.discard()
        assertNull(handoff.take(owner))
        assertThrows(IllegalArgumentException::class.java) { SharedCatalogPreviewHandoff.create("stale", entry) }
    }
}
