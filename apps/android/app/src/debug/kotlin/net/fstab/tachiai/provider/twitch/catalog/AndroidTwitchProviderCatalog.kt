package net.fstab.tachiai.provider.twitch.catalog

import android.content.Context
import java.io.IOException
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import net.fstab.tachiai.feature.connections.*
import net.fstab.tachiai.platform.diagnostics.*
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.ProviderCatalog

// Constructed on a worker. Unreadable routes keep configured items available for
// local management while preventing every provider request; they never pick System.
internal fun androidTwitchProviderCatalog(context: Context, instanceId: String,
    canUse: () -> Boolean = { true }): ProviderCatalog {
    val application = context.applicationContext
    val diagnostics = FailureDiagnostics.create(application)
    val route = runCatching { readTwitchCatalogRouteOwner(instanceId,
        { providerInstanceStore(application).read { legacyProviderSettings(application) } },
        { connectionProfileStore(application).selected(it) }) }
        .onFailure { diagnostics.report(FailureStage.CATALOG_LOAD, it) }.getOrNull()
    val binding = androidTwitchCatalogConnectionBinding(application, instanceId, canUse)
    try {
        val captured = binding.owner()
        val lock = Any()
        var closed = false
        var cleanupFailed = false
        fun current(): Boolean = canUse() && !synchronized(lock) { closed || cleanupFailed } &&
            route != null && captured.routeUsable && captured.sameOwnership(route.presentation) &&
            !TwitchCatalogConnectionWrites.isPending(instanceId) && !TwitchCatalogConnectionWrites.isFailed(instanceId) &&
            runCatching { captured.sameOwnership(binding.owner()) }
                .onFailure { diagnostics.report(FailureStage.CATALOG_LOAD, it) }.getOrDefault(false)
        val catalog = TwitchProviderCatalog(instanceId, binding.session(),
            transportFactory = { gate -> OwnedTwitchHelixTransport(route?.profile,
                canRequest = { gate() && current() },
                onCleanupFailure = { synchronized(lock) { cleanupFailed = true } }, diagnostics = diagnostics) },
            canPublish = ::current)
        return object : ProviderCatalog by catalog {
            override fun close() {
                if (!synchronized(lock) { if (closed) false else { closed = true; true } }) return
                try { catalog.close() } finally { binding.close() }
            }
            override fun toString() = "AndroidTwitchProviderCatalog(redacted)"
        }
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
    private val createRoute: (ConnectionProfile?, RoutePreparation) -> RouteSession = { selected, preparation ->
        RouteSession.createTwitchCatalog(selected, preparation)
    },
    private val createTransport: ((URL) -> javax.net.ssl.HttpsURLConnection, () -> Boolean) -> TwitchHelixTransport = { open, gate ->
        TwitchHelixHttpTransport(open = open, canRequest = gate)
    },
    private val onCleanupFailure: () -> Unit = {},
    private val diagnostics: FailureReporter = FailureReporter.NONE,
) : TwitchHelixTransport {
    companion object {
        private val cancellationWorkers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(32), { task -> Thread(task, "twitch-helix-cancel").apply { isDaemon = true } })
    }
    private val lock = Any()
    private var closed = false
    private var revision = 0L
    private var preparation: RoutePreparation? = null
    private var active: TwitchHelixTransport? = null
    private var cancelSignalled: TwitchHelixTransport? = null

    override fun execute(accessToken: String, request: TwitchHelixRequest): TwitchHelixResponse {
        val preparing = RoutePreparation(diagnostics)
        val current = synchronized(lock) {
            check(!closed && preparation == null)
            preparation = preparing
            revision
        }
        fun allowed() = synchronized(lock) { !closed && revision == current } && canRequest()
        var route: RouteSession? = null
        var transport: TwitchHelixTransport? = null
        try {
            if (!allowed()) throw IOException("Catalog request unavailable")
            route = createRoute(profile, preparing)
            if (!allowed()) throw IOException("Catalog request unavailable")
            val ownedRoute = route
            transport = createTransport({ url ->
                check(validTwitchHelixUrl(url))
                if (!allowed()) throw IOException("Catalog request unavailable")
                ownedRoute.open(url)
            }, ::allowed)
            synchronized(lock) {
                if (closed || revision != current) throw IOException("Catalog request unavailable")
                active = transport
            }
            val response = transport.execute(accessToken, request)
            if (!allowed()) throw IOException("Catalog request unavailable")
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
    override fun toString() = "OwnedTwitchHelixTransport(redacted)"
}
