package net.fstab.tachiai.feature.connections

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class SharedCatalogPreviewIntentTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val owner = ProviderInstance("12345678-1234-1234-1234-123456789abc", PrototypeService.ABEMA, "Chosen instance")
    private val entry = CatalogEntry(CatalogResource(ProviderId("abema"), "series", "FutureSeries", CatalogIntent.COLLECTION),
        "Public series", CatalogAvailability.UNKNOWN)
    private fun intent() = SharedCatalogPreviewHandoff.launchIntent(context, owner.id, entry)
    private fun assertClean(intent: Intent) {
        assertNull(intent.extras)
        assertNull(intent.data)
        assertNull(intent.clipData)
    }

    @Test fun explicitManagerHandoffRoundTripsOnlyNormalizedPublicFieldsAndConsumesIncomingIntent() {
        val incoming = intent()
        assertEquals(ManageStreamsActivity::class.java.name, incoming.component!!.className)
        assertTrue(SharedCatalogPreviewHandoff.isPresent(incoming))
        val handoff = SharedCatalogPreviewHandoff.consume(incoming, allowPreview = true)!!
        assertEquals(owner.id, handoff.instanceId)
        assertEquals(entry, handoff.take(owner))
        assertNull(handoff.take(owner))
        assertClean(incoming)
        assertNull(SharedCatalogPreviewHandoff.consume(incoming, allowPreview = true))
    }

    @Test fun restoredAndMalformedPayloadsAreErasedWithoutReturningAnEntryOrSenderDetails() {
        val restored = intent()
        assertNull(SharedCatalogPreviewHandoff.consume(restored, allowPreview = false))
        assertClean(restored)
        val mutations: List<(Intent) -> Unit> = listOf(
            { it.putExtra(ManageStreamsActivity.INSTANCE_ID, "not-a-uuid") },
            { it.putExtra("SHARED_CATALOG_PREVIEW_PROVIDER", "abema?private") },
            { it.putExtra("SHARED_CATALOG_PREVIEW_IDENTITY", "https://abema.tv/private?token=fixture") },
            { it.putExtra("SHARED_CATALOG_PREVIEW_TITLE", "private\n") },
            { it.putExtra("SHARED_CATALOG_PREVIEW_TITLE", "a".repeat(161)) },
            { it.putExtra("SHARED_CATALOG_PREVIEW_AVAILABILITY", "GUARANTEED_LIVE") },
            { it.putExtra("SHARED_CATALOG_PREVIEW_PRESENT", "true") },
            { it.putExtra("SHARED_CATALOG_PREVIEW_SCHEDULED", "123") },
            { it.putExtra("sender-private", "private") },
            { it.data = Uri.parse("https://invalid.test/private") },
            { it.clipData = ClipData.newPlainText("private", "private") },
        )
        mutations.forEach { mutate ->
            val incoming = intent()
            mutate(incoming)
            assertNull(SharedCatalogPreviewHandoff.consume(incoming, allowPreview = true))
            assertClean(incoming)
        }
    }
}
