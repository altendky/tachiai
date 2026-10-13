package net.fstab.tachiai.provider.twitch.catalog

import android.content.Context
import java.io.File
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import net.fstab.tachiai.feature.connections.*
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.platform.diagnostics.*
import net.fstab.tachiai.platform.storage.AndroidPrivateSecretStore
import net.fstab.tachiai.platform.storage.twitchCatalogInstanceBindingName
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.twitch.DeviceAuthResponse
import net.fstab.tachiai.provider.twitch.DeviceRequestPaused
import net.fstab.tachiai.provider.twitch.SMART_TV_TWITCH_CLIENT_ID

private val twitchCatalogMaintenance = TwitchCatalogMaintenanceRegistry()

// Constructing a competing registry entry allocates no timer or worker. Only
// admitted proof schedules work; cancelled entries leave no delayed queue item.
private object TwitchCatalogMaintenanceScheduling {
    private val timer by lazy { ScheduledThreadPoolExecutor(1, { task ->
        Thread(task, "twitch-maintenance-timer").apply { isDaemon = true }
    }).apply { removeOnCancelPolicy = true } }
    private val workers by lazy { ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(32), { task -> Thread(task, "twitch-maintenance-worker").apply { isDaemon = true } }) }
    fun schedule(delayMs: Long, callback: () -> Unit): AutoCloseable {
        val pending = timer.schedule(Runnable { callback() }, delayMs, TimeUnit.MILLISECONDS)
        return AutoCloseable { pending.cancel(false) }
    }
    fun dispatch(work: () -> Unit): Boolean = try { workers.execute(work); true }
        catch (_: RejectedExecutionException) { false }
}

// All registry/profile/keystore calls stay on workers. A saved System choice is
// intentional; an absent, inherited or broken imported choice is never System.
internal class TwitchCatalogRouteOwner(
    val instance: ProviderInstance, val profile: ConnectionProfile?,
) {
    private val choice = checkNotNull(instance.setup.route)
    val presentation = TwitchCatalogConnectionOwner(instance.id, instance.name, choice.title,
        "${instance.id}|${instance.service.name}|${choice.mode.name}|${choice.connectionId ?: ""}|" +
            (profile?.let(::routeConfigurationKey) ?: "SYSTEM"))
    override fun toString() = "TwitchCatalogRouteOwner(redacted)"
}

internal fun readTwitchCatalogRouteOwner(instanceId: String, readInstances: () -> List<ProviderInstance>,
    readProfile: (String) -> ConnectionProfile): TwitchCatalogRouteOwner {
    require(validProviderInstanceId(instanceId))
    val instance = checkNotNull(readInstances().singleOrNull { it.id == instanceId && it.service == PrototypeService.TWITCH })
    val choice = checkNotNull(instance.setup.route)
    val profile = when (choice.mode) {
        SourceRouteMode.SYSTEM -> null
        SourceRouteMode.SAVED_CONNECTION -> readProfile(checkNotNull(choice.connectionId))
        SourceRouteMode.SOURCE_DEFAULT -> error("Provider route needs review")
    }
    return TwitchCatalogRouteOwner(instance, profile)
}

internal fun androidTwitchCatalogConnectionBinding(context: Context,
    instanceId: String, canUse: () -> Boolean = { true }): TwitchCatalogConnectionBinding {
    val application = context.applicationContext
    AndroidPrivateSecretStore.removeRetiredTwitchRegistration(application)
    val diagnostics = FailureDiagnostics.create(application)
    class Snapshot(val presentation: TwitchCatalogConnectionOwner, val profile: ConnectionProfile?)
    fun readOwner(): Snapshot {
        require(validProviderInstanceId(instanceId))
        val instance = checkNotNull(providerInstanceStore(application).read { legacyProviderSettings(application) }
            .singleOrNull { it.id == instanceId && it.service == PrototypeService.TWITCH })
        val resolved = runCatching { readTwitchCatalogRouteOwner(instanceId, { listOf(instance) },
            { connectionProfileStore(application).selected(it) }) }
            .onFailure { diagnostics.report(FailureStage.CATALOG_AUTH_OWNER, it) }.getOrNull()
        return if (resolved != null) Snapshot(resolved.presentation, resolved.profile) else {
            val choice = instance.setup.route
            Snapshot(TwitchCatalogConnectionOwner(instanceId, instance.name, "Saved route unavailable",
                "UNAVAILABLE|$instanceId|${choice?.mode}|${choice?.connectionId}", routeUsable = false), null)
        }
    }
    val captured = readOwner()
    fun maintenanceOwner(): Boolean = captured.presentation.routeUsable &&
        !TwitchContinueWatchingAdmission.poisoned(instanceId) &&
        !TwitchCatalogConnectionWrites.isPending(instanceId) && !TwitchCatalogConnectionWrites.isFailed(instanceId) &&
        runCatching { val current = readOwner().presentation
            current.routeUsable && captured.presentation.sameOwnership(current)
        }.onFailure { diagnostics.report(FailureStage.CATALOG_AUTH_OWNER, it) }.getOrDefault(false)
    val storage = "${application.packageName}|${application.noBackupFilesDir.canonicalPath}|" +
        twitchCatalogInstanceBindingName(instanceId)
    val maintenance = twitchCatalogMaintenance.acquire(TwitchCatalogMaintenanceScope(storage,
        captured.presentation, SMART_TV_TWITCH_CLIENT_ID, TWITCH_CATALOG_SCOPE)) { onIdle ->
        TwitchCatalogMaintenanceCoordinator(androidTwitchCatalogGrantStore(application, instanceId),
            transportFactory = { canRequest ->
                check(maintenanceOwner())
                lateinit var owned: OwnedTwitchCatalogTransport
                owned = OwnedTwitchCatalogTransport(captured.profile,
                    canRequest = { canRequest() && maintenanceOwner() },
                    onCleanupFailure = { TwitchContinueWatchingAdmission.poison(instanceId, owned) },
                    diagnostics = diagnostics)
                owned
            }, canCommit = ::maintenanceOwner, onIdle = onIdle,
            schedule = TwitchCatalogMaintenanceScheduling::schedule, dispatch = TwitchCatalogMaintenanceScheduling::dispatch)
    }
    val store = maintenance.coordinator.store
    return object : TwitchCatalogConnectionBinding {
        private val lock = Any()
        private var closed = false
        private var cleanupFailed = false
        private val transports = mutableSetOf<TwitchCatalogTransport>()
        private val sessions = mutableSetOf<TwitchCatalogSession>()
        // Main-looper publication admission must never reread the registry,
        // imported profile or encrypted grant. Durable checks remain below.
        override fun canPublishLocally(): Boolean = canUse() && captured.presentation.routeUsable &&
            !TwitchContinueWatchingAdmission.poisoned(instanceId) &&
            !synchronized(lock) { closed || cleanupFailed }
        private fun currentOwner(): Boolean = canPublishLocally() &&
            runCatching { val current = readOwner().presentation
                current.routeUsable && captured.presentation.sameOwnership(current)
            }.onFailure { diagnostics.report(FailureStage.CATALOG_AUTH_OWNER, it) }.getOrDefault(false)
        override fun owner() = readOwner().presentation
        override fun session() = synchronized(lock) {
            check(!closed)
            TwitchCatalogSession(store, transportFactory = ::transport, canCommit = ::currentOwner,
                maintenance = maintenance.coordinator, canParticipate = ::canPublishLocally).also {
                    it.attach(); sessions.add(it)
                }
        }
        override fun transport(canRequest: () -> Boolean): TwitchCatalogTransport {
            check(currentOwner())
            lateinit var owned: OwnedTwitchCatalogTransport
            owned = OwnedTwitchCatalogTransport(captured.profile,
                canRequest = { canRequest() && currentOwner() },
                onCleanupFailure = { synchronized(lock) { cleanupFailed = true } },
                diagnostics = diagnostics,
                onClose = { synchronized(lock) { transports.remove(owned) } })
            synchronized(lock) { check(!closed); transports.add(owned) }
            return owned
        }
        override fun close() {
            val owned = synchronized(lock) {
                closed = true
                (transports.toList() to sessions.toList()).also { transports.clear(); sessions.clear() }
            }
            var failure: Throwable? = null
            val resources: List<AutoCloseable> = owned.second + owned.first
            for (resource in resources) {
                try { resource.close() } catch (error: Throwable) { if (failure == null) failure = error }
            }
            try { maintenance.close() } catch (error: Throwable) { if (failure == null) failure = error }
            failure?.let { throw it }
        }
        override fun toString() = "TwitchCatalogConnectionBinding(redacted)"
    }
}

private fun androidTwitchCatalogGrantStore(context: Context, instanceId: String) =
    TwitchCatalogGrantStore(AndroidPrivateSecretStore.twitchCatalogInstance(context, instanceId),
        instanceId, File(context.noBackupFilesDir, "twitch-catalog-$instanceId.lock"))

// Explicit failed-clear recovery needs neither a readable registry nor a route.
// The immutable internal activity UUID selects only its isolated catalog record.
internal fun forgetAndroidTwitchCatalogConnection(context: Context, instanceId: String): TwitchCatalogSessionResult =
    TwitchCatalogSession(androidTwitchCatalogGrantStore(context.applicationContext, instanceId),
        transportFactory = { error("Local clearing cannot open a transport") }, canCommit = { false }).use { it.forget() }

internal fun hasPendingAndroidTwitchCatalogForget(context: Context, instanceId: String): Boolean {
    require(validProviderInstanceId(instanceId) && instanceId != defaultProviderInstanceId(PrototypeService.ABEMA))
    val directory = context.applicationContext.noBackupFilesDir
    check(directory.canRead())
    return File(directory, "twitch-catalog-$instanceId.lock.forget-pending").exists()
}

// A request owns its temporary route and always releases it on the worker that
// performed HTTP. Cancellation signals preparation immediately; disconnect is
// scheduled so lifecycle callbacks never join a native route or network worker.
internal class OwnedTwitchCatalogTransport(
    private val profile: ConnectionProfile?,
    private val canRequest: () -> Boolean,
    private val createRoute: ((ConnectionProfile?, RoutePreparation) -> RouteSession)? = null,
    private val createTransport: ((URL) -> javax.net.ssl.HttpsURLConnection, () -> Boolean) -> TwitchCatalogTransport = { open, gate ->
        TwitchCatalogAuthTransport(open = open, canRequest = gate)
    },
    private val onClose: () -> Unit = {},
    private val onCleanupFailure: () -> Unit = {},
    private val diagnostics: FailureReporter = FailureReporter.NONE,
    private val createBackend: (ConnectionProfile, RouteProxySecurity, RoutePreparation) -> RouteBackend = { selected, security, preparation ->
        selected.protocol.createBackend(selected, security, preparation)
    },
) : TwitchCatalogTransport {
    companion object { private val cancellationWorkers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(32), { task -> Thread(task, "twitch-catalog-cancel").apply { isDaemon = true } }) }
    private val lock = Any()
    private var closed = false
    private var revision = 0L
    private var preparation: RoutePreparation? = null
    private var active: TwitchCatalogTransport? = null
    private var activeRoute: RouteSession? = null
    private var activeBackend: RouteBackend? = null
    private var cancelSignalled: TwitchCatalogTransport? = null
    private var preparationCancelSignalled: RoutePreparation? = null
    private var cancellationDone: CountDownLatch? = null
    private var cleanupUncertain = false
    private var finishing = false
    // The shared admission owner retains this transport after uncertainty. Keep
    // the actual route/preparation/HTTP owners alive even after finally clears
    // the active-request fields; a successful sibling close is not their proof.
    private val uncertainOwners = mutableSetOf<Any>()
    override fun device() = exchange { it.device() }
    override fun poll(deviceCode: String) = exchange { it.poll(deviceCode) }
    override fun validate(accessToken: String) = exchange { it.validate(accessToken) }
    override fun refresh(refreshToken: String) = exchange { it.refresh(refreshToken) }

    private fun exchange(request: (TwitchCatalogTransport) -> DeviceAuthResponse): DeviceAuthResponse {
        val preparing = RoutePreparation(diagnostics)
        val current = synchronized(lock) {
            check(!closed && preparation == null)
            preparation = preparing
            finishing = false
            revision
        }
        fun allowed() = synchronized(lock) { !closed && revision == current } && canRequest()
        var route: RouteSession? = null
        var transport: TwitchCatalogTransport? = null
        var backend: RouteBackend? = null
        var fatalConstruction: Throwable? = null
        try {
            if (!allowed()) throw DeviceRequestPaused()
            route = createRoute?.invoke(profile, preparing) ?: RouteSession.createTwitchCatalog(profile, preparing) { selected, security ->
                // Capture before proxy-port access, post-create admission or
                // constructor rollback can fail without returning a route.
                createBackend(selected, security, preparing).also {
                    backend = it; synchronized(lock) { activeBackend = it }
                }
            }
            synchronized(lock) { activeRoute = route }
            if (!allowed()) throw DeviceRequestPaused()
            val ownedRoute = route
            transport = createTransport({ url ->
                check(url.toExternalForm() in setOf("https://id.twitch.tv/oauth2/device",
                    "https://id.twitch.tv/oauth2/token", "https://id.twitch.tv/oauth2/validate"))
                if (!allowed()) throw DeviceRequestPaused()
                ownedRoute.open(url)
            }, ::allowed)
            synchronized(lock) { if (closed || revision != current) throw DeviceRequestPaused(); active = transport }
            val response = request(transport)
            if (!allowed()) throw DeviceRequestPaused()
            return response
        } catch (error: Throwable) {
            if (error !is Exception && route == null && backend != null) fatalConstruction = error
            if (error is Exception) {
                if (!allowed()) throw DeviceRequestPaused()
                diagnostics.report(FailureStage.CATALOG_AUTH_REQUEST, error)
            }
            throw error
        } finally {
            val cancellation = synchronized(lock) { finishing = true; cancellationDone }
            var failed = fatalConstruction != null
            try { transport?.close() } catch (error: Throwable) { diagnostics.report(FailureStage.CATALOG_AUTH_CLEANUP, error); failed = true }
            // Cancellation runs away from lifecycle callbacks. Its disconnect
            // must finish before the worker releases the dependent route or
            // publishes success; an unresolved HTTP owner keeps that route.
            try { if (cancellation?.await(1000, TimeUnit.MILLISECONDS) == false) failed = true }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); failed = true }
            synchronized(lock) {
                failed = failed || cleanupUncertain
            }
            if (!failed && !preparing.cleanupConfirmed) failed = true
            if (!failed) {
                try { route?.close() } catch (error: Throwable) { diagnostics.report(FailureStage.CATALOG_AUTH_CLEANUP, error); failed = true }
            }
            if (!failed && !preparing.cleanupConfirmed) failed = true
            synchronized(lock) {
                if (failed) uncertainOwners.addAll(listOfNotNull(preparing, transport, route, backend))
            }
            if (failed) failCleanup()
            synchronized(lock) {
                if (preparation === preparing) {
                    preparation = null; active = null; activeRoute = null; activeBackend = null
                    cancelSignalled = null; preparationCancelSignalled = null; cancellationDone = null
                }
            }
            fatalConstruction?.let { throw it }
            if (failed) throw java.io.IOException("Catalog route cleanup failed")
            if (!allowed()) throw DeviceRequestPaused()
        }
    }
    override fun cancelActiveRequest() {
        val pending = synchronized(lock) {
            revision++
            val preparing = preparation?.takeUnless { finishing || it === preparationCancelSignalled }
            val request = active?.takeUnless { finishing || it === cancelSignalled }
            val parts = (if (preparing != null) 1 else 0) + (if (request != null) 1 else 0)
            val done = if (parts > 0) CountDownLatch(parts).also {
                preparationCancelSignalled = preparing; cancelSignalled = request; cancellationDone = it
            } else null
            Triple(preparing, request, done)
        }
        pending.first?.let { preparing ->
            try { if (!preparing.cancel()) failCleanup() }
            finally { pending.third?.countDown() }
        }
        pending.second?.let { transport ->
            try { cancellationWorkers.execute {
                try { transport.cancelActiveRequest() } catch (_: Throwable) { failCleanup() }
                finally { pending.third?.countDown() }
            } } catch (_: RejectedExecutionException) { failCleanup(); pending.third?.countDown() }
        }
    }
    private fun failCleanup() {
        val first = synchronized(lock) {
            val first = !cleanupUncertain
            cleanupUncertain = true
            uncertainOwners.addAll(listOfNotNull(preparation, active, activeRoute, activeBackend))
            first
        }
        close(); if (first) onCleanupFailure()
    }
    override fun close() {
        if (!synchronized(lock) { if (closed) false else { closed = true; true } }) return
        cancelActiveRequest()
        onClose()
    }
    override fun toString() = "OwnedTwitchCatalogTransport(redacted)"
}
