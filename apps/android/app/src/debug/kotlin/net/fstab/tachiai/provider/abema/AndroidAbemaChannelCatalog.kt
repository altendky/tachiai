package net.fstab.tachiai.provider.abema

import android.content.Context
import java.io.IOException
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.feature.connections.*
import net.fstab.tachiai.platform.diagnostics.*
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.ProviderCatalog

internal class AbemaCatalogRouteOwner(val instance: ProviderInstance, val profile: ConnectionProfile?) {
    private val choice = checkNotNull(instance.setup.route)
    private val fingerprint = "${instance.id}|${instance.service.name}|${choice.mode.name}|${choice.connectionId ?: ""}|" +
        (profile?.let(::routeConfigurationKey) ?: "SYSTEM")
    fun sameOwnership(other: AbemaCatalogRouteOwner) = fingerprint == other.fingerprint
    override fun toString() = "AbemaCatalogRouteOwner(redacted)"
}

internal fun readAbemaCatalogRouteOwner(instanceId: String, readInstances: () -> List<ProviderInstance>,
    readProfile: (String) -> ConnectionProfile): AbemaCatalogRouteOwner {
    require(validProviderInstanceId(instanceId))
    val instance = checkNotNull(readInstances().singleOrNull { it.id == instanceId && it.service == PrototypeService.ABEMA })
    val choice = checkNotNull(instance.setup.route)
    val profile = when (choice.mode) {
        SourceRouteMode.SYSTEM -> null
        SourceRouteMode.SAVED_CONNECTION -> readProfile(checkNotNull(choice.connectionId))
        SourceRouteMode.SOURCE_DEFAULT -> error("Provider route needs review")
    }
    return AbemaCatalogRouteOwner(instance, profile)
}

// Constructed/called on workers. Local import owns the instance separately from
// metadata admission, so a broken saved route never becomes a System request.
internal fun androidAbemaChannelCatalog(context: Context, instanceId: String,
    setups: Map<PrototypeSource, SourceSetup>, canUse: () -> Boolean = { true }): ProviderCatalog {
    val application = context.applicationContext
    val diagnostics = FailureDiagnostics.create(application)
    fun instances() = providerInstanceStore(application).read { legacyProviderSettings(application) }
    val instance = checkNotNull(instances().singleOrNull { it.id == instanceId && it.service == PrototypeService.ABEMA })
    fun readOwner() = readAbemaCatalogRouteOwner(instanceId, ::instances,
        { connectionProfileStore(application).selected(it) })
    val captured = runCatching(::readOwner).onFailure { diagnostics.report(FailureStage.CATALOG_LOAD, it) }.getOrNull()
    val lock = Any()
    var closed = false
    var cleanupFailed = false
    fun current(): Boolean = canUse() && !synchronized(lock) { closed || cleanupFailed } && captured != null &&
        runCatching { captured.sameOwnership(readOwner()) }
            .onFailure { diagnostics.report(FailureStage.CATALOG_LOAD, it) }.getOrDefault(false)
    fun canImport(): Boolean = canUse() && !synchronized(lock) { closed } &&
        runCatching { instances().any { it.id == instanceId && it.service == PrototypeService.ABEMA } }
            .onFailure { diagnostics.report(FailureStage.CATALOG_LOAD, it) }.getOrDefault(false)
    val catalog = AbemaChannelCatalog(instanceId, transportFactory = { gate ->
        OwnedAbemaChannelTransport(captured?.profile, canRequest = { gate() && current() },
            onCleanupFailure = { synchronized(lock) { cleanupFailed = true } }, diagnostics = diagnostics)
    }, canPublish = ::current)
    val owned = object : ProviderCatalog by catalog {
        override fun close() {
            if (!synchronized(lock) { if (closed) false else { closed = true; true } }) return
            catalog.close()
        }
        override fun toString() = "AndroidAbemaChannelCatalog(redacted)"
    }
    return AbemaLocalImportCatalog(instance, setups, metadata = owned, canImport = ::canImport)
}

internal class OwnedAbemaChannelTransport(
    private val profile: ConnectionProfile?,
    private val canRequest: () -> Boolean,
    private val createRoute: (ConnectionProfile?, RoutePreparation) -> RouteSession = { selected, preparation ->
        RouteSession.createAbemaCatalog(selected, preparation)
    },
    private val createTransport: ((URL) -> HttpsURLConnection, () -> Boolean) -> AbemaChannelTransport = { open, gate ->
        AbemaChannelHttpTransport(open = open, canRequest = gate)
    },
    private val onCleanupFailure: () -> Unit = {},
    private val diagnostics: FailureReporter = FailureReporter.NONE,
) : AbemaChannelTransport {
    companion object {
        private val cancellationWorkers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(32), { task -> Thread(task, "abema-catalog-cancel").apply { isDaemon = true } })
    }
    private val lock = Any()
    private var closed = false
    private var revision = 0L
    private var preparation: RoutePreparation? = null
    private var active: AbemaChannelTransport? = null
    private var cancelSignalled: AbemaChannelTransport? = null

    override fun fetch(): AbemaChannelResponse {
        val preparing = RoutePreparation(diagnostics)
        val current = synchronized(lock) {
            check(!closed && preparation == null)
            preparation = preparing
            revision
        }
        fun allowed() = synchronized(lock) { !closed && revision == current } && canRequest()
        var route: RouteSession? = null
        var transport: AbemaChannelTransport? = null
        try {
            if (!allowed()) throw IOException("Channel catalog unavailable")
            route = createRoute(profile, preparing)
            if (!allowed()) throw IOException("Channel catalog unavailable")
            val ownedRoute = route
            transport = createTransport({ url ->
                check(validAbemaChannelUrl(url))
                if (!allowed()) throw IOException("Channel catalog unavailable")
                ownedRoute.open(url)
            }, ::allowed)
            synchronized(lock) {
                if (closed || revision != current) throw IOException("Channel catalog unavailable")
                active = transport
            }
            val response = transport.fetch()
            if (!allowed()) throw IOException("Channel catalog unavailable")
            return response
        } catch (error: Exception) {
            if (allowed()) diagnostics.report(FailureStage.CATALOG_LOAD, error)
            throw error
        } finally {
            var failed = false
            try { transport?.close() } catch (error: Throwable) { diagnostics.report(FailureStage.CATALOG_CLOSE, error); failed = true }
            try { route?.close() } catch (error: Throwable) { diagnostics.report(FailureStage.CATALOG_CLOSE, error); failed = true }
            if (!preparing.cleanupConfirmed) failed = true
            if (failed) failCleanup()
            synchronized(lock) {
                if (preparation === preparing) { preparation = null; active = null; cancelSignalled = null }
            }
            if (failed) throw IOException("Catalog route cleanup failed")
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
                try { transport.cancelActiveRequest() } catch (error: Throwable) {
                    diagnostics.report(FailureStage.CATALOG_CLOSE, error); failCleanup()
                }
            } } catch (error: RejectedExecutionException) {
                diagnostics.report(FailureStage.CATALOG_CLOSE, error); failCleanup()
            }
        }
    }
    private fun failCleanup() { close(); onCleanupFailure() }
    override fun close() {
        if (!synchronized(lock) { if (closed) false else { closed = true; true } }) return
        cancelActiveRequest()
    }
    override fun toString() = "OwnedAbemaChannelTransport(redacted)"
}
