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
import net.fstab.tachiai.feature.connections.legacyProviderSettings
import net.fstab.tachiai.feature.connections.providerInstanceStore
import net.fstab.tachiai.platform.network.connectionProfileStore
import net.fstab.tachiai.platform.storage.AndroidPrivateSecretStore
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.platform.storage.PrivateSecretStore
import net.fstab.tachiai.platform.storage.twitchCatalogInstanceBindingName
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import net.fstab.tachiai.provider.twitch.DeviceAuthResponse
import net.fstab.tachiai.provider.twitch.TwitchAccessCase
import net.fstab.tachiai.provider.twitch.validTwitchPlaybackResource
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

// Opt-in retained-account collection acceptance, separate from account setup.
// One positive validation, the production sentinel-denial proof, then one
// intended-account Continue Watching page. No retirement, capability sweep,
// Helix, refresh, pagination, Add or playback; responses pass through unchanged.
class TwitchContinueWatchingDeviceTest {
    private enum class ProbeStage { SETUP, VALIDATION_HTTP, VALIDATION_PARSE, VALIDATION_READY,
        COLLECTION_ADMISSION, SENTINEL_HTTP, SENTINEL_READY, ACCOUNT_HTTP, ACCOUNT_READY, PAGE_READY }
    private enum class ProbeFailure { NONE, ADMISSION, AUTH_TRANSPORT, AUTH_HTTP, AUTH_PARSE,
        EXPIRY_TIGHTENING, COLLECTION_SCOPE, COLLECTION_TRANSPORT, SENTINEL_PROOF, ACCOUNT_HTTP,
        ACCOUNT_BINDING, ACCOUNT_SHAPE, OTHER_SCOPE }
    companion object {
        private const val BUDGET_MS = 70_000L
        private const val PROOF_MS = 30_000L
        private val retained = mutableListOf<List<Any>>()
    }

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
        assumeTrue("Requires verified tachiai-dev AVD", avd)
        try { observe(instanceId) }
        catch (_: Throwable) { throw AssertionError("Explicit Continue Watching observation failed its closed read-only checks") }
    }

    private fun observe(instanceId: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = context.noBackupFilesDir
        val lockFile = File(directory, "twitch-catalog-$instanceId.lock")
        val grantFile = File(directory, "${twitchCatalogInstanceBindingName(instanceId)}.enc")
        // A normal AtomicFile read may recover a backup. Refuse known unfinished
        // state instead. These checks assume an idle device, not a global lock
        // against another process starting a write between filesystem checks.
        fun requireStableRead() {
            check(directory.isDirectory && directory.canRead() && !Files.isSymbolicLink(directory.toPath()))
            val files = checkNotNull(directory.listFiles())
            check(files.none { it.name.endsWith(".enc.bak") || it.name.endsWith(".enc.new") })
            check(lockFile.isFile && lockFile.canRead() && lockFile.canWrite() && !Files.isSymbolicLink(lockFile.toPath()))
            check(grantFile.isFile && !Files.isSymbolicLink(grantFile.toPath()) && grantFile.length() in 1..65536)
            check(!TwitchCatalogConnectionWrites.isPending(instanceId) && !TwitchCatalogConnectionWrites.isFailed(instanceId) &&
                !TwitchContinueWatchingAdmission.poisoned(instanceId))
        }
        fun readOwner(): TwitchCatalogRouteOwner {
            requireStableRead()
            return readTwitchCatalogRouteOwner(instanceId,
                { requireStableRead(); providerInstanceStore(context).read { legacyProviderSettings(context) } },
                { requireStableRead(); connectionProfileStore(context).selected(it) })
        }
        var before: Map<String, ByteArray>? = null
        val active = AtomicBoolean()
        val cleanupFailed = AtomicBoolean()
        val mutationRefused = AtomicBoolean()
        val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
        val callback = ActivityLifecycleCallback { _, stage ->
            if (stage in setOf(Stage.PAUSED, Stage.STOPPED, Stage.DESTROYED)) active.set(false)
        }
        var registrationStarted = false
        var foreground: ActivityScenario<net.fstab.tachiai.MainActivity>? = null
        var captured: TwitchCatalogRouteOwner? = null
        var session: TwitchCatalogSession? = null
        var delegate: TwitchProviderCatalog? = null
        var catalog: TwitchContinueWatchingCatalog? = null
        val transports = mutableListOf<AutoCloseable>()
        val collections = mutableListOf<OwnedTwitchContinueWatchingTransport>()
        var result: CatalogResult<CatalogPage>? = null
        var closeReturned = false
        var validationRequests = 0
        var collectionOwners = 0
        var collectionRequests = 0
        var sentinelRequests = 0
        var accountRequests = 0
        var stage = ProbeStage.SETUP
        var probeFailure = ProbeFailure.NONE
        var authFailure: TwitchCatalogAuthFailure? = null
        var collectionFailure: TwitchContinueWatchingFailure? = null
        var validationHttp = -1
        var sentinelHttp = -1
        var accountHttp = -1
        var typedEntryCount = -1
        var expectedEntries: List<CatalogEntry>? = null
        var projectionMatches = false
        var accountBound = false
        var typedPage = false
        var proofStarted = -1L
        var proofCompleted = false
        var collectionCleanupConfirmed = false
        fun admitted(value: Boolean, failure: ProbeFailure) {
            if (!value) {
                probeFailure = failure
                throw IOException("Read-only Continue Watching observation refused its closed admission check")
            }
        }
        val beganWall = System.currentTimeMillis()
        val started = SystemClock.elapsedRealtime()
        try {
            requireStableRead()
            before = encryptedRecords(directory)
            val owner = readOwner().also { captured = it }
            val secret = AndroidPrivateSecretStore.twitchCatalogInstance(context, instanceId)
            val readOnly = object : PrivateSecretStore {
                override fun read(): ByteArray? { requireStableRead(); return secret.read() }
                override fun write(plaintext: ByteArray): Unit {
                    mutationRefused.set(true)
                    throw IOException("Read-only observation refuses grant mutation")
                }
            }
            val store = TwitchCatalogGrantStore(readOnly, instanceId, lockFile)
            val grant = checkNotNull(store.read())
            check(grant.state == TwitchCatalogGrantState.READY && store.isStoredCurrent(grant) &&
                checkNotNull(grant.savedAtMs) <= beganWall && checkNotNull(grant.expiresAtMs) - beganWall > 90_000)
            fun current(): Boolean {
                if (!active.get() || cleanupFailed.get() || mutationRefused.get() ||
                    SystemClock.elapsedRealtime() - started !in 0 until BUDGET_MS ||
                    System.currentTimeMillis() - beganWall !in 0 until BUDGET_MS) return false
                requireStableRead()
                return store.isStoredCurrent(grant) && System.currentTimeMillis() < checkNotNull(grant.expiresAtMs) &&
                    owner.presentation.sameOwnership(readOwner().presentation)
            }

            foreground = ActivityScenario.launch<net.fstab.tachiai.MainActivity>(
                Intent(context, net.fstab.tachiai.MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN; addCategory(Intent.CATEGORY_LAUNCHER)
                    putExtra("net.fstab.tachiai.extra.NATIVE_ACCESS_PROBE", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            instrumentation.runOnMainSync {
                active.set(lifecycle.getActivitiesInStage(Stage.RESUMED).isNotEmpty())
                registrationStarted = true
                lifecycle.addLifecycleCallback(callback)
            }
            check(current())
            val accountSession = TwitchCatalogSession(store, { gate ->
                val remote = OwnedTwitchCatalogTransport(owner.profile, { gate() && current() },
                    onCleanupFailure = { cleanupFailed.set(true) }).also { transports.add(it) }
                object : TwitchCatalogTransport {
                    override fun device(): DeviceAuthResponse = throw IOException("Read-only observation refuses consent")
                    override fun poll(deviceCode: String): DeviceAuthResponse = throw IOException("Read-only observation refuses polling")
                    override fun refresh(refreshToken: String): DeviceAuthResponse = throw IOException("Read-only observation refuses refresh")
                    override fun validate(accessToken: String): DeviceAuthResponse {
                        admitted(current() && validationRequests == 0 && accessToken == checkNotNull(grant.credentials).accessToken,
                            ProbeFailure.ADMISSION)
                        validationRequests++
                        stage = ProbeStage.VALIDATION_HTTP
                        val response = try { remote.validate(accessToken) }
                        catch (error: TwitchCatalogAuthException) {
                            authFailure = error.failure; probeFailure = ProbeFailure.AUTH_TRANSPORT
                            throw IOException("Read-only observation refused validation transport")
                        } catch (_: IOException) {
                            probeFailure = ProbeFailure.AUTH_TRANSPORT
                            throw IOException("Read-only observation refused validation transport")
                        }
                        validationHttp = response.status.takeIf { it in 100..599 } ?: -1
                        admitted(response.status == 200, ProbeFailure.AUTH_HTTP)
                        stage = ProbeStage.VALIDATION_PARSE
                        val validation = try {
                            parseTwitchCatalogValidation(response, grant.userId, grant.localRetentionUntilMs != null)
                        } catch (error: TwitchCatalogAuthException) {
                            authFailure = error.failure; probeFailure = ProbeFailure.AUTH_PARSE
                            throw IOException("Read-only observation refuses invalid validation")
                        } catch (_: Throwable) {
                            probeFailure = ProbeFailure.AUTH_PARSE
                            throw IOException("Read-only observation refuses invalid validation")
                        }
                        // Session validation starts after this operation's
                        // start, on the same monotonic clock. Subtract the full
                        // operation budget from its earlier wall-clock bound;
                        // this deliberately understates the finite expiry and
                        // refuses uncertain tightening before session handling.
                        val finite = validation.expiresInMs
                        if (grant.localRetentionUntilMs != null && finite != null) {
                            admitted(beganWall >= 0 && finite <= Long.MAX_VALUE - beganWall && grant.providerExpiresAtMs != null &&
                                beganWall + finite - BUDGET_MS >= grant.providerExpiresAtMs, ProbeFailure.EXPIRY_TIGHTENING)
                        }
                        admitted(current(), ProbeFailure.ADMISSION)
                        stage = ProbeStage.VALIDATION_READY
                        return response
                    }
                    override fun close() = remote.close()
                }
            }, monotonicMs = SystemClock::elapsedRealtime, canCommit = ::current).also { session = it }
            val publicCatalog = TwitchProviderCatalog(instanceId, accountSession, {
                probeFailure = ProbeFailure.OTHER_SCOPE
                throw IOException("Read-only observation refuses Helix")
            }, canPublish = ::current).also { delegate = it }
            catalog = TwitchContinueWatchingCatalog(publicCatalog, accountSession, { gate ->
                stage = ProbeStage.COLLECTION_ADMISSION
                admitted(current() && validationRequests == 1 && collections.size < 2, ProbeFailure.COLLECTION_SCOPE)
                if (proofStarted < 0) proofStarted = SystemClock.elapsedRealtime()
                admitted(SystemClock.elapsedRealtime() - proofStarted in 0 until PROOF_MS, ProbeFailure.ADMISSION)
                val remote = OwnedTwitchContinueWatchingTransport(instanceId, owner.profile, { gate() && current() })
                    .also { collections.add(it); transports.add(it); collectionOwners++ }
                var fetched = false
                object : TwitchContinueWatchingTransport {
                    override val cleanupConfirmed get() = remote.cleanupConfirmed
                    override fun fetch(accessToken: String, expectedUserId: String, nowEpochMs: Long): TwitchContinueWatchingResponse {
                        stage = ProbeStage.COLLECTION_ADMISSION
                        admitted(!fetched && current() && expectedUserId == grant.userId && collectionRequests < 2 &&
                            nowEpochMs - beganWall in 0 until BUDGET_MS &&
                            SystemClock.elapsedRealtime() - proofStarted in 0 until PROOF_MS, ProbeFailure.COLLECTION_SCOPE)
                        val sentinel = collectionRequests == 0
                        if (sentinel) {
                            admitted(accessToken == "tachiai-invalid-bearer", ProbeFailure.COLLECTION_SCOPE)
                            sentinelRequests++
                            stage = ProbeStage.SENTINEL_HTTP
                        } else {
                            admitted(sentinelHttp in setOf(401, 403) && accessToken == checkNotNull(grant.credentials).accessToken,
                                ProbeFailure.COLLECTION_SCOPE)
                            accountRequests++
                            stage = ProbeStage.ACCOUNT_HTTP
                        }
                        fetched = true; collectionRequests++
                        val response = try { remote.fetch(accessToken, expectedUserId, nowEpochMs) }
                        catch (error: TwitchContinueWatchingException) {
                            collectionFailure = error.category; probeFailure = ProbeFailure.COLLECTION_TRANSPORT
                            // The transport may throw during JSON/projection;
                            // no response means its HTTP status stays unknown.
                            throw error
                        }
                        if (sentinel) sentinelHttp = response.status else accountHttp = response.status
                        if (!remote.cleanupConfirmed) {
                            cleanupFailed.set(true)
                            throw IOException("Read-only observation could not confirm collection cleanup")
                        }
                        admitted(response.collectionId == TWITCH_CONTINUE_WATCHING_ID && current(), ProbeFailure.ADMISSION)
                        if (sentinel) {
                            admitted(response.status in setOf(401, 403), ProbeFailure.SENTINEL_PROOF)
                            stage = ProbeStage.SENTINEL_READY
                        } else {
                            admitted(response.status == 200, ProbeFailure.ACCOUNT_HTTP)
                            val page = checkNotNull(response.page)
                            accountBound = page.userId == grant.userId
                            admitted(accountBound, ProbeFailure.ACCOUNT_BINDING)
                            typedEntryCount = page.entries.size
                            typedPage = validPage(page.entries)
                            admitted(typedPage, ProbeFailure.ACCOUNT_SHAPE)
                            expectedEntries = page.entries.toList()
                            stage = ProbeStage.ACCOUNT_READY
                        }
                        admitted(SystemClock.elapsedRealtime() - proofStarted in 0 until PROOF_MS, ProbeFailure.ADMISSION)
                        return response
                    }
                    override fun cancelActiveRequest() = remote.cancelActiveRequest()
                    override fun close() = remote.close()
                }
            }, ::current, monotonicMs = SystemClock::elapsedRealtime)
            result = catalog.browse(CatalogQuery(collectionId = TWITCH_CONTINUE_WATCHING_ID))
            projectionMatches = expectedEntries != null &&
                (result as? CatalogResult.Value)?.value?.entries == expectedEntries
            proofCompleted = result is CatalogResult.Value && proofStarted >= 0 &&
                SystemClock.elapsedRealtime() - proofStarted in 0 until PROOF_MS
            if (result is CatalogResult.Value) stage = ProbeStage.PAGE_READY
        } finally {
            expectedEntries = null
            active.set(false)
            try {
                try { catalog?.close() } catch (_: Throwable) { cleanupFailed.set(true) }
                try { delegate?.close() } catch (_: Throwable) { cleanupFailed.set(true) }
                try { session?.close() } catch (_: Throwable) { cleanupFailed.set(true) }
                for (transport in transports) {
                    try { transport.close() } catch (_: Throwable) { cleanupFailed.set(true) }
                }
                collectionCleanupConfirmed = collections.size == collectionOwners && collections.all { it.cleanupConfirmed }
                if (!collectionCleanupConfirmed) cleanupFailed.set(true)
                closeReturned = !cleanupFailed.get()
            } finally {
                try {
                    if (registrationStarted) instrumentation.runOnMainSync { lifecycle.removeLifecycleCallback(callback) }
                } finally {
                    try { foreground?.close() }
                    finally {
                        var after: Map<String, ByteArray>? = null
                        try {
                            try {
                                val verified = encryptedRecords(directory).also { after = it }
                                val baseline = checkNotNull(before)
                                assertTrue("Continue Watching observation changed protected records", baseline.keys == verified.keys &&
                                    baseline.all { (name, digest) -> verified[name]?.let { MessageDigest.isEqual(digest, it) } == true })
                            } finally {
                                // Ownership verification still runs after a
                                // failed after-snapshot or digest comparison.
                                captured?.let { owner -> assertTrue("Continue Watching observation changed captured route",
                                    owner.presentation.sameOwnership(readOwner().presentation)) }
                            }
                        } finally {
                            before?.values?.forEach { it.fill(0) }; after?.values?.forEach { it.fill(0) }
                            if (cleanupFailed.get()) synchronized(retained) {
                                retained.add(transports.toList() + listOfNotNull(catalog, delegate, session, foreground))
                            } else { transports.clear(); collections.clear() }
                        }
                    }
                }
            }
        }
        val page = (result as? CatalogResult.Value)?.value
        val exact = page != null && page.nextCursor == null && validPage(page.entries) &&
            page.entries.size == typedEntryCount && accountBound && typedPage && projectionMatches
        val failure = result as? CatalogResult.Failure
        val withinBudget = SystemClock.elapsedRealtime() - started in 0 until BUDGET_MS
        val requestScope = validationRequests == 1 && collectionOwners == 2 && collectionRequests == 2 &&
            sentinelRequests == 1 && accountRequests == 1 && sentinelHttp in setOf(401, 403) && accountHttp == 200
        val accepted = exact && withinBudget && proofCompleted && closeReturned && collectionCleanupConfirmed &&
            !mutationRefused.get() && requestScope
        // Diagnostics leave only after record/owner verification and confirmed
        // cleanup. A thrown response decoder never supplies an inferred status.
        if (closeReturned) Log.i("TachiaiTwitchContinueProbe",
            "outcome=${if (accepted) "BOUNDED_PAGE_ACCEPTED" else failure?.reason?.name ?: "INVALID_RESPONSE"} " +
            "sdk=${Build.VERSION.SDK_INT} routeMode=${captured?.instance?.setup?.route?.mode?.name ?: "UNAVAILABLE"} " +
            "entryCount=${page?.entries?.size ?: 0} hasNext=${page?.nextCursor != null} " +
            "validationRequests=$validationRequests collectionOwners=$collectionOwners collectionRequests=$collectionRequests " +
            "sentinelRequests=$sentinelRequests accountRequests=$accountRequests stage=${stage.name} " +
            "failure=${probeFailure.name} authFailure=${authFailure?.name ?: "NONE"} " +
            "collectionFailure=${collectionFailure?.name ?: "NONE"} validationHttp=$validationHttp " +
            "sentinelHttp=$sentinelHttp accountHttp=$accountHttp typedEntryCount=$typedEntryCount " +
            "accountBound=$accountBound typedPage=$typedPage projectionMatches=$projectionMatches proofCompleted=$proofCompleted " +
            "collectionCleanupConfirmed=$collectionCleanupConfirmed")
        assertTrue("Continue Watching observation exceeded operation/cleanup bounds", withinBudget)
        assertTrue("Continue Watching observation did not confirm cleanup", closeReturned && collectionCleanupConfirmed)
        assertTrue("Continue Watching observation attempted grant mutation", !mutationRefused.get())
        assertTrue("Continue Watching observation exceeded its request scope", requestScope)
        assertTrue("Continue Watching observation exceeded its proof budget", proofCompleted)
        assertTrue("Intended-account Continue Watching access was not established", exact)
    }

    private fun validPage(entries: List<CatalogEntry>): Boolean =
        entries.size <= TWITCH_CONTINUE_WATCHING_LIMIT &&
            entries.map { it.resource }.distinct().size == entries.size && entries.all {
                it.resource.providerId == ProviderId("twitch") && it.resource.kind == "video" &&
                    it.resource.intent == CatalogIntent.VIDEO &&
                    validTwitchPlaybackResource(TwitchAccessCase.REPLAY, it.resource.identity) &&
                    it.availability == CatalogAvailability.UNKNOWN && it.scheduledStartEpochMs == null
            }

    private fun encryptedRecords(directory: File): Map<String, ByteArray> {
        val began = SystemClock.elapsedRealtime()
        fun checkTime() = check(SystemClock.elapsedRealtime() - began in 0 until 5_000)
        check(directory.isDirectory && directory.canRead() && !Files.isSymbolicLink(directory.toPath()))
        val files = checkNotNull(directory.listFiles()).filter {
            it.name.endsWith(".enc") || it.name.endsWith(".enc.bak") || it.name.endsWith(".enc.new")
        }
        check(files.size in 1..64); checkTime()
        val records = linkedMapOf<String, ByteArray>()
        try {
            for (file in files) {
                checkTime()
                check(file.isFile && file.canRead() && !Files.isSymbolicLink(file.toPath()) &&
                    file.length() in 1..(PRIVATE_SECRET_LIMIT + 29).toLong())
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192); var size = 0
                    try { while (true) {
                        checkTime(); val count = input.read(buffer); if (count < 0) break
                        size += count; check(size <= PRIVATE_SECRET_LIMIT + 29); checkTime()
                        digest.update(buffer, 0, count)
                    } } finally { buffer.fill(0) }
                }
                records[file.name] = digest.digest()
                checkTime()
            }
            return records
        } catch (error: Throwable) {
            records.values.forEach { it.fill(0) }; records.clear()
            throw error
        }
    }
}
