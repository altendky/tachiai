package net.fstab.tachiai.provider.abema.account

import android.app.Application
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import net.fstab.tachiai.feature.connections.ManageStreamsActivity
import net.fstab.tachiai.feature.connections.StreamManagementController
import net.fstab.tachiai.presentation.PrototypeService
import net.fstab.tachiai.presentation.defaultProviderInstanceId
import net.fstab.tachiai.presentation.SourceRouteMode
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.platform.diagnostics.FailureJournalCodec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

// Explicit real-session observation. No credential/code/button entry, clock
// change, fabricated expiry, browser reset, Forget or configured-source write.
// Default instrumentation skips before private reads, binding or navigation.
class AbemaAutomaticRenewalDeviceTest {
    companion object { private val retained = mutableListOf<Any>() }
    @Test fun optInLocalExpiryReadinessWithoutProviderAccess() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tachiaiAutomaticAbemaRenewal") == "preflight")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(Application.getProcessName() == context.packageName)
        val began = SystemClock.elapsedRealtime()
        val binding = androidAbemaAccountConnectionBinding(context, defaultProviderInstanceId(PrototypeService.ABEMA))
        val owner = binding.owner()
        val snapshot = binding.store.readSnapshot()
        val now = System.currentTimeMillis()
        val result = when {
            !binding.sameOwner(owner) || !binding.store.usable(snapshot.revision) -> "STALE"
            snapshot.record == null -> "MISSING"
            abemaRenewalSnapshotMatches(snapshot, snapshot, now) -> "EXPIRED_READY"
            snapshot.record.grant.expiresAtMs > now && now >= snapshot.record.acceptedWallMs -> "UNEXPIRED"
            else -> "INVALID"
        }
        check(SystemClock.elapsedRealtime() - began in 0 until 5_000)
        Log.i("TachiaiAbemaRenewalProbe", "localReadiness=$result providerRequests=NONE")
    }

    @Test fun optInExpiredGrantRenewsWithoutClicksAndResumesOriginalDiscovery() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tachiaiAutomaticAbemaRenewal") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(Application.getProcessName() == context.packageName)
        val verifiedAvd = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name")
        ).use { input ->
            val bytes = ByteArray(129)
            var size = 0
            while (size < bytes.size) {
                val count = input.read(bytes, size, bytes.size - size)
                if (count < 0) break
                size += count
            }
            size <= 128 && String(bytes, 0, size, Charsets.UTF_8).trim() == "tachiai-dev"
        }
        assumeTrue("Requires verified persistent tachiai-dev", verifiedAvd)
        observe()
    }

    private fun observe() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val began = SystemClock.elapsedRealtime()
        val deadline = began + 120_000
        val evidence = AbemaRenewalProbeEvidence()
        var before: Map<String, ByteArray>? = null
        var accountFile: String? = null
        var scenario: ActivityScenario<ManageStreamsActivity>? = null
        var hostLaunchAttempted = false
        var observation: AbemaRenewalObservation? = null
        var replaced = false
        var discovery = false
        var hostClosed = false
        var preservationChecked = false
        var otherRecordsUnchanged = false
        var changedAccount = false
        var resourcesRetained = false
        var refusal: AbemaAccountReadinessResult? = null
        try {
            val instance = defaultProviderInstanceId(PrototypeService.ABEMA)
            accountFile = "no_backup/abema-account-$instance.enc"
            val baseline = protectedRecords(context.noBackupFilesDir, File(context.applicationInfo.dataDir, "shared_prefs"))
            before = baseline
            evidence.phase = AbemaRenewalProbeEvidence.Phase.OWNER
            val binding = androidAbemaAccountConnectionBinding(context, instance)
            val owner = binding.owner()
            evidence.require(owner.profile != null && owner.instance.setup.route?.mode == SourceRouteMode.SAVED_CONNECTION,
                AbemaRenewalProbeEvidence.Guard.ROUTE)
            val expected = binding.store.readSnapshot()
            evidence.require(expected.record != null, AbemaRenewalProbeEvidence.Guard.SNAPSHOT)
            evidence.require(binding.sameOwner(owner) && binding.store.usable(expected.revision), AbemaRenewalProbeEvidence.Guard.OWNER)
            evidence.phase = AbemaRenewalProbeEvidence.Phase.EXPIRED_INPUT
            evidence.require(abemaRenewalSnapshotMatches(expected, expected, System.currentTimeMillis()), AbemaRenewalProbeEvidence.Guard.EXPIRED_INPUT)
            evidence.require(accountFile in baseline, AbemaRenewalProbeEvidence.Guard.ACCOUNT_FILE)
            val probe = AbemaRenewalObservation(expected,
                { AndroidAbemaRenewalReadiness.completion(instance, owner.proof(), expected) },
                { binding.sameOwner(owner) }, binding.store::readSnapshot, binding.store::usable,
                { abemaReplacementReceiptCurrent(binding.store, it) }, System::currentTimeMillis)
            observation = probe
            evidence.phase = AbemaRenewalProbeEvidence.Phase.HOST_LAUNCH
            hostLaunchAttempted = true
            val host = ActivityScenario.launch<ManageStreamsActivity>(Intent(context, ManageStreamsActivity::class.java)
                .putExtra(ManageStreamsActivity.INSTANCE_ID, instance))
            scenario = host
            evidence.phase = AbemaRenewalProbeEvidence.Phase.COMPLETION
            while (SystemClock.elapsedRealtime() < deadline && !replaced && refusal == null) {
                when (probe.poll()) {
                    AbemaRenewalObservation.Step.REPLACED -> replaced = true
                    AbemaRenewalObservation.Step.REFUSED -> evidence.require(false, AbemaRenewalProbeEvidence.Guard.COMPLETION)
                    AbemaRenewalObservation.Step.WAIT -> Unit
                }
                host.onActivity { activity ->
                    val text = ManageStreamsActivity::class.java.getDeclaredField("message\$delegate")
                        .apply { isAccessible = true }.get(activity).let { it as MutableState<*> }.value as? String
                    refusal = abemaRenewalTerminalReadiness(text)
                }
                if (!replaced && refusal == null) Thread.sleep(100)
            }
            if (!replaced && refusal == null) probe.deadline()
            evidence.require(refusal == null, AbemaRenewalProbeEvidence.Guard.HOST_REFUSAL)
            evidence.require(replaced, AbemaRenewalProbeEvidence.Guard.DEADLINE)
            evidence.phase = AbemaRenewalProbeEvidence.Phase.DISCOVERY
            while (replaced && SystemClock.elapsedRealtime() < deadline && !discovery) {
                host.onActivity { activity ->
                    val state = controller(activity)?.state?.value
                    discovery = state != null && state.instance.id == instance && !state.loading && !state.saving &&
                        !state.storageFailed && state.failure == null && state.capabilities != null && state.entries.isNotEmpty()
                }
                if (!discovery) Thread.sleep(100)
            }
            evidence.require(discovery, AbemaRenewalProbeEvidence.Guard.DISCOVERY)
            evidence.phase = AbemaRenewalProbeEvidence.Phase.FINAL_REPLACEMENT
            evidence.require(probe.poll() == AbemaRenewalObservation.Step.REPLACED, AbemaRenewalProbeEvidence.Guard.COMPLETION)
            evidence.require(SystemClock.elapsedRealtime() in began until deadline, AbemaRenewalProbeEvidence.Guard.DEADLINE)
        } catch (_: Throwable) {
            evidence.fail(AbemaRenewalProbeEvidence.Guard.EXCEPTION)
        } finally {
            evidence.finish(close = {
                val owned = scenario
                if (owned == null) {
                    hostClosed = !hostLaunchAttempted
                    evidence.require(hostClosed, AbemaRenewalProbeEvidence.Guard.HOST_CLOSE)
                }
                else {
                    hostClosed = boundedProbeCheck(10_000, {
                        synchronized(retained) { retained.add(owned) }
                        resourcesRetained = true
                    }) { owned.close(); true } == true
                    evidence.require(hostClosed, AbemaRenewalProbeEvidence.Guard.HOST_CLOSE)
                }
            }, preservation = {
                val baseline = before
                val selected = accountFile
                val comparison = boundedProbeCheck(5_000, { resourcesRetained = true }) {
                    val after = protectedRecords(context.noBackupFilesDir, File(context.applicationInfo.dataDir, "shared_prefs"))
                    if (baseline != null && selected != null) {
                        val unchanged = baseline.keys == after.keys && baseline.filterKeys { it != selected }.all { (name, digest) ->
                            after[name]?.let { MessageDigest.isEqual(digest, it) } == true
                        }
                        val changed = baseline[selected]?.let { old -> after[selected]?.let { !MessageDigest.isEqual(old, it) } ?: true }
                            ?: after.containsKey(selected)
                        Preservation(unchanged, changed, !replaced || observation?.poll() == AbemaRenewalObservation.Step.REPLACED)
                    } else null
                }
                preservationChecked = comparison != null
                otherRecordsUnchanged = comparison?.otherRecordsUnchanged == true
                changedAccount = comparison?.accountRecordChanged == true
                evidence.require(preservationChecked && otherRecordsUnchanged && comparison?.replacementCurrent == true &&
                    (if (replaced) changedAccount else !changedAccount), AbemaRenewalProbeEvidence.Guard.PRESERVATION)
                evidence.require(SystemClock.elapsedRealtime() in began until deadline, AbemaRenewalProbeEvidence.Guard.DEADLINE)
            })
            Log.i("TachiaiAbemaRenewalProbe", "outcome=${if (evidence.firstFailedGuard == AbemaRenewalProbeEvidence.Guard.NONE) "PASS" else "FAIL"} " +
                "firstFailedPhase=${if (evidence.firstFailedGuard == AbemaRenewalProbeEvidence.Guard.NONE) "NONE" else evidence.firstFailedPhase.name} " +
                "firstFailedGuard=${evidence.firstFailedGuard.name} completion=${observation?.observedCompletion?.name ?: "NOT_OBSERVED"} " +
                "replacementGuard=${observation?.firstFailedGuard?.name ?: "NONE"} replacementVerified=$replaced " +
                "originalDiscoveryReady=$discovery hostClosed=$hostClosed preservationChecked=$preservationChecked " +
                "otherRecordsUnchanged=$otherRecordsUnchanged accountRecordChanged=$changedAccount " +
                "readiness=${refusal?.name ?: "NONE"} cleanupUnknown=${!hostClosed} " +
                "hostOwnershipMissing=${hostLaunchAttempted && scenario == null} resourcesRetained=$resourcesRetained")
        }
        if (evidence.firstFailedGuard != AbemaRenewalProbeEvidence.Guard.NONE)
            throw AssertionError("Automatic renewal observation did not satisfy closed completion, account, discovery and preservation checks")
    }

    private class Preservation(val otherRecordsUnchanged: Boolean, val accountRecordChanged: Boolean, val replacementCurrent: Boolean)
    private fun <T> boundedProbeCheck(timeoutMs: Long, retainedResource: () -> Unit, action: () -> T): T? {
        val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "abema-probe-cleanup").apply { isDaemon = true } }
        val result = worker.submit(Callable { action() })
        worker.shutdown()
        return try { result.get(timeoutMs, TimeUnit.MILLISECONDS) }
        catch (_: Throwable) {
            retainedResource()
            synchronized(retained) { retained.add(worker); retained.add(result) }
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun controller(activity: ManageStreamsActivity) = ManageStreamsActivity::class.java
        .getDeclaredField("controller\$delegate").apply { isAccessible = true }
        .get(activity).let { it as MutableState<StreamManagementController?> }.value

    private fun protectedRecords(noBackup: File, preferences: File): Map<String, ByteArray> {
        val began = SystemClock.elapsedRealtime()
        val result = linkedMapOf<String, ByteArray>()
        for ((prefix, directory) in listOf("no_backup" to noBackup, "shared_prefs" to preferences)) {
            check(directory.isDirectory && directory.canRead() && !Files.isSymbolicLink(directory.toPath()))
            val files = checkNotNull(directory.listFiles()).filter { it.isFile }
            check(files.size <= 64)
            for (file in files) {
                check(SystemClock.elapsedRealtime() - began in 0 until 5_000)
                check(!Files.isSymbolicLink(file.toPath()) && abemaRenewalRecordWithinLimit(prefix, file.name, file.length()))
                check(file.length() > 0 || file.name.endsWith(".lock"))
                val bytes = file.readBytes()
                try {
                    check(abemaRenewalRecordWithinLimit(prefix, file.name, bytes.size.toLong()))
                    result["$prefix/${file.name}"] = MessageDigest.getInstance("SHA-256").digest(bytes)
                }
                finally { bytes.fill(0) }
            }
        }
        check(result.size in 1..64)
        return result
    }
}

internal fun abemaRenewalRecordWithinLimit(prefix: String, name: String, size: Long): Boolean {
    val limit = if (prefix == "no_backup" && name == "failure-diagnostics.tsv") FailureJournalCodec.MAX_BYTES
        else PRIVATE_SECRET_LIMIT + 29
    return size in 0..limit.toLong()
}

internal fun abemaRenewalTerminalReadiness(message: String?): AbemaAccountReadinessResult? =
    listOf(AbemaAccountReadinessResult.FAILURE, AbemaAccountReadinessResult.TEMPORARY_FAILURE,
        AbemaAccountReadinessResult.INTERACTION_REQUIRED, AbemaAccountReadinessResult.STORAGE_FAILURE)
        .singleOrNull { message == abemaAccountReadinessMessage(it) }

class AbemaAutomaticRenewalRecordGuardDeviceTest {
    @Test fun onlyKnownTerminalHostMessagesBecomeClosedRefusals() {
        for (status in listOf(AbemaAccountReadinessResult.FAILURE, AbemaAccountReadinessResult.TEMPORARY_FAILURE,
            AbemaAccountReadinessResult.INTERACTION_REQUIRED, AbemaAccountReadinessResult.STORAGE_FAILURE)) {
            assertTrue(abemaRenewalTerminalReadiness(abemaAccountReadinessMessage(status)) == status)
        }
        for (status in listOf(AbemaAccountReadinessResult.READY, AbemaAccountReadinessResult.HANDOFF)) {
            assertTrue(abemaRenewalTerminalReadiness(abemaAccountReadinessMessage(status)) == null)
        }
        assertTrue(abemaRenewalTerminalReadiness(null) == null)
        assertTrue(abemaRenewalTerminalReadiness("synthetic provider text") == null)
    }
    @Test fun diagnosticJournalKeepsItsProductionBoundWithoutRelaxingEncryptedRecords() {
        assertTrue(abemaRenewalRecordWithinLimit("no_backup", "failure-diagnostics.tsv", 8_222))
        assertTrue(abemaRenewalRecordWithinLimit("no_backup", "failure-diagnostics.tsv", 65_536))
        assertFalse(abemaRenewalRecordWithinLimit("no_backup", "failure-diagnostics.tsv", 65_537))
        assertTrue(abemaRenewalRecordWithinLimit("no_backup", "abema-account-synthetic.enc", 8_221))
        assertFalse(abemaRenewalRecordWithinLimit("no_backup", "abema-account-synthetic.enc", 8_222))
    }
    @Test fun otherNamespacesAndJournalLookalikesKeepTheSecretBound() {
        for ((prefix, name) in listOf("shared_prefs" to "failure-diagnostics.tsv",
            "no_backup" to "failure-diagnostics.tsv.new", "no_backup" to "FAILURE-diagnostics.tsv")) {
            assertFalse(abemaRenewalRecordWithinLimit(prefix, name, 8_222))
        }
        assertFalse(abemaRenewalRecordWithinLimit("no_backup", "failure-diagnostics.tsv", -1))
    }
}
