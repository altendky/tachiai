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
        try { observe() }
        catch (_: Throwable) { throw AssertionError("Automatic renewal observation did not satisfy closed account, discovery and preservation checks") }
    }

    private fun observe() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val began = SystemClock.elapsedRealtime()
        val deadline = began + 120_000
        val before = protectedRecords(context.noBackupFilesDir, File(context.applicationInfo.dataDir, "shared_prefs"))
        val instance = defaultProviderInstanceId(PrototypeService.ABEMA)
        val binding = androidAbemaAccountConnectionBinding(context, instance)
        val owner = binding.owner()
        check(owner.profile != null && owner.instance.setup.route?.mode == SourceRouteMode.SAVED_CONNECTION)
        val expected = binding.store.readSnapshot()
        val old = checkNotNull(expected.record)
        check(binding.sameOwner(owner) && binding.store.usable(expected.revision))
        check(abemaRenewalSnapshotMatches(expected, expected, System.currentTimeMillis()))
        val accountFile = "no_backup/abema-account-$instance.enc"
        check(accountFile in before)
        var replaced = false
        var discovery = false
        var hostClosed = false
        var refusal: AbemaAccountReadinessResult? = null
        val scenario = ActivityScenario.launch<ManageStreamsActivity>(Intent(context, ManageStreamsActivity::class.java)
            .putExtra(ManageStreamsActivity.INSTANCE_ID, instance))
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                val current = binding.store.readSnapshot()
                val record = current.record
                if (record != null && current.revision > expected.revision) {
                    check(binding.sameOwner(owner) && binding.store.usable(current.revision))
                    check(record.generation == old.generation + 1 && record.grant.userId == old.grant.userId)
                    check(record.grant.bearer != old.grant.bearer && record.grant.expiresAtMs > old.grant.expiresAtMs)
                    check(record.acceptedWallMs >= old.acceptedWallMs && record.grant.expiresAtMs > System.currentTimeMillis())
                    check(decodedAbemaGrant(record.grant.bearer, record.grant.userId, System.currentTimeMillis()).expiresAtMs == record.grant.expiresAtMs)
                    replaced = true
                    break
                }
                check(binding.sameOwner(owner) && binding.store.usable(expected.revision))
                scenario.onActivity { activity ->
                    val text = ManageStreamsActivity::class.java.getDeclaredField("message\$delegate")
                        .apply { isAccessible = true }.get(activity).let { it as MutableState<*> }.value as? String
                    refusal = abemaRenewalTerminalReadiness(text)
                }
                if (refusal != null) break
                Thread.sleep(100)
            }
            check(replaced || refusal != null)
            while (replaced && SystemClock.elapsedRealtime() < deadline && !discovery) {
                scenario.onActivity { activity ->
                    val state = controller(activity)?.state?.value
                    discovery = state != null && state.instance.id == instance && !state.loading && !state.saving &&
                        !state.storageFailed && state.failure == null && state.capabilities != null && state.entries.isNotEmpty()
                }
                if (!discovery) Thread.sleep(100)
            }
            check((!replaced || discovery) && binding.sameOwner(owner))
        } finally {
            scenario.close()
            hostClosed = true
        }
        val after = protectedRecords(context.noBackupFilesDir, File(context.applicationInfo.dataDir, "shared_prefs"))
        val unchanged = before.keys == after.keys && before.filterKeys { it != accountFile }.all { (name, digest) ->
            after[name]?.let { MessageDigest.isEqual(digest, it) } == true
        }
        val changedAccount = after[accountFile]?.let { !MessageDigest.isEqual(checkNotNull(before[accountFile]), it) } == true
        if (!replaced) {
            check(refusal != null && unchanged && !changedAccount && hostClosed &&
                SystemClock.elapsedRealtime() < deadline && binding.sameOwner(owner) && binding.store.usable(expected.revision) &&
                abemaRenewalSnapshotMatches(expected, binding.store.readSnapshot(), System.currentTimeMillis()))
            check(SystemClock.elapsedRealtime() in began until deadline)
            Log.i("TachiaiAbemaRenewalProbe", "outcome=RENEWAL_REFUSED readiness=${refusal!!.name} " +
                "savedRecordsUnchanged=true count=${before.size} hostClosed=$hostClosed")
            throw AssertionError("Same-account renewal was not observed")
        }
        check(unchanged && changedAccount && hostClosed && SystemClock.elapsedRealtime() < deadline && binding.sameOwner(owner))
        Log.i("TachiaiAbemaRenewalProbe", "outcome=SAME_ACCOUNT_RENEWED_NO_CLICKS originalDiscoveryReady=$discovery " +
            "otherRecordsUnchanged=$unchanged count=${before.size} hostClosed=$hostClosed routeMode=${owner.instance.setup.route!!.mode.name}")
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
