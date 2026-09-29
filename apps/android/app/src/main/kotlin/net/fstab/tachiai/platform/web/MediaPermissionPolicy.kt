package net.fstab.tachiai.platform.web

import java.net.URI
import net.fstab.tachiai.provider.ProviderAdapter

internal object MediaPermissionPolicy {
    fun grantProtectedMediaOnly(
        adapter: ProviderAdapter,
        origin: URI,
        requestedResources: Array<String>,
        protectedMediaResource: String,
    ): Array<String> = if (
        adapter.isProtectedMediaOriginAllowed(origin) &&
        requestedResources.contentEquals(arrayOf(protectedMediaResource))
    ) {
        arrayOf(protectedMediaResource)
    } else {
        emptyArray()
    }
}
