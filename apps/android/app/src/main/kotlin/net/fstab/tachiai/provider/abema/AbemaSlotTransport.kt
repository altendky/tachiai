package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*

internal class AbemaSlotRequest(val channelId: String, val slotId: String) {
    init { require(validId(channelId) && validId(slotId)) }
    val resource get() = CatalogResource(ProviderId("abema"), "slot", "$channelId/$slotId", CatalogIntent.BROADCAST)
    fun url() = "https://api.p-c3-e.abema-tv.com/v1/media/slots/$slotId?include=payperview"
    override fun toString() = "AbemaSlotRequest(redacted)"
    companion object {
        fun validId(id: String) = id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
        fun from(resource: CatalogResource): AbemaSlotRequest? {
            if (resource.providerId.value != "abema" || resource.kind != "slot" || resource.intent != CatalogIntent.BROADCAST) return null
            val parts = resource.identity.split('/')
            return if (parts.size == 2 && parts.all(::validId)) AbemaSlotRequest(parts[0], parts[1]) else null
        }
    }
}

internal interface AbemaSlotTransport : AutoCloseable {
    fun fetch(resource: CatalogResource): CatalogResult<CatalogEntry>
    fun cancelActiveRequest()
}
