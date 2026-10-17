package net.fstab.tachiai.feature.presentation

import java.util.concurrent.Executor
import net.fstab.tachiai.platform.diagnostics.FailureReporter
import net.fstab.tachiai.platform.diagnostics.FailureStage

// One process owner, independent of Activity lifetime. All state transitions
// happen on main; workers may only register resources or close captured routes.
internal class CachedViewerCleanupCoordinator(
    private val post: (() -> Unit) -> Unit,
    private val delay: (Long, () -> Unit) -> Unit,
    private val closeWorker: Executor,
    private val clearProxy: (FailureReporter, () -> Unit) -> Unit,
    private val changed: () -> Unit,
) {
    internal class Feed(val resource: AutoCloseable, val confirmed: () -> Boolean, val pending: () -> Boolean,
        val knownFailed: () -> Boolean) {
        var attempted = false
        var accepted = false
    }
    internal class Helper(val retained: Any, val destroy: () -> Boolean, val confirmed: () -> Boolean)
    internal class Run internal constructor(val diagnostics: FailureReporter) {
        internal enum class Phase { ACTIVE, ENDING, CLEARING, CLOSING, FINISHED, POISONED }
        @Volatile internal var phase = Phase.ACTIVE
        internal val routes = mutableListOf<AutoCloseable>()
        internal val feeds = linkedMapOf<Int, Feed>()
        // Freed slots may be reused, but their receipts remain owned until the
        // run ends so a late cleanup failure cannot disappear on replacement.
        internal val retiredFeeds = mutableListOf<Feed>()
        internal val helpers = mutableListOf<Helper>()
        internal var cancelPreparation: (() -> Boolean)? = null
        internal var producerFinished = true
        internal var proxyTouched = false
        internal var feedsClosed = false
        internal var feedClosesInProgress = 0
        internal var cleanupFailed = false
        internal var failureStage: FailureStage? = null
        override fun toString() = "CachedViewerRun(status only)"
    }

    @Volatile private var owned: Run? = null
    val unavailable get() = owned != null
    val failed get() = owned?.phase == Run.Phase.POISONED
    val playbackFailed get() = failed && owned?.failureStage == FailureStage.FEED_CLEANUP_UNCONFIRMED
    fun begin(diagnostics: FailureReporter): Run? {
        if (owned != null) return null
        return Run(diagnostics).also { owned = it; changed() }
    }
    fun current(run: Run) = owned === run && run.phase == Run.Phase.ACTIVE
    fun preparation(run: Run, cancel: () -> Boolean) {
        check(current(run)); check(run.cancelPreparation == null)
        run.producerFinished = false
        run.cancelPreparation = cancel
    }
    // Register before posting any Activity result, including cancelled results.
    fun route(run: Run, route: AutoCloseable, replaces: AutoCloseable? = null) = synchronized(run.routes) {
        check(run.phase != Run.Phase.FINISHED)
        if (run.routes.none { it === route }) run.routes.add(route)
        if (replaces != null) run.routes.removeAll { it === replaces }
    }
    fun helper(run: Run, retained: Any, destroy: () -> Boolean, confirmed: () -> Boolean = { true }) {
        check(current(run))
        run.helpers.add(Helper(retained, destroy, confirmed))
    }
    fun feed(run: Run, slot: Int, feed: AutoCloseable, pending: () -> Boolean = { false },
        knownFailed: () -> Boolean = { false }, confirmed: () -> Boolean) {
        check(current(run)); check(slot !in run.feeds)
        run.feeds[slot] = Feed(feed, confirmed, pending, knownFailed)
    }
    fun proxyWillChange(run: Run) { check(current(run)); run.proxyTouched = true }
    fun prepared(run: Run, confirmed: Boolean) {
        if (owned !== run) return
        run.producerFinished = true
        if (!confirmed) poison(run, FailureStage.ROUTE_CANCEL_UNCONFIRMED)
        else advance(run)
    }
    fun closeFeed(run: Run, slot: Int): Boolean {
        if (owned !== run || run.phase != Run.Phase.ACTIVE) return false
        val entry = run.feeds[slot] ?: return true
        val accepted = closeFeed(run, entry)
        // Keep an uncertain consumer strongly owned even after UI detachment.
        if (!accepted) {
            run.cleanupFailed = true
            end(run)
        }
        if (run.phase == Run.Phase.ENDING) finishFeeds(run)
        return accepted
    }
    fun feedClosed(run: Run, slot: Int): Boolean {
        if (!current(run) || !consumersConfirmed(run.retiredFeeds)) return false
        val entry = run.feeds[slot] ?: return true
        return entry.attempted && entry.accepted && consumersConfirmed(listOf(entry))
    }
    fun retireClosedFeed(run: Run, slot: Int): Boolean {
        if (!feedClosed(run, slot)) return false
        run.feeds.remove(slot)?.let(run.retiredFeeds::add)
        return true
    }
    private fun feeds(run: Run): List<Feed> = run.retiredFeeds + run.feeds.values
    fun feedCleanupChanged(run: Run) {
        if (owned !== run) return
        if (run.phase == Run.Phase.ACTIVE) {
            val ownedFeeds = feeds(run)
            val completed = ownedFeeds.filter { it.attempted && !runCatching(it.pending).getOrDefault(true) }
            if (ownedFeeds.any { runCatching(it.knownFailed).getOrDefault(true) } ||
                completed.any { !runCatching(it.confirmed).getOrDefault(false) }) {
                run.cleanupFailed = true; end(run)
            }
        } else if (run.phase == Run.Phase.ENDING) finishFeeds(run)
        else if (run.phase in setOf(Run.Phase.CLEARING, Run.Phase.CLOSING) && !consumersConfirmed(feeds(run)))
            poison(run, FailureStage.FEED_CLEANUP_UNCONFIRMED)
    }
    private fun closeFeed(run: Run, entry: Feed): Boolean {
        if (!entry.attempted) {
            entry.attempted = true
            run.feedClosesInProgress++
            try {
                entry.accepted = run.diagnostics.cleanupAll(FailureStage.FEED_CLOSE) { entry.resource.close() }
            } finally { run.feedClosesInProgress-- }
        }
        return entry.accepted && !runCatching(entry.knownFailed).getOrDefault(true) &&
            (runCatching(entry.pending).getOrDefault(true) || runCatching(entry.confirmed).getOrDefault(false))
    }
    fun end(run: Run) {
        if (owned !== run || run.phase != Run.Phase.ACTIVE) return
        run.phase = Run.Phase.ENDING
        changed()
        delay(20_000) { if (owned === run && run.phase !in setOf(Run.Phase.FINISHED, Run.Phase.POISONED))
            poison(run, FailureStage.ROUTE_CLEAR_TIMEOUT) }
        if (!runCatching { run.cancelPreparation?.invoke() ?: true }.getOrDefault(false)) run.cleanupFailed = true
        finishFeeds(run)
    }
    private fun finishFeeds(run: Run) {
        // A synchronous feed event can ask for disposal inside that feed's
        // close(). Wait for its receipt before destroying helpers or clearing.
        if (owned !== run || run.phase != Run.Phase.ENDING || run.feedsClosed || run.feedClosesInProgress != 0) return
        feeds(run).forEach { entry ->
            val accepted = closeFeed(run, entry)
            if (!runCatching(entry.pending).getOrDefault(true) && !accepted) run.cleanupFailed = true
        }
        if (feeds(run).any { runCatching(it.pending).getOrDefault(true) }) return
        var destroyed = true
        run.helpers.forEach { helper ->
            if (!run.diagnostics.cleanupAll(FailureStage.WEBVIEW_DESTROY) {
                if (!helper.destroy()) destroyed = false
            }) destroyed = false
            if (!runCatching(helper.confirmed).getOrDefault(false)) run.cleanupFailed = true
        }
        run.feedsClosed = true
        if (!destroyed || run.cleanupFailed) poison(run, FailureStage.FEED_CLEANUP_UNCONFIRMED)
        else advance(run)
    }
    private fun advance(run: Run) {
        if (owned !== run || run.phase != Run.Phase.ENDING || !run.producerFinished || !run.feedsClosed) return
        if (!consumersConfirmed(feeds(run))) { poison(run, FailureStage.FEED_CLEANUP_UNCONFIRMED); return }
        if (!run.proxyTouched) { closeRoutes(run); return }
        run.phase = Run.Phase.CLEARING
        delay(3_000) { if (owned === run && run.phase == Run.Phase.CLEARING)
            poison(run, FailureStage.ROUTE_CLEAR_TIMEOUT) }
        try {
            clearProxy(run.diagnostics) {
                // Late acknowledgment is not permission to reopen poisoned admission.
                if (owned === run && run.phase == Run.Phase.CLEARING) closeRoutes(run)
            }
        } catch (error: Throwable) { run.diagnostics.report(FailureStage.WEB_ROUTE_CLEAR, error); poison(run) }
    }
    private fun consumersConfirmed(feeds: List<Feed>) = feeds.all {
        !runCatching(it.knownFailed).getOrDefault(true) && !runCatching(it.pending).getOrDefault(true) &&
            runCatching(it.confirmed).getOrDefault(false)
    }
    private fun closeRoutes(run: Run) {
        val feeds = feeds(run)
        if (!consumersConfirmed(feeds)) { poison(run, FailureStage.FEED_CLEANUP_UNCONFIRMED); return }
        run.phase = Run.Phase.CLOSING
        val routes = synchronized(run.routes) { run.routes.toList() }
        try { closeWorker.execute {
            var accepted = true
            for (route in routes) {
                if (run.phase != Run.Phase.CLOSING || !consumersConfirmed(feeds)) { accepted = false; break }
                if (!run.diagnostics.cleanupAll(FailureStage.ROUTE_CLOSE) { route.close() }) accepted = false
            }
            post {
                if (owned === run && run.phase == Run.Phase.CLOSING) {
                    if (accepted && consumersConfirmed(feeds)) {
                        run.phase = Run.Phase.FINISHED
                        run.feeds.clear(); run.retiredFeeds.clear(); run.helpers.clear(); synchronized(run.routes) { run.routes.clear() }
                        run.cancelPreparation = null
                        owned = null
                        changed()
                    } else poison(run, FailureStage.ROUTE_CLOSE)
                }
            }
        } } catch (error: Throwable) { run.diagnostics.report(FailureStage.ROUTE_CLOSE, error); poison(run) }
    }
    private fun poison(run: Run, stage: FailureStage? = null) {
        if (owned !== run || run.phase in setOf(Run.Phase.FINISHED, Run.Phase.POISONED)) return
        run.failureStage = stage
        run.phase = Run.Phase.POISONED
        stage?.let { run.diagnostics.report(it) }
        changed()
    }
}
