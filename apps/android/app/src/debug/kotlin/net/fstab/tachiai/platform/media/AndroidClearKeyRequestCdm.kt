package net.fstab.tachiai.platform.media

import android.media.MediaDrm
import android.media.NotProvisionedException
import android.media.UnsupportedSchemeException
import java.util.UUID

// Debug sources only. Direct request preparation, not a DRM session manager/player.
// No event listener, network callback, provisioning or key-response method is used.
internal class AndroidClearKeyRequestCdm : RequestOnlyCdm {
    private val drm = try {
        MediaDrm(UUID.fromString("e2719d58-a985-b3c9-781a-b030af78d30e"))
    } catch (_: UnsupportedSchemeException) {
        throw RequestProbeUnavailable(RequestProbeOutcome.CDM_UNSUPPORTED)
    }
    private var session: ByteArray? = null

    override fun openSession() {
        try { session = drm.openSession() } catch (_: NotProvisionedException) {
            throw RequestProbeUnavailable(RequestProbeOutcome.NOT_PROVISIONED)
        }
    }

    override fun describeRequest(initialization: ByteArray, expectedKids: List<UUID>): RequestMetadata {
        val request = try {
            drm.getKeyRequest(checkNotNull(session), initialization, "video/mp4", MediaDrm.KEY_TYPE_STREAMING, hashMapOf())
        } catch (_: NotProvisionedException) {
            throw RequestProbeUnavailable(RequestProbeOutcome.NOT_PROVISIONED)
        } catch (_: IllegalArgumentException) {
            throw RequestProbeUnavailable(RequestProbeOutcome.INITIALIZATION_REJECTED)
        } catch (_: MediaDrm.MediaDrmStateException) {
            throw RequestProbeUnavailable(RequestProbeOutcome.CDM_STATE_REJECTED)
        }
        val data = request.data
        return try {
            clearKeyRequestMetadata(data, expectedKids).copy(
                kind = when (request.requestType) {
                    MediaDrm.KeyRequest.REQUEST_TYPE_INITIAL -> RequestKind.INITIAL
                    MediaDrm.KeyRequest.REQUEST_TYPE_RENEWAL -> RequestKind.RENEWAL
                    MediaDrm.KeyRequest.REQUEST_TYPE_RELEASE -> RequestKind.RELEASE
                    MediaDrm.KeyRequest.REQUEST_TYPE_NONE -> RequestKind.NONE
                    MediaDrm.KeyRequest.REQUEST_TYPE_UPDATE -> RequestKind.UPDATE
                    else -> RequestKind.OTHER
                },
                destination = if (request.defaultUrl.isNullOrEmpty()) RequestDestination.EMPTY else RequestDestination.PRESENT,
            )
        } catch (_: Exception) {
            throw RequestProbeUnavailable(RequestProbeOutcome.REQUEST_METADATA_UNAVAILABLE)
        } finally { data.fill(0) }
    }

    override fun closeSession() {
        session?.let { bytes ->
            try { drm.closeSession(bytes) } finally { bytes.fill(0); session = null }
        }
    }

    @Suppress("DEPRECATION") // API 26/27 support; replacement close() was added in 28.
    override fun close() { drm.release() }
}
