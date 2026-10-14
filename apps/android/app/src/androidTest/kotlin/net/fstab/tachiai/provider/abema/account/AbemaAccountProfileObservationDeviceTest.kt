package net.fstab.tachiai.provider.abema.account

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.MainActivity
import net.fstab.tachiai.feature.connections.ProviderInstanceStore
import net.fstab.tachiai.platform.network.ConnectionProfileStore
import net.fstab.tachiai.platform.storage.AndroidPrivateSecretStore
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.platform.storage.PrivateAuthorizationSlot
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.validProviderInstanceId
import net.fstab.tachiai.provider.abema.AbemaCatalogRouteOwner
import net.fstab.tachiai.provider.abema.readAbemaCatalogRouteOwner
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

// A positive-only shape observation, not an entitlement grant. Default runs
// skip before protected reads. One retained grant, one captured route, one GET;
// the connection controller, collection leases and enforcement are never used.
class AbemaAccountProfileObservationDeviceTest {
    companion object {
        private const val OBSERVATION_MS = 45_000L
        private const val CLEANUP_MS = 20_000L
        private val retained = ConcurrentLinkedQueue<Any>()
    }

    @Test fun optInSavedAccountObservesOneProductionProfileWithoutChangingStores() {
        optIn(chase = false)
    }

    @Test fun optInSavedAccountObservesProductionChaseEligibility() {
        optIn(chase = true)
    }

    private fun optIn(chase: Boolean) {
        val args = InstrumentationRegistry.getArguments()
        val gate = if (chase) "abemaReadOnlyChaseEligibility" else "abemaReadOnlyProfileShape"
        assumeTrue(args.getString(gate) == "true")
        check(args.getString(if (chase) "abemaReadOnlyProfileShape" else "abemaReadOnlyChaseEligibility") != "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        assumeTrue(Application.getProcessName() == instrumentation.targetContext.packageName)
        check(Looper.myLooper() != Looper.getMainLooper())
        val instanceId = args.getString("abemaProviderInstance") ?: error("Explicit provider instance required")
        require(validProviderInstanceId(instanceId))
        val avd = try {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
                .executeShellCommand("getprop ro.boot.qemu.avd_name")).use { input ->
                val bytes = ByteArray(129)
                try {
                    var size = 0
                    while (size < bytes.size) {
                        val count = input.read(bytes, size, bytes.size - size)
                        if (count < 0) break
                        size += count
                    }
                    size <= 128 && String(bytes, 0, size, Charsets.UTF_8).trim() == "tachiai-dev"
                } finally { bytes.fill(0) }
            }
        } catch (_: Throwable) { false }
        assumeTrue("Requires verified tachiai-dev AVD", avd)
        try { observe(instanceId, chase) }
        catch (_: Throwable) { throw AssertionError("Profile observation failed its closed admission/result/cleanup checks") }
    }

    private fun observe(instanceId: String, chase: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // These clocks precede the first protected record/owner/grant read.
        val started = SystemClock.elapsedRealtime()
        val beganWall = System.currentTimeMillis()
        val deadline = started + OBSERVATION_MS
        val cleanupLimit = deadline + CLEANUP_MS
        val cleanupDeadline = AtomicLong(Long.MAX_VALUE)
        val revoked = AtomicBoolean()
        val mutation = AtomicBoolean()
        val cleanupFailed = AtomicBoolean()
        val active = AtomicBoolean()
        val foregroundLost = AtomicBoolean()
        val closingForeground = AtomicBoolean()
        val foregroundReceipt = AtomicBoolean()
        val finished = CountDownLatch(1)
        val output = AtomicReference<String?>()
        val chaseProof = AtomicReference<AbemaAccountProfileObservation.Proof?>()
        val publicationGate = AtomicReference<(() -> Boolean)?>(null)
        val failure = AtomicBoolean()
        val observation = AtomicReference<AbemaAccountProfileObservation?>()
        val closeReceipt = AtomicReference<AbemaProfileFixtureCleanup<AbemaAccountProfileObservation>?>()
        val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "abema-profile-observation").apply { isDaemon = true }
        }
        val holder = ProfileOwners(worker, observation, closeReceipt)
        fun clockCurrent() = profileObservationClockCurrent(started, beganWall,
            SystemClock.elapsedRealtime(), System.currentTimeMillis(), OBSERVATION_MS)
        fun localCurrent() = !revoked.get() && !mutation.get() && !cleanupFailed.get() &&
            !foregroundLost.get() && (active.get() || foregroundReceipt.get()) && clockCurrent()
        fun remaining(end: Long) = (end - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        fun cleanupEnd(): Long {
            cleanupDeadline.compareAndSet(Long.MAX_VALUE, minOf(cleanupLimit, SystemClock.elapsedRealtime() + CLEANUP_MS))
            return cleanupDeadline.get()
        }
        fun cleanupFailure() { cleanupFailed.set(true) }
        worker.execute {
            var before: Map<String, ByteArray>? = null
            var after: Map<String, ByteArray>? = null
            var captured: AbemaCatalogRouteOwner? = null
            var snapshot: AbemaAccountSnapshot? = null
            var candidate: AbemaStoredAccount? = null
            var store: AbemaAccountGrantStore? = null
            var localUnavailable = false
            var localReason = ProfileObservationLocalReason.SNAPSHOT_UNAVAILABLE
            var ownerUnchanged = false
            var storeUnchanged = false
            var recordsUnchanged = false
            var cleanupConfirmed = false
            val directory = context.noBackupFilesDir
            val poison = AbemaAccountWorkers.poisoned(instanceId)
            fun regular(file: File) = file.isFile && file.canRead() && !Files.isSymbolicLink(file.toPath())
            fun stable() {
                check(clockCurrent() && !revoked.get() && !mutation.get())
                check(directory.isDirectory && directory.canRead() && !Files.isSymbolicLink(directory.toPath()))
                check(checkNotNull(directory.listFiles()).none { it.name.endsWith(".bak") || it.name.endsWith(".new") })
            }
            fun existingLock(name: String): File {
                stable()
                val file = File(directory, name)
                check(regular(file) && file.canWrite())
                return file
            }
            fun refuseWrite(): Nothing { mutation.set(true); error("Read-only observation refused mutation") }
            fun slot(value: PrivateAuthorizationSlot): PrivateSecretStore {
                val secret = AndroidPrivateSecretStore(context, value)
                return object : PrivateSecretStore {
                    override fun read(): ByteArray? {
                        stable()
                        val file = File(directory, "${value.bindingName}.enc")
                        check(regular(file) && file.length() in 1..(PRIVATE_SECRET_LIMIT + 29).toLong())
                        return secret.read().also { stable() }
                    }
                    override fun write(plaintext: ByteArray): Unit = refuseWrite()
                }
            }
            fun readOwner(): AbemaCatalogRouteOwner = readAbemaCatalogRouteOwner(instanceId, {
                ProviderInstanceStore(slot(PrivateAuthorizationSlot.PROVIDER_INSTANCES), existingLock("provider-instances.lock"))
                    .read { error("Observation requires an existing registry") }
            }, { id ->
                ConnectionProfileStore(slot(PrivateAuthorizationSlot.CONNECTION_PROFILES), existingLock("connection-profiles.lock"))
                    .selected(id)
            })
            fun sameOwner() = captured?.sameOwnership(readOwner()) == true
            fun grantCurrent(): Boolean {
                val first = snapshot ?: return false
                val original = candidate ?: return false
                val saved = store ?: return false
                return profileObservationSnapshotCurrent(saved, first, original, beganWall, System::currentTimeMillis)
            }
            fun current() = localCurrent() && !poison.get() && sameOwner() && grantCurrent() && localCurrent()
            val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
            val callback = ActivityLifecycleCallback { activity, stage ->
                if (activity === holder.activity && stage in setOf(Stage.PAUSED, Stage.STOPPED, Stage.DESTROYED)) {
                    active.set(false)
                    if (!closingForeground.get()) foregroundLost.set(true)
                }
            }
            holder.callback = callback
            try {
                check(synchronized(retained) { retained.isEmpty() })
                stable()
                before = protectedRecords(directory, deadline)
                captured = readOwner()
                val raw = AndroidAbemaAccountStorage(context, instanceId)
                val readOnly = object : AbemaAccountStorage {
                    override fun read(): ByteArray? {
                        stable()
                        val file = File(directory, "abema-account-$instanceId.enc")
                        if (!file.exists()) { check(!Files.isSymbolicLink(file.toPath())); return null }
                        check(regular(file) && file.length() in 1..(PRIVATE_SECRET_LIMIT + 29).toLong())
                        return raw.read().also { stable() }
                    }
                    override fun forgetPending(): Boolean { stable(); return raw.forgetPending() }
                    override fun write(plaintext: ByteArray): Unit = refuseWrite()
                    override fun markForgetPending(): Unit = refuseWrite()
                    override fun finishForget(): Unit = refuseWrite()
                }
                val savedStore = AbemaAccountGrantStore(instanceId, readOnly)
                store = savedStore
                // An unavailable/corrupt/expired candidate never reaches the
                // observation factory. In particular it cannot become a guest.
                snapshot = try { savedStore.readSnapshot() } catch (_: Throwable) { null }
                candidate = snapshot?.record
                val original = snapshot
                val revisionUsable = original != null && savedStore.usable(original.revision)
                val accountPoisoned = poison.get()
                val admissionWall = System.currentTimeMillis()
                localReason = profileObservationLocalReason(original != null, revisionUsable, accountPoisoned,
                    candidate, beganWall, admissionWall)
                localUnavailable = localReason != ProfileObservationLocalReason.ADMITTED
                check(clockCurrent() && sameOwner())
                if (!localUnavailable) {
                    holder.scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply {
                        action = Intent.ACTION_MAIN; addCategory(Intent.CATEGORY_LAUNCHER)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    val registered = CountDownLatch(1)
                    val registration = Runnable {
                        try {
                            if (!revoked.get() && clockCurrent()) {
                                holder.activity = lifecycle.getActivitiesInStage(Stage.RESUMED).singleOrNull { it is MainActivity }
                                active.set(holder.activity != null)
                                holder.registrationStarted.set(true)
                                lifecycle.addLifecycleCallback(callback)
                            }
                        } catch (_: Throwable) { foregroundLost.set(true) }
                        finally { registered.countDown() }
                    }
                    holder.registration = registration
                    check(Handler(Looper.getMainLooper()).post(registration))
                    check(registered.await(remaining(deadline), TimeUnit.MILLISECONDS))
                    check(current())
                    val owner = checkNotNull(captured)
                    val helper = AbemaAccountProfileObservation(::current, { wrap ->
                        OwnedAbemaAccountTransport(owner.profile, ::current, ::cleanupFailure,
                            makeHttpTransport = { open, gate ->
                                AbemaAccountHttpTransport({ url -> wrap(open(url)) }, gate,
                                    SystemClock::elapsedRealtime, System::currentTimeMillis)
                            })
                    }, { it is OwnedAbemaAccountTransport && it.cleanupConfirmed }, ::cleanupFailure)
                    observation.set(helper)
                    closeReceipt.set(AbemaProfileFixtureCleanup(helper, { it.close() }))
                    check(current())
                    val saved = checkNotNull(candidate).grant
                    if (chase) helper.observeSubscriptions(saved.userId, saved.bearer)
                    else helper.observe(saved.userId, saved.bearer)
                    check(current())
                }
            } catch (_: Throwable) { failure.set(true) }
            finally {
                // The helper owns actual HTTP/input/disconnect/route/backend
                // receipts. One first close attempt, never a retry or fallback.
                val helper = observation.get()
                val end = cleanupEnd()
                val nativeClosed = closeReceipt.get()?.await(end) ?: (helper == null)
                val foregroundPreserved = active.get() && !foregroundLost.get()
                closingForeground.set(true)
                val lifecycleClose = AbemaProfileFixtureCleanup(holder, { owned ->
                    instrumentation.runOnMainSync {
                        owned.registration?.let { Handler(Looper.getMainLooper()).removeCallbacks(it) }
                        if (owned.registrationStarted.get()) lifecycle.removeLifecycleCallback(callback)
                    }
                    owned.scenario?.close()
                })
                holder.lifecycleClose = lifecycleClose
                val lifecycleClosed = lifecycleClose.await(end)
                active.set(false)
                foregroundReceipt.set(foregroundPreserved && lifecycleClosed)
                cleanupConfirmed = nativeClosed && (helper?.cleanupConfirmed != false) && lifecycleClosed &&
                    !cleanupFailed.get() && SystemClock.elapsedRealtime() < end
                if (!cleanupConfirmed) { cleanupFailure(); retained.add(holder) }
                // Independent checks run even if another preservation check
                // failed. No reset/poison workaround and no provider request.
                ownerUnchanged = try { clockCurrent() && sameOwner() } catch (_: Throwable) { false }
                storeUnchanged = try {
                    if (localUnavailable) !mutation.get() else grantCurrent()
                } catch (_: Throwable) { false }
                recordsUnchanged = try {
                    after = protectedRecords(directory, deadline)
                    profileObservationSameDigests(before, after)
                } catch (_: Throwable) { false }
                val finalGates = cleanupConfirmed && ownerUnchanged && storeUnchanged && recordsUnchanged &&
                    !mutation.get() && !revoked.get() && clockCurrent() && !failure.get()
                if (finalGates) {
                    if (localUnavailable && helper == null) {
                        publicationGate.set { clockCurrent() && !revoked.get() && !mutation.get() &&
                            ownerUnchanged && recordsUnchanged && cleanupConfirmed }
                        output.set("outcome=LOCAL_UNAVAILABLE httpCount=0 recordsUnchanged=true cleanupConfirmed=true" +
                            if (chase) " localReason=${localReason.name}" else "")
                    } else if (foregroundReceipt.get()) {
                        // No potentially blocking disk/owner reads outside the
                        // bounded worker. Its independent final proofs are
                        // joined below; the last emission gate is memory/clock.
                        val admittedStore = checkNotNull(store)
                        val admittedSnapshot = checkNotNull(snapshot)
                        val admittedCandidate = checkNotNull(candidate)
                        publicationGate.set { localCurrent() && !poison.get() && ownerUnchanged && recordsUnchanged &&
                            cleanupConfirmed && admittedStore.usable(admittedSnapshot.revision) &&
                            profileObservationGrantAdmitted(admittedCandidate, beganWall, System.currentTimeMillis()) && localCurrent() }
                        val proof = AbemaAccountProfileObservation.Proof(ownerUnchanged,
                            storeUnchanged, clockCurrent(), foregroundReceipt.get(), recordsUnchanged)
                        if (chase) {
                            chaseProof.set(proof)
                            output.set(helper?.publishChase(proof)?.render()?.let {
                                "outcome=CHASE_ELIGIBILITY_OBSERVED httpCount=${checkNotNull(helper).requestCount} $it"
                            })
                        } else output.set(helper?.publish(proof)?.render()?.let {
                            "outcome=PROFILE_ACCEPTED httpCount=${checkNotNull(helper).requestCount} $it"
                        })
                    }
                }
                before?.values?.forEach { it.fill(0) }
                after?.values?.forEach { it.fill(0) }
                finished.countDown()
            }
        }
        var completed = false
        var terminated = false
        try {
            completed = finished.await(remaining(deadline), TimeUnit.MILLISECONDS)
            if (!completed) {
                revoked.set(true)
                val end = cleanupEnd()
                if (remaining(end) > 0) closeReceipt.get()?.start()
                completed = finished.await(remaining(end), TimeUnit.MILLISECONDS)
            }
        } finally {
            worker.shutdown()
            terminated = try { worker.awaitTermination(remaining(cleanupEnd()), TimeUnit.MILLISECONDS) }
                catch (_: Throwable) { false }
            if (!completed || !terminated) { revoked.set(true); retained.add(holder) }
        }
        val finalAdmission = try { publicationGate.get()?.invoke() == true } catch (_: Throwable) { false }
        val summary = output.get().takeIf { completed && terminated && !revoked.get() && clockCurrent() && finalAdmission }
        assertNotNull("No profile summary may escape incomplete admission, observation or cleanup", summary)
        // Recompute the closed headroom at emission, without protected reads or
        // another request; a VERIFIED claim that expired during joining is lost.
        val emitted = if (chase && chaseProof.get() != null) observation.get()?.let { helper ->
            helper.publishChase(checkNotNull(chaseProof.get()))?.render()?.let {
                "outcome=CHASE_ELIGIBILITY_OBSERVED httpCount=${helper.requestCount} $it"
            }
        } else summary
        assertNotNull("No stale Chase evidence may escape final publication", emitted)
        if (chase) {
            val stillAdmitted = try { completed && terminated && !revoked.get() && clockCurrent() &&
                publicationGate.get()?.invoke() == true } catch (_: Throwable) { false }
            assertTrue("No Chase summary may escape lost final memory/clock admission", stillAdmitted)
        }
        Log.i(if (chase) "TachiaiAbemaChaseEligibility" else "TachiaiAbemaProfile", checkNotNull(emitted))
    }

    private class ProfileOwners(val worker: Any, val observation: AtomicReference<AbemaAccountProfileObservation?>,
        val helperClose: AtomicReference<AbemaProfileFixtureCleanup<AbemaAccountProfileObservation>?>) {
        @Volatile var scenario: ActivityScenario<MainActivity>? = null
        @Volatile var activity: android.app.Activity? = null
        @Volatile var callback: ActivityLifecycleCallback? = null
        @Volatile var registration: Runnable? = null
        val registrationStarted = AtomicBoolean()
        @Volatile var lifecycleClose: AbemaProfileFixtureCleanup<ProfileOwners>? = null
        override fun toString() = "AbemaProfileObservationOwners(redacted)"
    }

    private fun protectedRecords(directory: File, deadline: Long): Map<String, ByteArray> {
        val began = SystemClock.elapsedRealtime()
        fun bounded() = check(SystemClock.elapsedRealtime() < deadline &&
            SystemClock.elapsedRealtime() - began in 0 until 5000)
        val result = linkedMapOf<String, ByteArray>()
        try {
            bounded()
            check(directory.isDirectory && directory.canRead() && !Files.isSymbolicLink(directory.toPath()))
            val files = checkNotNull(directory.listFiles())
            check(files.none { it.name.endsWith(".bak") || it.name.endsWith(".new") })
            val protected = files.filter { it.name.endsWith(".enc") || it.name.endsWith(".lock") ||
                it.name.endsWith(".forget-pending") }
            check(protected.size in 1..96)
            for (file in protected) {
                bounded()
                check(file.isFile && file.canRead() && !Files.isSymbolicLink(file.toPath()) &&
                    file.length() in 0..(PRIVATE_SECRET_LIMIT + 29).toLong())
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    try {
                        var total = 0
                        while (true) {
                            bounded(); val count = input.read(buffer); if (count < 0) break
                            total += count; check(total <= PRIVATE_SECRET_LIMIT + 29)
                            digest.update(buffer, 0, count)
                        }
                    } finally { buffer.fill(0) }
                }
                bounded(); result[file.name] = digest.digest()
            }
            return result
        } catch (error: Throwable) { result.values.forEach { it.fill(0) }; throw error }
    }
}

// Parent closes must not occupy the production two-thread native cleanup pool:
// a transport cancellation needs that pool for its input and disconnect children.
// This fixture receipt owns one first attempt and an absolute cleanup deadline.
internal class AbemaProfileFixtureCleanup<T : Any>(val resource: T, private val close: (T) -> Unit,
    private val execute: (Runnable) -> Unit = { workers.execute(it) },
    private val elapsed: () -> Long = SystemClock::elapsedRealtime) {
    companion object {
        private val workers = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(2), { task ->
                Thread(task, "abema-profile-fixture-cleanup").apply { isDaemon = true }
            })
    }
    private val started = AtomicBoolean()
    private val failed = AtomicBoolean()
    private val successful = AtomicBoolean()
    private val finished = CountDownLatch(1)
    @Volatile private var owner: Thread? = null
    val confirmed get() = finished.count == 0L && successful.get() && !failed.get()
    fun start() {
        if (failed.get()) return
        if (!started.compareAndSet(false, true)) return
        try {
            execute(Runnable {
                owner = Thread.currentThread()
                try { close(resource); successful.set(true) }
                catch (_: Throwable) { failed.set(true) }
                finally { finished.countDown() }
            })
        } catch (_: Throwable) { failed.set(true); finished.countDown() }
    }
    fun await(deadline: Long): Boolean {
        if (failed.get()) return false
        val began = try { elapsed() } catch (_: Throwable) { failed.set(true); return false }
        if (began < 0 || began >= deadline) { failed.set(true); return false }
        start()
        if (owner === Thread.currentThread() && finished.count != 0L) { failed.set(true); return false }
        try {
            val now = elapsed()
            if (now < 0 || now >= deadline ||
                (finished.count != 0L && !finished.await(deadline - now, TimeUnit.MILLISECONDS))) {
                failed.set(true)
            }
            val after = elapsed()
            if (after < began || after >= deadline) failed.set(true)
        } catch (_: InterruptedException) { failed.set(true); Thread.currentThread().interrupt() }
        catch (_: Throwable) { failed.set(true) }
        return confirmed
    }
    override fun toString() = "AbemaProfileFixtureCleanup(redacted)"
}

internal fun profileObservationClockCurrent(startElapsed: Long, startWall: Long, elapsed: Long, wall: Long,
    budget: Long): Boolean = startElapsed >= 0 && startWall >= 0 && budget > 0 &&
    elapsed >= startElapsed && wall >= startWall && elapsed - startElapsed < budget && wall - startWall < budget

internal enum class ProfileObservationLocalReason {
    ADMITTED, SNAPSHOT_UNAVAILABLE, REVISION_UNUSABLE, ACCOUNT_POISONED, MISSING_RECORD,
    INVALID_ACCEPTANCE_OR_GENERATION, CLOCK_BEFORE_ACCEPTANCE, CLOCK_ROLLBACK, SAVED_EXPIRY_ELAPSED,
    DECODED_BEARER_REJECTED, STORED_DECODED_EXPIRY_MISMATCH,
}

internal fun profileObservationLocalReason(snapshotAvailable: Boolean, revisionUsable: Boolean, poisoned: Boolean,
    record: AbemaStoredAccount?, startWall: Long, nowWall: Long): ProfileObservationLocalReason = when {
    !snapshotAvailable -> ProfileObservationLocalReason.SNAPSHOT_UNAVAILABLE
    !revisionUsable -> ProfileObservationLocalReason.REVISION_UNUSABLE
    poisoned -> ProfileObservationLocalReason.ACCOUNT_POISONED
    else -> profileObservationGrantReason(record, startWall, nowWall)
}

private fun profileObservationGrantReason(record: AbemaStoredAccount?, startWall: Long, nowWall: Long): ProfileObservationLocalReason {
    if (record == null) return ProfileObservationLocalReason.MISSING_RECORD
    if (record.generation < 1 || record.acceptedWallMs < 0) return ProfileObservationLocalReason.INVALID_ACCEPTANCE_OR_GENERATION
    if (startWall < record.acceptedWallMs) return ProfileObservationLocalReason.CLOCK_BEFORE_ACCEPTANCE
    if (nowWall < startWall) return ProfileObservationLocalReason.CLOCK_ROLLBACK
    if (record.grant.expiresAtMs <= nowWall) return ProfileObservationLocalReason.SAVED_EXPIRY_ELAPSED
    val decoded = try { decodedAbemaGrant(record.grant.bearer, record.grant.userId, nowWall) }
        catch (_: Throwable) { return ProfileObservationLocalReason.DECODED_BEARER_REJECTED }
    return if (decoded.expiresAtMs == record.grant.expiresAtMs) ProfileObservationLocalReason.ADMITTED
        else ProfileObservationLocalReason.STORED_DECODED_EXPIRY_MISMATCH
}

internal fun profileObservationGrantAdmitted(record: AbemaStoredAccount?, startWall: Long, nowWall: Long): Boolean =
    profileObservationGrantReason(record, startWall, nowWall) == ProfileObservationLocalReason.ADMITTED

internal fun profileObservationSameRecord(a: AbemaStoredAccount?, b: AbemaStoredAccount?): Boolean = a != null && b != null &&
    a.generation == b.generation && a.acceptedWallMs == b.acceptedWallMs && a.grant.userId == b.grant.userId &&
    a.grant.bearer == b.grant.bearer && a.grant.expiresAtMs == b.grant.expiresAtMs

internal fun profileObservationSnapshotCurrent(store: AbemaAccountGrantStore, original: AbemaAccountSnapshot,
    candidate: AbemaStoredAccount, startWall: Long, nowWall: () -> Long): Boolean {
    if (!store.usable(original.revision)) return false
    val fresh = store.readSnapshot()
    return fresh.revision == original.revision && store.usable(original.revision) &&
        profileObservationSameRecord(candidate, fresh.record) &&
        profileObservationGrantAdmitted(fresh.record, startWall, nowWall()) && store.usable(original.revision)
}

private fun profileObservationSameDigests(a: Map<String, ByteArray>?, b: Map<String, ByteArray>?): Boolean =
    a != null && b != null && a.keys == b.keys && a.all { (name, digest) ->
        b[name]?.let { MessageDigest.isEqual(digest, it) } == true
    }

class AbemaAccountProfileAdmissionDeviceTest {
    private fun record(expiry: Long = 100_000, accepted: Long = 1000, generation: Long = 1,
        id: String = "synthetic", claim: String = "synthetic", tokenExpiry: Long = 100): AbemaStoredAccount {
        fun part(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        val token = "${part("{}")}.${part("{\"sub\":\"$claim\",\"exp\":$tokenExpiry}")}.${part("synthetic-signature")}"
        return AbemaStoredAccount(AbemaAccountGrant(id, token, expiry), accepted, generation)
    }
    @Test fun exactDecodedStoredExpiryAdmitsOnlyFreshCandidate() {
        assertTrue(profileObservationGrantAdmitted(record(), 2000, 3000))
        assertFalse(profileObservationGrantAdmitted(record(expiry = 100_001), 2000, 3000))
        assertFalse(profileObservationGrantAdmitted(record(), 2000, 100_000))
        assertFalse(profileObservationGrantAdmitted(null, 2000, 3000))
    }
    @Test fun subjectMismatchAndBothWallRollbacksRefuseAdmission() {
        assertFalse(profileObservationGrantAdmitted(record(claim = "other"), 2000, 3000))
        assertFalse(profileObservationGrantAdmitted(record(accepted = 2001), 2000, 3000))
        assertFalse(profileObservationGrantAdmitted(record(), 2000, 1999))
    }
    @Test fun equalReconstructedRecordAdmitsButGenerationAndExpiryChangesRefuse() {
        assertTrue(profileObservationSameRecord(record(), record()))
        assertFalse(profileObservationSameRecord(record(), record(generation = 2)))
        assertFalse(profileObservationSameRecord(record(), record(expiry = 100_001)))
        assertFalse(profileObservationSameRecord(record(), null))
    }
    @Test fun absoluteDeadlineAndElapsedRollbackNeverRefreshBudget() {
        assertTrue(profileObservationClockCurrent(10, 1000, 44_999, 45_999, 45_000))
        assertFalse(profileObservationClockCurrent(10, 1000, 45_010, 2000, 45_000))
        assertFalse(profileObservationClockCurrent(10, 1000, 11, 46_000, 45_000))
        assertFalse(profileObservationClockCurrent(10, 1000, 9, 1000, 45_000))
        assertFalse(profileObservationClockCurrent(10, 1000, 10, 999, 45_000))
    }
    @Test fun closedLocalAdmissionReasonsPreserveSnapshotRevisionAndPoisonShortCircuitOrder() {
        fun reason(snapshot: Boolean, usable: Boolean, poison: Boolean, value: AbemaStoredAccount? = record()) =
            profileObservationLocalReason(snapshot, usable, poison, value, 2000, 3000)
        assertEquals(ProfileObservationLocalReason.SNAPSHOT_UNAVAILABLE, reason(false, false, true, null))
        assertEquals(ProfileObservationLocalReason.REVISION_UNUSABLE, reason(true, false, true, null))
        assertEquals(ProfileObservationLocalReason.ACCOUNT_POISONED, reason(true, true, true, null))
        assertEquals(ProfileObservationLocalReason.MISSING_RECORD, reason(true, true, false, null))
        assertEquals(ProfileObservationLocalReason.ADMITTED, reason(true, true, false))
        for (reason in ProfileObservationLocalReason.entries) {
            assertFalse(reason.name.contains("synthetic")); assertFalse(reason.name.contains("100000"))
        }
    }
    @Test fun closedGrantReasonMatrixMatchesOriginalBooleanAndSeparatesStoredFromDecodedExpiry() {
        class Case(val value: AbemaStoredAccount?, val start: Long = 2000, val now: Long = 3000,
            val reason: ProfileObservationLocalReason)
        val malformed = AbemaStoredAccount(AbemaAccountGrant("synthetic", "invalid", 100_000), 1000, 1)
        val cases = listOf(
            Case(record(), reason = ProfileObservationLocalReason.ADMITTED),
            Case(null, reason = ProfileObservationLocalReason.MISSING_RECORD),
            Case(record(generation = 0), reason = ProfileObservationLocalReason.INVALID_ACCEPTANCE_OR_GENERATION),
            Case(record(accepted = -1), reason = ProfileObservationLocalReason.INVALID_ACCEPTANCE_OR_GENERATION),
            Case(record(accepted = 2001), reason = ProfileObservationLocalReason.CLOCK_BEFORE_ACCEPTANCE),
            Case(record(), now = 1999, reason = ProfileObservationLocalReason.CLOCK_ROLLBACK),
            Case(record(), now = 100_000, reason = ProfileObservationLocalReason.SAVED_EXPIRY_ELAPSED),
            Case(malformed, reason = ProfileObservationLocalReason.DECODED_BEARER_REJECTED),
            Case(record(claim = "other"), reason = ProfileObservationLocalReason.DECODED_BEARER_REJECTED),
            Case(record(tokenExpiry = 3), reason = ProfileObservationLocalReason.DECODED_BEARER_REJECTED),
            Case(record(expiry = 100_001), reason = ProfileObservationLocalReason.STORED_DECODED_EXPIRY_MISMATCH),
        )
        for (case in cases) {
            assertEquals(case.reason, profileObservationLocalReason(true, true, false, case.value, case.start, case.now))
            assertEquals(case.reason == ProfileObservationLocalReason.ADMITTED,
                profileObservationGrantAdmitted(case.value, case.start, case.now))
        }
    }
    @Test fun revokedRevisionRefusesBeforeASecondProtectedRead() {
        val instance = java.util.UUID.randomUUID().toString()
        val value = record()
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(1); out.writeUTF(instance); out.writeBoolean(true)
                out.writeUTF(value.grant.userId); out.writeUTF(value.grant.bearer)
                out.writeLong(value.grant.expiresAtMs); out.writeLong(value.acceptedWallMs); out.writeLong(value.generation)
            }
        }.toByteArray()
        var reads = 0
        var writes = 0
        val storage = object : AbemaAccountStorage {
            override fun read(): ByteArray { reads++; return bytes.copyOf() }
            override fun write(plaintext: ByteArray) { writes++; error("Unexpected write") }
            override fun forgetPending() = false
            override fun markForgetPending() { writes++; error("Unexpected mutation") }
            override fun finishForget() { writes++; error("Unexpected mutation") }
        }
        try {
            val store = AbemaAccountGrantStore(instance, storage)
            val first = store.readSnapshot()
            assertTrue(profileObservationSnapshotCurrent(store, first, value, 2000) { 3000 })
            assertEquals(2, reads)
            store.invalidateForget()
            assertFalse(profileObservationSnapshotCurrent(store, first, value, 2000) { 3000 })
            assertEquals(2, reads)
            assertEquals(0, writes)
        } finally { bytes.fill(0) }
    }
    @Test fun parentCleanupLeavesNativeChildrenAvailable() {
        val value = Any()
        val receipt = AbemaProfileFixtureCleanup(value, {
            val input = AbemaAccountCleanupReceipt(Any(), {})
            val disconnect = AbemaAccountCleanupReceipt(Any(), {})
            input.start(); disconnect.start()
            check(input.await() && disconnect.await())
            check(Thread.currentThread().name == "abema-profile-fixture-cleanup")
        })
        assertTrue(receipt.await(SystemClock.elapsedRealtime() + 3000))
        assertTrue(receipt.confirmed)
        assertSame(value, receipt.resource)
    }
    @Test fun lateParentCloseCannotLaunderTimeoutOrRetryOriginalOwner() {
        val queued = mutableListOf<Runnable>()
        val owner = Any()
        var calls = 0
        val receipt = AbemaProfileFixtureCleanup(owner, { calls++ }, { queued.add(it) }, { 10 })
        receipt.start()
        assertFalse(receipt.await(10))
        assertEquals(1, queued.size)
        queued.single().run()
        assertFalse(receipt.confirmed)
        assertFalse(receipt.await(20))
        assertEquals(1, calls)
        assertSame(owner, receipt.resource)
    }
    @Test fun failedParentCloseStaysStickyAndDoesNotRetry() {
        var calls = 0
        val receipt = AbemaProfileFixtureCleanup(Any(), { calls++; error("Synthetic closed failure") },
            { it.run() }, { 10 })
        assertFalse(receipt.await(20))
        assertFalse(receipt.await(30))
        assertEquals(1, calls)
    }
    @Test fun exhaustedParentBudgetCannotStartALaterCloseAttempt() {
        var queued = 0
        val receipt = AbemaProfileFixtureCleanup(Any(), {}, { queued++; it.run() }, { 20 })
        assertFalse(receipt.await(20))
        assertFalse(receipt.await(30))
        receipt.start()
        assertEquals(0, queued)
        assertFalse(receipt.confirmed)
    }
}
