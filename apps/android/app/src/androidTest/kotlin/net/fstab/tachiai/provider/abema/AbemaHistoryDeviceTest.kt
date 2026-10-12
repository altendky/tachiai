package net.fstab.tachiai.provider.abema

import android.app.Application
import android.content.Intent
import android.os.Build
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
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import net.fstab.tachiai.feature.connections.*
import net.fstab.tachiai.platform.network.connectionProfileStore
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

// Run only after owner-approved account connection, with a separate external
// protected-record baseline. Default invocation skips before any private store,
// service or foreground access. No Connect, playback, retry or saved write.
class AbemaHistoryDeviceTest {
    @Test fun optInSavedAccountReadsOneProviderHistoryPageWithoutChangingStores() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("tachiaiReadOnlyAbemaHistory") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        assumeTrue(Application.getProcessName() == instrumentation.targetContext.packageName)
        check(Looper.myLooper() != Looper.getMainLooper())
        val instanceId = args.getString("abemaProviderInstance") ?: error("Explicit provider instance required")
        require(validProviderInstanceId(instanceId))
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
        assumeTrue("Requires verified tachiai-dev AVD", verifiedAvd)
        try { observe(instanceId) }
        catch (_: Throwable) { throw AssertionError("Explicit History observation did not satisfy its closed safety/result checks") }
    }

    private fun observe(instanceId: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun readOwner() = readAbemaCatalogRouteOwner(instanceId,
            { providerInstanceStore(context).read { legacyProviderSettings(context) } },
            { connectionProfileStore(context).selected(it) })
        val before = encryptedRecords(context.noBackupFilesDir)
        val owner = readOwner()
        val grant = File(context.noBackupFilesDir, "abema-account-$instanceId.enc")
        check(grant.isFile && !Files.isSymbolicLink(grant.toPath()) && grant.length() in 1..65536)
        var foreground: ActivityScenario<net.fstab.tachiai.MainActivity>? = null
        val active = AtomicBoolean()
        val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
        val callback = ActivityLifecycleCallback { _, stage ->
            if (stage in setOf(Stage.PAUSED, Stage.STOPPED, Stage.DESTROYED)) active.set(false)
        }
        var registrationStarted = false
        var catalog: ProviderCatalog? = null
        var result: CatalogResult<CatalogPage>? = null
        var closeReturned = false
        val started = SystemClock.elapsedRealtime()
        try {
            foreground = ActivityScenario.launch<net.fstab.tachiai.MainActivity>(
                Intent(context, net.fstab.tachiai.MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN; addCategory(Intent.CATEGORY_LAUNCHER)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            instrumentation.runOnMainSync {
                active.set(lifecycle.getActivitiesInStage(Stage.RESUMED).isNotEmpty())
                registrationStarted = true
                lifecycle.addLifecycleCallback(callback)
            }
            check(active.get())
            catalog = androidAbemaChannelCatalog(context, instanceId, sourceSetupStore(context).read(), active::get)
            // Direct browse establishes only History's own endpoint proof and
            // consumes its one-use first positive page. No unrelated My List
            // capability probe, continuation or automatic retry is requested.
            result = catalog.browse(CatalogQuery(collectionId = AbemaCollectionKind.HISTORY.collectionId))
        } finally {
            active.set(false)
            try { catalog?.close(); closeReturned = true }
            finally {
                try {
                    if (registrationStarted) instrumentation.runOnMainSync { lifecycle.removeLifecycleCallback(callback) }
                } finally {
                    try { foreground?.close() }
                    finally {
                        var after: Map<String, ByteArray>? = null
                        try {
                            val verified = encryptedRecords(context.noBackupFilesDir).also { after = it }
                            assertTrue("History observation changed protected records", before.keys == verified.keys &&
                                before.all { (name, digest) -> verified[name]?.let { MessageDigest.isEqual(digest, it) } == true })
                            assertTrue("History observation changed its captured owner", owner.sameOwnership(readOwner()))
                        } finally { before.values.forEach { it.fill(0) }; after?.values?.forEach { it.fill(0) } }
                    }
                }
            }
        }
        val page = (result as? CatalogResult.Value)?.value
        val exact = page != null && page.entries.size <= ABEMA_MY_LIST_PAGE_LIMIT &&
            page.entries.map { it.resource }.distinct().size == page.entries.size && page.entries.all { entry ->
                entry.resource.providerId == ProviderId("abema") &&
                    ((entry.resource.kind == "episode" && entry.resource.intent == CatalogIntent.VIDEO) ||
                    (entry.resource.kind in setOf("slot", "live_event") && entry.resource.intent == CatalogIntent.BROADCAST)) &&
                    entry.availability == CatalogAvailability.UNKNOWN && entry.scheduledStartEpochMs == null
            }
        val failure = result as? CatalogResult.Failure
        val outcome = when {
            !closeReturned -> "CLOSE_FAILED"
            exact -> "HISTORY_PAGE_ACCEPTED"
            failure != null -> "CATALOG_${failure.reason.name}"
            else -> "INVALID_RESPONSE"
        }
        Log.i("TachiaiAbemaHistoryProbe", "outcome=$outcome sdk=${Build.VERSION.SDK_INT} " +
            "routeMode=${owner.instance.setup.route!!.mode.name} entryCount=${page?.entries?.size ?: 0} " +
            "hasNext=${page?.nextCursor != null}")
        assertTrue("History observation exceeded bounded operation/cleanup budgets",
            SystemClock.elapsedRealtime() - started in 0 until 70_000)
        assertTrue("History observation did not release its local facade", closeReturned)
        assertTrue("Intended-account History access was not established", exact)
    }

    private fun encryptedRecords(directory: File): Map<String, ByteArray> {
        val started = SystemClock.elapsedRealtime()
        fun checkTime() = check(SystemClock.elapsedRealtime() - started in 0 until 5_000)
        check(directory.isDirectory && directory.canRead() && !Files.isSymbolicLink(directory.toPath()))
        val files = checkNotNull(directory.listFiles()).filter { file ->
            file.name.endsWith(".enc") || file.name.endsWith(".enc.bak") || file.name.endsWith(".enc.new")
        }
        check(files.size in 1..64); checkTime()
        return files.associate { file ->
            checkTime()
            check(file.isFile && file.canRead() && !Files.isSymbolicLink(file.toPath()) &&
                file.length() in 1..(PRIVATE_SECRET_LIMIT + 29).toLong())
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192); var size = 0
                try { while (true) {
                    checkTime(); val count = input.read(buffer); if (count < 0) break
                    checkTime(); size += count; check(size <= PRIVATE_SECRET_LIMIT + 29)
                    digest.update(buffer, 0, count)
                } } finally { buffer.fill(0) }
            }
            checkTime(); file.name to digest.digest()
        }
    }
}
