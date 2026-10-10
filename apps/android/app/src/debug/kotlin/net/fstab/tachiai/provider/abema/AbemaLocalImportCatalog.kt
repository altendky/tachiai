package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*

// Import public identities locally. Samples remain samples: this adapter does
// not retrieve catalogs, account lists, availability or playback entitlement.
internal class AbemaLocalImportCatalog(instance: ProviderInstance, setups: Map<PrototypeSource, SourceSetup>) : ProviderCatalog {
    init { require(instance.service == PrototypeService.ABEMA) }
    private val samples = SampleProviderCatalog(instance, setups)
    override val providerId = samples.providerId
    override val instanceId = samples.instanceId
    @Volatile private var closed = false

    override fun capabilities() = samples.capabilities().copy(lookup = CatalogAccess.AVAILABLE)
    override fun browse(query: CatalogQuery) = samples.browse(query)
    override fun lookup(input: String): CatalogResult<CatalogEntry> {
        if (closed) return CatalogResult.Failure(CatalogFailure.TEMPORARY)
        return parseAbemaPublicResource(input)?.let { CatalogResult.Value(it) }
            ?: CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
    }
    override fun refresh(resource: CatalogResource) = samples.refresh(resource)
    override fun resolve(resource: CatalogResource) = samples.resolve(resource)
    override fun close() { closed = true; samples.close() }
}
