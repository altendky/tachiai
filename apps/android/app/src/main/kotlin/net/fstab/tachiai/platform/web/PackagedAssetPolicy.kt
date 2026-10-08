package net.fstab.tachiai.platform.web

import java.net.URI
import net.fstab.tachiai.provider.isExactHttpsOrigin

internal object PackagedAssetPolicy {
    const val HOST = "appassets.androidplatform.net"

    fun allows(uri: URI, isPackagedPage: Boolean, childPaths: Set<String>): Boolean =
        uri.isExactHttpsOrigin(HOST) &&
            uri.rawPath == uri.path &&
            (isPackagedPage || uri.path in childPaths)

    fun isAssetHost(uri: URI): Boolean = uri.host.equals(HOST, ignoreCase = true)
}
