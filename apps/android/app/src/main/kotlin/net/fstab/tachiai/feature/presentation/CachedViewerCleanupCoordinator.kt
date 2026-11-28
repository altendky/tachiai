package net.fstab.tachiai.feature.presentation

import java.util.concurrent.Executor
import net.fstab.tachiai.platform.diagnostics.FailureCategory
import net.fstab.tachiai.platform.diagnostics.FailureReporter
import net.fstab.tachiai.platform.diagnostics.FailureStage

// The first step that left a cached viewer run's cleanup unconfirmed (#783),
// as one closed cause for logs and the failure journal.
internal enum class ViewerCleanupCause(val category: FailureCategory) {
    CANCEL_PREPARATION(FailureCategory.VIEWER_CLEANUP_CANCEL_PREPARATION),
    PREPARATION_UNCONFIRMED(FailureCategory.VIEWER_CLEANUP_PREPARATION_UNCONFIRMED),
    FEED_CLOSE(FailureCategory.VIEWER_CLEANUP_FEED_CLOSE),
    FEED_KNOWN_FAILED(FailureCategory.VIEWER_CLEANUP_FEED_KNOWN_FAILED),
    FEED_UNCONFIRMED(FailureCategory.VIEWER_CLEANUP_FEED_UNCONFIRMED),
    HELPER_DESTROY(FailureCategory.VIEWER_CLEANUP_HELPER_DESTROY),
    HELPER_UNCONFIRMED(FailureCategory.VIEWER_CLEANUP_HELPER_UNCONFIRMED),
    ROUTE_RETIRE(FailureCategory.VIEWER_CLEANUP_ROUTE_RETIRE),
    ROUTE_CLOSE(FailureCategory.VIEWER_CLEANUP_ROUTE_CLOSE),
    PROXY_CLEAR(FailureCategory.VIEWER_CLEANUP_PROXY_CLEAR),
    TIMEOUT(FailureCategory.VIEWER_CLEANUP_TIMEOUT),
}

// One process owner, independent of Activity lifetime. All state transitions
// happen on main; workers may only register resources or close captured routes.
internal class CachedViewerCleanupCoordinator(
    private val post: (() -> Unit) -> Unit,
    private val delay: (Long, () -> Unit) -> Unit,
    private val closeWorker: Executor,
    private val clearProxy: (FailureReporter, () -> Unit) -> Unit,
    private val changed: () -> Unit,
    // Called once per run with its first unconfirmed cause (#783).
    private val unconfirmed: (ViewerCleanupCause) -> Unit = {},
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
        internal var firstCause: ViewerCleanupCause? = null
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
        if (!confirmed) poison(run, FailureStage.ROUTE_CANCEL_UNCONFIRMED, ViewerCleanupCause.PREPARATION_UNCONFIRMED)
        else advance(run)
    }
    // #271: a playback route switch is another producer within the active run.
    // The run cannot close its routes until that worker posts prepared().
    fun producing(run: Run): Boolean {
        if (!current(run) || !run.producerFinished) return false
        run.producerFinished = false
        return true
    }
    // A switched-away route closed during the run. Only a confirmed close leaves
    // run ownership; an uncertain one ends and poisons the run.
    fun retireRoute(run: Run, route: AutoCloseable, confirmed: Boolean) {
        if (owned !== run) return
        if (confirmed) synchronized(run.routes) { run.routes.removeAll { it === route } }
        else {
            failed(run, ViewerCleanupCause.ROUTE_RETIRE)
            if (run.phase == Run.Phase.ACTIVE) end(run) else poison(run, FailureStage.ROUTE_CLOSE)
        }
    }
    fun closeFeed(run: Run, slot: Int): Boolean {
        if (owned !== run || run.phase != Run.Phase.ACTIVE) return false
        val entry = run.feeds[slot] ?: return true
        val accepted = closeFeed(run, entry)
        // Keep an uncertain consumer strongly owned even after UI detachment.
        if (!accepted) {
            failed(run, closeCause(entry))
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
            val known = ownedFeeds.any { runCatching(it.knownFailed).getOrDefault(true) }
            if (known || completed.any { !runCatching(it.confirmed).getOrDefault(false) }) {
                failed(run, if (known) ViewerCleanupCause.FEED_KNOWN_FAILED else ViewerCleanupCause.FEED_UNCONFIRMED); end(run)
            }
        } else if (run.phase == Run.Phase.ENDING) finishFeeds(run)
        else if (run.phase in setOf(Run.Phase.CLEARING, Run.Phase.CLOSING) && !consumersConfirmed(feeds(run)))
            poison(run, FailureStage.FEED_CLEANUP_UNCONFIRMED, consumersCause(feeds(run)))
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
            poison(run, FailureStage.ROUTE_CLEAR_TIMEOUT, ViewerCleanupCause.TIMEOUT) }
        if (!runCatching { run.cancelPreparation?.invoke() ?: true }.getOrDefault(false))
            failed(run, ViewerCleanupCause.CANCEL_PREPARATION)
        finishFeeds(run)
    }
    private fun finishFeeds(run: Run) {
        // A synchronous feed event can ask for disposal inside that feed's
        // close(). Wait for its receipt before destroying helpers or clearing.
        if (owned !== run || run.phase != Run.Phase.ENDING || run.feedsClosed || run.feedClosesInProgress != 0) return
        feeds(run).forEach { entry ->
            val accepted = closeFeed(run, entry)
            if (!runCatching(entry.pending).getOrDefault(true) && !accepted) failed(run, closeCause(entry))
        }
        if (feeds(run).any { runCatching(it.pending).getOrDefault(true) }) return
        var destroyed = true
        run.helpers.forEach { helper ->
            if (!run.diagnostics.cleanupAll(FailureStage.WEBVIEW_DESTROY) {
                if (!helper.destroy()) destroyed = false
            }) destroyed = false
            if (!destroyed) failed(run, ViewerCleanupCause.HELPER_DESTROY)
            if (!runCatching(helper.confirmed).getOrDefault(false)) failed(run, ViewerCleanupCause.HELPER_UNCONFIRMED)
        }
        run.feedsClosed = true
        if (!destroyed || run.cleanupFailed) poison(run, FailureStage.FEED_CLEANUP_UNCONFIRMED)
        else advance(run)
    }
    private fun advance(run: Run) {
        if (owned !== run || run.phase != Run.Phase.ENDING || !run.producerFinished || !run.feedsClosed) return
        if (!consumersConfirmed(feeds(run))) { poison(run, FailureStage.FEED_CLEANUP_UNCONFIRMED, consumersCause(feeds(run))); return }
        if (!run.proxyTouched) { closeRoutes(run); return }
        run.phase = Run.Phase.CLEARING
        delay(3_000) { if (owned === run && run.phase == Run.Phase.CLEARING)
            poison(run, FailureStage.ROUTE_CLEAR_TIMEOUT, ViewerCleanupCause.TIMEOUT) }
        try {
            clearProxy(run.diagnostics) {
                // Late acknowledgment is not permission to reopen poisoned admission.
                if (owned === run && run.phase == Run.Phase.CLEARING) closeRoutes(run)
            }
        } catch (error: Throwable) {
            run.diagnostics.report(FailureStage.WEB_ROUTE_CLEAR, error); poison(run, cause = ViewerCleanupCause.PROXY_CLEAR)
        }
    }
    private fun consumersConfirmed(feeds: List<Feed>) = feeds.all {
        !runCatching(it.knownFailed).getOrDefault(true) && !runCatching(it.pending).getOrDefault(true) &&
            runCatching(it.confirmed).getOrDefault(false)
    }
    private fun closeRoutes(run: Run) {
        val feeds = feeds(run)
        if (!consumersConfirmed(feeds)) { poison(run, FailureStage.FEED_CLEANUP_UNCONFIRMED, consumersCause(feeds)); return }
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
                    } else poison(run, FailureStage.ROUTE_CLOSE,
                        if (accepted) consumersCause(feeds) else ViewerCleanupCause.ROUTE_CLOSE)
                }
            }
        } } catch (error: Throwable) {
            run.diagnostics.report(FailureStage.ROUTE_CLOSE, error); poison(run, cause = ViewerCleanupCause.ROUTE_CLOSE)
        }
    }
    // Remembers the run's first unconfirmed cause; the flag itself is unchanged.
    private fun failed(run: Run, cause: ViewerCleanupCause) {
        run.cleanupFailed = true
        if (run.firstCause == null) run.firstCause = cause
    }
    private fun closeCause(entry: Feed): ViewerCleanupCause = when {
        !entry.accepted -> ViewerCleanupCause.FEED_CLOSE
        runCatching(entry.knownFailed).getOrDefault(true) -> ViewerCleanupCause.FEED_KNOWN_FAILED
        else -> ViewerCleanupCause.FEED_UNCONFIRMED
    }
    private fun consumersCause(feeds: List<Feed>): ViewerCleanupCause =
        if (feeds.any { runCatching(it.knownFailed).getOrDefault(true) }) ViewerCleanupCause.FEED_KNOWN_FAILED
        else ViewerCleanupCause.FEED_UNCONFIRMED
    private fun poison(run: Run, stage: FailureStage? = null, cause: ViewerCleanupCause? = null) {
        if (owned !== run || run.phase in setOf(Run.Phase.FINISHED, Run.Phase.POISONED)) return
        run.failureStage = stage
        run.phase = Run.Phase.POISONED
        if (run.firstCause == null) run.firstCause = cause
        val first = run.firstCause
        // The first cause travels as the poison record's category (#783).
        stage?.let { if (first != null) run.diagnostics.reportCategory(it, first.category) else run.diagnostics.report(it) }
        first?.let { runCatching { unconfirmed(it) } }
        changed()
    }
}
