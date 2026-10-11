package net.fstab.tachiai.provider.abema

import android.content.Context
import net.fstab.tachiai.provider.catalog.*

// Typed facade over the exercised private service/client lifetime. No generic
// URL/operation input can reach the closed slot wire contract.
internal class AndroidAbemaSlotMetadataClient(context: Context, instanceId: String, expectedOwnerProof: String,
    canRequest: () -> Boolean, canRemainActive: () -> Boolean) : AbemaSlotTransport {
    private val client = AndroidAbemaEpisodeMetadataClient.forSlot(context, instanceId, expectedOwnerProof,
        canRequest, canRemainActive)
    override fun fetch(resource: CatalogResource) = client.fetch(resource)
    override fun cancelActiveRequest() = client.cancelActiveRequest()
    override fun close() = client.close()
    override fun toString() = "AndroidAbemaSlotMetadataClient(redacted)"
}
