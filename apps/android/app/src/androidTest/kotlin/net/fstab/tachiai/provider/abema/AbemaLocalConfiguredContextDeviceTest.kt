package net.fstab.tachiai.provider.abema

import android.app.Application
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.feature.connections.configuredSourceStore
import net.fstab.tachiai.feature.connections.legacyProviderSettings
import net.fstab.tachiai.feature.connections.providerInstanceStore
import net.fstab.tachiai.feature.connections.sourceSetupStore
import net.fstab.tachiai.feature.connections.streamQualityStore
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.platform.diagnostics.FailureJournalCodec
import net.fstab.tachiai.presentation.PrototypeService
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.presentation.defaultProviderInstanceId
import net.fstab.tachiai.presentation.legacyConfiguredSources
import net.fstab.tachiai.provider.abema.account.abemaRenewalSnapshotMatches
import net.fstab.tachiai.provider.abema.account.androidAbemaAccountConnectionBinding
import net.fstab.tachiai.provider.catalog.CatalogIntent
import net.fstab.tachiai.provider.catalog.CatalogResource
import org.junit.Assume.assumeTrue
import org.junit.Test

// Local configured-list evidence, not picker selection, provider validation or
// entitlement. No Activity, route/session, catalog, readiness service or write
// method is invoked. Default instrumentation skips before any private read.
class AbemaLocalConfiguredContextDeviceTest {
    // Exact public identities of the web trials, independent of future Android defaults.
    private val webReplaySeed = CatalogResource(ProviderId("abema"), "episode", "394-72_s10_p8529", CatalogIntent.VIDEO)
    private val webNews = CatalogResource(ProviderId("abema"), "channel", "abema-news", CatalogIntent.CHANNEL)
    private enum class Phase { BASELINE, SETUP, CONFIGURED, OWNER, ACCOUNT, FINAL_OWNER, PRESERVATION }
    private enum class Origin { NOT_READ, LEGACY_DERIVED, COMMITTED_RECORD }
    private enum class Readiness { NOT_READ, STALE, MISSING, EXPIRED_READY, UNEXPIRED, INVALID }

    @Test(timeout = 20_000) fun optInDefaultAbemaLocalConfiguredContextWithoutProviderAccess() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("tachiaiReadOnlyAbemaConfiguredContext") == "true")
        val context = instrumentation.targetContext
        assumeTrue(BuildConfig.DEBUG && !BuildConfig.LOCAL_INSPECTION && Build.VERSION.SDK_INT >= 28 &&
            Application.getProcessName() == context.packageName)
        val verifiedAvd = try {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
                .executeShellCommand("getprop ro.boot.qemu.avd_name")).use { input ->
                val bytes = ByteArray(129); var size = 0
                while (size < bytes.size) {
                    val count = input.read(bytes, size, bytes.size - size)
                    if (count < 0) break
                    size += count
                }
                size <= 128 && String(bytes, 0, size, Charsets.UTF_8).trim() == "tachiai-dev"
            }
        } catch (_: Throwable) { false }
        assumeTrue("Requires verified persistent tachiai-dev", verifiedAvd)

        val began = SystemClock.elapsedRealtime()
        fun inBudget() = SystemClock.elapsedRealtime() - began in 0 until 10_000
        var phase = Phase.BASELINE
        var failurePhase: Phase? = null
        var baseline: Map<String, ByteArray>? = null
        var origin = Origin.NOT_READ
        var registryOrigin = Origin.NOT_READ
        var count = 0
        var channels = 0
        var episodes = 0
        var slots = 0
        var collections = 0
        var other = 0
        var legacyReplayMatches = 0
        var newsPresent = false
        var routeCategory = "NOT_READ"
        var ownerStable = false
        var readiness = Readiness.NOT_READ
        var observed = false
        var preservationChecked = false
        var recordsUnchanged = false
        try {
            baseline = protectedRecords(context.noBackupFilesDir, File(context.applicationInfo.dataDir, "shared_prefs"))
            phase = Phase.SETUP
            check(inBudget())
            val id = defaultProviderInstanceId(PrototypeService.ABEMA)
            val setups = sourceSetupStore(context).read()
            val registry = providerInstanceStore(context).readSnapshot { legacyProviderSettings(context) }
            registryOrigin = if (registry.committed) Origin.COMMITTED_RECORD else Origin.LEGACY_DERIVED
            val instance = checkNotNull(registry.instances.singleOrNull { it.id == id && it.service == PrototypeService.ABEMA })
            val quality = streamQualityStore(context).read()
            phase = Phase.CONFIGURED
            check(inBudget())
            var legacyDerived = false
            val sources = configuredSourceStore(context, instance).read {
                legacyDerived = true
                legacyConfiguredSources(instance, setups, quality)
            }
            origin = if (legacyDerived) Origin.LEGACY_DERIVED else Origin.COMMITTED_RECORD
            count = sources.size
            for (source in sources) {
                val resource = source.entry.resource
                when {
                    resource.intent == CatalogIntent.COLLECTION -> collections++
                    resource.kind == "channel" -> channels++
                    resource.kind == "episode" -> episodes++
                    resource.kind == "slot" -> slots++
                    else -> other++
                }
                if (resource == webReplaySeed) legacyReplayMatches++
                if (resource == webNews) newsPresent = true
            }
            phase = Phase.OWNER
            check(inBudget())
            val binding = androidAbemaAccountConnectionBinding(context, id)
            val owner = binding.owner()
            routeCategory = checkNotNull(owner.instance.setup.route).mode.name
            check(owner.configuredInstance == instance)
            phase = Phase.ACCOUNT
            val snapshot = binding.store.readSnapshot()
            val now = System.currentTimeMillis()
            // Same local-only predicates as the existing expiry preflight.
            // Grant/account values remain inside the target application.
            readiness = when {
                !binding.sameOwner(owner) || !binding.store.usable(snapshot.revision) -> Readiness.STALE
                snapshot.record == null -> Readiness.MISSING
                abemaRenewalSnapshotMatches(snapshot, snapshot, now) -> Readiness.EXPIRED_READY
                snapshot.record.grant.expiresAtMs > now && now >= snapshot.record.acceptedWallMs -> Readiness.UNEXPIRED
                else -> Readiness.INVALID
            }
            phase = Phase.FINAL_OWNER
            ownerStable = binding.sameOwner(owner) && binding.store.usable(snapshot.revision)
            check(ownerStable && inBudget())
            observed = true
        } catch (_: Throwable) {
            failurePhase = phase
        } finally {
            phase = Phase.PRESERVATION
            try {
                val after = protectedRecords(context.noBackupFilesDir, File(context.applicationInfo.dataDir, "shared_prefs"))
                try {
                    val before = baseline
                    preservationChecked = before != null
                    recordsUnchanged = before != null && before.keys == after.keys && before.all { (name, digest) ->
                        after[name]?.let { MessageDigest.isEqual(digest, it) } == true
                    }
                    check(preservationChecked && recordsUnchanged && SystemClock.elapsedRealtime() - began in 0 until 15_000)
                } finally { after.values.forEach { it.fill(0) } }
            } catch (_: Throwable) {
                if (failurePhase == null) failurePhase = phase
            }
            baseline?.values?.forEach { it.fill(0) }
            val success = observed && failurePhase == null && ownerStable && preservationChecked && recordsUnchanged
            Log.i("TachiaiAbemaLocalContext", "outcome=${if (success) "CONTEXT_OBSERVED" else "FAILED"} " +
                "failurePhase=${failurePhase?.name ?: "NONE"} configuredOrigin=${origin.name} registryOrigin=${registryOrigin.name} " +
                "configuredCount=$count channels=$channels episodes=$episodes slots=$slots collections=$collections other=$other " +
                "legacyReplayPresent=${legacyReplayMatches > 0} legacyReplayMatchingCount=$legacyReplayMatches newsPresent=$newsPresent " +
                "routeContext=CONFIGURED_FIRST_CANDIDATE routeCategory=$routeCategory ownerStable=$ownerStable localReadiness=${readiness.name} " +
                "preservationChecked=$preservationChecked recordsUnchanged=$recordsUnchanged pickerSelection=UNKNOWN " +
                "effectiveNetwork=UNKNOWN entitlement=UNKNOWN providerRequests=NONE")
        }
        if (!observed || failurePhase != null || !ownerStable || !preservationChecked || !recordsUnchanged)
            throw AssertionError("Local ABEMA configured-context observation failed closed checks")
    }

    private fun protectedRecords(noBackup: File, preferences: File): Map<String, ByteArray> {
        val began = SystemClock.elapsedRealtime()
        val result = linkedMapOf<String, ByteArray>()
        try {
            for ((prefix, directory) in listOf("no_backup" to noBackup, "shared_prefs" to preferences)) {
                check(!Files.isSymbolicLink(directory.toPath()))
                // An absent target-app preference directory contains no legacy
                // settings. Never inspect the instrumentation package's prefs.
                if (prefix == "shared_prefs" && !directory.exists()) continue
                check(directory.isDirectory && directory.canRead())
                val files = checkNotNull(directory.listFiles()).filter { it.isFile }
                check(files.size <= 64 && files.none { it.name.endsWith(".enc.bak") || it.name.endsWith(".enc.new") || it.name.endsWith(".xml.bak") })
                for (file in files) {
                    val limit = if (prefix == "no_backup" && file.name == "failure-diagnostics.tsv") FailureJournalCodec.MAX_BYTES
                        else PRIVATE_SECRET_LIMIT + 29
                    check(SystemClock.elapsedRealtime() - began in 0 until 5_000 && file.canRead() &&
                        !Files.isSymbolicLink(file.toPath()) && file.length() in 0..limit.toLong() &&
                        (file.length() > 0 || file.name.endsWith(".lock")))
                    val bytes = file.readBytes()
                    try {
                        check(bytes.size <= limit && (bytes.isNotEmpty() || file.name.endsWith(".lock")))
                        result["$prefix/${file.name}"] = MessageDigest.getInstance("SHA-256").digest(bytes)
                    } finally { bytes.fill(0) }
                }
            }
            check(result.size in 1..64)
            return result
        } catch (error: Throwable) { result.values.forEach { it.fill(0) }; throw error }
    }
}
