package net.fstab.tachiai.provider.abema.account

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.BuildConfig
import net.fstab.tachiai.feature.connections.legacyProviderSettings
import net.fstab.tachiai.feature.connections.providerInstanceStore
import net.fstab.tachiai.platform.network.ProviderStartupCleanupAdmission
import net.fstab.tachiai.platform.network.ProviderStartupScopeCleanup
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.presentation.PrototypeService
import org.junit.Assume.assumeTrue
import org.junit.Test

// Actual production service/client, never replacement adoption. The saved grant
// may still be valid; this fixture does not manufacture expiry or reset login.
class AbemaProductionRenewalBrowserProbeTest {
    companion object { private val retained = mutableListOf<Any>() }

    @Test(timeout = 140_000) fun productionHostPreservesAccountWithoutActivityOrAdoption() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("abemaProductionRenewalProbe") == "true")
        val context = instrumentation.targetContext.applicationContext
        assumeTrue(BuildConfig.DEBUG && !BuildConfig.LOCAL_INSPECTION &&
            Application.getProcessName() == context.packageName && Looper.myLooper() != Looper.getMainLooper())
        val avd = try {
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
        assumeTrue("Requires verified persistent tachiai-dev", avd)
        try { observe(context, args.getString("abemaRenewalProbeInstanceId")) }
        catch (_: Throwable) { throw AssertionError("Production renewal-host probe did not satisfy its finite preservation checks") }
    }

    private fun observe(context: Context, selected: String?) {
        check(synchronized(retained) { retained.isEmpty() })
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val began = SystemClock.elapsedRealtime()
        val beganWall = System.currentTimeMillis()
        val deadline = began + 90_000L
        val wallHigh = AtomicLong(beganWall)
        val active = AtomicBoolean(true)
        val instances = providerInstanceStore(context).read { legacyProviderSettings(context) }
            .filter { it.service == PrototypeService.ABEMA }
        val instance = checkNotNull(if (selected == null) instances.singleOrNull() else instances.singleOrNull { it.id == selected })
        val binding = androidAbemaAccountConnectionBinding(context, instance.id)
        val owner = binding.owner()
        val before = binding.store.readSnapshot()
        val expected = checkNotNull(before.record)
        val savedFiles = encryptedRecords(context.noBackupFilesDir)
        val naturallyExpired = beganWall >= expected.grant.expiresAtMs
        fun current(): Boolean {
            val wall = System.currentTimeMillis()
            val previous = wallHigh.getAndUpdate { maxOf(it, wall) }
            return active.get() && SystemClock.elapsedRealtime() in began until deadline &&
                wall >= previous && wall - beganWall in 0 until 90_000 &&
                binding.store.usable(before.revision) && binding.sameOwner(owner)
        }
        check(ProviderStartupScopeCleanup.awaitReady(instance.id, deadline, ::current) == ProviderStartupCleanupAdmission.READY)
        val tasks = context.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        fun taskState() = tasks.appTasks.map { task ->
            val info = checkNotNull(task.taskInfo)
            Triple(info.persistentId, info.baseActivity, info.topActivity)
        }.toSet()
        val originalTasks = taskState()
        var tasksUnchanged = true
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val candidate = AtomicReference<AbemaAccountGrant?>()
        val browser = AtomicReference<AbemaRenewalBrowser?>()
        val native = AtomicReference<AbemaAccountTransport?>()
        val nativeCloseStarted = AtomicBoolean()
        val nativeClosed = CountDownLatch(1)
        val nativeCloseSucceeded = AtomicBoolean()
        val nativeRetained = AtomicBoolean()
        val result = AtomicReference<AbemaRenewalBrowserResult?>()
        val finished = CountDownLatch(1)
        val main = Handler(Looper.getMainLooper())
        var released = false
        var releaseAttempted = false
        var nativeCleanup = true
        var sameAccount = false
        var registered = false
        var extendsExpiry = false
        var preserved = false
        var completed = false
        fun closeNative() {
            native.get()?.let { owned ->
                if (nativeCloseStarted.compareAndSet(false, true)) try {
                    AbemaAccountWorkers.cancellation.execute {
                        try { owned.close(); nativeCloseSucceeded.set(true) }
                        catch (_: Throwable) { }
                        finally { nativeClosed.countDown() }
                    }
                } catch (_: Throwable) { nativeClosed.countDown() }
                val confirmed = try {
                    nativeClosed.await(7, TimeUnit.SECONDS) && nativeCloseSucceeded.get()
                } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
                if (confirmed) native.compareAndSet(owned, null) else {
                    nativeCleanup = false
                    if (nativeRetained.compareAndSet(false, true)) synchronized(retained) { retained.add(owned) }
                }
            }
        }
        AndroidAbemaEmailGrantHandoff.inbox.register(nonce, instance.id, owner.proof(), deadline) { candidate.set(it) }
        val timeout = Runnable {
            active.set(false)
            browser.get()?.cancel()
            AbemaAccountWorkers.cancellation.execute { runCatching { native.get()?.cancelActiveRequest() } }
        }
        main.postDelayed(timeout, maxOf(0L, deadline - SystemClock.elapsedRealtime()))
        try {
            instrumentation.runOnMainSync {
                val client = AndroidAbemaRenewalBrowser(context)
                browser.set(client)
                client.start(AbemaApprovalBrowserRequest(instance.id, owner.proof(), "https://abema.tv/account", deadline,
                    nonce, resetBrowserSession = false, automaticReturn = true)) { value ->
                    result.set(value); finished.countDown()
                }
            }
            while (finished.count != 0L && SystemClock.elapsedRealtime() < deadline + 7_000L) {
                tasksUnchanged = tasksUnchanged && taskState() == originalTasks
                finished.await(250, TimeUnit.MILLISECONDS)
            }
            check(result.get() == AbemaRenewalBrowserResult.DELIVERED && current())
            instrumentation.runOnMainSync { releaseAttempted = true; released = browser.get()?.release() == true }
            check(released)
            val grant = checkNotNull(candidate.getAndSet(null))
            sameAccount = grant.userId == expected.grant.userId
            extendsExpiry = grant.expiresAtMs > expected.grant.expiresAtMs
            if (sameAccount && current()) {
                val transport = binding.transport(owner, ::current)
                native.set(transport)
                try {
                    val profile = transport.profile(grant.userId, grant.bearer)
                    sameAccount = profile is AbemaAccountProfileResult.Identity && profile.id == expected.grant.userId
                    if (sameAccount && current()) registered =
                        transport.emailRegistration(grant.userId, grant.bearer) == AbemaAccountEmailResult.REGISTERED
                } finally { closeNative() }
            }
            completed = current() && sameAccount && registered && nativeCleanup
        } finally {
            active.set(false)
            main.removeCallbacks(timeout)
            AndroidAbemaEmailGrantHandoff.inbox.unregister(nonce)
            candidate.set(null)
            closeNative()
            if (!released && !releaseAttempted) {
                instrumentation.runOnMainSync { browser.get()?.cancel() }
                finished.await(7, TimeUnit.SECONDS)
                instrumentation.runOnMainSync { releaseAttempted = true; released = browser.get()?.release() == true }
            }
            if (!released) browser.get()?.let { synchronized(retained) { retained.add(it) } }
            tasksUnchanged = tasksUnchanged && taskState() == originalTasks
            val after = binding.store.readSnapshot()
            val saved = after.record
            val exact = saved != null && after.revision == before.revision &&
                saved.grant.userId == expected.grant.userId && saved.grant.bearer == expected.grant.bearer &&
                saved.grant.expiresAtMs == expected.grant.expiresAtMs && saved.generation == expected.generation &&
                saved.acceptedWallMs == expected.acceptedWallMs
            val afterFiles = encryptedRecords(context.noBackupFilesDir)
            preserved = exact && savedFiles.keys == afterFiles.keys && savedFiles.all { (name, digest) ->
                afterFiles[name]?.let { MessageDigest.isEqual(digest, it) } == true
            }
            savedFiles.values.forEach { it.fill(0) }; afterFiles.values.forEach { it.fill(0) }
            instrumentation.sendStatus(0, Bundle().apply {
                putBoolean("productionHostCompleted", completed); putBoolean("noActivityObserved", tasksUnchanged)
                putBoolean("sameAccountValidated", sameAccount); putBoolean("emailRegistered", registered)
                putBoolean("extendsSavedExpiry", extendsExpiry); putBoolean("savedGrantNaturallyExpired", naturallyExpired)
                putBoolean("savedRecordsUnchanged", preserved); putBoolean("browserReleased", released)
                putBoolean("nativeCleanupConfirmed", nativeCleanup)
                putInt("retainedResourceCount", synchronized(retained) { retained.size })
                putLong("elapsedMs", SystemClock.elapsedRealtime() - began)
            })
        }
        check(completed && released && nativeCleanup && tasksUnchanged && preserved)
    }

    private fun encryptedRecords(directory: File): Map<String, ByteArray> {
        val began = SystemClock.elapsedRealtime()
        fun bounded() = check(SystemClock.elapsedRealtime() - began in 0 until 5_000)
        check(directory.isDirectory && directory.canRead() && !Files.isSymbolicLink(directory.toPath()))
        val files = checkNotNull(directory.listFiles())
        check(files.none { it.name.endsWith(".enc.bak") || it.name.endsWith(".enc.new") })
        val records = files.filter { it.name.endsWith(".enc") }
        check(records.size in 1..64)
        return records.associate { file ->
            bounded()
            check(file.isFile && file.canRead() && !Files.isSymbolicLink(file.toPath()) &&
                file.length() in 1..(PRIVATE_SECRET_LIMIT + 29).toLong())
            val bytes = ByteArray(PRIVATE_SECRET_LIMIT + 30)
            var size = 0
            file.inputStream().use { input ->
                while (size < bytes.size) {
                    bounded()
                    val count = input.read(bytes, size, bytes.size - size)
                    if (count < 0) break
                    size += count
                }
            }
            try {
                check(size in 1..(PRIVATE_SECRET_LIMIT + 29))
                file.name to MessageDigest.getInstance("SHA-256").apply { update(bytes, 0, size) }.digest()
            } finally { bytes.fill(0) }
        }
    }
}
