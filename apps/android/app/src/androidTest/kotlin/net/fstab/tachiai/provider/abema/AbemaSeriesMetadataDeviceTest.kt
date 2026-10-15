package net.fstab.tachiai.provider.abema

import android.app.Application
import android.content.Intent
import android.os.Build
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

// Explicit provider observation only. Default invocation skips before any
// store/service/foreground access. No configured-source/assignment/grant write,
// account connection, playback, retry or alternate parent is attempted.
class AbemaSeriesMetadataDeviceTest {
    @Test fun optInSavedRouteChecksExactSeriesPageAndOptionalExplicitContinuationWithoutSavedWrites() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("tachiaiReadOnlyAbemaSeries") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        assumeTrue(Application.getProcessName() == instrumentation.targetContext.packageName)
        val seriesId = args.getString("tachiaiAbemaSeriesId") ?: "394-72"
        assumeTrue(validAbemaSeriesId(seriesId))
        val continuation = args.getString("tachiaiAbemaSeriesContinue") == "true"
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
        // No raw exception text/cause, private record name, route/profile name,
        // parent/episode identity, title or version enters instrumentation output.
        try { observe(seriesId, continuation) }
        catch (_: Throwable) { throw AssertionError("Explicit series observation did not satisfy its closed safety/result checks") }
    }

    private fun observe(seriesId: String, continuation: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val instanceId = defaultProviderInstanceId(PrototypeService.ABEMA)
        fun readOwner() = readAbemaCatalogRouteOwner(instanceId,
            { providerInstanceStore(context).read { legacyProviderSettings(context) } },
            { connectionProfileStore(context).selected(it) })
        val before = encryptedRecords(context.noBackupFilesDir)
        val owner = readOwner()
        val foreground = ActivityScenario.launch<net.fstab.tachiai.MainActivity>(
            Intent(context, net.fstab.tachiai.MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN; addCategory(Intent.CATEGORY_LAUNCHER)
                putExtra("net.fstab.tachiai.extra.NATIVE_ACCESS_PROBE", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        val active = AtomicBoolean()
        val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
        val callback = ActivityLifecycleCallback { _, stage ->
            if (stage in setOf(Stage.PAUSED, Stage.STOPPED, Stage.DESTROYED)) active.set(false)
        }
        instrumentation.runOnMainSync {
            active.set(lifecycle.getActivitiesInStage(Stage.RESUMED).isNotEmpty())
            lifecycle.addLifecycleCallback(callback)
        }
        val parent = CatalogResource(ProviderId("abema"), "series", seriesId, CatalogIntent.COLLECTION)
        var catalog: ProviderCatalog? = null
        var first: CatalogResult<CatalogPage>? = null
        var second: CatalogResult<CatalogPage>? = null
        var closeReturned = false
        val started = SystemClock.elapsedRealtime()
        try {
            check(active.get())
            catalog = androidAbemaChannelCatalog(context, instanceId, sourceSetupStore(context).read(), active::get)
            first = catalog.browse(CatalogQuery(parent = parent))
            // A second provider request is a separate explicit test argument;
            // no automatic retry or unversioned continuation/fallback exists.
            if (continuation) {
                val page = checkNotNull((first as? CatalogResult.Value)?.value)
                check(page.entries.size == ABEMA_SERIES_PAGE_LIMIT && page.nextCursor != null)
                second = catalog.browse(CatalogQuery(parent = parent, cursor = page.nextCursor))
            }
        } finally {
            active.set(false)
            try { catalog?.close(); closeReturned = true }
            finally {
                instrumentation.runOnMainSync { lifecycle.removeLifecycleCallback(callback) }
                foreground.close()
            }
        }
        val after = encryptedRecords(context.noBackupFilesDir)
        val storesUnchanged = before.keys == after.keys && before.all { (name, digest) ->
            after[name]?.let { MessageDigest.isEqual(digest, it) } == true
        }
        val ownerUnchanged = owner.sameOwnership(readOwner())
        fun exact(page: CatalogPage?) = page != null && page.entries.size <= ABEMA_SERIES_PAGE_LIMIT &&
            page.entries.map { it.resource.identity }.distinct().size == page.entries.size && page.entries.all { entry ->
                entry.resource.providerId == ProviderId("abema") && entry.resource.kind == "episode" &&
                    entry.resource.intent == CatalogIntent.VIDEO && validAbemaSeriesId(entry.resource.identity) &&
                    safeAbemaSeriesText(entry.title, 160) && entry.availability == CatalogAvailability.UNKNOWN &&
                    entry.scheduledStartEpochMs == null
            }
        val firstPage = (first as? CatalogResult.Value)?.value
        val secondPage = (second as? CatalogResult.Value)?.value
        val success = exact(firstPage) && (!continuation || exact(secondPage) &&
            firstPage!!.entries.none { a -> secondPage!!.entries.any { b -> a.resource == b.resource } })
        val failure = (second as? CatalogResult.Failure) ?: (first as? CatalogResult.Failure)
        val outcome = when {
            !closeReturned -> "CLOSE_FAILED"
            !storesUnchanged -> "STORES_CHANGED"
            !ownerUnchanged -> "OWNER_CHANGED"
            success -> "EXACT_SERIES_PAGE"
            failure != null -> "CATALOG_${failure.reason.name}"
            else -> "INVALID_RESPONSE"
        }
        Log.i("TachiaiAbemaSeriesProbe", "outcome=$outcome sdk=${Build.VERSION.SDK_INT} " +
            "routeMode=${owner.instance.setup.route!!.mode.name} pageCount=${if (second != null) 2 else 1} " +
            "entryCount=${firstPage?.entries?.size ?: 0} continued=$continuation")
        assertTrue("Series observation exceeded bounded operation/cleanup budgets",
            SystemClock.elapsedRealtime() - started in 0 until (if (continuation) 140_000 else 70_000))
        assertTrue("Series observation changed protected records", storesUnchanged)
        assertTrue("Series observation changed its captured owner", ownerUnchanged)
        assertTrue("Series observation did not release its local facade", closeReturned)
        // A refused endpoint/version is honest runtime evidence, never success
        // or an inferred account requirement. Actual acceptance requires this.
        assertTrue("Series observation did not return exact normalized pages", success)
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
