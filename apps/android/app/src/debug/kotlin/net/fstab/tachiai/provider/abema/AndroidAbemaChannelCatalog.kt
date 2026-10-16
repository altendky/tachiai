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

internal class AbemaCatalogRouteOwner private constructor(val instance: ProviderInstance, val profile: ConnectionProfile?,
    val configuredInstance: ProviderInstance, private val candidateProof: String?) {
    // Direct construction is retained for provider-free ownership fixtures.
    constructor(instance: ProviderInstance, profile: ConnectionProfile?) : this(instance, profile, instance, null)
    // A viewer's worker-built route plan supplies this proof. The first worker
    // ownership check still reconciles its choice/profile with protected state.
    constructor(instance: ProviderInstance, profile: ConnectionProfile?, expectedProof: String) :
        this(instance, profile, instance, expectedProof)
    constructor(route: ProviderEffectiveRoute) : this(route.effectiveInstance, route.profile, route.configuredInstance, route.proof)
    init { require(candidateProof == null || candidateProof.matches(Regex("[a-f0-9]{64}"))) }
    private val choice = checkNotNull(instance.setup.route)
    private val fingerprint = "${instance.id}|${instance.service.name}|${choice.mode.name}|${choice.connectionId ?: ""}|" +
        (profile?.let(::routeConfigurationKey) ?: "SYSTEM")
    fun sameOwnership(other: AbemaCatalogRouteOwner) = proof() == other.proof() && fingerprint == other.fingerprint
    fun proof(): String = candidateProof ?: MessageDigest.getInstance("SHA-256").digest(fingerprint.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    override fun toString() = "AbemaCatalogRouteOwner(redacted)"
}

internal fun readAbemaCatalogRouteOwner(instanceId: String, readInstances: () -> List<ProviderInstance>,
    readProfile: (String) -> ConnectionProfile): AbemaCatalogRouteOwner =
    captureAbemaCatalogRouteOwner(instanceId, readInstances, { ids -> ids.associateWith(readProfile) })

// Production callers supply one coherent protected profile snapshot. A null
// proof chooses only the first configured candidate; no fallback happens here.
internal fun captureAbemaCatalogRouteOwner(instanceId: String, readInstances: () -> List<ProviderInstance>,
    readProfiles: (Set<String>) -> Map<String, ConnectionProfile>, expectedProof: String? = null): AbemaCatalogRouteOwner =
    AbemaCatalogRouteOwner(captureProviderRoutePool(instanceId, PrototypeService.ABEMA, readInstances, readProfiles).resolve(expectedProof))

// Protected owner reads run on workers, outside lifecycle locks. A successful
// read cannot resurrect admission revoked while that read was in progress.
internal fun abemaCatalogOwnerCurrent(canRemainActive: () -> Boolean, readOwnerMatches: () -> Boolean): Boolean =
    canRemainActive() && (try { readOwnerMatches() } catch (_: Throwable) { false }) && canRemainActive()

// Constructed/called on workers. Local import owns the instance separately from
// metadata admission, so a broken saved route never becomes a System request.
internal fun androidAbemaChannelCatalog(context: Context, instanceId: String,
    setups: Map<PrototypeSource, SourceSetup>, canUse: () -> Boolean = { true }, expectedOwnerProof: String? = null): ProviderCatalog {
    val application = context.applicationContext
    val diagnostics = FailureDiagnostics.create(application)
    fun instances() = providerInstanceStore(application).read { legacyProviderSettings(application) }
    val instance = checkNotNull(instances().singleOrNull { it.id == instanceId && it.service == PrototypeService.ABEMA })
    fun readOwner(expected: String?) = captureAbemaCatalogRouteOwner(instanceId, ::instances,
        { connectionProfileStore(application).snapshot(it) }, expected)
    val captured = runCatching { readOwner(expectedOwnerProof) }.onFailure { diagnostics.report(FailureStage.CATALOG_LOAD, it) }.getOrNull()
    val routeAdmission = ProviderRouteAdmission(captured?.proof() ?: expectedOwnerProof)
    val lock = Any()
    var closed = false
    var cleanupFailed = false
    fun canRemainActive(): Boolean = canUse() && routeAdmission.canContinue() && !synchronized(lock) { closed || cleanupFailed }
    fun current(): Boolean = routeAdmission.check(::canRemainActive) { expected ->
        if (captured == null) null else runCatching { readOwner(expected).takeIf(captured::sameOwnership)?.proof() }
            .onFailure { diagnostics.report(FailureStage.CATALOG_LOAD, it) }.getOrNull()
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
    }, slotTransportFactory = if (captured == null) null else { gate ->
        AndroidAbemaSlotMetadataClient(application, instanceId, captured.proof(),
            canRequest = { gate() && current() }, canRemainActive = ::canRemainActive)
    })
    val schedule = AbemaScheduleCatalog(channels,
        transportFactory = if (captured == null || android.os.Build.VERSION.SDK_INT < 28) null else { gate ->
            AndroidAbemaSeriesMetadataClient(application, instanceId, captured.proof(),
                canRequest = { gate() && current() }, canRemainActive = ::canRemainActive)
        }, canPublish = ::current)
    val search = AbemaSearchCatalog(schedule,
        transportFactory = if (captured == null || android.os.Build.VERSION.SDK_INT < 28) null else { gate ->
            AndroidAbemaSeriesMetadataClient(application, instanceId, captured.proof(),
                canRequest = { gate() && current() }, canRemainActive = ::canRemainActive)
        }, canPublish = ::current)
    val publicCatalog = AbemaSeriesChildrenCatalog(search,
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
    fun accountBinding(kind: AbemaCollectionKind) = captured?.let { owner ->
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
                    onCleanupFailure = { accountPoisoned.set(true) }, diagnostics = diagnostics, kind = kind)
            },
            elapsedMs = android.os.SystemClock::elapsedRealtime,
            wallMs = System::currentTimeMillis,
            onCleanupFailure = { accountPoisoned.set(true) },
            kind = kind,
            sharedAccountFactory = {
                AndroidAbemaAccountAccessOwners.attach(application, instanceId, owner.proof())
            },
        )
    }
    val account = accountBinding(AbemaCollectionKind.MY_LIST)
    val history = accountBinding(AbemaCollectionKind.HISTORY)
    fun access(binding: AbemaCollectionAccountBinding?, selected: AbemaCollectionKind): AbemaCollectionAccountAccess =
        binding ?: object : AbemaCollectionAccountAccess {
        override val kind = selected
        override fun access() = CatalogAccess.NOT_VERIFIED
        override fun lease(): AbemaCollectionLease? = null
    }
    val catalog = AbemaAccountCollectionsCatalog(publicCatalog, access(account, AbemaCollectionKind.MY_LIST),
        canUse = ::canRemainActive, history = access(history, AbemaCollectionKind.HISTORY))
    val owned = object : ProviderCatalog by catalog {
        override fun close() {
            if (!synchronized(lock) { if (closed) false else { closed = true; true } }) return
            try { catalog.close() } finally { try { account?.close() } finally { history?.close() } }
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
