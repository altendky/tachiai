package net.fstab.tachiai.provider.twitch.catalog

import android.content.Context
import java.io.IOException
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import net.fstab.tachiai.feature.connections.*
import net.fstab.tachiai.platform.diagnostics.*
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.ProviderCatalog

// Constructed on a worker. Unreadable routes keep configured items available for
// local management while preventing every provider request; they never pick System.
internal fun androidTwitchProviderCatalog(context: Context, instanceId: String,
    canUse: () -> Boolean = { true }, expectedOwnerProof: String? = null): ProviderCatalog {
    val application = context.applicationContext
    val diagnostics = FailureDiagnostics.create(application)
    val route = runCatching { captureTwitchCatalogRouteOwner(instanceId,
        { providerInstanceStore(application).read { legacyProviderSettings(application) } },
        connectionProfileStore(application)::snapshot, expectedOwnerProof) }
        .onFailure { diagnostics.report(FailureStage.CATALOG_LOAD, it) }.getOrNull()
    val binding = androidTwitchCatalogConnectionBinding(application, instanceId, canUse, route?.proof ?: expectedOwnerProof)
    try {
        val captured = binding.owner()
        val lock = Any()
        var closed = false
        var cleanupFailed = false
        fun current(): Boolean = canUse() && !synchronized(lock) { closed || cleanupFailed } &&
            !TwitchContinueWatchingAdmission.poisoned(instanceId) &&
            route != null && captured.routeUsable && captured.sameOwnership(route.presentation) &&
            !TwitchCatalogConnectionWrites.isPending(instanceId) && !TwitchCatalogConnectionWrites.isFailed(instanceId) &&
            runCatching { captured.sameOwnership(binding.owner()) }
                .onFailure { diagnostics.report(FailureStage.CATALOG_LOAD, it) }.getOrDefault(false)
        // Public identity import needs the current instance, not a usable
        // account or metadata route. It never constructs a route/transport.
        fun canImport(): Boolean = canUse() && !synchronized(lock) { closed } &&
            runCatching { providerInstanceStore(application).read { legacyProviderSettings(application) }
                .any { it.id == instanceId && it.service == PrototypeService.TWITCH } }
                .onFailure { diagnostics.report(FailureStage.CATALOG_LOAD, it) }.getOrDefault(false)
        val session = binding.session()
        val helix = TwitchProviderCatalog(instanceId, session,
            transportFactory = { gate -> OwnedTwitchHelixTransport(route?.profile,
                canRequest = { gate() && current() },
                onCleanupFailure = { synchronized(lock) { cleanupFailed = true } }, diagnostics = diagnostics,
                instanceId = instanceId) },
            canPublish = ::current)
        val catalog = TwitchContinueWatchingCatalog(helix, session,
            transportFactory = { gate -> OwnedTwitchContinueWatchingTransport(instanceId, route?.profile,
                canRequest = { gate() && current() }, diagnostics = diagnostics) }, canUse = ::current)
        val owned = object : ProviderCatalog by catalog {
            override fun close() {
                if (!synchronized(lock) { if (closed) false else { closed = true; true } }) return
                try { catalog.close() } finally { binding.close() }
            }
            override fun toString() = "AndroidTwitchProviderCatalog(redacted)"
        }
        return TwitchLocalVideoImportCatalog(owned, ::canImport)
    } catch (error: Throwable) {
        runCatching { binding.close() }.onFailure { diagnostics.report(FailureStage.CATALOG_CLOSE, it) }
        throw error
    }
}

// Each GET owns a temporary closed-purpose route. Close/pause only signal and
// schedule interruption; native cleanup completes on the request's worker.
internal class OwnedTwitchHelixTransport(
    private val profile: ConnectionProfile?,
    private val canRequest: () -> Boolean,
    private val createRoute: ((ConnectionProfile?, RoutePreparation) -> RouteSession)? = null,
    private val createTransport: ((URL) -> javax.net.ssl.HttpsURLConnection, () -> Boolean) -> TwitchHelixTransport = { open, gate ->
        TwitchHelixHttpTransport(open = open, canRequest = gate)
    },
    private val onCleanupFailure: () -> Unit = {},
    private val diagnostics: FailureReporter = FailureReporter.NONE,
    private val instanceId: String? = null,
    private val createBackend: (ConnectionProfile, RouteProxySecurity, RoutePreparation) -> RouteBackend = { selected, security, preparation ->
        selected.protocol.createBackend(selected, security, preparation)
    },
    private val cleanupWaitMs: Long = 1000,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : TwitchHelixTransport {
    init { require((instanceId == null || validProviderInstanceId(instanceId)) && cleanupWaitMs in 1..5000) }
    private class Run(val preparation: RoutePreparation, val revision: Long, val began: Long) {
        @Volatile var backend: RouteBackend? = null
        @Volatile var backendClose: TwitchCollectionCleanupReceipt<RouteBackend>? = null
        @Volatile var route: RouteSession? = null
        @Volatile var routeClose: TwitchCollectionCleanupReceipt<RouteSession>? = null
        @Volatile var transport: TwitchHelixTransport? = null
        @Volatile var transportClose: TwitchCollectionCleanupReceipt<TwitchHelixTransport>? = null
        val cancelSignalled = AtomicBoolean()
        val retainedOnce = AtomicBoolean()
        val closeObserved = AtomicBoolean()
        val cancellationDone = CountDownLatch(1)
        val finished = CountDownLatch(1)
        @Volatile var cancellationFailed = false
        var cancellationPending = false
        var finishing = false
        override fun toString() = "TwitchHelixOwnedRun(redacted)"
    }
    companion object {
        private val pending = ConcurrentHashMap.newKeySet<OwnedTwitchHelixTransport>()
        private val retained = ConcurrentLinkedQueue<Any>()
        private val cancellationWorkers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(32), { task -> Thread(task, "twitch-helix-cancel").apply { isDaemon = true } })
        private val transportCloseWorkers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(4), { task -> Thread(task, "twitch-helix-http-close").apply { isDaemon = true } })
        private val routeCloseWorkers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(4), { task -> Thread(task, "twitch-helix-route-close").apply { isDaemon = true } })
        private val closeObservers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(4), { task -> Thread(task, "twitch-helix-close-observer").apply { isDaemon = true } })
    }
    private val lock = Any()
    private var closed = false
    private var revision = 0L
    private var poisoned = false
    private var active: Run? = null
    override val cleanupConfirmed get() = synchronized(lock) { !poisoned && active == null }
    private fun local(run: Run) = synchronized(lock) { !closed && !poisoned && active === run && revision == run.revision } &&
        instanceId?.let(TwitchContinueWatchingAdmission::poisoned) != true && !run.preparation.isCancelled
    private fun current(run: Run): Boolean {
        val now = try { clockMs() } catch (_: Throwable) { return false }
        if (!local(run) || now < run.began || now - run.began >= 30_000) return false
        val allowed = try { canRequest() } catch (_: Throwable) { false }
        val after = try { clockMs() } catch (_: Throwable) { return false }
        return allowed && local(run) && after >= run.began && after - run.began < 30_000
    }
    private fun poison(run: Run) {
        synchronized(lock) { poisoned = true; closed = true; revision++ }
        pending.add(this)
        if (run.retainedOnce.compareAndSet(false, true)) {
            val owners = listOfNotNull(this, run, run.preparation, run.backend, run.backendClose,
                run.route, run.routeClose, run.transport, run.transportClose)
            retained.add(owners)
            instanceId?.let { TwitchContinueWatchingAdmission.poison(it, owners) }
            try { onCleanupFailure() } catch (_: Throwable) { }
        }
    }

    override fun execute(accessToken: String, request: TwitchHelixRequest): TwitchHelixResponse {
        val began = try { clockMs() } catch (_: Throwable) { throw IOException("Catalog request unavailable") }
        if (began < 0) throw IOException("Catalog request unavailable")
        val run = synchronized(lock) {
            check(!closed && !poisoned && active == null && instanceId?.let(TwitchContinueWatchingAdmission::poisoned) != true)
            Run(RoutePreparation(diagnostics), revision, began).also { active = it; pending.add(this) }
        }
        var fatalConstruction: Throwable? = null
        var returning = false
        try {
            if (!current(run)) throw IOException("Catalog request unavailable")
            val route = createRoute?.invoke(profile, run.preparation) ?: RouteSession.createTwitchCatalog(profile, run.preparation) { selected, security ->
                val allocated = createBackend(selected, security, run.preparation)
                run.backend = allocated
                val receipt = TwitchCollectionCleanupReceipt(allocated, RouteBackend::close, cleanupWaitMs)
                run.backendClose = receipt
                object : RouteBackend {
                    override val proxyPort get() = allocated.proxyPort
                    override fun close() {
                        if (synchronized(lock) { poisoned } || run.transport?.cleanupConfirmed == false || !receipt.await())
                            throw IOException("Catalog route cleanup failed")
                    }
                    override fun toString() = "TwitchHelixOwnedBackend(redacted)"
                }
            }
            run.route = route
            run.routeClose = TwitchCollectionCleanupReceipt(route, RouteSession::close, cleanupWaitMs, routeCloseWorkers)
            if (!current(run)) throw IOException("Catalog request unavailable")
            val transport = createTransport({ url ->
                check(validTwitchHelixUrl(url))
                if (!current(run)) throw IOException("Catalog request unavailable")
                route.open(url)
            }, { current(run) })
            run.transport = transport
            run.transportClose = TwitchCollectionCleanupReceipt(transport, TwitchHelixTransport::close, cleanupWaitMs, transportCloseWorkers)
            if (!current(run)) throw IOException("Catalog request unavailable")
            val response = transport.execute(accessToken, request)
            if (!current(run)) throw IOException("Catalog request unavailable")
            returning = true
            return response
        } catch (error: Throwable) {
            if (error !is Exception && run.route == null && run.backend != null) fatalConstruction = error
            if (error is Exception && current(run)) diagnostics.report(FailureStage.CATALOG_LOAD, error)
            throw error
        } finally {
            try {
                val cancellationPending = synchronized(lock) { run.finishing = true; run.cancellationPending }
                if (cancellationPending && (!awaitDone(run.cancellationDone) || run.cancellationFailed)) poison(run)
                var clean = run.transportClose?.await() != false && run.transport?.cleanupConfirmed != false &&
                    !synchronized(lock) { poisoned } && fatalConstruction == null
                if (clean) clean = if (run.route != null) run.routeClose?.await() == true else run.backendClose?.await() != false
                clean = clean && run.preparation.cleanupConfirmed && run.backendClose?.confirmed != false
                if (!clean) {
                    poison(run)
                    diagnostics.report(FailureStage.CATALOG_CLOSE)
                    fatalConstruction?.let { throw it }
                    throw IOException("Catalog route cleanup failed")
                }
                var usable = current(run)
                synchronized(lock) {
                    usable = usable && !closed && !poisoned && revision == run.revision
                    if (active === run) active = null
                    if (!poisoned) pending.remove(this)
                }
                if (returning && !usable) throw IOException("Catalog request unavailable")
            } finally { run.finished.countDown() }
        }
    }
    private fun awaitDone(done: CountDownLatch): Boolean {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(cleanupWaitMs)
        var interrupted = false
        try {
            while (done.count != 0L) {
                val remaining = end - System.nanoTime()
                if (remaining <= 0) return false
                try { done.await(remaining, TimeUnit.NANOSECONDS) } catch (_: InterruptedException) { interrupted = true }
            }
            return true
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }
    override fun cancelActiveRequest() {
        val run = synchronized(lock) { revision++; active } ?: return
        try { if (!run.preparation.cancel()) poison(run) } catch (_: Throwable) { poison(run) }
        val transport = synchronized(lock) {
            run.transport?.takeIf { !run.finishing && run.cancelSignalled.compareAndSet(false, true) }?.also { run.cancellationPending = true }
        }
        transport?.let {
            try { cancellationWorkers.execute {
                try { transport.cancelActiveRequest() } catch (error: Throwable) {
                    run.cancellationFailed = true; poison(run)
                    try { diagnostics.report(FailureStage.CATALOG_CLOSE, error) } catch (_: Throwable) { }
                } finally { run.cancellationDone.countDown() }
            } } catch (error: Throwable) {
                run.cancellationFailed = true; run.cancellationDone.countDown()
                poison(run)
                try { diagnostics.report(FailureStage.CATALOG_CLOSE, error) } catch (_: Throwable) { }
            }
        }
    }
    override fun close() {
        val run = synchronized(lock) { closed = true; active }
        cancelActiveRequest()
        if (run != null && run.closeObserved.compareAndSet(false, true)) try { closeObservers.execute {
            if (!awaitDone(run.finished) || !cleanupConfirmed) poison(run)
        } } catch (_: Throwable) { poison(run) }
    }
    override fun toString() = "OwnedTwitchHelixTransport(redacted)"
}
