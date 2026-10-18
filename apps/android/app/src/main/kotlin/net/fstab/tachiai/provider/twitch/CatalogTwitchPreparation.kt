package net.fstab.tachiai.provider.twitch

import android.content.Context
import java.io.IOException
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.feature.connections.*
import net.fstab.tachiai.platform.net.AccessProbeHttp
import net.fstab.tachiai.platform.media.closeNativeResources
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
    private val expectedOwnerProof: String? = null,
    private val bindingFactory: () -> Pair<TwitchCatalogRouteOwner, TwitchCatalogConnectionBinding> = {
        val application = context.applicationContext
        val route = captureTwitchCatalogRouteOwner(instanceId,
            { providerInstanceStore(application).read { legacyProviderSettings(application) } },
            connectionProfileStore(application)::snapshot, expectedOwnerProof)
        route to androidTwitchCatalogConnectionBinding(application, instanceId, active, route.proof)
    },
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val startupValidation: TwitchCatalogReadOnlyValidation? = null,
) : TwitchPlaybackPreparation {
    private val valid = AtomicBoolean(true)
    private val started = AtomicBoolean()
    private val binding = AtomicReference<TwitchCatalogConnectionBinding?>()
    private val session = AtomicReference<TwitchCatalogSession?>()
    private val access = AtomicReference<AccessProbeHttp?>()
    private val cleanup = TwitchPreparationCleanup()
    @Volatile private var owner: TwitchCatalogConnectionOwner? = null
    @Volatile private var lease: TwitchCatalogLease? = null
    private val storedLock = Any()
    override var acceptanceDeadlineMs = 0L
        private set

    // UI publication/budget admission: memory and monotonic time only.
    override fun canContinue(): Boolean = valid.get() && active() &&
        !TwitchCatalogConnectionWrites.isPending(instanceId) && !TwitchCatalogConnectionWrites.isFailed(instanceId) &&
        binding.get()?.canPublishLocally() == true &&
        lease?.let { session.get()?.isLocallyCurrentForViewer(it) } == true

    // Worker/Media3 checks retain the catalog grant expiry, not the short source
    // preparation deadline. The native playback budget owns source acceptance.
    // Always read the durable bound: another consumer can tighten expiry without
    // rotating the pair generation. UI publication remains memory-only above.
    // Adopt only shared proof, optionally waiting for work already underway;
    // these checks never start validation or refresh and do not renew the source.
    override fun checkStored(force: Boolean): Boolean {
        val began = monotonicMs()
        fun inBudget(): Boolean = began >= 0 && monotonicMs().let { it >= began && it - began < 30_000L }
        return synchronized(storedLock) {
            if (!canContinue()) return false
            val current = runCatching {
                val connection = checkNotNull(session.get())
                val previous = checkNotNull(lease)
                when (connection.admitIdleViewer(previous) { inBudget() && canContinue() }) {
                    TwitchCatalogIdleViewerAdmission.ADMITTED -> true
                    TwitchCatalogIdleViewerAdmission.REFUSED -> false
                    TwitchCatalogIdleViewerAdmission.MAINTENANCE_REQUIRED -> {
                        if (!inBudget() || owner?.let { it.sameOwnership(checkNotNull(binding.get()).owner()) } != true ||
                            !inBudget()) false
                        else {
                            val successor = connection.adoptSuccessor(previous, waitForExistingFlight = true, admissionStartedMs = began)
                            if (successor != null) lease = successor.lease
                            inBudget() && lease?.let(connection::isCurrent) == true && inBudget()
                        }
                    }
                }
            }.getOrDefault(false)
            val admitted = current && canContinue() && inBudget()
            if (!admitted) valid.set(false)
            admitted
        }
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
            val result = startupValidation?.let { connection.adoptReadOnlyValidation(it, route.proof) }
                ?: if (startupValidation == null) connection.validate(force = true)
                else throw IOException("Startup validation is no longer current")
            report(result.summary, onStatus)
            lease = result.lease ?: throw IOException("Playback connection unavailable")
            acceptanceDeadlineMs = minOf(checkNotNull(lease).deadlineMs, sourceDeadline)
            if (!checkStored(force = true) || monotonicMs() >= acceptanceDeadlineMs)
                throw IOException("Playback connection ended")
            val requestLease = checkNotNull(lease)
            val request = AccessProbeHttp(open = openConnection, canRequest = {
                checkStored(force = true) && lease === requestLease && monotonicMs() < acceptanceDeadlineMs
            }, clockMs = monotonicMs)
            check(access.compareAndSet(null, request))
            try {
                val selected = resolveTwitchPlayback(request, if (replay) TwitchAccessCase.REPLAY else TwitchAccessCase.LIVE,
                    resource, requestLease.accessToken, onHttpStatus = { onStatus("ACCESS", it) })
                if (!checkStored(force = true) || lease !== requestLease || monotonicMs() >= acceptanceDeadlineMs)
                    throw IOException("Playback connection ended")
                return selected
            } finally { access.compareAndSet(request, null); cleanup.close(request) }
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

    // Worker-only. The session tracks this cancellation and the resolve worker's
    // finally block before confirming shutdown or releasing its selected route.
    override fun close() {
        valid.set(false)
        closeNativeResources(
            { cleanup.close(access.getAndSet(null)) },
            { cleanup.close(session.getAndSet(null)) },
            { cleanup.close(binding.getAndSet(null)) },
        )
        cleanup.requireConfirmed()
    }
    override fun toString() = "CatalogTwitchPreparation(redacted)"
}
