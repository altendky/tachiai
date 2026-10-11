package net.fstab.tachiai.provider.abema

import android.os.SystemClock
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.platform.diagnostics.*
import net.fstab.tachiai.platform.network.*
import net.fstab.tachiai.provider.abema.account.AbemaAccountCleanupReceipt

// Worker-only reads, bound to one captured route/account lease. Every request
// gets its own closed-purpose route; cancellation only signals/schedules work.
internal class OwnedAbemaMyListTransport(
    private val profile: ConnectionProfile?,
    private val bearer: () -> String?,
    private val canRequest: () -> Boolean,
    private val onCleanupFailure: () -> Unit = {},
    private val diagnostics: FailureReporter = FailureReporter.NONE,
    private val createBackend: (ConnectionProfile, RouteProxySecurity, RoutePreparation) -> RouteBackend = { selected, security, preparation ->
        selected.protocol.createBackend(selected, security, preparation)
    },
    private val createTransport: ((URL) -> HttpsURLConnection, () -> String?, () -> Boolean) -> AbemaCollectionTransport = { open, token, gate ->
        AbemaMyListHttpTransport(open, token, gate, SystemClock::elapsedRealtime,
            onFailure = { category ->
                try { diagnostics.report(if (category == AbemaCollectionFailure.CLEANUP_FAILED)
                    FailureStage.CATALOG_CLOSE else FailureStage.CATALOG_LOAD) } catch (_: Throwable) { }
            })
    },
    private val closeRoute: (RouteSession) -> Unit = RouteSession::close,
    private val cleanupWaitMs: Long = 1000,
) : AbemaCollectionTransport {
    init { require(cleanupWaitMs in 1..5000) }
    // Route.close awaits its backend receipt. A distinct bounded pool avoids
    // occupying every backend worker with parents waiting on queued children.
    private class RouteCloseReceipt(val resource: RouteSession,
        private val action: (RouteSession) -> Unit, private val waitMs: Long) {
        companion object {
            private val workers = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                ArrayBlockingQueue<Runnable>(2), { task -> Thread(task, "abema-mylist-route-cleanup").apply { isDaemon = true } })
        }
        private val lock = Any()
        private val finished = CountDownLatch(1)
        private var started = false
        @Volatile private var owner: Thread? = null
        @Volatile private var failed = false
        @Volatile private var successful = false
        fun await(): Boolean {
            val first = synchronized(lock) { if (started) false else { started = true; true } }
            if (first) {
                try { workers.execute {
                    owner = Thread.currentThread()
                    try { action(resource); successful = true } catch (_: Throwable) { failed = true }
                    finally { finished.countDown() }
                } } catch (_: Throwable) { failed = true; finished.countDown() }
            }
            if (finished.count != 0L) {
                if (owner === Thread.currentThread()) { failed = true; return false }
                val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs)
                var interrupted = false
                try {
                    while (finished.count != 0L) {
                        val remaining = end - System.nanoTime()
                        if (remaining <= 0) { failed = true; return false }
                        try { finished.await(remaining, TimeUnit.NANOSECONDS) }
                        catch (_: InterruptedException) { interrupted = true }
                    }
                } finally { if (interrupted) Thread.currentThread().interrupt() }
            }
            return finished.count == 0L && successful && !failed
        }
        override fun toString() = "AbemaMyListRouteCloseReceipt(redacted)"
    }
    private class Run(val preparation: RoutePreparation, val revision: Long, val expectedUrl: String) {
        @Volatile var backend: RouteBackend? = null
        @Volatile var backendClose: AbemaAccountCleanupReceipt<RouteBackend>? = null
        @Volatile var route: RouteSession? = null
        @Volatile var routeClose: RouteCloseReceipt? = null
        @Volatile var transport: AbemaCollectionTransport? = null
        @Volatile var transportClose: AbemaAccountCleanupReceipt<AbemaCollectionTransport>? = null
        val cancelSignalled = AtomicBoolean()
        val retainedOnce = AtomicBoolean()
        val cancellationDone = CountDownLatch(1)
        @Volatile var cancellationFailed = false
        var cancellationPending = false // Owner monitor only.
        var finishing = false // No new signal may race the final cleanup receipt.
        override fun toString() = "AbemaMyListOwnedRun(redacted)"
    }
    companion object {
        // Pending owners survive UI/controller loss; uncertain resources remain
        // process-owned even if a late cleanup action eventually returns.
        private val pending = ConcurrentHashMap.newKeySet<OwnedAbemaMyListTransport>()
        private val retained = ConcurrentLinkedQueue<List<Any>>()
        private val cancellation = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(32), { action -> Thread(action, "abema-mylist-cancel").apply { isDaemon = true } })
    }
    private val lock = Any()
    private var closed = false
    private var poisoned = false
    private var revision = 0L
    private var active: Run? = null
    private val cleanupNotified = AtomicBoolean()

    private fun current(run: Run): Boolean = synchronized(lock) {
        !closed && !poisoned && active === run && revision == run.revision
    } && !run.preparation.isCancelled && try { canRequest() } catch (_: Throwable) { false }
    private fun requireCurrent(run: Run) { if (!current(run)) fail(AbemaCollectionFailure.CANCELLED) }
    private fun fail(category: AbemaCollectionFailure): Nothing = throw AbemaCollectionException(category)
    private fun poison(run: Run?) {
        synchronized(lock) { closed = true; poisoned = true; revision++ }
        pending.add(this)
        if (run != null && run.retainedOnce.compareAndSet(false, true)) {
            retained.add(listOfNotNull(this, run, run.preparation, run.backend, run.backendClose, run.route, run.routeClose, run.transport, run.transportClose))
        }
        if (cleanupNotified.compareAndSet(false, true)) {
            try { diagnostics.report(FailureStage.CATALOG_CLOSE) } catch (_: Throwable) { }
            try { onCleanupFailure() } catch (_: Throwable) { }
        }
    }

    override fun fetchMyList(next: String?): AbemaMyListResponse {
        val expected = try { abemaMyListRequestUri(next).toURL().toExternalForm() }
        catch (_: Throwable) { fail(AbemaCollectionFailure.POLICY_REFUSED) }
        val run = synchronized(lock) {
            if (poisoned) fail(AbemaCollectionFailure.CLEANUP_FAILED)
            if (closed) fail(AbemaCollectionFailure.CANCELLED)
            if (active != null) fail(AbemaCollectionFailure.POLICY_REFUSED)
            Run(RoutePreparation(), revision, expected).also { active = it; pending.add(this) }
        }
        var fatalConstruction = false
        var returning = false
        try {
            requireCurrent(run)
            val route = RouteSession.createAbemaAccountCollections(profile, run.preparation) { selected, security ->
                val allocated = createBackend(selected, security, run.preparation)
                // Registration precedes RouteSession's cancellation/constructor
                // rollback checks and any proxy-port access.
                run.backend = allocated
                val receipt = AbemaAccountCleanupReceipt(allocated, RouteBackend::close, cleanupWaitMs)
                run.backendClose = receipt
                object : RouteBackend {
                    override val proxyPort get() = allocated.proxyPort
                    override fun close() { if (!receipt.await()) fail(AbemaCollectionFailure.CLEANUP_FAILED) }
                    override fun toString() = "AbemaMyListOwnedBackend(redacted)"
                }
            }
            run.route = route
            run.routeClose = RouteCloseReceipt(route, closeRoute, cleanupWaitMs)
            requireCurrent(run)
            val transport = createTransport({ url ->
                requireCurrent(run)
                if (url.toExternalForm() != run.expectedUrl) fail(AbemaCollectionFailure.POLICY_REFUSED)
                route.open(url)
            }, bearer, { current(run) })
            // Capture even if cancellation raced construction. No request may
            // run before both its owner and confirmed-close receipt exist.
            run.transport = transport
            run.transportClose = AbemaAccountCleanupReceipt(transport, AbemaCollectionTransport::close, cleanupWaitMs)
            requireCurrent(run)
            val result = transport.fetchMyList(next)
            requireCurrent(run)
            returning = true
            return result
        } catch (error: Throwable) {
            fatalConstruction = error !is Exception && run.route == null && run.backend != null
            val category = when {
                error is AbemaCollectionException -> error.category
                !current(run) -> AbemaCollectionFailure.CANCELLED
                else -> AbemaCollectionFailure.NETWORK_FAILED
            }
            fail(category)
        } finally {
            // Never release a route/backend while HTTP cleanup is unconfirmed.
            // A route that never returned still has the captured raw backend.
            val cancellationPending = synchronized(lock) { run.finishing = true; run.cancellationPending }
            if (cancellationPending && !awaitCancellation(run)) poison(run)
            val innerClosed = try { run.transportClose?.await() != false } catch (_: Throwable) { false }
            var confirmed = innerClosed && !synchronized(lock) { poisoned }
            if (confirmed) {
                if (run.route != null) {
                    try { if (run.routeClose?.await() != true) confirmed = false } catch (_: Throwable) { confirmed = false }
                } else if (try { run.backendClose?.await() == false } catch (_: Throwable) { true }) confirmed = false
            }
            confirmed = confirmed && !fatalConstruction && run.preparation.cleanupConfirmed &&
                run.backendClose?.confirmed != false
            if (!confirmed) {
                poison(run)
                fail(AbemaCollectionFailure.CLEANUP_FAILED)
            }
            // Owner rechecks may read protected data, so they are outside the
            // monitor and remain on this request worker after cleanup.
            var usable = current(run)
            synchronized(lock) {
                usable = usable && !closed && !poisoned && revision == run.revision
                if (active === run) active = null
                if (!poisoned) pending.remove(this)
            }
            if (returning && !usable) fail(AbemaCollectionFailure.CANCELLED)
        }
    }

    override fun cancelActiveRequest() {
        val run = synchronized(lock) { revision++; active }
        if (run == null) return
        try { if (!run.preparation.cancel()) poison(run) } catch (_: Throwable) { poison(run) }
        val transport = synchronized(lock) {
            run.transport?.takeIf { !run.finishing && run.cancelSignalled.compareAndSet(false, true) }?.also {
                run.cancellationPending = true
            }
        }
        if (transport != null) {
            try { cancellation.execute {
                try { transport.cancelActiveRequest() } catch (_: Throwable) {
                    run.cancellationFailed = true; poison(run)
                } finally { run.cancellationDone.countDown() }
            } } catch (_: Throwable) {
                run.cancellationFailed = true; run.cancellationDone.countDown(); poison(run)
            }
        }
    }
    private fun awaitCancellation(run: Run): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(cleanupWaitMs)
        var interrupted = false
        try {
            while (run.cancellationDone.count != 0L) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                try { if (!run.cancellationDone.await(remaining, TimeUnit.NANOSECONDS)) return false }
                catch (_: InterruptedException) { interrupted = true }
            }
            return !run.cancellationFailed
        } finally { if (interrupted) Thread.currentThread().interrupt() }
    }
    override fun close() {
        synchronized(lock) { closed = true }
        cancelActiveRequest()
        if (synchronized(lock) { poisoned }) fail(AbemaCollectionFailure.CLEANUP_FAILED)
    }
    override fun toString() = "OwnedAbemaMyListTransport(redacted)"
}
