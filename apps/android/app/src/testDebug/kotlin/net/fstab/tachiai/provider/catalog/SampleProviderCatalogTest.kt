package net.fstab.tachiai.provider.catalog

import net.fstab.tachiai.presentation.*
import org.junit.Assert.*
import org.junit.Test

class SampleProviderCatalogTest {
    @Test fun samplesAreExistingPublicChoicesWithUnknownStatusAndUnconnectedAccountCollections() {
        defaultProviderInstances().forEach { instance ->
            val catalog: ProviderCatalog = SampleProviderCatalog(instance, defaultSourceSetups())
            val page = (catalog.browse(CatalogQuery()) as CatalogResult.Value<CatalogPage>).value
            assertEquals(PrototypeSource.entries.filter { it.service == instance.service }.map(::prototypeCatalogResource),
                page.entries.map { it.resource })
            assertTrue(page.entries.all { it.availability == CatalogAvailability.UNKNOWN })
            assertNull(page.nextCursor)
            val collection = catalog.capabilities().collections.single()
            assertEquals("All", catalog.capabilities().browseTitle)
            assertNull(catalog.capabilities().initialCollectionId)
            assertEquals(CatalogAccess.NOT_VERIFIED, collection.access)
            assertEquals(CatalogResult.Failure(CatalogFailure.NOT_VERIFIED), catalog.browse(CatalogQuery(collectionId = collection.id)))
            assertEquals(CatalogAccess.UNSUPPORTED, catalog.capabilities().lookup)
            assertEquals(CatalogAccess.UNSUPPORTED, catalog.capabilities().children)
            assertEquals(CatalogResult.Failure(CatalogFailure.NOT_VERIFIED), catalog.resolve(page.entries.first().resource))
            val searched = (catalog.browse(CatalogQuery(search = page.entries.first().resource.identity)) as CatalogResult.Value<CatalogPage>).value
            assertEquals(page.entries.first(), searched.entries.single())
            catalog.close()
            assertEquals(CatalogResult.Failure(CatalogFailure.TEMPORARY), catalog.browse(CatalogQuery()))
        }
    }
}
