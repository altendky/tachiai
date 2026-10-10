package net.fstab.tachiai.provider.catalog

import net.fstab.tachiai.presentation.*

// Existing public samples only: no network, account collection or live-status
// observation. Real catalog adapters replace this factory in their own issues.
internal class SampleProviderCatalog(instance: ProviderInstance, setups: Map<PrototypeSource, SourceSetup>) : ProviderCatalog {
    override val providerId = ProviderId(instance.service.name.lowercase(java.util.Locale.ROOT))
    override val instanceId = instance.id
    private val entries = PrototypeSource.entries.filter { it.service == instance.service }.map {
        CatalogEntry(prototypeCatalogResource(it), checkNotNull(setups[it]).name)
    }
    @Volatile private var closed = false
    override fun capabilities() = CatalogCapabilities(
        browse = CatalogAccess.AVAILABLE, search = CatalogAccess.AVAILABLE,
        collections = listOf(if (providerId == ProviderId("twitch"))
            CatalogCollection("following", "Following", CatalogAccess.NOT_VERIFIED)
        else CatalogCollection("my_list", "My List", CatalogAccess.NOT_VERIFIED),
            CatalogCollection("history", "History", CatalogAccess.NOT_VERIFIED)),
    )
    override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> {
        if (closed) return CatalogResult.Failure(CatalogFailure.TEMPORARY)
        if (query.collectionId != null) return CatalogResult.Failure(
            if (capabilities().collections.any { it.id == query.collectionId }) CatalogFailure.NOT_VERIFIED else CatalogFailure.INVALID_INPUT)
        if (query.parent != null) return CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
        if (query.cursor != null) return CatalogResult.Failure(CatalogFailure.INVALID_INPUT)
        val matches = entries.filter { query.search == null || it.title.contains(query.search, true) ||
            it.resource.identity.contains(query.search, true) }
        return CatalogResult.Value(CatalogPage(matches))
    }
    override fun lookup(input: String): CatalogResult<CatalogEntry> = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
    override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> = CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
    override fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource> = CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
    override fun close() { closed = true }
}
