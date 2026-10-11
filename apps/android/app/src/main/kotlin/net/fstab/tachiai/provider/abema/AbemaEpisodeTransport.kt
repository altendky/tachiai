package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.provider.catalog.CatalogEntry
import net.fstab.tachiai.provider.catalog.CatalogResource
import net.fstab.tachiai.provider.catalog.CatalogResult

// One instance/route-owned exact metadata attempt. Implementations keep guest
// authorization private and return only normalized public metadata or reviewed
// failures after runtime/route cleanup is confirmed. Calls run on a worker.
internal interface AbemaEpisodeTransport : AutoCloseable {
    fun fetch(resource: CatalogResource): CatalogResult<CatalogEntry>
    // Cancellation/close must invalidate admission without waiting for the
    // caller's fetch lock. Both signals must be safe to repeat/concurrently use.
    fun cancelActiveRequest()
}
