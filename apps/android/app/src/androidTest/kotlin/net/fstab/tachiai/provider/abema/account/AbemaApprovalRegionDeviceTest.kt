package net.fstab.tachiai.provider.abema.account

import android.app.Application
import android.os.Build
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import net.fstab.tachiai.feature.connections.legacyProviderSettings
import net.fstab.tachiai.feature.connections.providerInstanceStore
import net.fstab.tachiai.platform.network.RouteBackend
import net.fstab.tachiai.platform.network.RoutePreparation
import net.fstab.tachiai.platform.network.RouteSession
import net.fstab.tachiai.platform.network.connectionProfileStore
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.presentation.PrototypeService
import net.fstab.tachiai.presentation.defaultProviderInstanceId
import net.fstab.tachiai.presentation.validProviderInstanceId
import net.fstab.tachiai.provider.abema.readAbemaCatalogRouteOwner
import org.junit.Assume.assumeTrue
import org.junit.Test

// Explicit public region observation only. Default runs skip before private
// state. No Connect, token, account profile, MyList, browser, playback, retry or
// store write is attempted; a broken saved route never falls back to System.
class AbemaApprovalRegionDeviceTest {
    companion object {
        private const val LOG_TAG = "TachiaiAbemaApprovalRegionProbe"
        // An uncertain close keeps the real adapter/route/backend until the
        // instrumentation process ends; no retry can release that ownership.
        private val retained = mutableListOf<Owned>()
    }
    private class ClosedFailure(val category: AbemaApprovalRegionFailure) : RuntimeException()
    private class Owned {
        val preparation = RoutePreparation()
        var backend: AbemaAccountCleanupReceipt<RouteBackend>? = null
        var route: RouteSession? = null
        var routeClose: AbemaAccountCleanupReceipt<RouteSession>? = null
        var region: AbemaApprovalRegionCheck? = null
        fun closeConfirmed(): Boolean {
            region?.cancel()
            if (region?.closeConfirmed() == false) return false
            val closed = routeClose?.await() ?: (backend?.await() ?: true)
            return closed && preparation.cleanupConfirmed
        }
    }

    @Test fun optInSavedRouteChecksOneFixedRegionResourceWithoutSavedWrites() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("tachiaiReadOnlyAbemaApprovalRegion") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        assumeTrue(Application.getProcessName() == instrumentation.targetContext.packageName)
        assumeTrue(Looper.myLooper() != Looper.getMainLooper())
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
        try {
            val instanceId = args.getString("abemaProviderInstance")
                ?: defaultProviderInstanceId(PrototypeService.ABEMA)
            if (!validProviderInstanceId(instanceId)) throw ClosedFailure(AbemaApprovalRegionFailure.REFUSED_REQUEST)
            observe(instanceId)
        } catch (error: ClosedFailure) {
            throw AssertionError("REGION_FAILURE_${error.category.name}")
        } catch (_: Throwable) {
            // Provider/private exception messages, causes, request metadata,
            // record names and account/route fields never enter test output.
            throw AssertionError("REGION_FAILURE_TRANSPORT_FAILED")
        }
    }

    private fun observe(instanceId: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun readOwner() = readAbemaCatalogRouteOwner(instanceId,
            { providerInstanceStore(context).read { legacyProviderSettings(context) } },
            { connectionProfileStore(context).selected(it) })
        val before = encryptedRecords(context.noBackupFilesDir)
        val owner = readOwner()
        val profile = owner.profile ?: throw ClosedFailure(AbemaApprovalRegionFailure.REFUSED_REQUEST)
        val started = SystemClock.elapsedRealtime()
        val deadline = started + 45_000
        val owned = Owned()
        var result: AbemaApprovalRegionResult? = null
        try {
            owned.route = RouteSession.createAbemaApprovalBrowser(profile, owned.preparation,
                ownBackend = { backend ->
                    val receipt = AbemaAccountCleanupReceipt(backend, RouteBackend::close)
                    owned.backend = receipt
                    object : RouteBackend {
                        override val proxyPort get() = backend.proxyPort
                        override fun close() { check(receipt.await()) }
                    }
                })
            val route = checkNotNull(owned.route)
            owned.routeClose = AbemaAccountCleanupReceipt(route, RouteSession::close)
            val region = AbemaApprovalRegionCheck(route,
                { SystemClock.elapsedRealtime() < deadline && owner.sameOwnership(readOwner()) },
                SystemClock::elapsedRealtime, deadline)
            owned.region = region
            // Exactly one cookie-free fixed GET. Origin is a fixed public
            // first-party value, not copied from a private browser request.
            result = region.check("https://abema.tv")
        } finally {
            val confirmed = runCatching { owned.closeConfirmed() }.getOrDefault(false)
            if (!confirmed) {
                synchronized(retained) { retained.add(owned) }
                // Never publish a provider result after uncertain cleanup.
                throw ClosedFailure(AbemaApprovalRegionFailure.CLEANUP_FAILED)
            }
        }
        val after = encryptedRecords(context.noBackupFilesDir)
        if (before.keys != after.keys || before.any { (name, digest) ->
            after[name]?.let { MessageDigest.isEqual(digest, it) } != true
        }) throw ClosedFailure(AbemaApprovalRegionFailure.REFUSED_REQUEST)
        if (!owner.sameOwnership(readOwner())) throw ClosedFailure(AbemaApprovalRegionFailure.OWNER_NOT_CURRENT)
        if (SystemClock.elapsedRealtime() - started !in 0 until 45_000)
            throw ClosedFailure(AbemaApprovalRegionFailure.EXPIRED)
        val outcome = when (val reply = result) {
            is AbemaApprovalRegionResult.Reply -> when (reply.status) {
                200 -> "REGION_HTTP_200"
                403 -> "REGION_HTTP_403"
                else -> "REGION_HTTP_OTHER"
            }
            is AbemaApprovalRegionResult.Failure -> "REGION_FAILURE_${reply.category.name}"
            null -> "REGION_FAILURE_TRANSPORT_FAILED"
        }
        Log.i(LOG_TAG, outcome)
        // A real 403 is valid diagnostic evidence, never rewritten as allowed.
        // A transport/refusal category does not establish provider eligibility.
        if (result !is AbemaApprovalRegionResult.Reply)
            throw ClosedFailure((result as? AbemaApprovalRegionResult.Failure)?.category
                ?: AbemaApprovalRegionFailure.TRANSPORT_FAILED)
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
                try {
                    while (true) {
                        checkTime(); val count = input.read(buffer); if (count < 0) break
                        checkTime(); size += count; check(size <= PRIVATE_SECRET_LIMIT + 29)
                        digest.update(buffer, 0, count)
                    }
                } finally { buffer.fill(0) }
            }
            checkTime(); file.name to digest.digest()
        }
    }
}
