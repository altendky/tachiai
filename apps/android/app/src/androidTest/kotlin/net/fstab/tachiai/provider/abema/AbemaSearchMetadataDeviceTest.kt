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

// Both observations skip before private reads, services or UI by default.
// Public metadata only: no capabilities/account validation, Add or playback.
class AbemaSearchMetadataDeviceTest {
    @Test fun optInOnePublicSummaryAndAtMostOneDetailPreserveProtectedRecords() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tachiaiReadOnlyAbemaSearch") == "true")
        guarded { observe(null) }
    }
    @Test fun optInOneSeparatePublicSeasonCandidateSearchPreserveProtectedRecords() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("tachiaiReadOnlyAbemaSeasonCandidateSearch") == "true")
        // A separate fixed public query, not an inferred or fabricated Season ID.
        guarded { observe(null, "呪術廻戦") }
    }
    @Test fun optInOneExactSeasonPageUsesOnlyAnExplicitPublicCandidate() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("tachiaiReadOnlyAbemaSeason") == "true")
        val series = args.getString("tachiaiAbemaSeriesId")
        val season = args.getString("tachiaiAbemaSeasonId")
        assumeTrue(series != null && season != null && validAbemaSeriesId(series) && validAbemaSeriesId(season))
        val parent = abemaSearchResource("season", "$series/$season")
        assumeTrue(parent != null)
        guarded { observe(checkNotNull(parent)) }
    }
    private fun guarded(action: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        assumeTrue(Application.getProcessName() == instrumentation.targetContext.packageName)
        val avd = try {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name")).use {
                val bytes = ByteArray(129); var size = 0
                while (size < bytes.size) { val count = it.read(bytes, size, bytes.size - size); if (count < 0) break; size += count }
                size <= 128 && String(bytes, 0, size, Charsets.UTF_8).trim() == "tachiai-dev"
            }
        } catch (_: Throwable) { false }
        assumeTrue("Requires verified tachiai-dev", avd)
        try { action() } catch (_: Throwable) { throw AssertionError("Public Search/Season observation failed closed safety/result checks") }
    }
    private fun observe(parent: CatalogResource?, publicQuery: String = "相撲") {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val instanceId = defaultProviderInstanceId(PrototypeService.ABEMA)
        fun readOwner() = readAbemaCatalogRouteOwner(instanceId,
            { providerInstanceStore(context).read { legacyProviderSettings(context) } },
            { connectionProfileStore(context).selected(it) })
        val before = encryptedRecords(context.noBackupFilesDir); val owner = readOwner()
        check(owner.profile != null && owner.instance.setup.route?.mode == SourceRouteMode.SAVED_CONNECTION)
        val foreground = ActivityScenario.launch<net.fstab.tachiai.MainActivity>(Intent(context, net.fstab.tachiai.MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN; addCategory(Intent.CATEGORY_LAUNCHER); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("net.fstab.tachiai.extra.NATIVE_ACCESS_PROBE", true)
        })
        val active = AtomicBoolean(); val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
        val callback = ActivityLifecycleCallback { _, stage ->
            if (stage in setOf(Stage.PAUSED, Stage.STOPPED, Stage.DESTROYED)) active.set(false)
        }
        instrumentation.runOnMainSync {
            active.set(lifecycle.getActivitiesInStage(Stage.RESUMED).isNotEmpty()); lifecycle.addLifecycleCallback(callback)
        }
        var catalog: ProviderCatalog? = null; var first: CatalogResult<CatalogPage>? = null; var second: CatalogResult<CatalogPage>? = null
        var closed = false; val started = SystemClock.elapsedRealtime()
        try {
            check(active.get() && owner.sameOwnership(readOwner()))
            catalog = androidAbemaChannelCatalog(context, instanceId, sourceSetupStore(context).read(), active::get)
            val query = if (parent == null) CatalogQuery(search = publicQuery) else CatalogQuery(parent = parent)
            first = catalog.browse(query)
            val page = (first as? CatalogResult.Value)?.value
            if (parent == null && page?.nextCursor != null) {
                check(active.get() && owner.sameOwnership(readOwner()))
                second = catalog.browse(query.copy(cursor = page.nextCursor))
            }
        } finally {
            active.set(false)
            try { catalog?.close(); closed = true }
            finally { instrumentation.runOnMainSync { lifecycle.removeLifecycleCallback(callback) }; foreground.close() }
        }
        val after = encryptedRecords(context.noBackupFilesDir)
        val unchanged = before.keys == after.keys && before.all { (name, digest) -> after[name]?.let { MessageDigest.isEqual(digest, it) } == true }
        val current = owner.sameOwnership(readOwner())
        val firstPage = (first as? CatalogResult.Value)?.value; val secondPage = (second as? CatalogResult.Value)?.value
        fun exact(page: CatalogPage?, limit: Int) = page != null && page.entries.size <= limit &&
            page.entries.map { it.resource }.distinct().size == page.entries.size && page.entries.all {
                abemaSearchResource(it.resource.kind, it.resource.identity) == it.resource &&
                    (parent == null || it.resource.kind == "episode") && safeAbemaSeriesText(it.title, 160) &&
                    it.availability == CatalogAvailability.UNKNOWN && it.scheduledStartEpochMs == null
            }
        val success = exact(firstPage, if (parent == null) 48 else 20) && (second == null || exact(secondPage, 20) &&
            firstPage!!.entries.none { a -> secondPage!!.entries.any { b -> a.resource == b.resource } })
        val failure = (second as? CatalogResult.Failure) ?: (first as? CatalogResult.Failure)
        val outcome = when {
            !closed -> "CLOSE_FAILED"; !unchanged -> "STORES_CHANGED"; !current -> "OWNER_CHANGED"
            success -> if (parent == null) "SEARCH_PAGE" else "EXACT_SEASON_PAGE"
            failure != null -> "CATALOG_${failure.reason.name}"; else -> "INVALID_RESPONSE"
        }
        val entries = firstPage?.entries.orEmpty() + secondPage?.entries.orEmpty()
        // Only public parent candidates are emitted, after cleanup and checks.
        // No titles, query, account identity, URI, grant, proof, private names or
        // protected record hashes are logged. This is not playback evidence.
        val candidate = if (closed && unchanged && current && success && parent == null)
            entries.firstNotNullOfOrNull { abemaSeasonParent(it.resource) } else null
        Log.i("TachiaiAbemaSearchProbe", "outcome=$outcome pages=${if (second == null) 1 else 2} count=${entries.size} " +
            "kinds=${entries.map { it.resource.kind }.distinct().sorted().joinToString(",")} " +
            "more=${(secondPage ?: firstPage)?.nextCursor != null} candidateSeries=${candidate?.first ?: "NONE"} " +
            "candidateSeason=${candidate?.second ?: "NONE"} sdk=${Build.VERSION.SDK_INT} routeMode=${owner.instance.setup.route!!.mode.name}")
        assertTrue("Public observation exceeded bounded budgets", SystemClock.elapsedRealtime() - started in 0 until (if (parent == null) 140_000 else 70_000))
        assertTrue("Public observation changed protected records", unchanged)
        assertTrue("Public observation changed captured owner", current)
        assertTrue("Public observation did not release local facade", closed)
        assertTrue("Public observation did not return strict normalized metadata", success)
    }
    private fun encryptedRecords(directory: File): Map<String, ByteArray> {
        val started = SystemClock.elapsedRealtime()
        fun checkTime() = check(SystemClock.elapsedRealtime() - started in 0 until 5000)
        check(directory.isDirectory && directory.canRead() && !Files.isSymbolicLink(directory.toPath()))
        val files = checkNotNull(directory.listFiles()).filter { it.name.endsWith(".enc") || it.name.endsWith(".enc.bak") || it.name.endsWith(".enc.new") }
        check(files.size in 1..64); checkTime()
        return files.associate { file ->
            checkTime(); check(file.isFile && file.canRead() && !Files.isSymbolicLink(file.toPath()) && file.length() in 1..(PRIVATE_SECRET_LIMIT + 29).toLong())
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192); var size = 0
                try { while (true) {
                    checkTime(); val count = input.read(buffer); if (count < 0) break
                    checkTime(); size += count; check(size <= PRIVATE_SECRET_LIMIT + 29); digest.update(buffer, 0, count)
                } } finally { buffer.fill(0) }
            }
            checkTime(); file.name to digest.digest()
        }
    }
}
