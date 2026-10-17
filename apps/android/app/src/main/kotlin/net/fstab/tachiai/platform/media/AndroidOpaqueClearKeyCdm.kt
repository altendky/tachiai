package net.fstab.tachiai.platform.media

import android.media.MediaDrm
import java.util.UUID

// No player, provisioning, offline key set, event listener, key export or diagnostics.
internal class AndroidOpaqueClearKeyCdm : OpaqueExchangeCdm {
    private val drm = MediaDrm(UUID.fromString("e2719d58-a985-b3c9-781a-b030af78d30e"))
    private var session: ByteArray? = null

    override fun openSession() { session = drm.openSession() }

    override fun request(initialization: ByteArray): ByteArray {
        val request = drm.getKeyRequest(checkNotNull(session), initialization, "video/mp4",
            MediaDrm.KEY_TYPE_STREAMING, hashMapOf())
        check(request.requestType == MediaDrm.KeyRequest.REQUEST_TYPE_INITIAL)
        check(request.defaultUrl.isNullOrEmpty())
        return request.data
    }

    override fun submit(response: ByteArray) {
        // Only acceptance of the opaque standard response is reported, not its contents.
        val keySet = drm.provideKeyResponse(checkNotNull(session), response)
        keySet?.fill(0)
    }

    override fun closeSession() {
        session?.let { value ->
            try { drm.closeSession(value) } finally { value.fill(0); session = null }
        }
    }

    @Suppress("DEPRECATION")
    override fun close() { drm.release() }
}
