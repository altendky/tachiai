package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*

// Public-link import stays separate from metadata access. Production supplies
// the real channel adapter; the default retains historical sample fixtures.
internal class AbemaLocalImportCatalog(instance: ProviderInstance, setups: Map<PrototypeSource, SourceSetup>,
    private val metadata: ProviderCatalog = SampleProviderCatalog(instance, setups),
    private val canImport: () -> Boolean = { true }) : ProviderCatalog {
    init { require(instance.service == PrototypeService.ABEMA) }
    init { require(metadata.providerId == ProviderId("abema") && metadata.instanceId == instance.id) }
    override val providerId = metadata.providerId
    override val instanceId = metadata.instanceId
    @Volatile private var closed = false

    private fun localAvailable() = !closed && try { canImport() } catch (_: Exception) { false }
    override fun capabilities() = metadata.capabilities().copy(lookup =
        if (localAvailable()) CatalogAccess.AVAILABLE else CatalogAccess.NOT_VERIFIED)
    override fun browse(query: CatalogQuery) = metadata.browse(query)
    override fun lookup(input: String): CatalogResult<CatalogEntry> {
        if (closed) return CatalogResult.Failure(CatalogFailure.TEMPORARY)
        if (!localAvailable()) return CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED)
        val imported = parseAbemaPublicResource(input)
        if (imported == null && !input.trim(' ').matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")))
            return CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
        if (imported != null && (imported.resource.intent != CatalogIntent.CHANNEL ||
            metadata.capabilities().lookup != CatalogAccess.AVAILABLE)) {
            return if (localAvailable()) CatalogResult.Value(imported) else CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED)
        }
        val result = metadata.lookup(input)
        return if (localAvailable()) result else CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED)
    }
    override fun refresh(resource: CatalogResource) = metadata.refresh(resource)
    override fun resolve(resource: CatalogResource) = metadata.resolve(resource)
    override fun close() { closed = true; metadata.close() }
}
