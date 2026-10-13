package net.fstab.tachiai.provider.twitch

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.CookieHandler
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.security.cert.Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.MainActivity
import net.fstab.tachiai.feature.connections.*
import net.fstab.tachiai.platform.diagnostics.FailureReporter
import net.fstab.tachiai.platform.diagnostics.FailureStage
import net.fstab.tachiai.platform.media.*
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.platform.storage.*
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import net.fstab.tachiai.provider.twitch.catalog.*
import org.junit.Assume.assumeTrue
import org.junit.Test

// One explicitly opted-in current configured replay or canonical broadcaster. Real retained catalog
// authorization and production preparation/player; no consent, refresh, Add,
// metadata sweep, checkpoint consumption, legacy grant or stored choice write.
@UnstableApi
class ConfiguredTwitchPlaybackAcceptanceDeviceTest {
    companion object {
        private const val BUDGET_MS = 70_000L
        private const val CLEANUP_MS = 20_000L
        private const val MAX_MEDIA_REQUESTS = 64
        private val retained = mutableListOf<Any>()
        private val closing = Executors.newFixedThreadPool(2) { action ->
            Thread(action, "twitch-configured-fixture-cleanup").apply { isDaemon = true }
        }
    }
    private enum class Mode { REPLAY, CHANNEL }
    private enum class Outcome { REFUSED, SAFE_FAILURE, FRAME_AND_MOVEMENT, CLEANUP_UNCONFIRMED, REPLAY_ACCEPTED, CHANNEL_ACCEPTED }
    private enum class SourceOrigin { CONFIGURED_RECORD, LEGACY_PROJECTION, UNAVAILABLE }
    private enum class SampleState { UNAVAILABLE, IDLE, BUFFERING, READY, ENDED }
    private enum class Failure { NONE, SETUP, ADMISSION, MUTATION, VALIDATION, EXPIRY_TIGHTENING,
        ELIGIBILITY, IDENTITY_SCOPE, ACCESS_SCOPE, MEDIA_SCOPE, FRAME, MOVEMENT, CLEANUP, PRESERVATION }
    private class Refusal : IOException("Configured playback observation refused its closed checks")
    private class CloseReceipt<T : Any>(val resource: T, private val close: (T) -> Unit) {
        private val started = AtomicBoolean()
        private val finished = CountDownLatch(1)
        private val successful = AtomicBoolean()
        private val failed = AtomicBoolean()
        fun start() {
            if (started.compareAndSet(false, true)) {
                try { closing.execute {
                    try { close(resource); successful.set(true) } catch (_: Throwable) { failed.set(true) }
                    finally { finished.countDown() }
                } } catch (_: Throwable) { failed.set(true); finished.countDown() }
            }
        }
        fun await(deadline: Long): Boolean {
            start()
            val result = try {
                (finished.count == 0L || finished.await((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0),
                    TimeUnit.MILLISECONDS)) && successful.get() && !failed.get()
            } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
            if (!result) failed.set(true)
            return result
        }
        val confirmed get() = finished.count == 0L && successful.get() && !failed.get()
    }

    @Test fun optInCurrentConfiguredReplayUsesRetainedCatalogGrantAndPreservesSavedRecords() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("tachiaiBoundedTwitchConfiguredPlayback") == "true")
        if (args.getString("tachiaiBoundedTwitchConfiguredChannel") == "true")
            throw AssertionError("Configured playback observation modes must be separately selected")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        assumeTrue(Application.getProcessName() == instrumentation.targetContext.packageName)
        assumeTrue(Looper.myLooper() != Looper.getMainLooper())
        val instanceId = args.getString("twitchProviderInstance")
        if (instanceId == null || !validProviderInstanceId(instanceId))
            throw AssertionError("Configured replay requires an explicit valid provider instance")
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
        if (!synchronized(retained) { retained.isEmpty() })
            throw AssertionError("Configured replay observation retains uncertain cleanup")
        try { observe(instanceId, Mode.REPLAY) }
        catch (_: Throwable) { throw AssertionError("Configured replay observation did not pass its closed acceptance checks") }
    }

    @Test fun optInCurrentConfiguredChannelUsesRetainedCatalogGrantAndPreservesSavedRecords() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("tachiaiBoundedTwitchConfiguredChannel") == "true")
        if (args.getString("tachiaiBoundedTwitchConfiguredPlayback") == "true")
            throw AssertionError("Configured playback observation modes must be separately selected")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        assumeTrue(Application.getProcessName() == instrumentation.targetContext.packageName)
        assumeTrue(Looper.myLooper() != Looper.getMainLooper())
        val instanceId = args.getString("twitchProviderInstance")
        val itemId = args.getString("twitchConfiguredItem")
        if (instanceId == null || !validProviderInstanceId(instanceId) || itemId != null && !validProviderInstanceId(itemId))
            throw AssertionError("Configured channel requires valid explicit ownership arguments")
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
        if (!synchronized(retained) { retained.isEmpty() })
            throw AssertionError("Configured channel observation retains uncertain cleanup")
        try { observe(instanceId, Mode.CHANNEL, itemId) }
        catch (_: Throwable) { throw AssertionError("Configured channel observation did not pass its closed acceptance checks") }
    }

    private fun observe(instanceId: String, mode: Mode, itemId: String? = null) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = context.noBackupFilesDir
        val started = SystemClock.elapsedRealtime()
        val beganWall = System.currentTimeMillis()
        val active = AtomicBoolean()
        val cleanupFailed = AtomicBoolean()
        val mutationRefused = AtomicBoolean()
        val frameObserved = AtomicBoolean()
        val playbackFailed = AtomicBoolean()
        val qualityApplied = AtomicBoolean()
        val muted = AtomicBoolean()
        val playRequested = AtomicBoolean()
        val validationRequests = AtomicInteger()
        val accessRequests = AtomicInteger()
        val mediaRequests = AtomicInteger()
        val validationHttp = AtomicInteger(-1)
        val accessHttp = AtomicInteger(-1)
        val failure = AtomicReference(Failure.NONE)
        val stages = linkedSetOf<FailureStage>()
        val reporter = FailureReporter({ observation -> synchronized(stages) {
            if (stages.size < 16) stages.add(observation.stage)
        } })
        fun admit(value: Boolean, category: Failure) {
            if (!value) { failure.compareAndSet(Failure.NONE, category); throw Refusal() }
        }
        fun localCurrent(): Boolean = active.get() && !cleanupFailed.get() && !mutationRefused.get() &&
            SystemClock.elapsedRealtime() - started in 0 until BUDGET_MS &&
            System.currentTimeMillis() - beganWall in 0 until BUDGET_MS
        fun regular(file: File, writable: Boolean = false): Boolean = file.isFile && file.canRead() &&
            (!writable || file.canWrite()) && !Files.isSymbolicLink(file.toPath())
        val grantLock = File(directory, "twitch-catalog-$instanceId.lock")
        val grantFile = File(directory, "${twitchCatalogInstanceBindingName(instanceId)}.enc")
        // Idle-device observation only: refuse observed unfinished AtomicFile
        // state before every read; no claim of a global external-writer lock.
        fun stable() {
            admit(directory.isDirectory && directory.canRead() && !Files.isSymbolicLink(directory.toPath()), Failure.SETUP)
            val files = directory.listFiles() ?: throw Refusal()
            admit(files.none { it.name.endsWith(".bak") || it.name.endsWith(".new") } &&
                regular(grantLock, true) && regular(grantFile) && grantFile.length() in 1..65536 &&
                !File(directory, "twitch-catalog-$instanceId.lock.forget-pending").exists() &&
                !TwitchCatalogConnectionWrites.isPending(instanceId) && !TwitchCatalogConnectionWrites.isFailed(instanceId) &&
                !TwitchContinueWatchingAdmission.poisoned(instanceId), Failure.ADMISSION)
        }
        fun optionalLock(name: String): File? {
            stable()
            val file = File(directory, name)
            if (!file.exists()) { admit(!Files.isSymbolicLink(file.toPath()), Failure.SETUP); return null }
            admit(regular(file, true), Failure.SETUP)
            return file
        }
        fun readOnly(secret: PrivateSecretStore, binding: String): PrivateSecretStore = object : PrivateSecretStore {
            override fun read(): ByteArray? {
                stable()
                val file = File(directory, "$binding.enc")
                if (!file.exists()) { admit(!Files.isSymbolicLink(file.toPath()), Failure.SETUP); return null }
                admit(regular(file) && file.length() in 1..(PRIVATE_SECRET_LIMIT + 29).toLong(), Failure.SETUP)
                return secret.read()
            }
            override fun write(plaintext: ByteArray): Unit {
                mutationRefused.set(true); admit(false, Failure.MUTATION)
            }
        }
        fun slot(value: PrivateAuthorizationSlot) = readOnly(AndroidPrivateSecretStore(context, value), value.bindingName)
        fun instances(): List<ProviderInstance> {
            stable()
            return ProviderInstanceStore(slot(PrivateAuthorizationSlot.PROVIDER_INSTANCES), optionalLock("provider-instances.lock")).read {
                val setups = SourceSetupStore(slot(PrivateAuthorizationSlot.SOURCE_SETUP), optionalLock("source-setup.lock")).read()
                ProviderSetupStore(slot(PrivateAuthorizationSlot.PROVIDER_SETUP), optionalLock("provider-setup.lock")).read(setups)
            }
        }
        fun readOwner(): TwitchCatalogRouteOwner {
            stable()
            return readTwitchCatalogRouteOwner(instanceId, ::instances) { id ->
                ConnectionProfileStore(slot(PrivateAuthorizationSlot.CONNECTION_PROFILES), optionalLock("connection-profiles.lock")).selected(id)
            }
        }
        fun sources(instance: ProviderInstance): List<ConfiguredSource> {
            stable()
            val binding = configuredProviderInstanceBindingName(instance.id)
            return ConfiguredSourceStore(readOnly(AndroidPrivateSecretStore.configuredProviderInstance(context, instance.id), binding),
                instance.id, ProviderId("twitch"), optionalLock("$binding.lock")).read {
                val setups = SourceSetupStore(slot(PrivateAuthorizationSlot.SOURCE_SETUP), optionalLock("source-setup.lock")).read()
                val qualities = StreamQualityStore(slot(PrivateAuthorizationSlot.STREAM_QUALITY), optionalLock("stream-quality.lock")).read()
                legacyConfiguredSources(instance, setups, qualities)
            }
        }

        var before: Map<String, ByteArray>? = null
        var captured: TwitchCatalogRouteOwner? = null
        var selected: ConfiguredSource? = null
        var foreground: ActivityScenario<MainActivity>? = null
        var foregroundClose: CloseReceipt<ActivityScenario<MainActivity>>? = null
        val activity = AtomicReference<MainActivity?>()
        var view: PlayerView? = null
        var session: PrototypeTwitchSession? = null
        var sessionWorker: ExecutorService? = null
        var route: RouteSession? = null
        var backendClose: CloseReceipt<RouteBackend>? = null
        var routeClose: CloseReceipt<RouteSession>? = null
        var budget: NativePlaybackBudget? = null
        var basePreparation: CatalogTwitchPreparation? = null
        var catalogSession: TwitchCatalogSession? = null
        var coordinator: TwitchCatalogMaintenanceCoordinator? = null
        var channelScope: ConfiguredTwitchChannelScope? = null
        var identityResolver: TwitchLiveIdentityResolver? = null
        val catalogSessions = CopyOnWriteArrayList<TwitchCatalogSession>()
        val helixTransports = CopyOnWriteArrayList<OwnedTwitchHelixTransport>()
        val helixPreparations = CopyOnWriteArrayList<RoutePreparation>()
        val helixRouteCloses = CopyOnWriteArrayList<CloseReceipt<RouteSession>>()
        val helixBackendCloses = CopyOnWriteArrayList<CloseReceipt<RouteBackend>>()
        val transports = CopyOnWriteArrayList<OwnedTwitchCatalogTransport>()
        val connections = CopyOnWriteArrayList<ObservedConnection>()
        val resolveStarted = AtomicBoolean()
        val resolveFinished = CountDownLatch(1)
        val prepared = CountDownLatch(1)
        val frame = CountDownLatch(1)
        val selectedUri = AtomicReference<java.net.URI?>()
        val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
        var registered = false
        val callback = ActivityLifecycleCallback { owner, stage ->
            if (owner === activity.get() && stage in setOf(Stage.PAUSED, Stage.STOPPED, Stage.DESTROYED)) active.set(false)
        }
        var outcome = Outcome.REFUSED
        var beforeCleanup = Outcome.REFUSED
        var moved: Boolean? = null
        var workerTerminated = false
        var preparationSettled = false
        var nativeConfirmed = false
        var httpConfirmed = false
        var routeConfirmed = false
        var cleanupConfirmed = false
        var recordsSame = false
        var ownerSame = false
        var itemSame = false
        var withinPlaybackBudget = false
        var noFeedFailure = false
        var firstSampleState = SampleState.UNAVAILABLE
        var lastSampleState = SampleState.UNAVAILABLE
        var sourceOrigin = SourceOrigin.UNAVAILABLE
        var sourceCount = -1
        var replayCount = -1
        var channelCount = -1
        val routePreparation = RoutePreparation(reporter)
        try {
            stable()
            admit(CookieHandler.getDefault() == null, Failure.SETUP)
            before = encryptedRecords(directory)
            val owner = readOwner().also { captured = it }
            sourceOrigin = if (File(directory, "${configuredProviderInstanceBindingName(instanceId)}.enc").exists())
                SourceOrigin.CONFIGURED_RECORD else SourceOrigin.LEGACY_PROJECTION
            val sourceList = sources(owner.instance)
            sourceCount = sourceList.size
            val eligible = sourceList.filter { candidate ->
                (if (mode == Mode.REPLAY) supportedConfiguredTwitchVideo(candidate.entry.resource)
                    else supportedConfiguredTwitchBroadcaster(candidate.entry.resource)) &&
                    resolveConfiguredPrototypeSelection(ConfiguredFeedAssignments(candidate.choice, candidate.choice),
                        sourceList, listOf(owner.instance)) is ConfiguredPrototypeSelectionResult.Ready
            }
            if (mode == Mode.REPLAY) replayCount = eligible.size else channelCount = eligible.size
            // Freeze one local item before any requests. An explicit missing or
            // unsupported item, or this item's later offline state, never picks another.
            val source = (if (itemId == null) eligible.firstOrNull() else eligible.singleOrNull { it.id == itemId })
                ?: run { admit(false, Failure.ELIGIBILITY); throw Refusal() }
            selected = source
            if (mode == Mode.CHANNEL) channelScope = ConfiguredTwitchChannelScope(source.entry.resource)
            val resolved = resolveConfiguredPrototypeSelection(ConfiguredFeedAssignments(source.choice, source.choice),
                sourceList, listOf(owner.instance)) as? ConfiguredPrototypeSelectionResult.Ready ?: throw Refusal()
            val feed = resolved.selection.a
            admit(feed.choice == source.choice && resolved.selection.b.choice == source.choice &&
                feed.resource == source.entry.resource && resolved.selection.b.resource == feed.resource &&
                feed.service == PrototypeService.TWITCH && feed.kind ==
                    (if (mode == Mode.REPLAY) PrototypePlaybackKind.REPLAY else PrototypePlaybackKind.LIVE), Failure.SETUP)
            val grantStore = TwitchCatalogGrantStore(readOnly(AndroidPrivateSecretStore.twitchCatalogInstance(context, instanceId),
                twitchCatalogInstanceBindingName(instanceId)), instanceId, grantLock)
            val grant = grantStore.read() ?: throw Refusal()
            admit(grant.state == TwitchCatalogGrantState.READY && grantStore.isStoredCurrent(grant) &&
                checkNotNull(grant.savedAtMs) <= beganWall && checkNotNull(grant.expiresAtMs) - beganWall > 90_000, Failure.ADMISSION)
            fun current(): Boolean {
                if (!localCurrent()) return false
                stable()
                val currentOwner = readOwner()
                val configured = File(directory, "${configuredProviderInstanceBindingName(instanceId)}.enc").exists()
                return grantStore.isStoredCurrent(grant) && System.currentTimeMillis() < checkNotNull(grant.expiresAtMs) &&
                    owner.presentation.sameOwnership(currentOwner.presentation) &&
                    configured == (sourceOrigin == SourceOrigin.CONFIGURED_RECORD) &&
                    sources(currentOwner.instance).singleOrNull { it.choice == source.choice } == source && localCurrent()
            }
            // Session canCommit may run under the grant's file lock. Do not
            // recursively enter that lock by rereading the grant here.
            fun ownerCurrent(): Boolean = localCurrent() &&
                owner.presentation.sameOwnership(readOwner().presentation) &&
                (mode == Mode.REPLAY || sources(owner.instance).singleOrNull { it.choice == source.choice } == source) && localCurrent()
            foreground = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN; addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            foregroundClose = CloseReceipt(checkNotNull(foreground)) { it.close() }
            foreground.onActivity { currentActivity ->
                activity.set(currentActivity)
                active.set(lifecycle.getActivitiesInStage(Stage.RESUMED).contains(currentActivity))
                registered = true; lifecycle.addLifecycleCallback(callback)
                view = PlayerView(currentActivity).apply { useController = false }
                currentActivity.findViewById<ViewGroup>(android.R.id.content).addView(view,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            admit(current(), Failure.ADMISSION)
            route = RouteSession.createRetained(owner.profile, routePreparation, ownBackend = { backend ->
                val receipt = CloseReceipt(backend, RouteBackend::close).also { backendClose = it }
                object : RouteBackend {
                    override val proxyPort get() = backend.proxyPort
                    override fun close() { if (!receipt.await(SystemClock.elapsedRealtime() + 5000)) throw Refusal() }
                }
            })
            routeClose = CloseReceipt(checkNotNull(route), RouteSession::close)
            val ownedRoute = checkNotNull(route)
            fun catalogTransport(canRequest: () -> Boolean): TwitchCatalogTransport {
                admit(current(), Failure.ADMISSION)
                val remote = OwnedTwitchCatalogTransport(owner.profile, { canRequest() && current() },
                    onCleanupFailure = { cleanupFailed.set(true) }, diagnostics = reporter).also { transports.add(it) }
                return object : TwitchCatalogTransport {
                    override fun device(): DeviceAuthResponse { admit(false, Failure.MUTATION); throw Refusal() }
                    override fun poll(deviceCode: String): DeviceAuthResponse { admit(false, Failure.MUTATION); throw Refusal() }
                    override fun refresh(refreshToken: String): DeviceAuthResponse { admit(false, Failure.MUTATION); throw Refusal() }
                    override fun validate(accessToken: String): DeviceAuthResponse {
                        admit(current() && validationRequests.incrementAndGet() in
                            1..(if (mode == Mode.REPLAY) 1 else 2) &&
                            accessToken == checkNotNull(grant.credentials).accessToken, Failure.VALIDATION)
                        channelScope?.beginValidation()
                        val response = try { remote.validate(accessToken) }
                        catch (_: Throwable) { admit(false, Failure.VALIDATION); throw Refusal() }
                        validationHttp.set(response.status.takeIf { it in 100..599 } ?: -1)
                        admit(response.status == 200, Failure.VALIDATION)
                        val validation = try { parseTwitchCatalogValidation(response, grant.userId, grant.localRetentionUntilMs != null) }
                        catch (_: Throwable) { admit(false, Failure.VALIDATION); throw Refusal() }
                        val finite = validation.expiresInMs
                        if (grant.localRetentionUntilMs != null && finite != null) admit(beganWall >= 0 &&
                            finite <= Long.MAX_VALUE - beganWall && grant.providerExpiresAtMs != null &&
                            beganWall + finite - BUDGET_MS >= grant.providerExpiresAtMs, Failure.EXPIRY_TIGHTENING)
                        admit(current(), Failure.ADMISSION)
                        channelScope?.validated(response.status)
                        return response
                    }
                    override fun cancelActiveRequest() = remote.cancelActiveRequest()
                    override fun close() = remote.close()
                }
            }
            if (mode == Mode.CHANNEL) coordinator = TwitchCatalogMaintenanceCoordinator(grantStore,
                ::catalogTransport, ::ownerCurrent) // No scheduler, renewal or registry retirement in this fixture.
            fun newBinding(): TwitchCatalogConnectionBinding = object : TwitchCatalogConnectionBinding {
                private val closed = AtomicBoolean()
                private val ownedSessions = CopyOnWriteArrayList<TwitchCatalogSession>()
                private val ownedTransports = CopyOnWriteArrayList<TwitchCatalogTransport>()
                override fun owner(): TwitchCatalogConnectionOwner { admit(current(), Failure.ADMISSION); return readOwner().presentation }
                override fun canPublishLocally() = !closed.get() && localCurrent() && coordinator?.canPublishLocally != false
                override fun session(): TwitchCatalogSession {
                    admit(current() && ownedSessions.isEmpty() && (mode == Mode.CHANNEL || catalogSession == null), Failure.ADMISSION)
                    return TwitchCatalogSession(grantStore, ::transport, canCommit = ::ownerCurrent,
                        maintenance = coordinator, canParticipate = ::canPublishLocally).also {
                        ownedSessions.add(it); catalogSessions.add(it)
                        if (mode == Mode.REPLAY) catalogSession = it
                    }
                }
                override fun transport(canRequest: () -> Boolean): TwitchCatalogTransport =
                    catalogTransport { canRequest() && canPublishLocally() }.also { ownedTransports.add(it) }
                override fun close() {
                    if (!closed.compareAndSet(false, true)) return
                    var failed = false
                    ownedSessions.forEach { try { it.close() } catch (_: Throwable) { failed = true } }
                    ownedTransports.forEach { try { it.close() } catch (_: Throwable) { failed = true } }
                    if (failed) { cleanupFailed.set(true); throw Refusal() }
                }
            }
            val binding = newBinding()
            val accessBody = if (mode == Mode.REPLAY) twitchAccessProbeBody(TwitchAccessCase.REPLAY, feed.resource.identity) else null
            fun open(url: URL): HttpsURLConnection {
                admit(current() && CookieHandler.getDefault() == null, Failure.ADMISSION)
                val access = url.toExternalForm() == "https://gql.twitch.tv/gql"
                if (access) admit(accessRequests.incrementAndGet() == 1 && validationRequests.get() in
                    1..(if (mode == Mode.REPLAY) 1 else 2) && validationHttp.get() == 200, Failure.ACCESS_SCOPE)
                else {
                    admit(allowedTwitchMediaUri(url.toURI(), observedReplayCdn = mode == Mode.REPLAY) &&
                        mediaRequests.incrementAndGet() <= MAX_MEDIA_REQUESTS && selectedUri.get() != null, Failure.MEDIA_SCOPE)
                    if (url.host == "usher.ttvnw.net") admit(url.toURI() == selectedUri.get(), Failure.MEDIA_SCOPE)
                    channelScope?.admitMedia(url.toURI())
                }
                val expectedBody = if (access) channelScope?.beginAccess() ?: accessBody else null
                return ObservedConnection(ownedRoute.open(url), cleanupFailed, { current() },
                    expectedBody = expectedBody,
                    expectedAuthorization = if (access) "OAuth ${checkNotNull(grant.credentials).accessToken}" else null,
                    onStatus = { status -> if (access) { accessHttp.set(status); channelScope?.accessStatus(status) } })
                    .also { connections.add(it) }
            }
            val preparation = CatalogTwitchPreparation(context, instanceId, owner.profile, checkNotNull(owner.instance.setup.route),
                ::localCurrent, ::open, bindingFactory = { readOwner() to binding }).also { basePreparation = it }
            val observedPreparation = object : TwitchPlaybackPreparation {
                override val acceptanceDeadlineMs get() = preparation.acceptanceDeadlineMs
                override fun canContinue() = preparation.canContinue()
                override fun checkStored(force: Boolean) = preparation.checkStored(force)
                override fun resolve(replay: Boolean, resource: String, onStatus: (String, Int) -> Unit): TwitchPlaybackSource {
                    admit(resolveStarted.compareAndSet(false, true) && replay == (mode == Mode.REPLAY), Failure.ACCESS_SCOPE)
                    try {
                        if (mode == Mode.REPLAY) admit(resource == feed.resource.identity, Failure.ACCESS_SCOPE)
                        else checkNotNull(channelScope).beginNative(replay, resource)
                        return preparation.resolve(replay, resource, onStatus).also {
                            if (mode == Mode.REPLAY) admit(it.uri.host == "usher.ttvnw.net" &&
                                it.uri.path == "/vod/v2/${feed.resource.identity}.m3u8", Failure.ACCESS_SCOPE)
                            else checkNotNull(channelScope).bindSource(it.uri)
                            admit(current(), Failure.ACCESS_SCOPE)
                            selectedUri.set(it.uri)
                        }
                    } finally { resolveFinished.countDown() }
                }
                override fun close() = preparation.close()
            }
            // This unused legacy constructor dependency cannot read, write or
            // provide a token. The real catalog preparation above owns access.
            val noLegacy = TwitchSavedAuthorization(object : PrivateSecretStore {
                override fun read(): ByteArray? { mutationRefused.set(true); admit(false, Failure.MUTATION); return null }
                override fun write(plaintext: ByteArray) { mutationRefused.set(true); admit(false, Failure.MUTATION) }
            }, profile = TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL)
            budget = NativePlaybackBudget(BUDGET_MS, ::localCurrent, maximumDurationMs = BUDGET_MS)
            instrumentation.runOnMainSync {
                val events: (PrototypeFeedEvent) -> Unit = { event ->
                    when (event) {
                        PrototypeFeedEvent.READY -> {
                            try {
                                view?.player = session?.player
                                qualityApplied.set(session?.member?.setQualityPreferences(source.quality) == true)
                                muted.set(session?.member?.setVolume(0f) == true)
                                playRequested.set(session?.member?.setTimingPlaying(true) == true)
                                if (!qualityApplied.get() || !muted.get() || !playRequested.get()) {
                                    playbackFailed.set(true); frame.countDown()
                                }
                            } catch (_: Throwable) { playbackFailed.set(true); frame.countDown() }
                            prepared.countDown()
                        }
                        PrototypeFeedEvent.VIDEO_FRAME -> { frameObserved.set(true); frame.countDown() }
                        PrototypeFeedEvent.FAILED -> { playbackFailed.set(true); prepared.countDown(); frame.countDown() }
                        else -> Unit
                    }
                }
                session = if (mode == Mode.REPLAY) PrototypeTwitchSession(checkNotNull(activity.get()), true,
                    feed.resource.identity, ::localCurrent, events, openConnection = ::open, authorization = noLegacy, diagnostics = reporter,
                    initialPositionMs = if (feed.historicalSource == PrototypeSource.TWITCH_REPLAY) 70 * 60 * 1000L else 0L,
                    preparationFactory = { observedPreparation }, onCleanupChanged = { cleanupFailed.set(true) })
                else configuredTwitchBroadcasterSession(checkNotNull(activity.get()), feed.resource, ::localCurrent, events,
                    liveIdentityResolverFactory = {
                        admit(current(), Failure.ADMISSION)
                        ownedTwitchLiveIdentityResolver(instanceId, owner.profile, owner, newBinding(), ::localCurrent,
                            transportFactory = { profile, gate, failed ->
                                admit(current() && (profile?.let(::routeConfigurationKey) ?: "SYSTEM") ==
                                    (owner.profile?.let(::routeConfigurationKey) ?: "SYSTEM"), Failure.ADMISSION)
                                val remote = OwnedTwitchHelixTransport(profile, { gate() && current() },
                                    createRoute = { selectedProfile, preparing ->
                                        helixPreparations.add(preparing)
                                        RouteSession.createTwitchCatalog(selectedProfile, preparing) { selectedProfile, security ->
                                            val backend = selectedProfile.protocol.createBackend(selectedProfile, security, preparing)
                                            val receipt = CloseReceipt(backend, RouteBackend::close).also { helixBackendCloses.add(it) }
                                            object : RouteBackend {
                                                override val proxyPort get() = backend.proxyPort
                                                override fun close() {
                                                    // Actual HTTP/input uncertainty must retain this
                                                    // backend even if the transport cleared its active field.
                                                    if (cleanupFailed.get()) throw Refusal()
                                                    if (!receipt.await(SystemClock.elapsedRealtime() + 5000)) throw Refusal()
                                                }
                                            }
                                        }.also { helixRouteCloses.add(CloseReceipt(it, RouteSession::close)) }
                                    }, createTransport = { routeOpen, allowed ->
                                        TwitchHelixHttpTransport(open = { url ->
                                            admit(current() && allowed() && validTwitchHelixUrl(url) &&
                                                CookieHandler.getDefault() == null, Failure.IDENTITY_SCOPE)
                                            ObservedConnection(routeOpen(url), cleanupFailed, { current() && allowed() },
                                                expectedBody = null, expectedAuthorization = null, onStatus = {},
                                                expectedGetAuthorization = "Bearer ${checkNotNull(grant.credentials).accessToken}",
                                                expectedGetClientId = SMART_TV_TWITCH_CLIENT_ID).also { connections.add(it) }
                                        }, canRequest = { current() && allowed() })
                                    }, onCleanupFailure = { cleanupFailed.set(true); failed() }, diagnostics = reporter)
                                    .also { helixTransports.add(it) }
                                object : TwitchHelixTransport {
                                    override fun execute(accessToken: String, request: TwitchHelixRequest): TwitchHelixResponse {
                                        admit(current() && accessToken == checkNotNull(grant.credentials).accessToken, Failure.IDENTITY_SCOPE)
                                        val scope = checkNotNull(channelScope)
                                        scope.beginHelix(request)
                                        val response = try { remote.execute(accessToken, request) }
                                        catch (_: Throwable) { admit(false, Failure.IDENTITY_SCOPE); throw Refusal() }
                                        admit(current(), Failure.ADMISSION)
                                        scope.completeHelix(request, response)
                                        return response // The owned exchange has already confirmed HTTP/route cleanup.
                                    }
                                    override fun cancelActiveRequest() = remote.cancelActiveRequest()
                                    override fun close() = remote.close()
                                }
                            }, diagnostics = reporter, expectedRoute = owner.instance.setup.route).also { identityResolver = it }
                    }, openConnection = ::open, authorization = noLegacy, diagnostics = reporter,
                    preparationFactory = { observedPreparation }, onCleanupChanged = { cleanupFailed.set(true) })
                sessionWorker = PrototypeTwitchSession::class.java.getDeclaredField("worker").apply { isAccessible = true }
                    .get(checkNotNull(session)) as ExecutorService
                checkNotNull(session).prepare(checkNotNull(budget))
            }
            fun remaining(maximum: Long): Long =
                (BUDGET_MS - CLEANUP_MS - (SystemClock.elapsedRealtime() - started)).coerceIn(0, maximum)
            admit(prepared.await(remaining(55_000), TimeUnit.MILLISECONDS) && !playbackFailed.get(), Failure.FRAME)
            admit(frame.await(remaining(10_000), TimeUnit.MILLISECONDS) && frameObserved.get() && !playbackFailed.get(), Failure.FRAME)
            var first: Player? = null; var firstPosition = -1L; var firstElapsed = -1L
            var movement = false
            var samplingRefused = false
            // Buffering between observations is not movement or a new source
            // attempt. Keep the original READY baseline and finite run budget.
            val samplingEnd = minOf(SystemClock.elapsedRealtime() + 10_000, started + BUDGET_MS - CLEANUP_MS)
            while (!movement && !samplingRefused && SystemClock.elapsedRealtime() < samplingEnd) {
                instrumentation.runOnMainSync {
                    val player = session?.player
                    val now = SystemClock.elapsedRealtime()
                    if (player == null || player.applicationLooper != Looper.myLooper() || !localCurrent() ||
                        playbackFailed.get() || session?.failure != null || session?.canContinue() != true ||
                        budget?.active != true || now >= samplingEnd || first != null && player !== first) {
                        samplingRefused = true
                    } else {
                        lastSampleState = when (player.playbackState) {
                            Player.STATE_IDLE -> SampleState.IDLE
                            Player.STATE_BUFFERING -> SampleState.BUFFERING
                            Player.STATE_READY -> SampleState.READY
                            Player.STATE_ENDED -> SampleState.ENDED
                            else -> SampleState.UNAVAILABLE
                        }
                        if (lastSampleState == SampleState.READY) {
                            val position = player.currentPosition
                            if (position >= 0) {
                                if (first == null && samplingEnd - now >= 1500) {
                                    first = player; firstPosition = position; firstElapsed = now
                                    firstSampleState = lastSampleState
                                } else if (first === player && now >= firstElapsed && now - firstElapsed >= 1500) {
                                    movement = position > firstPosition && localCurrent() && budget?.active == true
                                }
                            }
                        }
                    }
                }
                if (!movement && !samplingRefused) {
                    val pause = (samplingEnd - SystemClock.elapsedRealtime()).coerceIn(0, 100)
                    if (pause > 0) Thread.sleep(pause)
                }
            }
            moved = movement
            admit(movement && current(), Failure.MOVEMENT)
            withinPlaybackBudget = localCurrent()
            outcome = Outcome.FRAME_AND_MOVEMENT
        } catch (_: Throwable) { outcome = Outcome.SAFE_FAILURE }
        finally {
            beforeCleanup = outcome
            noFeedFailure = session != null && session?.failure == null && !playbackFailed.get()
            active.set(false); budget?.stop()
            val cleanupEnd = minOf(started + BUDGET_MS, SystemClock.elapsedRealtime() + CLEANUP_MS)
            fun mainClose(action: () -> Unit): Boolean {
                val done = CountDownLatch(1); val success = AtomicBoolean()
                val posted = Handler(Looper.getMainLooper()).post {
                    try { action(); success.set(true) } catch (_: Throwable) { }
                    finally { done.countDown() }
                }
                return try { posted && done.await((cleanupEnd - SystemClock.elapsedRealtime()).coerceAtLeast(0),
                    TimeUnit.MILLISECONDS) && success.get() } catch (_: Throwable) { false }
            }
            var confirmed = mainClose {
                var failed = false
                for (close in listOf<() -> Unit>(
                    { session?.close() }, { basePreparation?.close() }, { identityResolver?.close() },
                    { view?.player = null },
                ) + catalogSessions.map { catalog -> { catalog.close() } })
                    try { close() } catch (_: Throwable) { failed = true }
                if (failed) throw Refusal()
            }
            connections.forEach { it.startClose() }
            preparationSettled = !resolveStarted.get() || try { resolveFinished.await(
                (cleanupEnd - SystemClock.elapsedRealtime()).coerceAtLeast(0), TimeUnit.MILLISECONDS) } catch (_: Throwable) { false }
            workerTerminated = sessionWorker?.let { worker -> try {
                worker.isShutdown && worker.awaitTermination((cleanupEnd - SystemClock.elapsedRealtime()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            } catch (_: Throwable) { false } } ?: (session == null)
            nativeConfirmed = mainClose { admit(session?.cleanupConfirmed != false, Failure.CLEANUP) }
            confirmed = confirmed && preparationSettled && workerTerminated && nativeConfirmed
            transports.forEach { transport -> if (!CloseReceipt(transport, OwnedTwitchCatalogTransport::close).await(cleanupEnd)) confirmed = false }
            helixTransports.forEach { transport -> if (!CloseReceipt(transport, OwnedTwitchHelixTransport::close).await(cleanupEnd)) confirmed = false }
            // The resolver's owned exchanges finish on the session worker.
            // Do not release their route/backends while that worker is uncertain.
            if (workerTerminated && !cleanupFailed.get() && helixPreparations.all { it.cleanupConfirmed }) {
                helixRouteCloses.forEach { if (!it.await(cleanupEnd)) confirmed = false }
                helixBackendCloses.forEach { if (!it.await(cleanupEnd)) confirmed = false }
            } else if (mode == Mode.CHANNEL) confirmed = false
            confirmed = confirmed && helixPreparations.all { it.cleanupConfirmed } && coordinator?.isIdle != false &&
                catalogSessions.none { it.cleanupUncertain }
            httpConfirmed = connections.all { it.closeConfirmed(cleanupEnd) }
            confirmed = confirmed && httpConfirmed && !cleanupFailed.get()
            if (confirmed) {
                routeConfirmed = routeClose?.await(cleanupEnd) ?: true
                routeConfirmed = routeConfirmed && (backendClose?.await(cleanupEnd) ?: true) && routePreparation.cleanupConfirmed
            }
            confirmed = confirmed && routeConfirmed
            val removed = mainClose {
                if (registered) lifecycle.removeLifecycleCallback(callback)
                (view?.parent as? ViewGroup)?.removeView(view)
            }
            val scenarioClosed = foregroundClose?.await(cleanupEnd) ?: true
            cleanupConfirmed = confirmed && removed && scenarioClosed
            if (!cleanupConfirmed) { outcome = Outcome.CLEANUP_UNCONFIRMED; failure.compareAndSet(Failure.NONE, Failure.CLEANUP) }
            var after: Map<String, ByteArray>? = null
            try {
                try {
                    after = encryptedRecords(directory)
                    recordsSame = before?.let { baseline -> baseline.keys == checkNotNull(after).keys &&
                        baseline.all { (name, digest) -> after?.get(name)?.let { MessageDigest.isEqual(digest, it) } == true } } == true
                } catch (_: Throwable) { recordsSame = false }
                finally {
                    try { ownerSame = captured?.presentation?.sameOwnership(readOwner().presentation) == true }
                    catch (_: Throwable) { ownerSame = false }
                    finally {
                        try { itemSame = captured?.let { owner -> selected?.let { item ->
                            sources(readOwner().instance).singleOrNull { it.choice == item.choice } == item &&
                                owner.presentation.sameOwnership(readOwner().presentation)
                        } } == true } catch (_: Throwable) { itemSame = false }
                    }
                }
            } finally {
                before?.values?.forEach { it.fill(0) }; after?.values?.forEach { it.fill(0) }
                if (!cleanupConfirmed) synchronized(retained) {
                    retained.add(listOfNotNull(session, sessionWorker, route, backendClose, routeClose, basePreparation,
                        catalogSession, coordinator, identityResolver, foreground, foregroundClose, view) + connections.toList() +
                        transports.toList() + catalogSessions.toList() + helixTransports.toList() + helixPreparations.toList() +
                        helixRouteCloses.toList() + helixBackendCloses.toList())
                }
            }
        }
        val requestScope = validationRequests.get() in 1..(if (mode == Mode.REPLAY) 1 else 2) &&
            validationHttp.get() == 200 && accessRequests.get() == 1 && accessHttp.get() == 200 &&
            mediaRequests.get() in 1..MAX_MEDIA_REQUESTS && (mode == Mode.REPLAY || channelScope?.accepted() == true)
        channelScope?.clear()
        val accepted = beforeCleanup == Outcome.FRAME_AND_MOVEMENT && frameObserved.get() && moved == true &&
            noFeedFailure && withinPlaybackBudget && SystemClock.elapsedRealtime() - started in 0 until BUDGET_MS &&
            System.currentTimeMillis() - beganWall in 0 until BUDGET_MS &&
            cleanupConfirmed && recordsSame && ownerSame && itemSame &&
            requestScope && qualityApplied.get() && muted.get() && playRequested.get() &&
            !mutationRefused.get() && !cleanupFailed.get()
        if (accepted) outcome = if (mode == Mode.REPLAY) Outcome.REPLAY_ACCEPTED else Outcome.CHANNEL_ACCEPTED
        Log.i("TachiaiTwitchConfiguredProbe", "outcome=${outcome.name} playbackOutcome=${beforeCleanup.name} " +
            "mode=${mode.name} sdk=${Build.VERSION.SDK_INT} routeMode=${captured?.instance?.setup?.route?.mode?.name ?: "UNAVAILABLE"} " +
            "sourceOrigin=${sourceOrigin.name} sourceCount=$sourceCount replayCount=$replayCount channelCount=$channelCount " +
            "failure=${failure.get().name} frame=${frameObserved.get()} movement=$moved " +
            "firstSampleState=${firstSampleState.name} lastSampleState=${lastSampleState.name} " +
            "noFeedFailure=$noFeedFailure withinBudget=$withinPlaybackBudget " +
            "qualityApplied=${qualityApplied.get()} muted=${muted.get()} playRequested=${playRequested.get()} " +
            "validationRequests=${validationRequests.get()} validationHttp=${validationHttp.get()} " +
            "channelStage=${channelScope?.stage?.name ?: "UNAVAILABLE"} channelRefusal=${channelScope?.refusal?.name ?: "NONE"} " +
            "helixRequests=${channelScope?.helixRequests ?: 0} usersIdHttp=${channelScope?.usersIdHttp ?: -1} " +
            "streamsHttp=${channelScope?.streamsHttp ?: -1} usersLoginHttp=${channelScope?.usersLoginHttp ?: -1} " +
            "streamsParsed=${channelScope?.streamsParsed ?: false} streamsRawCount=${channelScope?.streamsRawCount ?: -1} " +
            "streamsTypedCount=${channelScope?.streamsTypedCount ?: -1} streamsHasCursor=${channelScope?.streamsHasCursor ?: false} " +
            "streamsIdentityMatched=${channelScope?.streamsIdentityMatched} " +
            "accessRequests=${accessRequests.get()} accessHttp=${accessHttp.get()} mediaRequests=${mediaRequests.get()} " +
            "preparationSettled=$preparationSettled workerTerminated=$workerTerminated nativeConfirmed=$nativeConfirmed " +
            "httpConfirmed=$httpConfirmed routeConfirmed=$routeConfirmed cleanupConfirmed=$cleanupConfirmed " +
            "recordsSame=$recordsSame ownerSame=$ownerSame itemSame=$itemSame mutationRefused=${mutationRefused.get()} " +
            "failureStages=${synchronized(stages) { stages.joinToString(",") { it.name } }}")
        if (!accepted) throw Refusal()
    }

    // Delegates actual response bytes unchanged. Only the exact access request
    // is buffered/verified before forwarding; inputs and disconnects have their
    // own definite bounded receipts independent of session/worker completion.
    internal class ObservedConnection(private val remote: HttpsURLConnection,
        private val cleanupFailed: AtomicBoolean, private val current: () -> Boolean,
        private var expectedBody: ByteArray?, private var expectedAuthorization: String?,
        private val onStatus: (Int) -> Unit,
        private var expectedGetAuthorization: String? = null,
        private val expectedGetClientId: String? = null) : HttpsURLConnection(remote.url) {
        private val disconnect = CloseReceipt(remote, HttpsURLConnection::disconnect)
        private val inputs = CopyOnWriteArrayList<CloseReceipt<InputStream>>()
        private val outputClosed = AtomicBoolean()
        private fun guard() { if (!current()) throw Refusal() }
        fun startClose() {
            expectedBody?.fill(0); expectedBody = null; expectedAuthorization = null
            expectedGetAuthorization = null
            disconnect.start(); inputs.forEach { it.start() }
        }
        fun closeConfirmed(deadline: Long) = inputs.all { it.await(deadline) } && disconnect.await(deadline) &&
            inputs.all { it.confirmed } && disconnect.confirmed
        override fun disconnect() {
            if (!disconnect.await(SystemClock.elapsedRealtime() + 5000)) { cleanupFailed.set(true); throw Refusal() }
        }
        override fun connect() { guard(); remote.connect() }
        override fun usingProxy() = remote.usingProxy()
        override fun getCipherSuite(): String = remote.cipherSuite
        override fun getLocalCertificates(): Array<Certificate>? = remote.localCertificates
        override fun getServerCertificates(): Array<Certificate> = remote.serverCertificates
        override fun setRequestMethod(method: String) { remote.requestMethod = method }
        override fun getRequestMethod(): String = remote.requestMethod
        override fun setDoOutput(value: Boolean) { remote.doOutput = value }
        override fun getDoOutput() = remote.doOutput
        override fun setUseCaches(value: Boolean) { remote.useCaches = value }
        override fun getUseCaches() = remote.useCaches
        override fun setInstanceFollowRedirects(value: Boolean) { remote.instanceFollowRedirects = value }
        override fun getInstanceFollowRedirects() = remote.instanceFollowRedirects
        override fun setConnectTimeout(value: Int) { remote.connectTimeout = value }
        override fun getConnectTimeout() = remote.connectTimeout
        override fun setReadTimeout(value: Int) { remote.readTimeout = value }
        override fun getReadTimeout() = remote.readTimeout
        override fun setFixedLengthStreamingMode(value: Int) { remote.setFixedLengthStreamingMode(value) }
        override fun setRequestProperty(key: String, value: String) { remote.setRequestProperty(key, value) }
        override fun getRequestProperty(key: String): String? = remote.getRequestProperty(key)
        override fun getContentLengthLong() = remote.contentLengthLong
        override fun getHeaderField(name: String?): String? = remote.getHeaderField(name)
        override fun getResponseCode(): Int {
            guard()
            if (expectedBody != null && !outputClosed.get()) throw Refusal()
            if (expectedBody == null) {
                if (remote.requestMethod != "GET" || remote.doOutput || remote.getRequestProperty("Cookie") != null) throw Refusal()
                if (expectedGetClientId != null) {
                    if (expectedGetAuthorization == null || remote.instanceFollowRedirects ||
                        remote.getRequestProperty("Authorization") != expectedGetAuthorization ||
                        remote.getRequestProperty("Client-Id") != expectedGetClientId) throw Refusal()
                } else if (remote.getRequestProperty("Authorization") != null) throw Refusal()
            }
            return remote.responseCode.also { onStatus(it.takeIf { code -> code in 100..599 } ?: -1) }
        }
        private fun own(input: InputStream): InputStream {
            val receipt = CloseReceipt(input, InputStream::close).also { inputs.add(it) }
            return object : FilterInputStream(input) {
                override fun close() {
                    if (!receipt.await(SystemClock.elapsedRealtime() + 5000)) { cleanupFailed.set(true); throw Refusal() }
                }
            }
        }
        override fun getInputStream(): InputStream { guard(); return own(remote.inputStream) }
        override fun getErrorStream(): InputStream? = remote.errorStream?.let(::own)
        override fun getOutputStream(): OutputStream {
            val expected = expectedBody ?: throw Refusal()
            val bytes = ByteArrayOutputStream()
            return object : OutputStream() {
                override fun write(value: Int) { if (bytes.size() >= 4096) throw Refusal(); bytes.write(value) }
                override fun write(value: ByteArray, offset: Int, length: Int) {
                    if (length < 0 || bytes.size() + length > 4096) throw Refusal()
                    bytes.write(value, offset, length)
                }
                override fun close() {
                    val body = bytes.toByteArray()
                    try {
                        guard()
                        if (!outputClosed.compareAndSet(false, true) || !body.contentEquals(expected) ||
                            remote.requestMethod != "POST" || !remote.doOutput || remote.instanceFollowRedirects ||
                            remote.getRequestProperty("Authorization") != expectedAuthorization ||
                            remote.getRequestProperty("Client-ID") != "" || remote.getRequestProperty("Cookie") != null)
                            throw Refusal()
                        remote.outputStream.use { it.write(body) }
                    } finally { body.fill(0); bytes.reset() }
                }
            }
        }
    }

    private fun encryptedRecords(directory: File): Map<String, ByteArray> {
        val started = SystemClock.elapsedRealtime()
        fun bounded() = check(SystemClock.elapsedRealtime() - started in 0 until 5000)
        check(directory.isDirectory && directory.canRead() && !Files.isSymbolicLink(directory.toPath()))
        val files = checkNotNull(directory.listFiles()).filter {
            it.name.endsWith(".enc") || it.name.endsWith(".enc.bak") || it.name.endsWith(".enc.new")
        }
        check(files.size in 1..64)
        val result = linkedMapOf<String, ByteArray>()
        try {
            for (file in files) {
                bounded(); check(file.isFile && file.canRead() && !Files.isSymbolicLink(file.toPath()) &&
                    file.length() in 1..(PRIVATE_SECRET_LIMIT + 29).toLong())
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192); var size = 0
                    try { while (true) {
                        bounded(); val count = input.read(buffer); if (count < 0) break
                        size += count; check(size <= PRIVATE_SECRET_LIMIT + 29); digest.update(buffer, 0, count)
                    } } finally { buffer.fill(0) }
                }
                result[file.name] = digest.digest(); bounded()
            }
            return result
        } catch (error: Throwable) { result.values.forEach { it.fill(0) }; result.clear(); throw error }
    }
}
