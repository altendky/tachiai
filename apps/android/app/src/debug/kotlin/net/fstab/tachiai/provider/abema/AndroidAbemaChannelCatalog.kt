package net.fstab.tachiai.provider.abema

import android.content.Context
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.feature.connections.*
import net.fstab.tachiai.platform.diagnostics.*
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import net.fstab.tachiai.provider.abema.account.*

internal class AbemaCatalogRouteOwner(val instance: ProviderInstance, val profile: ConnectionProfile?) {
    private val choice = checkNotNull(instance.setup.route)
    private val fingerprint = "${instance.id}|${instance.service.name}|${choice.mode.name}|${choice.connectionId ?: ""}|" +
        (profile?.let(::routeConfigurationKey) ?: "SYSTEM")
    fun sameOwnership(other: AbemaCatalogRouteOwner) = fingerprint == other.fingerprint
    fun proof(): String = MessageDigest.getInstance("SHA-256").digest(fingerprint.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
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

// Protected owner reads run on workers, outside lifecycle locks. A successful
// read cannot resurrect admission revoked while that read was in progress.
internal fun abemaCatalogOwnerCurrent(canRemainActive: () -> Boolean, readOwnerMatches: () -> Boolean): Boolean =
    canRemainActive() && (try { readOwnerMatches() } catch (_: Throwable) { false }) && canRemainActive()

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
    fun canRemainActive(): Boolean = canUse() && !synchronized(lock) { closed || cleanupFailed }
    fun current(): Boolean = abemaCatalogOwnerCurrent(::canRemainActive) {
        captured != null && runCatching { captured.sameOwnership(readOwner()) }
            .onFailure { diagnostics.report(FailureStage.CATALOG_LOAD, it) }.getOrDefault(false)
    }
    fun canImport(): Boolean = canUse() && !synchronized(lock) { closed } &&
        runCatching { instances().any { it.id == instanceId && it.service == PrototypeService.ABEMA } }
            .onFailure { diagnostics.report(FailureStage.CATALOG_LOAD, it) }.getOrDefault(false)
    val channels = AbemaChannelCatalog(instanceId, transportFactory = { gate ->
        OwnedAbemaChannelTransport(captured?.profile, canRequest = { gate() && current() },
            onCleanupFailure = { synchronized(lock) { cleanupFailed = true } }, diagnostics = diagnostics)
    }, canPublish = ::current, episodeTransportFactory = if (captured == null) null else { gate ->
        AndroidAbemaEpisodeMetadataClient(application, instanceId, captured.proof(),
            canRequest = { gate() && current() }, canRemainActive = ::canRemainActive)
    })
    val publicCatalog = AbemaSeriesChildrenCatalog(channels,
        transportFactory = if (captured == null || android.os.Build.VERSION.SDK_INT < 28) null else { gate ->
            AndroidAbemaSeriesMetadataClient(application, instanceId, captured.proof(),
                canRequest = { gate() && current() }, canRemainActive = ::canRemainActive)
        }, canPublish = ::current)
    // Account proof is worker-owned and cannot start Connect or mutate storage.
    // Its cleanup gate is separate so an unverified collection does not disable
    // independently usable public discovery or local configured-item management.
    val accountPoisoned = AbemaAccountWorkers.poisoned(instanceId)
    fun accountCurrent() = !accountPoisoned.get() && current()
    fun accountActive() = !accountPoisoned.get() && canRemainActive()
    val account = captured?.let { owner ->
        AbemaCollectionAccountBinding(
            store = AbemaAccountGrantStore(instanceId, AndroidAbemaAccountStorage(application, instanceId)),
            instanceId = instanceId,
            ownerProof = owner.proof(),
            sameOwner = ::accountCurrent,
            canRemainActive = ::accountActive,
            prepareValidationFactory = { gate, foreground ->
                AndroidAbemaAccountBootstrapClient(application, instanceId, owner.proof(),
                    canRequest = { gate() && accountCurrent() }, canRemainActive = foreground)
            },
            profileTransportFactory = { gate ->
                OwnedAbemaAccountTransport(owner.profile, { gate() && accountCurrent() }, { accountPoisoned.set(true) })
            },
            collectionTransportFactory = { bearer, gate ->
                OwnedAbemaMyListTransport(owner.profile, bearer, { gate() && accountCurrent() },
                    onCleanupFailure = { accountPoisoned.set(true) }, diagnostics = diagnostics)
            },
            elapsedMs = android.os.SystemClock::elapsedRealtime,
            wallMs = System::currentTimeMillis,
            onCleanupFailure = { accountPoisoned.set(true) },
        )
    }
    val access = account ?: object : AbemaCollectionAccountAccess {
        override fun access() = CatalogAccess.NOT_VERIFIED
        override fun lease(): AbemaCollectionLease? = null
    }
    val catalog = AbemaAccountCollectionsCatalog(publicCatalog, access, canUse = ::canRemainActive)
    val owned = object : ProviderCatalog by catalog {
        override fun close() {
            if (!synchronized(lock) { if (closed) false else { closed = true; true } }) return
            try { catalog.close() } finally { account?.close() }
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
