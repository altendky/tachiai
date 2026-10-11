package net.fstab.tachiai.provider.abema

import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import net.fstab.tachiai.feature.connections.*
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class AbemaChannelDeviceTest {
    @Test fun androidJsonRejectsAmbiguousChannelsAndKeepsOnlyNormalizedUnknownMetadata() {
        val entries = parseAbemaChannelResponse("""{"channels":[{"id":"fixture","name":"Fixture","viewingOpened":true,"playback":{"hls":"https://example.invalid/signed"}}]}""")
        assertEquals(1, entries.size)
        assertEquals("fixture", entries.single().resource.identity)
        assertEquals(CatalogAvailability.UNKNOWN, entries.single().availability)
        assertNull(entries.single().scheduledStartEpochMs)
        assertThrows(AbemaChannelException::class.java) {
            parseAbemaChannelResponse("""{"channels":[{"id":"fixture","id":"other","name":"Fixture"}]}""")
        }
    }

    // Explicit opt-in only, on the verified persistent development AVD. Reads
    // the existing default instance's selected route and public channel metadata;
    // never writes account, route, configuration or provider data. No UI capture.
    @Test fun savedAbemaRouteReadsRealChannelMetadataWithoutChangingStores() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tachiaiReadOnlyAbemaRoute") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun instances() = providerInstanceStore(context).read { legacyProviderSettings(context) }
        val before = instances()
        val owner = before.single { it.id == defaultProviderInstanceId(PrototypeService.ABEMA) }
        val selectedRoute = checkNotNull(owner.setup.route)
        assertTrue(selectedRoute.mode != SourceRouteMode.SOURCE_DEFAULT)
        val setups = sourceSetupStore(context).read()
        androidAbemaChannelCatalog(context, owner.id, setups).use { catalog ->
            assertEquals("Channels", catalog.capabilities().browseTitle)
            var cursor: String? = null
            val entries = mutableListOf<CatalogEntry>()
            do {
                val result = catalog.browse(CatalogQuery(cursor = cursor))
                assertTrue("Saved-route public channel request must succeed", result is CatalogResult.Value)
                val page = (result as CatalogResult.Value).value
                entries.addAll(page.entries)
                assertTrue(entries.size <= ABEMA_CHANNEL_ENTRY_LIMIT)
                cursor = page.nextCursor
            } while (cursor != null)
            assertTrue("Public channel snapshot must contain normalized entries", entries.isNotEmpty())
            assertTrue(entries.all { it.availability == CatalogAvailability.UNKNOWN && it.scheduledStartEpochMs == null })
            assertTrue(entries.map { it.resource }.distinct().size == entries.size)
            Log.i("TachiaiAbemaCheck", "CHANNELS_OK sdk=${Build.VERSION.SDK_INT} route=${selectedRoute.mode.name} count=${entries.size} sumoPresent=${entries.any { it.resource.identity == "sumo" }} region=UNVERIFIED account=NOT_USED playback=NOT_TESTED")
        }
        assertTrue("Provider registry must remain unchanged", before == instances())
        assertTrue("Source configuration must remain unchanged", setups == sourceSetupStore(context).read())
    }
}
