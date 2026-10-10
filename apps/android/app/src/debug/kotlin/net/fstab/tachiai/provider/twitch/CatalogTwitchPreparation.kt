package net.fstab.tachiai.provider.twitch

import android.content.Context
import java.io.IOException
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.feature.connections.*
import net.fstab.tachiai.platform.net.AccessProbeHttp
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.twitch.catalog.*

// The main viewer consumes the instance's single scoped Smart TV connection.
// Construction is cheap; owner, route and protected grant reads start on resolve's
// worker. Tokens stay in the session lease and are never copied to legacy stores.
internal class CatalogTwitchPreparation(context: Context, private val instanceId: String,
    private val expectedProfile: ConnectionProfile?, private val expectedRoute: SourceRouteChoice,
    private val active: () -> Boolean,
    private val openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
    private val bindingFactory: () -> Pair<TwitchCatalogRouteOwner, TwitchCatalogConnectionBinding> = {
        val application = context.applicationContext
        val route = readTwitchCatalogRouteOwner(instanceId,
            { providerInstanceStore(application).read { legacyProviderSettings(application) } },
            { connectionProfileStore(application).selected(it) })
        route to androidTwitchCatalogConnectionBinding(application, instanceId, active)
    },
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
) : TwitchPlaybackPreparation {
    companion object {
        private val cancellationWorkers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(32), { task -> Thread(task, "twitch-playback-cancel").apply { isDaemon = true } })
    }
    private val valid = AtomicBoolean(true)
    private val started = AtomicBoolean()
    private val binding = AtomicReference<TwitchCatalogConnectionBinding?>()
    private val session = AtomicReference<TwitchCatalogSession?>()
    private val access = AtomicReference<AccessProbeHttp?>()
    @Volatile private var owner: TwitchCatalogConnectionOwner? = null
    @Volatile private var lease: TwitchCatalogLease? = null
    private var checkedMs = Long.MIN_VALUE
    private val storedLock = Any()
    override var acceptanceDeadlineMs = 0L
        private set

    // UI publication/budget admission: memory and monotonic time only.
    override fun canContinue(): Boolean = valid.get() && active() &&
        !TwitchCatalogConnectionWrites.isPending(instanceId) && !TwitchCatalogConnectionWrites.isFailed(instanceId) &&
        binding.get()?.canPublishLocally() == true &&
        lease?.let { session.get()?.isLocallyCurrent(it) } == true

    // Worker/Media3 checks retain the catalog grant expiry, not the short source
    // preparation deadline. The native playback budget owns source acceptance.
    override fun checkStored(force: Boolean): Boolean = synchronized(storedLock) {
        if (!canContinue()) return false
        val now = monotonicMs()
        if (!force && checkedMs != Long.MIN_VALUE && now >= checkedMs && now - checkedMs < 1_000) return true
        val current = runCatching {
            owner?.let { it.sameOwnership(checkNotNull(binding.get()).owner()) } == true &&
                lease?.let { session.get()?.isCurrent(it) } == true
        }.getOrDefault(false)
        checkedMs = now
        if (!current) valid.set(false)
        current && canContinue()
    }

    override fun resolve(replay: Boolean, resource: String, onStatus: (String, Int) -> Unit): TwitchPlaybackSource {
        check(started.compareAndSet(false, true) && valid.get() && active())
        require(validTwitchPlaybackResource(if (replay) TwitchAccessCase.REPLAY else TwitchAccessCase.LIVE, resource))
        val sourceDeadline = Math.addExact(monotonicMs(), 30_000L)
        try {
            val (route, owned) = bindingFactory()
            check(binding.compareAndSet(null, owned))
            check(valid.get() && active())
            val actual = checkNotNull(route.instance.setup.route)
            check(route.instance.id == instanceId && route.instance.service == PrototypeService.TWITCH &&
                actual.mode == expectedRoute.mode && actual.connectionId == expectedRoute.connectionId &&
                (route.profile?.let(::routeConfigurationKey) ?: "SYSTEM") ==
                (expectedProfile?.let(::routeConfigurationKey) ?: "SYSTEM"))
            val captured = owned.owner()
            check(captured.routeUsable && captured.sameOwnership(route.presentation))
            owner = captured
            check(!TwitchCatalogConnectionWrites.isPending(instanceId) && !TwitchCatalogConnectionWrites.isFailed(instanceId) &&
                owned.canPublishLocally())
            val connection = owned.session()
            check(session.compareAndSet(null, connection))
            check(valid.get() && active())
            report(connection.readSummary(), onStatus)
            val result = connection.validate(force = true)
            report(result.summary, onStatus)
            lease = result.lease ?: throw IOException("Playback connection unavailable")
            acceptanceDeadlineMs = minOf(checkNotNull(lease).deadlineMs, sourceDeadline)
            if (!checkStored(force = true) || monotonicMs() >= acceptanceDeadlineMs)
                throw IOException("Playback connection ended")
            val request = AccessProbeHttp(open = openConnection, canRequest = {
                checkStored(force = true) && monotonicMs() < acceptanceDeadlineMs
            }, clockMs = monotonicMs)
            check(access.compareAndSet(null, request))
            try {
                val selected = resolveTwitchPlayback(request, if (replay) TwitchAccessCase.REPLAY else TwitchAccessCase.LIVE,
                    resource, checkNotNull(lease).accessToken, onHttpStatus = { onStatus("ACCESS", it) })
                if (!checkStored(force = true) || monotonicMs() >= acceptanceDeadlineMs)
                    throw IOException("Playback connection ended")
                return selected
            } finally { access.compareAndSet(request, null); request.close() }
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    private fun report(summary: TwitchCatalogSessionSummary, onStatus: (String, Int) -> Unit) {
        when (summary.state) {
            TwitchCatalogSessionState.MISSING -> onStatus("STORAGE_MISSING", 0)
            TwitchCatalogSessionState.STORAGE_FAILURE -> onStatus("STORAGE_UNREADABLE", 0)
            TwitchCatalogSessionState.RECONNECT_REQUIRED -> onStatus(
                if (summary.failure == TwitchCatalogAuthFailure.EXPIRED) "STORAGE_EXPIRED" else "VALIDATION_REJECTED", 0)
            TwitchCatalogSessionState.SUPERSEDED, TwitchCatalogSessionState.PAUSED -> onStatus("SUPERSEDED", 0)
            TwitchCatalogSessionState.TEMPORARY_FAILURE -> onStatus("NETWORK_FAILED", 0)
            TwitchCatalogSessionState.CONNECTED -> onStatus("VALIDATE", 200)
            TwitchCatalogSessionState.UNVERIFIED -> onStatus("STORAGE_READY", 0)
        }
    }

    // No protected reads or route joins on lifecycle callbacks. The HTTP worker
    // owns final disconnect; cancellation schedules an early disconnect attempt.
    override fun close() {
        valid.set(false)
        try { session.getAndSet(null)?.close() }
        finally {
            try { binding.getAndSet(null)?.close() }
            finally { access.getAndSet(null)?.let { request ->
                try { cancellationWorkers.execute { request.close() } }
                catch (_: RejectedExecutionException) { /* Admission is already ended; the bounded worker closes in finally. */ }
            } }
        }
    }
    override fun toString() = "CatalogTwitchPreparation(redacted)"
}
