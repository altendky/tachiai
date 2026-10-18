package net.fstab.tachiai.provider.twitch

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import net.fstab.tachiai.platform.network.connectionProfileStore
import net.fstab.tachiai.feature.connections.legacyProviderSettings
import net.fstab.tachiai.feature.connections.providerInstanceStore
import net.fstab.tachiai.platform.storage.AndroidPrivateSecretStore
import net.fstab.tachiai.presentation.PrototypeService
import net.fstab.tachiai.presentation.SourceRouteMode
import net.fstab.tachiai.provider.twitch.catalog.TwitchCatalogGrantStore
import net.fstab.tachiai.provider.twitch.catalog.captureTwitchCatalogRouteOwner
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Manual, read-only saved-store timing. No transport, mutation, grant output or Activity. */
class CatalogAdmissionCostDeviceTest {
    @Test(timeout = 60_000) fun measureProtectedReadsEquivalentToOneMediaChunkAdmission() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowSavedStoreReads") == "true")
        val context = instrumentation.targetContext.applicationContext
        try {
            val registry = providerInstanceStore(context)
            val profiles = connectionProfileStore(context)
            fun instances() = registry.read { legacyProviderSettings(context) }
            val instance = instances().single { it.service == PrototypeService.TWITCH }
            // Imported candidates (even unselected fallbacks) add protected
            // profile snapshots; this exact read-count model is SYSTEM-only.
            check(instance.setup.routes.isNotEmpty() && instance.setup.routes.all {
                it.mode == SourceRouteMode.SYSTEM && it.connectionId == null
            })
            val store = TwitchCatalogGrantStore(AndroidPrivateSecretStore.twitchCatalogInstance(context, instance.id),
                instance.id, File(context.noBackupFilesDir, "twitch-catalog-${instance.id}.lock"))
            val grant = checkNotNull(store.read())
            check(store.isStoredCurrent(grant))
            val original = captureTwitchCatalogRouteOwner(instance.id, ::instances, profiles::snapshot)
            // Uses readOwner's exact capture/observation seam, without transport or maintenance.
            fun owner() {
                var observations = 0
                check(original.presentation.sameOwnership(
                    captureTwitchCatalogRouteOwner(instance.id, ::instances, profiles::snapshot, original.proof,
                        onInstanceObserved = { observations++ }).presentation))
                check(observations == 2)
            }
            fun measure(name: String, action: () -> Unit) {
                repeat(2) { action() }
                val timings = List(12) {
                    val start = SystemClock.elapsedRealtimeNanos()
                    action()
                    SystemClock.elapsedRealtimeNanos() - start
                }.sorted()
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("readOnlyCost", name)
                    putInt("iterations", timings.size)
                    putLong("meanUs", timings.sum() / timings.size / 1_000)
                    putLong("medianUs", timings[timings.size / 2] / 1_000)
                    putLong("maximumUs", timings.last() / 1_000)
                })
            }
            measure("OWNER_TWO_PROTECTED_REGISTRY_READS", ::owner)
            measure("DURABLE_GRANT_READ") { check(store.isStoredCurrent(grant)) }
            // Two ownership checks and one durable grant read per admission;
            // admission runs before and after each positive media body read.
            measure("MODELED_TEN_PROTECTED_READS_PER_CHUNK") {
                repeat(2) { owner(); owner(); check(store.isStoredCurrent(grant)) }
            }
        } catch (_: Throwable) {
            throw AssertionError("READ_ONLY_COST_PROBE_FAILED")
        }
    }
}
