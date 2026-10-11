package net.fstab.tachiai.provider.twitch.catalog

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
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import net.fstab.tachiai.feature.connections.providerInstanceStore
import net.fstab.tachiai.platform.network.connectionProfileStore
import net.fstab.tachiai.platform.storage.AndroidPrivateSecretStore
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import net.fstab.tachiai.provider.twitch.DeviceAuthResponse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

// Explicit opt-in only. The probe cannot consent, refresh, reconnect or write a
// protected grant. It exercises the production collection/session/route classes
// against an already retained grant, with a write-refusing validation wrapper.
class TwitchContinueWatchingDeviceTest {
    @Test fun optInMaintainedAccountReadsOneBoundedContinueWatchingPage() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("tachiaiReadOnlyTwitchContinueWatching") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        assumeTrue(Application.getProcessName() == instrumentation.targetContext.packageName)
        check(Looper.myLooper() != Looper.getMainLooper())
        val instanceId = args.getString("twitchProviderInstance") ?: error("Explicit provider instance required")
        require(validProviderInstanceId(instanceId))
        val avd = try {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name")).use {
                val bytes = ByteArray(129); var size = 0
                while (size < bytes.size) { val count = it.read(bytes, size, bytes.size - size); if (count < 0) break; size += count }
                size <= 128 && String(bytes, 0, size, Charsets.UTF_8).trim() == "tachiai-dev"
            }
        } catch (_: Throwable) { false }
        assumeTrue("Requires verified tachiai-dev AVD", avd)
        try { observe(instanceId) }
        catch (_: Throwable) { throw AssertionError("Explicit Continue Watching observation failed its closed read-only checks") }
    }
    private fun observe(instanceId: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun readOwner() = readTwitchCatalogRouteOwner(instanceId,
            { providerInstanceStore(context).read { legacyProviderSettings(context) } },
            { connectionProfileStore(context).selected(it) })
        val owner = readOwner()
        val before = encryptedRecords(context.noBackupFilesDir)
        val secretStore = AndroidPrivateSecretStore.twitchCatalogInstance(context, instanceId)
        val readOnly = object : PrivateSecretStore {
            override fun read() = secretStore.read()
            override fun write(plaintext: ByteArray): Unit = throw IOException("Read-only probe refuses grant writes")
        }
        val store = TwitchCatalogGrantStore(readOnly, instanceId, File(context.noBackupFilesDir, "twitch-catalog-$instanceId.lock"))
        val grant = checkNotNull(store.read())
        val beganWall = System.currentTimeMillis()
        val started = SystemClock.elapsedRealtime()
        check(grant.state == TwitchCatalogGrantState.READY && store.isStoredCurrent(grant) &&
            checkNotNull(grant.savedAtMs) <= beganWall && checkNotNull(grant.expiresAtMs) - beganWall > 90_000)
        val foreground = ActivityScenario.launch<net.fstab.tachiai.MainActivity>(Intent(context, net.fstab.tachiai.MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN; addCategory(Intent.CATEGORY_LAUNCHER); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        val active = AtomicBoolean()
        val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
        val callback = ActivityLifecycleCallback { _, stage -> if (stage in setOf(Stage.PAUSED, Stage.STOPPED, Stage.DESTROYED)) active.set(false) }
        instrumentation.runOnMainSync {
            active.set(lifecycle.getActivitiesInStage(Stage.RESUMED).isNotEmpty()); lifecycle.addLifecycleCallback(callback)
        }
        val binding = androidTwitchCatalogConnectionBinding(context, instanceId, active::get)
        fun current() = active.get() && SystemClock.elapsedRealtime() - started in 0 until 70_000 &&
            System.currentTimeMillis() - beganWall in 0 until 70_000 && binding.canPublishLocally() &&
            store.isStoredCurrent(grant) && owner.presentation.sameOwnership(binding.owner())
        var catalog: ProviderCatalog? = null
        var result: CatalogResult<CatalogPage>? = null
        try {
            check(current())
            val session = TwitchCatalogSession(store, { gate ->
                val remote = binding.transport { gate() && current() }
                object : TwitchCatalogTransport {
                    override fun device(): DeviceAuthResponse = throw IOException("Read-only probe refuses consent")
                    override fun poll(deviceCode: String): DeviceAuthResponse = throw IOException("Read-only probe refuses polling")
                    override fun refresh(refreshToken: String): DeviceAuthResponse = throw IOException("Read-only probe refuses refresh")
                    override fun validate(accessToken: String): DeviceAuthResponse {
                        val at = SystemClock.elapsedRealtime()
                        val response = remote.validate(accessToken)
                        val validation = try { parseTwitchCatalogValidation(response, grant.userId, grant.localRetentionUntilMs != null) }
                        catch (_: Throwable) { throw IOException("Read-only probe refuses grant mutation") }
                        // A newly finite provider expiry may require a write.
                        // Refuse that fixture case before the session sees it.
                        if (grant.localRetentionUntilMs != null && validation.expiresInMs != null &&
                            (grant.providerExpiresAtMs == null || System.currentTimeMillis() + validation.expiresInMs -
                                (SystemClock.elapsedRealtime() - at) < grant.providerExpiresAtMs))
                            throw IOException("Read-only probe refuses expiry tightening")
                        return response
                    }
                    override fun close() = remote.close()
                }
            }, canCommit = ::current)
            val helix = TwitchProviderCatalog(instanceId, session, { error("This probe never browses Helix") }, canPublish = ::current)
            catalog = TwitchContinueWatchingCatalog(helix, session,
                { gate -> OwnedTwitchContinueWatchingTransport(instanceId, owner.profile, { gate() && current() }) }, ::current)
            // Own endpoint proof and one-use positive seed only. No unrelated
            // Following collection, retry, second page, Add or playback request.
            result = catalog.browse(CatalogQuery(collectionId = TWITCH_CONTINUE_WATCHING_ID))
        } finally {
            active.set(false)
            try { catalog?.close() } finally {
                binding.close()
                instrumentation.runOnMainSync { lifecycle.removeLifecycleCallback(callback) }
                foreground.close()
                val after = encryptedRecords(context.noBackupFilesDir)
                try {
                    assertTrue("Continue Watching probe changed protected records", before.keys == after.keys &&
                        before.all { (name, digest) -> after[name]?.let { MessageDigest.isEqual(digest, it) } == true })
                    assertTrue("Continue Watching probe changed captured route", owner.presentation.sameOwnership(readOwner().presentation))
                } finally { before.values.forEach { it.fill(0) }; after.values.forEach { it.fill(0) } }
            }
        }
        val page = (result as? CatalogResult.Value)?.value
        val exact = page != null && page.entries.size <= TWITCH_CONTINUE_WATCHING_LIMIT && page.nextCursor == null &&
            page.entries.map { it.resource }.distinct().size == page.entries.size && page.entries.all {
                it.resource.providerId == ProviderId("twitch") && it.resource.kind == "video" && it.resource.intent == CatalogIntent.VIDEO &&
                    it.availability == CatalogAvailability.UNKNOWN && it.scheduledStartEpochMs == null }
        val failure = result as? CatalogResult.Failure
        Log.i("TachiaiTwitchContinueProbe", "outcome=${if (exact) "BOUNDED_PAGE_ACCEPTED" else failure?.reason?.name ?: "INVALID_RESPONSE"} " +
            "sdk=${Build.VERSION.SDK_INT} routeMode=${owner.instance.setup.route!!.mode.name} entryCount=${page?.entries?.size ?: 0}")
        assertTrue("Continue Watching probe exceeded operation/cleanup bounds", SystemClock.elapsedRealtime() - started in 0 until 70_000)
        assertTrue("Smart TV collection authorization was not established", exact)
    }
    private fun encryptedRecords(directory: File): Map<String, ByteArray> {
        check(directory.isDirectory && !Files.isSymbolicLink(directory.toPath()))
        val began = SystemClock.elapsedRealtime()
        val files = checkNotNull(directory.listFiles()).filter { it.name.endsWith(".enc") || it.name.endsWith(".enc.bak") || it.name.endsWith(".enc.new") }
        check(files.size in 1..64)
        return files.associate { file ->
            check(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() in 1..(PRIVATE_SECRET_LIMIT + 29).toLong())
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192); var size = 0
                try { while (true) {
                    check(SystemClock.elapsedRealtime() - began in 0 until 5_000)
                    val count = input.read(buffer); if (count < 0) break
                    size += count; check(size <= PRIVATE_SECRET_LIMIT + 29); digest.update(buffer, 0, count)
                } } finally { buffer.fill(0) }
            }
            file.name to digest.digest()
        }
    }
}
