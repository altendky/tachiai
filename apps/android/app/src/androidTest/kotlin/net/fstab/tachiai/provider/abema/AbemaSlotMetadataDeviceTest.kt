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

// Explicit one-slot acceptance observation. Defaults skip before protected
// stores/services/UI. No persistent configuration, account or checkpoint writes.
class AbemaSlotMetadataDeviceTest {
    @Test fun optInSavedRouteRefreshesOnlyExplicitExactSlotAndPreservesProtectedRecords() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("tachiaiReadOnlyAbemaSlot") == "true")
        val channelId = args.getString("tachiaiAbemaSlotChannel")
        val slotId = args.getString("tachiaiAbemaSlotId")
        assumeTrue(channelId != null && slotId != null && AbemaSlotRequest.validId(channelId) && AbemaSlotRequest.validId(slotId))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        assumeTrue(Application.getProcessName() == instrumentation.targetContext.packageName)
        val avd = try {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name")).use {
                val bytes = ByteArray(129); var size = 0
                while (size < bytes.size) { val n = it.read(bytes, size, bytes.size - size); if (n < 0) break; size += n }
                size <= 128 && String(bytes, 0, size, Charsets.UTF_8).trim() == "tachiai-dev"
            }
        } catch (_: Throwable) { false }
        assumeTrue("Requires verified tachiai-dev", avd)
        try { observe(AbemaSlotRequest(checkNotNull(channelId), checkNotNull(slotId))) }
        catch (_: Throwable) { throw AssertionError("Explicit slot observation did not satisfy closed safety/result checks") }
    }
    private fun observe(slot: AbemaSlotRequest) {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val instanceId = defaultProviderInstanceId(PrototypeService.ABEMA)
        fun readOwner() = readAbemaCatalogRouteOwner(instanceId,
            { providerInstanceStore(context).read { legacyProviderSettings(context) } },
            { connectionProfileStore(context).selected(it) })
        val before = encryptedRecords(context.noBackupFilesDir); val owner = readOwner()
        check(owner.profile != null && owner.instance.setup.route?.mode == SourceRouteMode.SAVED_CONNECTION)
        val foreground = ActivityScenario.launch<net.fstab.tachiai.MainActivity>(Intent(context, net.fstab.tachiai.MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN; addCategory(Intent.CATEGORY_LAUNCHER); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        val active = AtomicBoolean(); val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
        val callback = ActivityLifecycleCallback { _, stage ->
            if (stage in setOf(Stage.PAUSED, Stage.STOPPED, Stage.DESTROYED)) active.set(false)
        }
        instrumentation.runOnMainSync {
            active.set(lifecycle.getActivitiesInStage(Stage.RESUMED).isNotEmpty()); lifecycle.addLifecycleCallback(callback)
        }
        var catalog: ProviderCatalog? = null; var result: CatalogResult<CatalogEntry>? = null; var closed = false
        val started = SystemClock.elapsedRealtime()
        try {
            check(active.get())
            catalog = androidAbemaChannelCatalog(context, instanceId, sourceSetupStore(context).read(), active::get)
            result = catalog.refresh(slot.resource)
        } finally {
            active.set(false)
            try { catalog?.close(); closed = true }
            finally { instrumentation.runOnMainSync { lifecycle.removeLifecycleCallback(callback) }; foreground.close() }
        }
        val after = encryptedRecords(context.noBackupFilesDir)
        val unchanged = before.keys == after.keys && before.all { (name, digest) -> after[name]?.let { MessageDigest.isEqual(digest, it) } == true }
        val current = owner.sameOwnership(readOwner())
        val entry = (result as? CatalogResult.Value)?.value
        val exact = entry != null && entry.resource == slot.resource && entry.availability == CatalogAvailability.UNKNOWN && entry.scheduledStartEpochMs == null
        val outcome = when {
            !closed -> "CLOSE_FAILED"; !unchanged -> "STORES_CHANGED"; !current -> "OWNER_CHANGED"
            exact -> "EXACT_SLOT_METADATA"; result is CatalogResult.Failure -> "CATALOG_${result.reason.name}"
            else -> "INVALID_RESPONSE"
        }
        // Fixed categories/context only; no IDs, title, body, grant, URI, proof,
        // private record names/hashes or exception text is emitted.
        Log.i("TachiaiAbemaSlotProbe", "outcome=$outcome sdk=${Build.VERSION.SDK_INT} routeMode=${owner.instance.setup.route!!.mode.name}")
        assertTrue("Slot observation exceeded its bounded budgets", SystemClock.elapsedRealtime() - started in 0 until 70_000)
        assertTrue("Slot observation changed protected records", unchanged)
        assertTrue("Slot observation changed its captured owner", current)
        assertTrue("Slot observation did not release its local facade", closed)
        assertTrue("Slot observation did not return exact normalized metadata", exact)
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
