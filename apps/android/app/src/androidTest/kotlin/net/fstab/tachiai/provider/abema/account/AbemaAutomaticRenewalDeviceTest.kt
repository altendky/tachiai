package net.fstab.tachiai.provider.abema.account

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import androidx.lifecycle.Lifecycle
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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

    @SdkSuppress(minSdkVersion = 29)
    @Test fun optInExpiredGrantRenewsWithoutClicksAndResumesOriginalDiscovery() {
        val mode = InstrumentationRegistry.getArguments().getString("tachiaiAutomaticAbemaRenewal")
        assumeTrue(mode in listOf("true", "minimized"))
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
        observe(minimized = mode == "minimized")
    }

    @RequiresApi(29)
    private fun observe(minimized: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = context.applicationContext as Application
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
        var otherMutations: AbemaRenewalOtherMutations? = null
        var familyMutations = emptyList<AbemaRenewalFamilyMutation>()
        var resourcesRetained = false
        var refusal: AbemaAccountReadinessResult? = null
        val background = if (minimized) AbemaRenewalBackgroundObservation() else null
        val trackedHost = AtomicReference<Activity?>()
        var hostTaskId = -1
        var hostComponent: ComponentName? = null
        var callbacks: Application.ActivityLifecycleCallbacks? = null
        var returnedToOriginalHost = false
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
                { abemaReplacementReceiptCurrent(binding.store, it) }, System::currentTimeMillis,
                evidence = { AndroidAbemaRenewalReadiness.evidence(instance, owner.proof(), expected) })
            observation = probe
            evidence.phase = AbemaRenewalProbeEvidence.Phase.HOST_LAUNCH
            if (background != null) {
                callbacks = object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                    override fun onActivityStarted(activity: Activity) { if (activity === trackedHost.get()) background.hostStarted() }
                    override fun onActivityResumed(activity: Activity) { background.activityResumed() }
                    override fun onActivityPaused(activity: Activity) = Unit
                    override fun onActivityStopped(activity: Activity) { if (activity === trackedHost.get()) background.hostStopped() }
                    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
                    override fun onActivityDestroyed(activity: Activity) { if (activity === trackedHost.get()) background.hostDestroyed() }
                }
                application.registerActivityLifecycleCallbacks(callbacks)
            }
            hostLaunchAttempted = true
            val host = ActivityScenario.launch<ManageStreamsActivity>(Intent(context, ManageStreamsActivity::class.java)
                .putExtra(ManageStreamsActivity.INSTANCE_ID, instance))
            scenario = host
            host.onActivity { trackedHost.set(it); hostTaskId = it.taskId; hostComponent = it.componentName }
            if (background != null) {
                evidence.phase = AbemaRenewalProbeEvidence.Phase.MINIMIZING
                while (SystemClock.elapsedRealtime() < deadline) {
                    val step = probe.poll()
                    if (step == AbemaRenewalObservation.Step.REPLACED) replaced = true
                    evidence.require(step != AbemaRenewalObservation.Step.REFUSED, AbemaRenewalProbeEvidence.Guard.COMPLETION)
                    if (probe.observedCompletion != AbemaRenewalReadinessCoordinator.Completion.ABSENT) break
                    Thread.sleep(50)
                }
                evidence.require(host.state == Lifecycle.State.RESUMED && background.begin(probe.observedCompletion),
                    AbemaRenewalProbeEvidence.Guard.BACKGROUND)
                val home = boundedProbeCheck(5_000, { resourcesRetained = true }) {
                    ParcelFileDescriptor.AutoCloseInputStream(
                        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME")
                    ).use { it.read() == -1 }
                }
                evidence.require(home == true, AbemaRenewalProbeEvidence.Guard.BACKGROUND)
                while (SystemClock.elapsedRealtime() < deadline && !background.hostIsStopped &&
                    background.firstFailedGuard == AbemaRenewalBackgroundObservation.Guard.NONE) Thread.sleep(50)
                evidence.require(host.state == Lifecycle.State.CREATED && background.hostIsStopped,
                    AbemaRenewalProbeEvidence.Guard.BACKGROUND)
                val stopped = probe.poll()
                if (stopped == AbemaRenewalObservation.Step.REPLACED) replaced = true
                val stoppedPending = background.afterStop(probe.observedCompletion)
                evidence.require(stopped == AbemaRenewalObservation.Step.WAIT && stoppedPending,
                    AbemaRenewalProbeEvidence.Guard.BACKGROUND)
                evidence.phase = AbemaRenewalProbeEvidence.Phase.BACKGROUND
            }
            evidence.phase = AbemaRenewalProbeEvidence.Phase.COMPLETION
            while (SystemClock.elapsedRealtime() < deadline && !replaced && refusal == null) {
                when (probe.poll()) {
                    AbemaRenewalObservation.Step.REPLACED -> replaced = true
                    AbemaRenewalObservation.Step.REFUSED -> evidence.require(false, AbemaRenewalProbeEvidence.Guard.COMPLETION)
                    AbemaRenewalObservation.Step.WAIT -> Unit
                }
                if (background != null) {
                    evidence.phase = AbemaRenewalProbeEvidence.Phase.BACKGROUND
                    evidence.require(host.state == Lifecycle.State.CREATED && background.sample(probe.observedCompletion, replaced),
                        AbemaRenewalProbeEvidence.Guard.BACKGROUND)
                } else host.onActivity { activity ->
                    val text = ManageStreamsActivity::class.java.getDeclaredField("message\$delegate")
                        .apply { isAccessible = true }.get(activity).let { it as MutableState<*> }.value as? String
                    refusal = abemaRenewalTerminalReadiness(text)
                }
                if (!replaced && refusal == null) Thread.sleep(100)
            }
            if (!replaced && refusal == null) probe.deadline()
            evidence.require(refusal == null, AbemaRenewalProbeEvidence.Guard.HOST_REFUSAL)
            evidence.require(replaced, AbemaRenewalProbeEvidence.Guard.DEADLINE)
            if (background != null) {
                evidence.phase = AbemaRenewalProbeEvidence.Phase.RETURN
                // Close the observation window on the lifecycle callback thread,
                // after any queued resume/start event has had a chance to refuse it.
                var allowed = false
                instrumentation.runOnMainSync { allowed = background.allowReturn() }
                evidence.require(allowed, AbemaRenewalProbeEvidence.Guard.BACKGROUND)
                // ActivityScenario's resume driver only dismisses its own empty
                // Activities; an actual Home requires returning the owned task.
                returnAbemaRenewalTask(context, hostTaskId, checkNotNull(hostComponent))
                while (SystemClock.elapsedRealtime() < deadline && host.state != Lifecycle.State.RESUMED &&
                    host.state != Lifecycle.State.DESTROYED) Thread.sleep(50)
                evidence.require(host.state == Lifecycle.State.RESUMED, AbemaRenewalProbeEvidence.Guard.HOST_RETURN)
                host.onActivity { returnedToOriginalHost = it === trackedHost.get() }
                evidence.require(returnedToOriginalHost && host.state == Lifecycle.State.RESUMED,
                    AbemaRenewalProbeEvidence.Guard.HOST_RETURN)
            }
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
            val backgroundEvidence = background?.freeze()
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
                        // The separately classified public scheduling ledger may
                        // change after accepted renewal. Its bytes were strictly
                        // decoded below; private inventory remains exact.
                        fun privateKeys(records: Map<String, ByteArray>) = records.keys.filter {
                            abemaRenewalRecordFamily(it, selected) != AbemaRenewalRecordFamily.RENEWAL_SCHEDULE
                        }.toSet()
                        val unchanged = privateKeys(baseline) == privateKeys(after) && baseline.filterKeys {
                            it != selected && abemaRenewalRecordFamily(it, selected) != AbemaRenewalRecordFamily.RENEWAL_SCHEDULE
                        }.all { (name, digest) ->
                            after[name]?.let { MessageDigest.isEqual(digest, it) } == true
                        }
                        val changed = baseline[selected]?.let { old -> after[selected]?.let { !MessageDigest.isEqual(old, it) } ?: true }
                            ?: after.containsKey(selected)
                        Preservation(unchanged, changed, !replaced || observation?.poll() == AbemaRenewalObservation.Step.REPLACED,
                            abemaRenewalOtherMutations(baseline, after, selected), abemaRenewalFamilyMutations(baseline, after, selected))
                    } else null
                }
                preservationChecked = comparison != null
                otherRecordsUnchanged = comparison?.otherRecordsUnchanged == true
                changedAccount = comparison?.accountRecordChanged == true
                otherMutations = comparison?.otherMutations
                familyMutations = comparison?.families ?: emptyList()
                evidence.require(preservationChecked && otherRecordsUnchanged && comparison?.replacementCurrent == true &&
                    (if (replaced) changedAccount else !changedAccount), AbemaRenewalProbeEvidence.Guard.PRESERVATION)
                evidence.require(SystemClock.elapsedRealtime() in began until deadline, AbemaRenewalProbeEvidence.Guard.DEADLINE)
            })
            evidence.phase = AbemaRenewalProbeEvidence.Phase.HOST_CLOSE
            callbacks?.let { registered ->
                runCatching { application.unregisterActivityLifecycleCallbacks(registered) }.onFailure {
                    resourcesRetained = true
                    evidence.fail(AbemaRenewalProbeEvidence.Guard.HOST_CLOSE)
                }
            }
            trackedHost.set(null)
            evidence.phase = AbemaRenewalProbeEvidence.Phase.FINISHED
            Log.i("TachiaiAbemaRenewalProbe", "outcome=${if (evidence.firstFailedGuard == AbemaRenewalProbeEvidence.Guard.NONE) "PASS" else "FAIL"} " +
                "firstFailedPhase=${if (evidence.firstFailedGuard == AbemaRenewalProbeEvidence.Guard.NONE) "NONE" else evidence.firstFailedPhase.name} " +
                "firstFailedGuard=${evidence.firstFailedGuard.name} completion=${observation?.observedCompletion?.name ?: "NOT_OBSERVED"} " +
                "replacementGuard=${observation?.firstFailedGuard?.name ?: "NONE"} replacementVerified=$replaced " +
                "originalDiscoveryReady=$discovery hostClosed=$hostClosed preservationChecked=$preservationChecked " +
                "otherRecordsUnchanged=$otherRecordsUnchanged accountRecordChanged=$changedAccount " +
                "readiness=${refusal?.name ?: "NONE"} cleanupUnknown=${!hostClosed} " +
                "hostOwnershipMissing=${hostLaunchAttempted && scenario == null} resourcesRetained=$resourcesRetained " +
                "mode=${if (minimized) "MINIMIZED" else "FOREGROUND"} " +
                "backgroundPhase=${backgroundEvidence?.phase?.name ?: "NOT_REQUESTED"} " +
                "backgroundGuard=${backgroundEvidence?.guard?.name ?: "NONE"} returnedToOriginalHost=$returnedToOriginalHost " +
                "otherMutationKind=${otherMutations?.kind?.name ?: "UNAVAILABLE"} " +
                "otherChanged=${otherMutations?.changed ?: 0} otherAdded=${otherMutations?.added ?: 0} otherRemoved=${otherMutations?.removed ?: 0} " +
                "familyMutations=${familyMutations.joinToString(",") { "${it.family.name}:${it.changed}:${it.added}:${it.removed}" }} " +
                (observation?.observedDiagnostic?.fields()?.entries?.joinToString(" ") { "${it.key}=${it.value}" } ?: "diagnostic=UNAVAILABLE") + " " +
                (observation?.observedProducerDiagnostic?.fields()?.entries?.joinToString(" ") {
                    "producer${it.key.replaceFirstChar(Char::uppercaseChar)}=${it.value}"
                } ?: "producerDiagnostic=UNAVAILABLE"))
        }
        if (evidence.firstFailedGuard != AbemaRenewalProbeEvidence.Guard.NONE)
            throw AssertionError("Automatic renewal observation did not satisfy closed completion, account, discovery and preservation checks")
    }

    private class Preservation(val otherRecordsUnchanged: Boolean, val accountRecordChanged: Boolean,
        val replacementCurrent: Boolean, val otherMutations: AbemaRenewalOtherMutations, val families: List<AbemaRenewalFamilyMutation>)
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
                    if (abemaRenewalRecordFamily("$prefix/${file.name}", "") == AbemaRenewalRecordFamily.RENEWAL_SCHEDULE)
                        decodeAbemaRenewalJobMetadata(bytes)
                    result["$prefix/${file.name}"] = MessageDigest.getInstance("SHA-256").digest(bytes)
                }
                finally { bytes.fill(0) }
            }
        }
        check(result.size in 1..64)
        return result
    }
}

@RequiresApi(29)
internal fun returnAbemaRenewalTask(context: Context, taskId: Int, component: ComponentName) {
    check(taskId >= 0)
    val manager = checkNotNull(context.getSystemService(ActivityManager::class.java))
    val task = checkNotNull(manager.appTasks.singleOrNull { it.taskInfo?.taskId == taskId })
    check(task.taskInfo?.topActivity == component)
    task.moveToFront()
}

internal fun abemaRenewalRecordWithinLimit(prefix: String, name: String, size: Long): Boolean {
    val limit = if (prefix == "no_backup" && name == "failure-diagnostics.tsv") FailureJournalCodec.MAX_BYTES
        else if (abemaRenewalRecordFamily("$prefix/$name", "") == AbemaRenewalRecordFamily.RENEWAL_SCHEDULE) AbemaRenewalJobLedger.MAX_BYTES
        else PRIVATE_SECRET_LIMIT + 29
    return size in 0..limit.toLong()
}

internal fun abemaRenewalTerminalReadiness(message: String?): AbemaAccountReadinessResult? =
    listOf(AbemaAccountReadinessResult.FAILURE, AbemaAccountReadinessResult.TEMPORARY_FAILURE,
        AbemaAccountReadinessResult.INTERACTION_REQUIRED, AbemaAccountReadinessResult.STORAGE_FAILURE)
        .singleOrNull { message == abemaAccountReadinessMessage(it) }

class AbemaAutomaticRenewalRecordGuardDeviceTest {
    @SdkSuppress(minSdkVersion = 29)
    @Test fun realHomeReturnsSameBlankHostWithoutProviderOrPrivateStoreAccess() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ActivityScenario.launch(ComponentActivity::class.java).use { host ->
            val original = AtomicReference<Activity?>()
            var taskId = -1
            var component: ComponentName? = null
            host.onActivity { original.set(it); taskId = it.taskId; component = it.componentName }
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME")
            ).use { check(it.read() == -1) }
            val stoppedDeadline = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < stoppedDeadline && host.state != Lifecycle.State.CREATED) Thread.sleep(50)
            assertTrue(host.state == Lifecycle.State.CREATED)
            returnAbemaRenewalTask(context, taskId, checkNotNull(component))
            val resumedDeadline = SystemClock.elapsedRealtime() + 10_000
            while (SystemClock.elapsedRealtime() < resumedDeadline && host.state != Lifecycle.State.RESUMED) Thread.sleep(50)
            assertTrue(host.state == Lifecycle.State.RESUMED)
            host.onActivity { assertTrue(it === original.get() && it.taskId == taskId) }
            original.set(null)
        }
    }
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
    @Test fun onlyExactPublicScheduleFilesHaveTheirOwnMetadataBound() {
        for (suffix in listOf("", ".bak", ".new")) {
            assertTrue(abemaRenewalRecordWithinLimit("no_backup", AbemaRenewalJobLedger.FILE_NAME + suffix, 16_384))
            assertFalse(abemaRenewalRecordWithinLimit("no_backup", AbemaRenewalJobLedger.FILE_NAME + suffix, 16_385))
        }
        assertFalse(abemaRenewalRecordWithinLimit("shared_prefs", AbemaRenewalJobLedger.FILE_NAME, 16_384))
        assertFalse(abemaRenewalRecordWithinLimit("no_backup", AbemaRenewalJobLedger.FILE_NAME + ".extra", 16_384))
    }
}
