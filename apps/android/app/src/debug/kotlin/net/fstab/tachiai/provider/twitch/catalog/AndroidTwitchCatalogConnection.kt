package net.fstab.tachiai.provider.twitch.catalog

import android.content.Context
import java.io.File
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import net.fstab.tachiai.feature.connections.*
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.platform.diagnostics.*
import net.fstab.tachiai.platform.storage.AndroidPrivateSecretStore
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.twitch.DeviceAuthResponse
import net.fstab.tachiai.provider.twitch.DeviceRequestPaused

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
    instanceId: String): TwitchCatalogConnectionBinding {
    val application = context.applicationContext
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
    val store = androidTwitchCatalogGrantStore(application, instanceId)
    return object : TwitchCatalogConnectionBinding {
        private val lock = Any()
        private var closed = false
        private var cleanupFailed = false
        private val transports = mutableSetOf<TwitchCatalogTransport>()
        private fun currentOwner(): Boolean = captured.presentation.routeUsable && !synchronized(lock) { closed || cleanupFailed } &&
            runCatching { val current = readOwner().presentation
                current.routeUsable && captured.presentation.sameOwnership(current)
            }.onFailure { diagnostics.report(FailureStage.CATALOG_AUTH_OWNER, it) }.getOrDefault(false)
        override fun owner() = readOwner().presentation
        override fun session() = TwitchCatalogSession(store,
            transportFactory = { transport { true } }, canCommit = ::currentOwner)
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
            val owned = synchronized(lock) { closed = true; transports.toList().also { transports.clear() } }
            owned.forEach { it.close() }
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
    private val createRoute: (ConnectionProfile?, RoutePreparation) -> RouteSession = { selected, preparation ->
        RouteSession.create(selected, preparation)
    },
    private val createTransport: ((URL) -> javax.net.ssl.HttpsURLConnection, () -> Boolean) -> TwitchCatalogTransport = { open, gate ->
        TwitchCatalogAuthTransport(open = open, canRequest = gate)
    },
    private val onClose: () -> Unit = {},
    private val onCleanupFailure: () -> Unit = {},
    private val diagnostics: FailureReporter = FailureReporter.NONE,
) : TwitchCatalogTransport {
    companion object { private val cancellationWorkers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(32), { task -> Thread(task, "twitch-catalog-cancel").apply { isDaemon = true } }) }
    private val lock = Any()
    private var closed = false
    private var revision = 0L
    private var preparation: RoutePreparation? = null
    private var active: TwitchCatalogTransport? = null
    private var cancelSignalled: TwitchCatalogTransport? = null
    override fun device() = exchange { it.device() }
    override fun poll(deviceCode: String) = exchange { it.poll(deviceCode) }
    override fun validate(accessToken: String) = exchange { it.validate(accessToken) }
    override fun refresh(refreshToken: String) = exchange { it.refresh(refreshToken) }

    private fun exchange(request: (TwitchCatalogTransport) -> DeviceAuthResponse): DeviceAuthResponse {
        val preparing = RoutePreparation(diagnostics)
        val current = synchronized(lock) {
            check(!closed && preparation == null)
            preparation = preparing
            revision
        }
        fun allowed() = synchronized(lock) { !closed && revision == current } && canRequest()
        var route: RouteSession? = null
        var transport: TwitchCatalogTransport? = null
        try {
            if (!allowed()) throw DeviceRequestPaused()
            route = createRoute(profile, preparing)
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
        } catch (error: Exception) {
            if (!allowed()) throw DeviceRequestPaused()
            diagnostics.report(FailureStage.CATALOG_AUTH_REQUEST, error)
            throw error
        } finally {
            var failed = false
            try { transport?.close() } catch (error: Throwable) { diagnostics.report(FailureStage.CATALOG_AUTH_CLEANUP, error); failed = true }
            try { route?.close() } catch (error: Throwable) { diagnostics.report(FailureStage.CATALOG_AUTH_CLEANUP, error); failed = true }
            if (!preparing.cleanupConfirmed) failed = true
            if (failed) failCleanup()
            synchronized(lock) {
                if (preparation === preparing) { preparation = null; active = null; cancelSignalled = null }
            }
            if (failed) throw java.io.IOException("Catalog route cleanup failed")
        }
    }
    override fun cancelActiveRequest() {
        val pending = synchronized(lock) {
            revision++
            val request = active?.takeUnless { it === cancelSignalled }
            if (request != null) cancelSignalled = request
            preparation to request
        }
        pending.first?.cancel()
        pending.second?.let { transport ->
            try { cancellationWorkers.execute {
                try { transport.cancelActiveRequest() } catch (_: Throwable) { failCleanup() }
            } } catch (_: RejectedExecutionException) { failCleanup() }
        }
    }
    private fun failCleanup() { close(); onCleanupFailure() }
    override fun close() {
        if (!synchronized(lock) { if (closed) false else { closed = true; true } }) return
        cancelActiveRequest()
        onClose()
    }
    override fun toString() = "OwnedTwitchCatalogTransport(redacted)"
}
