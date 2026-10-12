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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

// Opt-in retained-account acceptance only. One positive validation, one first
// Following page and, if populated, a returned-ID Streams batch with at most
// one provider-cursor continuation for that exact batch. No factory retirement,
// capability sweep, refresh, Following continuation, Add, history or playback.
class TwitchFollowingDeviceTest {
    private enum class ProbeStage { SETUP, VALIDATION_HTTP, VALIDATION_PARSE, VALIDATION_READY,
        FOLLOWING_ADMISSION, FOLLOWING_HTTP, FOLLOWING_PARSE, FOLLOWING_READY,
        STREAMS_ADMISSION, STREAMS_HTTP, STREAMS_PARSE, STREAMS_READY, PAGE_READY }
    private enum class ProbeFailure { NONE, ADMISSION, AUTH_TRANSPORT, AUTH_HTTP, AUTH_PARSE, EXPIRY_TIGHTENING,
        FOLLOWING_SCOPE, STREAMS_SCOPE, STREAMS_CYCLE, STREAMS_SHAPE, OTHER_SCOPE,
        HELIX_TRANSPORT, HELIX_HTTP, HELIX_PARSE, FOLLOWING_SHAPE }
    companion object {
        private const val BUDGET_MS = 70_000L
        private const val PAGE_LIMIT = 20
        private val retained = mutableListOf<List<Any>>()
    }

    @Test fun optInMaintainedAccountReadsOneFollowingPage() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("tachiaiReadOnlyTwitchFollowing") == "true")
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
        catch (_: Throwable) { throw AssertionError("Explicit Following observation failed its closed read-only checks") }
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
        var catalog: TwitchProviderCatalog? = null
        val transports = mutableListOf<AutoCloseable>()
        var result: CatalogResult<CatalogPage>? = null
        var closeReturned = false
        var validationRequests = 0
        var followingRequests = 0
        var streamsRequests = 0
        var followedIds: List<String>? = null
        var stage = ProbeStage.SETUP
        var probeFailure = ProbeFailure.NONE
        var authFailure: TwitchCatalogAuthFailure? = null
        var helixFailure: TwitchHelixFailure? = null
        var validationHttp = -1
        var followingHttp = -1
        var streamsHttp = -1
        var streamsFirstHttp = -1
        var streamsSecondHttp = -1
        var followedCount = -1
        var streamsCount = -1
        var streamsAggregateRows = 0
        val streamsIds = mutableSetOf<String>()
        var streamsNext: String? = null
        val consumedStreamsCursors = mutableSetOf<String>()
        var streamsHasNext = false
        var streamsSubset = true
        var streamsCompleted = false
        fun admitted(value: Boolean, failure: ProbeFailure) {
            if (!value) {
                probeFailure = failure
                throw IOException("Read-only Following observation refused its closed admission check")
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
            catalog = TwitchProviderCatalog(instanceId, accountSession, { gate ->
                val remote = OwnedTwitchHelixTransport(owner.profile, { gate() && current() },
                    onCleanupFailure = { cleanupFailed.set(true) }).also { transports.add(it) }
                object : TwitchHelixTransport {
                    override fun execute(accessToken: String, request: TwitchHelixRequest): TwitchHelixResponse {
                        admitted(current() && accessToken == checkNotNull(grant.credentials).accessToken, ProbeFailure.ADMISSION)
                        when (request.operation) {
                            TwitchHelixOperation.FOLLOWED -> {
                                stage = ProbeStage.FOLLOWING_ADMISSION
                                admitted(followingRequests == 0 && request.url.toExternalForm() ==
                                    TwitchHelixRequest.following(checkNotNull(grant.userId)).url.toExternalForm(), ProbeFailure.FOLLOWING_SCOPE)
                                followingRequests++
                                stage = ProbeStage.FOLLOWING_HTTP
                            }
                            TwitchHelixOperation.STREAMS -> {
                                stage = ProbeStage.STREAMS_ADMISSION
                                val ids = checkNotNull(followedIds)
                                admitted(ids.isNotEmpty() && streamsRequests < 2, ProbeFailure.STREAMS_SCOPE)
                                val cursor = if (streamsRequests == 0) null else {
                                    val next = streamsNext
                                    admitted(next != null && !streamsCompleted, ProbeFailure.STREAMS_SCOPE)
                                    checkNotNull(next)
                                }
                                admitted(request.url.toExternalForm() == TwitchHelixRequest.streams(ids, cursor).url.toExternalForm(),
                                    ProbeFailure.STREAMS_SCOPE)
                                if (cursor != null) admitted(consumedStreamsCursors.add(cursor), ProbeFailure.STREAMS_CYCLE)
                                streamsRequests++
                                stage = ProbeStage.STREAMS_HTTP
                            }
                            else -> {
                                probeFailure = ProbeFailure.OTHER_SCOPE
                                throw IOException("Read-only observation refuses unrelated metadata")
                            }
                        }
                        val response = try { remote.execute(accessToken, request) }
                        catch (error: TwitchHelixException) {
                            helixFailure = error.failure; probeFailure = ProbeFailure.HELIX_TRANSPORT
                            throw IOException("Read-only observation refused metadata transport")
                        } catch (_: IOException) {
                            probeFailure = ProbeFailure.HELIX_TRANSPORT
                            throw IOException("Read-only observation refused metadata transport")
                        }
                        val status = response.status.takeIf { it in 100..599 } ?: -1
                        if (request.operation == TwitchHelixOperation.FOLLOWED) followingHttp = status else {
                            streamsHttp = status
                            if (streamsRequests == 1) streamsFirstHttp = status else streamsSecondHttp = status
                        }
                        // Prevent production refresh/retry paths from treating
                        // refusal as an instruction to mutate authorization.
                        admitted(response.status == 200, ProbeFailure.HELIX_HTTP)
                        try {
                            if (request.operation == TwitchHelixOperation.FOLLOWED) {
                                stage = ProbeStage.FOLLOWING_PARSE
                                val page = parseTwitchFollowing(response)
                                followedCount = page.items.size
                                admitted(page.items.size <= PAGE_LIMIT && page.items.map { it.id }.distinct().size == page.items.size,
                                    ProbeFailure.FOLLOWING_SHAPE)
                                followedIds = page.items.map { it.id }
                                stage = ProbeStage.FOLLOWING_READY
                            } else {
                                stage = ProbeStage.STREAMS_PARSE
                                val page = parseTwitchStreams(response)
                                streamsCount = page.items.size
                                admitted(page.items.size <= PAGE_LIMIT, ProbeFailure.STREAMS_SHAPE)
                                streamsAggregateRows += page.items.size
                                streamsHasNext = page.nextCursor != null
                                streamsSubset = streamsSubset && page.items.all { it.broadcaster.id in checkNotNull(followedIds) }
                                admitted(streamsSubset, ProbeFailure.STREAMS_SHAPE)
                                admitted(page.nextCursor == null || page.nextCursor !in consumedStreamsCursors, ProbeFailure.STREAMS_CYCLE)
                                streamsIds.addAll(page.items.map { it.broadcaster.id })
                                streamsNext = page.nextCursor
                                streamsCompleted = page.nextCursor == null || streamsIds.size == checkNotNull(followedIds).size
                                stage = ProbeStage.STREAMS_READY
                            }
                        } catch (error: TwitchHelixException) {
                            helixFailure = error.failure; probeFailure = ProbeFailure.HELIX_PARSE
                            throw IOException("Read-only observation refused typed metadata")
                        }
                        admitted(current(), ProbeFailure.ADMISSION)
                        return response
                    }
                    override fun cancelActiveRequest() = remote.cancelActiveRequest()
                    override fun close() = remote.close()
                }
            }, canPublish = ::current)
            result = catalog.browse(CatalogQuery(collectionId = "following"))
            if (result is CatalogResult.Value) stage = ProbeStage.PAGE_READY
        } finally {
            active.set(false)
            try {
                try { catalog?.close() } catch (_: Throwable) { cleanupFailed.set(true) }
                try { session?.close() } catch (_: Throwable) { cleanupFailed.set(true) }
                for (transport in transports) {
                    try { transport.close() } catch (_: Throwable) { cleanupFailed.set(true) }
                }
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
                                assertTrue("Following observation changed protected records", baseline.keys == verified.keys &&
                                    baseline.all { (name, digest) -> verified[name]?.let { MessageDigest.isEqual(digest, it) } == true })
                            } finally {
                                // Ownership verification still runs after a
                                // failed after-snapshot or digest comparison.
                                captured?.let { owner -> assertTrue("Following observation changed captured route",
                                    owner.presentation.sameOwnership(readOwner().presentation)) }
                            }
                        } finally {
                            before?.values?.forEach { it.fill(0) }; after?.values?.forEach { it.fill(0) }
                            if (cleanupFailed.get()) synchronized(retained) {
                                retained.add(transports.toList() + listOfNotNull(catalog, session, foreground))
                            } else transports.clear()
                        }
                    }
                }
            }
        }
        val page = (result as? CatalogResult.Value)?.value
        val exact = page != null && page.entries.size <= PAGE_LIMIT &&
            page.entries.map { it.resource.identity } == followedIds &&
            page.entries.map { it.resource }.distinct().size == page.entries.size && page.entries.all {
                it.resource.providerId == ProviderId("twitch") && it.resource.kind == "broadcaster" &&
                    it.resource.intent == CatalogIntent.CHANNEL && validTwitchCatalogId(it.resource.identity) &&
                    it.availability == (if (it.resource.identity in streamsIds) CatalogAvailability.LIVE else CatalogAvailability.OFFLINE) &&
                    it.scheduledStartEpochMs == null
            }
        val failure = result as? CatalogResult.Failure
        val withinBudget = SystemClock.elapsedRealtime() - started in 0 until BUDGET_MS
        val requestScope = validationRequests == 1 && followingRequests == 1 &&
            (if (followedIds?.isEmpty() == false) streamsRequests in 1..2 && streamsCompleted else streamsRequests == 0)
        val accepted = exact && withinBudget && closeReturned && !mutationRefused.get() && requestScope
        // Provider diagnostics leave the fixture only after record/owner checks
        // and definite cleanup. An acceptance marker also includes its budget.
        if (closeReturned) Log.i("TachiaiTwitchFollowingProbe", "outcome=${if (accepted) "FOLLOWING_PAGE_ACCEPTED" else failure?.reason?.name ?: "INVALID_RESPONSE"} " +
            "sdk=${Build.VERSION.SDK_INT} routeMode=${captured?.instance?.setup?.route?.mode?.name ?: "UNAVAILABLE"} " +
            "entryCount=${page?.entries?.size ?: 0} hasNext=${page?.nextCursor != null} " +
            "validationRequests=$validationRequests followingRequests=$followingRequests streamsRequests=$streamsRequests " +
            "stage=${stage.name} failure=${probeFailure.name} authFailure=${authFailure?.name ?: "NONE"} " +
            "helixFailure=${helixFailure?.name ?: "NONE"} validationHttp=$validationHttp followingHttp=$followingHttp streamsHttp=$streamsHttp " +
            "streamsFirstHttp=$streamsFirstHttp streamsSecondHttp=$streamsSecondHttp followedCount=$followedCount " +
            "streamsCount=$streamsCount streamsAggregateRows=$streamsAggregateRows streamsUniqueCount=${streamsIds.size} " +
            "streamsHasNext=$streamsHasNext streamsSubset=$streamsSubset streamsCompleted=$streamsCompleted")
        assertTrue("Following observation exceeded operation/cleanup bounds", withinBudget)
        assertTrue("Following observation did not confirm cleanup", closeReturned)
        assertTrue("Following observation attempted grant mutation", !mutationRefused.get())
        assertTrue("Following observation exceeded its request scope", requestScope)
        assertTrue("Intended-account Following access was not established", exact)
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
