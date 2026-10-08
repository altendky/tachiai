package net.fstab.tachiai.platform.media

import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal enum class OpaquePlaybackLicenseEvent { REQUESTED, RESPONSE_HANDED_OFF, REFUSED }

// Media3 owns the CDM/session. This gate only transports its initial challenge
// and returns the opaque response to that caller, never a second session.
internal class OneShotOpaquePlaybackLicense(
    expectedKids: List<UUID>,
    private val canRun: () -> Boolean,
    private val exchange: (ByteArray) -> ByteArray?,
    private val onEvent: (OpaquePlaybackLicenseEvent) -> Unit = {},
) {
    private val kids = expectedKids.toList()
    private val used = AtomicBoolean()

    fun request(data: ByteArray, initial: Boolean): ByteArray {
        if (!canRun() || !initial || !used.compareAndSet(false, true) || data.size !in 1..16 * 1024) refuse()
        val challenge = data.copyOf()
        var response: ByteArray? = null
        var handedOff = false
        try {
            val metadata = clearKeyRequestMetadata(challenge, kids)
            if (!canRun() || metadata.shape != RequestShape.STANDARD_KIDS_JSON ||
                metadata.kidMatch != RequestKidMatch.SAME_SET) refuse()
            onEvent(OpaquePlaybackLicenseEvent.REQUESTED)
            response = exchange(challenge)
            if (!canRun() || response == null || response.size !in 1..64 * 1024) refuse()
            onEvent(OpaquePlaybackLicenseEvent.RESPONSE_HANDED_OFF)
            if (!canRun()) refuse()
            handedOff = true
            return response
        } catch (_: Exception) {
            throw IOException("Native DRM exchange failed")
        } finally {
            challenge.fill(0)
            if (!handedOff) response?.fill(0)
        }
    }

    private fun refuse(): Nothing {
        onEvent(OpaquePlaybackLicenseEvent.REFUSED)
        throw IOException("Native DRM exchange refused")
    }
}
