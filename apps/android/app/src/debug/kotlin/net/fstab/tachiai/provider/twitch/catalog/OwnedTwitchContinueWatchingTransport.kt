package net.fstab.tachiai.provider.twitch.catalog

import android.os.SystemClock
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.platform.diagnostics.*
import net.fstab.tachiai.platform.network.*

// One exact collection request owns its temporary route and all HTTP handles.
// Never release a backend while dependent HTTP cleanup remains uncertain.
internal class OwnedTwitchContinueWatchingTransport(
    private val instanceId: String,
    private val profile: ConnectionProfile?,
    private val canRequest: () -> Boolean,
    private val diagnostics: FailureReporter = FailureReporter.NONE,
    private val createBackend: (ConnectionProfile, RouteProxySecurity, RoutePreparation) -> RouteBackend = { selected, security, preparation ->
        selected.protocol.createBackend(selected, security, preparation)
    },
    private val createTransport: (((URL) -> HttpsURLConnection, () -> Boolean) -> TwitchContinueWatchingTransport)? = null,
    private val closeRoute: (RouteSession) -> Unit = RouteSession::close,
    private val cleanupWaitMs: Long = 1000,
) : TwitchContinueWatchingTransport {
    init { require(net.fstab.tachiai.presentation.validProviderInstanceId(instanceId) && cleanupWaitMs in 1..5000) }
    private class Run(val preparation: RoutePreparation, val revision: Long) {
        @Volatile var backend: RouteBackend? = null
        @Volatile var backendClose: TwitchCollectionCleanupReceipt<RouteBackend>? = null
        @Volatile var route: RouteSession? = null
        @Volatile var routeClose: TwitchCollectionCleanupReceipt<RouteSession>? = null
        @Volatile var transport: TwitchContinueWatchingTransport? = null
        @Volatile var transportClose: TwitchCollectionCleanupReceipt<TwitchContinueWatchingTransport>? = null
        val cancelSignalled = AtomicBoolean()
        val retainedOnce = AtomicBoolean()
        val cancellationDone = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val closeObserved = AtomicBoolean()
        @Volatile var cancellationFailed = false
        var cancellationPending = false // Owner monitor only.
        var finishing = false
        override fun toString() = "TwitchContinueWatchingOwnedRun(redacted)"
    }
    companion object {
        private val pending = ConcurrentHashMap.newKeySet<OwnedTwitchContinueWatchingTransport>()
        private val cancellation = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(32), { task -> Thread(task, "twitch-collection-cancel").apply { isDaemon = true } })
        // Parent close workers cannot occupy the native backend/HTTP workers.
        private val transportCloseWorkers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(4), { task -> Thread(task, "twitch-collection-http-close").apply { isDaemon = true } })
        private val routeCloseWorkers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(4), { task -> Thread(task, "twitch-collection-route-close").apply { isDaemon = true } })
        private val closeObservers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(4), { task -> Thread(task, "twitch-collection-close-observer").apply { isDaemon = true } })
    }
    private val lock = Any()
    private var closed = false
    private var poisoned = false
    private var revision = 0L
    private var active: Run? = null
    override val cleanupConfirmed get() = synchronized(lock) { !poisoned && active == null }
    private fun fail(category: TwitchContinueWatchingFailure): Nothing = throw TwitchContinueWatchingException(category)
    private fun local(run: Run): Boolean = synchronized(lock) {
        !closed && !poisoned && active === run && revision == run.revision
    } && !TwitchContinueWatchingAdmission.poisoned(instanceId) && !run.preparation.isCancelled
    private fun current(run: Run): Boolean = local(run) &&
        (try { canRequest() } catch (_: Throwable) { false }) && local(run)
    private fun requireCurrent(run: Run) { if (!current(run)) fail(TwitchContinueWatchingFailure.CANCELLED) }
    private fun poison(run: Run) {
        synchronized(lock) { poisoned = true; closed = true; revision++ }
        pending.add(this)
        if (run.retainedOnce.compareAndSet(false, true)) {
            TwitchContinueWatchingAdmission.poison(instanceId, listOfNotNull(this, run, run.preparation,
                run.backend, run.backendClose, run.route, run.routeClose, run.transport, run.transportClose))
            try { diagnostics.report(FailureStage.CATALOG_CLOSE) } catch (_: Throwable) { }
        }
    }
    override fun fetch(accessToken: String, expectedUserId: String, nowEpochMs: Long): TwitchContinueWatchingResponse {
        val run = synchronized(lock) {
            if (poisoned || TwitchContinueWatchingAdmission.poisoned(instanceId)) fail(TwitchContinueWatchingFailure.CLEANUP_FAILED)
            if (closed) fail(TwitchContinueWatchingFailure.CANCELLED)
            if (active != null) fail(TwitchContinueWatchingFailure.POLICY_REFUSED)
            Run(RoutePreparation(), revision).also { active = it; pending.add(this) }
        }
        var fatalConstruction = false
        var returning = false
        try {
            requireCurrent(run)
            val route = RouteSession.createTwitchContinueWatching(profile, run.preparation) { selected, security ->
                val allocated = createBackend(selected, security, run.preparation)
                // Capture the real owner before cancellation, proxy-port reads,
                // constructor rollback, or any native fatal failure can occur.
                run.backend = allocated
                val receipt = TwitchCollectionCleanupReceipt(allocated, RouteBackend::close, cleanupWaitMs)
                run.backendClose = receipt
                object : RouteBackend {
                    override val proxyPort get() = allocated.proxyPort
                    override fun close() { if (!receipt.await()) fail(TwitchContinueWatchingFailure.CLEANUP_FAILED) }
                    override fun toString() = "TwitchContinueWatchingOwnedBackend(redacted)"
                }
            }
            run.route = route
            run.routeClose = TwitchCollectionCleanupReceipt(route, closeRoute, cleanupWaitMs, routeCloseWorkers)
            requireCurrent(run)
            val open: (URL) -> HttpsURLConnection = { url ->
                requireCurrent(run)
                if (url.toExternalForm() != TWITCH_CONTINUE_WATCHING_URL) fail(TwitchContinueWatchingFailure.POLICY_REFUSED)
                route.open(url)
            }
            val transport = createTransport?.invoke(open, { current(run) }) ?: TwitchContinueWatchingHttpTransport(open,
                { current(run) }, SystemClock::elapsedRealtime, cleanupWaitMs)
            run.transport = transport
            run.transportClose = TwitchCollectionCleanupReceipt(transport, TwitchContinueWatchingTransport::close,
                cleanupWaitMs, transportCloseWorkers)
            requireCurrent(run)
            if (transport.collectionId != TWITCH_CONTINUE_WATCHING_ID) fail(TwitchContinueWatchingFailure.POLICY_REFUSED)
            val response = transport.fetch(accessToken, expectedUserId, nowEpochMs)
            requireCurrent(run)
            if (response.collectionId != TWITCH_CONTINUE_WATCHING_ID) fail(TwitchContinueWatchingFailure.INVALID_RESPONSE)
            returning = true
            return response
        } catch (error: Throwable) {
            fatalConstruction = error !is Exception && run.route == null && run.backend != null
            fail(when {
                error is TwitchContinueWatchingException -> error.category
                !current(run) -> TwitchContinueWatchingFailure.CANCELLED
                else -> TwitchContinueWatchingFailure.NETWORK_FAILED
            })
        } finally {
            try {
                val cancellationPending = synchronized(lock) { run.finishing = true; run.cancellationPending }
                if (cancellationPending && !awaitCancellation(run)) poison(run)
                val httpClosed = run.transportClose?.await() != false && run.transport?.cleanupConfirmed != false
                var clean = httpClosed && !synchronized(lock) { poisoned } && !fatalConstruction
                if (clean) {
                    clean = if (run.route != null) run.routeClose?.await() == true else run.backendClose?.await() != false
                }
                clean = clean && run.preparation.cleanupConfirmed && run.backendClose?.confirmed != false
                if (!clean) { poison(run); fail(TwitchContinueWatchingFailure.CLEANUP_FAILED) }
                var usable = current(run)
                synchronized(lock) {
                    usable = usable && !closed && !poisoned && revision == run.revision
                    if (active === run) active = null
                    if (!poisoned) pending.remove(this)
                }
                if (returning && !usable) fail(TwitchContinueWatchingFailure.CANCELLED)
            } finally { run.finished.countDown() }
        }
    }
    override fun cancelActiveRequest() {
        val run = synchronized(lock) { revision++; active } ?: return
        try { if (!run.preparation.cancel()) poison(run) } catch (_: Throwable) { poison(run) }
        val transport = synchronized(lock) {
            run.transport?.takeIf { !run.finishing && run.cancelSignalled.compareAndSet(false, true) }?.also { run.cancellationPending = true }
        }
        if (transport != null) try { cancellation.execute {
            try { transport.cancelActiveRequest() } catch (_: Throwable) { run.cancellationFailed = true; poison(run) }
            finally { run.cancellationDone.countDown() }
        } } catch (_: Throwable) { run.cancellationFailed = true; run.cancellationDone.countDown(); poison(run) }
    }
    private fun awaitCompletion(done: CountDownLatch): Boolean {
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
    private fun awaitCancellation(run: Run) = awaitCompletion(run.cancellationDone) && !run.cancellationFailed
    override fun close() {
        val run = synchronized(lock) { closed = true; active }
        cancelActiveRequest()
        // A held owner read or stream must not defer uncertainty indefinitely.
        // Observe completion off the caller; keep all actual handles on timeout.
        if (run != null && run.closeObserved.compareAndSet(false, true)) try { closeObservers.execute {
            if (!awaitCompletion(run.finished) || !cleanupConfirmed) poison(run)
        } } catch (_: Throwable) { poison(run) }
    }
    override fun toString() = "OwnedTwitchContinueWatchingTransport(redacted)"
}
