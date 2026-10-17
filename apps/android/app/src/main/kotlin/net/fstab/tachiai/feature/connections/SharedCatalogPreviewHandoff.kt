package net.fstab.tachiai.feature.connections

import android.content.Context
import android.content.Intent
import android.os.Bundle
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*

// A normalized public entry, held only until its first ready preview or discard.
// The exported share boundary owns provider-specific input parsing.
internal class SharedCatalogPreviewHandoff private constructor(
    val instanceId: String,
    private var pending: CatalogEntry?,
) {
    val providerId: ProviderId = checkNotNull(pending).resource.providerId
    fun matches(owner: ProviderInstance) = owner.id == instanceId &&
        providerId == ProviderId(owner.service.name.lowercase(java.util.Locale.ROOT))
    @Synchronized fun take(owner: ProviderInstance): CatalogEntry? {
        val entry = pending
        pending = null
        return entry?.takeIf { matches(owner) }
    }
    @Synchronized fun discard() { pending = null }
    override fun toString() = "SharedCatalogPreviewHandoff(redacted)"

    companion object {
        private const val PREFIX = "SHARED_CATALOG_PREVIEW_"
        private const val PRESENT = PREFIX + "PRESENT"
        private const val PROVIDER = PREFIX + "PROVIDER"
        private const val KIND = PREFIX + "KIND"
        private const val IDENTITY = PREFIX + "IDENTITY"
        private const val INTENT = PREFIX + "INTENT"
        private const val TITLE = PREFIX + "TITLE"
        private const val AVAILABILITY = PREFIX + "AVAILABILITY"
        private const val SCHEDULED = PREFIX + "SCHEDULED"

        internal fun create(instanceId: String, entry: CatalogEntry): SharedCatalogPreviewHandoff {
            require(validProviderInstanceId(instanceId))
            return SharedCatalogPreviewHandoff(instanceId, entry)
        }

        fun launchIntent(context: Context, instanceId: String, entry: CatalogEntry): Intent {
            create(instanceId, entry)
            return Intent(context, ManageStreamsActivity::class.java)
                .putExtra(ManageStreamsActivity.INSTANCE_ID, instanceId)
                .putExtra(PRESENT, true)
                .putExtra(PROVIDER, entry.resource.providerId.value)
                .putExtra(KIND, entry.resource.kind)
                .putExtra(IDENTITY, entry.resource.identity)
                .putExtra(INTENT, entry.resource.intent.name)
                .putExtra(TITLE, entry.title)
                .putExtra(AVAILABILITY, entry.availability.name)
                .also { intent -> entry.scheduledStartEpochMs?.let { intent.putExtra(SCHEDULED, it) } }
        }

        fun isPresent(intent: Intent): Boolean = try {
            intent.extras?.keySet()?.any { it.startsWith(PREFIX) } == true
        } catch (_: Exception) { true }

        // Clear the entire incoming payload even if its fields cannot be read.
        // The Activity separately retains only its validated owner UUID.
        fun consume(intent: Intent, allowPreview: Boolean): SharedCatalogPreviewHandoff? = try {
            if (!allowPreview || !intent.hasExtra(PRESENT)) null else {
                val extras = intent.extras ?: error("Missing preview")
                check(extras.get(PRESENT) == true)
                check(extras.keySet().all { it in setOf(ManageStreamsActivity.INSTANCE_ID, PRESENT, PROVIDER,
                    KIND, IDENTITY, INTENT, TITLE, AVAILABILITY, SCHEDULED) })
                check(intent.data == null && intent.clipData == null)
                fun text(key: String, maximum: Int): String =
                    (extras.get(key) as? String)?.takeIf { it.length in 1..maximum } ?: error("Invalid preview")
                val instance = text(ManageStreamsActivity.INSTANCE_ID, 36)
                val resource = CatalogResource(ProviderId(text(PROVIDER, 32)), text(KIND, 32), text(IDENTITY, 256),
                    CatalogIntent.valueOf(text(INTENT, 16)))
                val scheduled = if (extras.containsKey(SCHEDULED)) extras.get(SCHEDULED) as? Long
                    ?: error("Invalid preview") else null
                create(instance, CatalogEntry(resource, text(TITLE, 160),
                    CatalogAvailability.valueOf(text(AVAILABILITY, 16)), scheduled))
            }
        } catch (_: Exception) { null }
        finally {
            intent.replaceExtras(null as Bundle?)
            intent.data = null
            intent.clipData = null
        }
    }
}
