package net.fstab.tachiai.feature.presentation

import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import net.fstab.tachiai.platform.diagnostics.FailureReporter
import net.fstab.tachiai.platform.diagnostics.FailureStage
import net.fstab.tachiai.provider.abema.CachedAbemaHelperCleanup
import net.fstab.tachiai.provider.abema.CachedCleanupWorker
import net.fstab.tachiai.provider.abema.AbemaPublicBundleHttp
import net.fstab.tachiai.provider.abema.AbemaBorrowedSlotProfile
import net.fstab.tachiai.platform.media.NativeCleanupReceipt
import org.junit.Assert.*
import org.junit.Test

class CachedViewerCleanupCoordinatorTest {
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        var refusal: Throwable? = null
        override fun execute(command: Runnable) { refusal?.let { throw it }; tasks.add(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private class Fixture {
        val events = mutableListOf<String>()
        val worker = Queue()
        val main = Queue()
        val timers = mutableListOf<Pair<Long, () -> Unit>>()
        val acknowledgments = mutableListOf<() -> Unit>()
        var clearFailure: Throwable? = null
        val owner = CachedViewerCleanupCoordinator(
            post = { main.execute(it) }, delay = { millis, task -> timers.add(millis to task) },
            closeWorker = worker, clearProxy = { _, complete ->
                events.add("clear")
                clearFailure?.let { throw it }
                acknowledgments.add(complete)
            }, changed = { events.add("changed") },
        )
        fun begin() = checkNotNull(owner.begin(FailureReporter.NONE))
        fun route(run: CachedViewerCleanupCoordinator.Run, name: String = "route", failure: Throwable? = null): AutoCloseable {
            val resource = AutoCloseable { events.add("close:$name"); failure?.let { throw it } }
            owner.route(run, resource)
            return resource
        }
        fun helper(run: CachedViewerCleanupCoordinator.Run, result: Boolean = true, failure: Throwable? = null): Any {
            val retained = Any()
            owner.helper(run, retained, { events.add("destroy"); failure?.let { throw it }; result })
            return retained
        }
        fun finish() { worker.drain(); main.drain() }
        fun timeout(millis: Long) { timers.filter { it.first == millis }.toList().forEach { it.second() } }
        fun assertPoisoned() {
            assertTrue(owner.failed); assertTrue(owner.unavailable)
            assertNull(owner.begin(FailureReporter.NONE))
        }
    }

    @Test fun destructionRefusalExceptionAndErrorRetainContextAndNeverClearOrClose() {
        for (failure in listOf(null, IOException("fixture"), AssertionError("fixture"))) {
            val f = Fixture(); val run = f.begin(); f.route(run)
            val retained = f.helper(run, result = false, failure = failure)
            f.owner.proxyWillChange(run)
            f.owner.end(run); f.finish(); f.timeout(20_000)
            f.assertPoisoned()
            assertSame(retained, run.helpers.single().retained)
            assertEquals(1, run.routes.size)
            assertFalse("clear after failed destruction", "clear" in f.events)
            assertFalse(f.events.any { it.startsWith("close:") })
        }
    }

    @Test fun successfulDuplicateFeedsDestroyBeforeAcknowledgmentAndCloseSharedRouteOnce() {
        val f = Fixture(); val run = f.begin(); val route = f.route(run)
        f.owner.route(run, route)
        repeat(2) { slot ->
            f.helper(run)
            f.owner.feed(run, slot, AutoCloseable { f.events.add("feed:$slot") }) { true }
        }
        f.owner.proxyWillChange(run)
        f.owner.end(run); f.owner.end(run)
        assertEquals(2, f.events.count { it == "destroy" })
        assertTrue(f.events.lastIndexOf("destroy") < f.events.indexOf("clear"))
        f.finish(); assertFalse("close:route" in f.events)
        f.acknowledgments.single()(); f.acknowledgments.single()()
        f.finish()
        assertEquals(1, f.events.count { it == "close:route" })
        assertFalse(f.owner.unavailable)
        assertTrue(run.helpers.isEmpty()); assertTrue(run.routes.isEmpty())
        assertNotNull(f.owner.begin(FailureReporter.NONE))
    }

    @Test fun lostOrLateClearAfterTimeoutNeverClosesRoutesOrReadmits() {
        val f = Fixture(); val run = f.begin(); f.route(run); f.helper(run)
        f.owner.proxyWillChange(run); f.owner.end(run)
        f.timeout(3_000); f.assertPoisoned()
        f.acknowledgments.single()(); f.finish()
        assertFalse("close:route" in f.events); f.assertPoisoned()
    }

    @Test fun clearExceptionAndErrorKeepBackendStronglyOwned() {
        for (failure in listOf(IOException("fixture"), AssertionError("fixture"))) {
            val f = Fixture(); f.clearFailure = failure
            val run = f.begin(); f.route(run); f.helper(run)
            f.owner.proxyWillChange(run); f.owner.end(run); f.finish()
            f.assertPoisoned(); assertEquals(1, run.routes.size)
            assertFalse("close:route" in f.events)
        }
    }

    @Test fun cancelledProducerRegistersLateRouteBeforeActivityDeliveryAndCleanupWaitsForCompletion() {
        val f = Fixture(); val run = f.begin(); var cancellation = 0
        f.owner.preparation(run) { cancellation++; true }
        f.owner.end(run)
        f.route(run) // Worker registration happens before an Activity callback.
        f.finish(); assertFalse("close:route" in f.events)
        assertEquals(1, cancellation); assertFalse(f.owner.current(run))
        f.owner.prepared(run, true); f.finish()
        assertEquals(1, f.events.count { it == "close:route" })
        assertFalse(f.owner.unavailable)
    }

    @Test fun producerTimeoutRetainsLateRoutesAndFailedCancellationNeverReopensAdmission() {
        for (confirmed in listOf(true, false)) {
            val f = Fixture(); val run = f.begin()
            f.owner.preparation(run) { confirmed }
            f.owner.end(run); f.timeout(20_000)
            f.route(run); f.owner.prepared(run, confirmed); f.finish()
            f.assertPoisoned(); assertEquals(1, run.routes.size)
            assertFalse("close:route" in f.events)
        }
    }

    @Test fun oldCallbacksCannotCloseReplacementRunOrOverwriteItsAdmission() {
        val f = Fixture(); val old = f.begin(); f.route(old, "old"); f.helper(old)
        f.owner.proxyWillChange(old); f.owner.end(old)
        val oldAck = f.acknowledgments.single(); oldAck(); f.finish()
        val replacement = f.begin(); f.route(replacement, "new")
        oldAck(); f.owner.end(old); f.owner.prepared(old, false)
        f.timeout(3_000); f.timeout(20_000); f.finish()
        assertTrue(f.owner.current(replacement))
        assertFalse("close:new" in f.events); assertFalse(f.owner.failed)
    }

    @Test fun reentrantFeedDisposalAndFailedFeedCloseAreOnceOnlyAndRetained() {
        val f = Fixture(); val run = f.begin(); f.route(run); f.helper(run)
        f.owner.proxyWillChange(run)
        f.owner.feed(run, 0, AutoCloseable { f.events.add("feed:0"); f.owner.end(run); throw AssertionError("fixture") }) { false }
        assertFalse(f.owner.closeFeed(run, 0)); f.owner.end(run); f.finish()
        assertEquals(1, f.events.count { it == "feed:0" })
        f.assertPoisoned(); assertEquals(1, run.feeds.size)
        assertFalse("close:route" in f.events)
    }

    @Test fun backendFailurePoisonsAcrossNewActivityAndNoOpRetryIsNotConfirmation() {
        val f = Fixture(); val run = f.begin(); f.route(run, failure = AssertionError("fixture")); f.helper(run)
        f.owner.proxyWillChange(run); f.owner.end(run); f.acknowledgments.single()(); f.finish()
        f.assertPoisoned()
        f.owner.end(run); f.finish()
        assertEquals(1, f.events.count { it == "close:route" })
        assertEquals(1, run.routes.size)
    }

    @Test fun twitchOnlyRunClosesWithoutTouchingChromiumProxy() {
        val f = Fixture(); val run = f.begin(); f.route(run)
        f.owner.feed(run, 0, AutoCloseable { f.events.add("twitch") }) { true }
        f.owner.end(run); f.finish()
        assertEquals(listOf("twitch", "close:route"), f.events.filter { it == "twitch" || it.startsWith("close:") })
        assertFalse("clear" in f.events); assertFalse(f.owner.unavailable)
    }

    @Test fun helperRegisteredBeforeConfigurationFailureRetainsDestructionReceiptSeparately() {
        for (failureStage in listOf(FailureStage.WEBVIEW_STOP, FailureStage.WEBVIEW_DESTROY)) {
            val f = Fixture(); val run = f.begin(); f.route(run)
            val helper = CachedAbemaHelperCleanup(Any(), listOf(
                FailureStage.WEBVIEW_STOP to { if (failureStage == FailureStage.WEBVIEW_STOP) throw AssertionError("fixture") },
                FailureStage.WEBVIEW_DESTROY to { f.events.add("destroy"); if (failureStage == FailureStage.WEBVIEW_DESTROY) throw IOException("fixture") },
            ), FailureReporter.NONE)
            f.owner.helper(run, helper, helper::destroy) { !helper.cleanupFailed }
            f.owner.proxyWillChange(run)
            // Browser configuration failed before a session was returned.
            f.owner.end(run); f.finish()
            assertEquals(failureStage != FailureStage.WEBVIEW_DESTROY, helper.destructionConfirmed)
            assertTrue(helper.cleanupFailed); f.assertPoisoned()
            assertFalse("clear" in f.events); assertFalse("close:route" in f.events)
        }
    }

    @Test fun failedAbemaSlotWithConfirmedCleanupKeepsHealthyTwitchAndSharedRouteAlive() {
        val f = Fixture(); val run = f.begin(); val shared = f.route(run)
        f.owner.route(run, shared); f.helper(run)
        f.owner.feed(run, 0, AutoCloseable { f.events.add("abema") }) { true }
        f.owner.feed(run, 1, AutoCloseable { f.events.add("twitch") }) { true }
        f.owner.proxyWillChange(run)
        assertTrue(f.owner.closeFeed(run, 0))
        assertTrue(f.owner.current(run)); assertFalse("twitch" in f.events)
        assertFalse("clear" in f.events); assertFalse("close:route" in f.events)
        f.owner.end(run); f.acknowledgments.single()(); f.finish()
        assertEquals(1, f.events.count { it == "abema" })
        assertEquals(1, f.events.count { it == "twitch" })
        assertEquals(1, f.events.count { it == "close:route" })
    }

    @Test fun nativeCleanupFailureRemainsUncertainEvenWhenHelperDestructionSucceeds() {
        val f = Fixture(); val run = f.begin(); f.route(run)
        val helper = CachedAbemaHelperCleanup(Any(), listOf(FailureStage.WEBVIEW_DESTROY to {}), FailureReporter.NONE)
        f.owner.helper(run, helper, helper::destroy)
        f.owner.feed(run, 0, AutoCloseable { throw IOException("Synthetic native close") }) { false }
        f.owner.proxyWillChange(run); f.owner.end(run); f.finish()
        assertTrue(helper.destructionConfirmed); f.assertPoisoned()
        assertFalse("clear" in f.events); assertFalse("close:route" in f.events)
    }

    @Test fun refusedOrTimedOutCleanupWorkerCannotLaterCloseRetainedRoute() {
        for (refusal in listOf(null, AssertionError("Synthetic dispatch failure"))) {
            val f = Fixture(); val run = f.begin(); f.route(run)
            f.worker.refusal = refusal
            f.owner.end(run); f.timeout(20_000); f.finish()
            f.assertPoisoned(); assertFalse("close:route" in f.events)
        }
    }

    @Test fun synchronousClearAndWorkerCallbacksFinishBeforeNextRunWithoutStaleTimeoutEffects() {
        val timers = mutableListOf<() -> Unit>(); val events = mutableListOf<String>()
        val owner = CachedViewerCleanupCoordinator(post = { it() }, delay = { _, task -> timers.add(task) },
            closeWorker = Executor { it.run() }, clearProxy = { _, complete -> events.add("clear"); complete(); complete() }, changed = {})
        val old = checkNotNull(owner.begin(FailureReporter.NONE))
        owner.route(old, AutoCloseable { events.add("close") })
        owner.helper(old, Any(), { events.add("destroy"); owner.end(old); true })
        owner.proxyWillChange(old); owner.end(old)
        assertEquals(listOf("destroy", "clear", "close"), events)
        val next = checkNotNull(owner.begin(FailureReporter.NONE))
        timers.forEach { it() }
        assertTrue(owner.current(next)); assertFalse(owner.failed)
    }

    @Test fun nativeSelfCloseFailureThenSessionDisposalKeepsActualCleanupReceiptUnconfirmed() {
        val f = Fixture(); val run = f.begin(); f.route(run); f.helper(run)
        val native = NativeCleanupReceipt(); var releases = 0
        native.own(30) { releases++; throw AssertionError("Synthetic player self-close") }
        assertThrows(AssertionError::class.java) { native.close() }
        f.owner.feed(run, 0, AutoCloseable { native.close() }) { native.confirmed }
        f.owner.proxyWillChange(run); f.owner.end(run); f.finish()
        f.assertPoisoned(); assertEquals(1, releases)
        assertFalse("clear" in f.events); assertFalse("close:route" in f.events)
    }

    @Test fun workerDisconnectFailureRacingMainDisposalSettlesBeforeRouteCleanupAdmission() {
        val f = Fixture(); val run = f.begin(); f.route(run); f.helper(run)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val settled = CountDownLatch(1)
        val failure = AtomicBoolean(); val finalized = AtomicBoolean()
        val receipt = NativeCleanupReceipt()
        val http = AbemaPublicBundleHttp(open = { url -> object : HttpsURLConnection(url) {
            override fun getResponseCode(): Int {
                entered.countDown()
                while (true) { try { check(release.await(5, TimeUnit.SECONDS)); break } catch (_: InterruptedException) {} }
                return 503
            }
            override fun disconnect() { throw IOException("Synthetic disconnect refusal") }
            override fun connect() = error("No network permitted")
            override fun usingProxy() = false
            override fun getCipherSuite() = "fixture"
            override fun getLocalCertificates(): Array<Certificate>? = null
            override fun getServerCertificates(): Array<Certificate> = emptyArray()
        } }, onFailure = { failure.set(true) }, onConnectionOwned = { connection -> receipt.own(10) { connection.disconnect() } })
        val executor = Executors.newSingleThreadExecutor()
        val worker = CachedCleanupWorker(executor) {
            try { receipt.close() } catch (_: Throwable) { failure.set(true) }
            finalized.set(true)
            f.main.execute { f.owner.feedCleanupChanged(run) }
            settled.countDown()
        }
        f.owner.feed(run, 0, AutoCloseable {
            try { http.close() } finally { worker.shutdownNow() }
        }, pending = { worker.pending || !finalized.get() }) { !failure.get() }
        f.owner.proxyWillChange(run)
        worker.execute { try { http.fetch() } catch (_: Throwable) { /* Fixed synthetic failure. */ } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            f.owner.end(run)
            assertFalse("clear" in f.events); assertFalse("close:route" in f.events)
            release.countDown(); assertTrue(settled.await(5, TimeUnit.SECONDS)); f.finish()
            f.assertPoisoned(); assertFalse("clear" in f.events); assertFalse("close:route" in f.events)
            assertFalse(receipt.confirmed)
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun lateFailedReceiptDuringClearOrBeforeWorkerCloseCannotReleaseBackend() {
        for (acknowledge in listOf(false, true)) {
            val f = Fixture(); val run = f.begin(); f.route(run); f.helper(run)
            val native = NativeCleanupReceipt { f.main.execute { f.owner.feedCleanupChanged(run) } }
            f.owner.feed(run, 0, AutoCloseable { native.close() }) { native.confirmed }
            f.owner.proxyWillChange(run); f.owner.end(run)
            assertTrue(native.confirmed)
            if (acknowledge) f.acknowledgments.single()()
            assertThrows(IOException::class.java) { native.own(20) {} }
            // Worker checking the dynamic receipt must be safe even before
            // the queued failure notification arrives.
            f.finish(); f.assertPoisoned()
            assertFalse(f.events.any { it.startsWith("close:") })
            assertEquals(1, run.feeds.size)
        }
    }

    @Test fun borrowedProfileMustSettleAndConfirmBeforeTheNativeRouteCanClose() {
        for (disconnectFails in listOf(false, true)) {
            val f = Fixture(); val run = f.begin(); val route = f.route(run); f.helper(run)
            val entered = CountDownLatch(1); val release = CountDownLatch(1); val settled = CountDownLatch(1)
            val operation = AbemaBorrowedSlotProfile({ url -> object : HttpsURLConnection(url) {
                override fun getResponseCode(): Int {
                    entered.countDown()
                    while (true) {
                        try { check(release.await(5, TimeUnit.SECONDS)); break }
                        catch (_: InterruptedException) { /* Wait for actual transport completion. */ }
                    }
                    return 200
                }
                override fun disconnect() { if (disconnectFails) throw IOException("Synthetic profile close refusal") }
                override fun connect() = error("No network permitted")
                override fun usingProxy() = false
                override fun getCipherSuite() = "fixture"
                override fun getLocalCertificates(): Array<Certificate>? = null
                override fun getServerCertificates(): Array<Certificate> = emptyArray()
            } }, { true }, { 1000 }, { 2000 }, 45000)
            val executor = Executors.newSingleThreadExecutor()
            val worker = CachedCleanupWorker(executor) {
                f.main.execute { f.owner.feedCleanupChanged(run) }; settled.countDown()
            }
            f.owner.feed(run, 0, AutoCloseable { operation.cancel(); worker.shutdownNow() },
                pending = { worker.pending }) { operation.cleanupConfirmed }
            f.owner.proxyWillChange(run)
            worker.execute { try { operation.read("fixture", "private-bearer") } catch (_: Throwable) { } }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                f.owner.end(run); f.finish()
                assertFalse("clear" in f.events); assertFalse("close:route" in f.events)
                release.countDown(); assertTrue(settled.await(5, TimeUnit.SECONDS)); f.finish()
                if (disconnectFails) {
                    f.assertPoisoned(); assertSame(route, run.routes.single())
                    assertFalse("clear" in f.events); assertFalse("close:route" in f.events)
                } else {
                    assertTrue(operation.cleanupConfirmed)
                    f.acknowledgments.single()(); f.finish()
                    assertEquals(1, f.events.count { it == "close:route" })
                    assertFalse(f.owner.unavailable)
                }
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }

    @Test fun failedReceiptBeforeQueuedSuccessPublicationRetainsOwnerAndBlocksReplacement() {
        val f = Fixture(); val run = f.begin(); val route = f.route(run); val helper = f.helper(run)
        val native = NativeCleanupReceipt { f.main.execute { f.owner.feedCleanupChanged(run) } }
        f.owner.feed(run, 0, AutoCloseable { native.close() }) { native.confirmed }
        f.owner.proxyWillChange(run); f.owner.end(run)
        f.acknowledgments.single()()
        f.worker.drain()
        assertEquals(1, f.events.count { it == "close:route" })
        assertEquals(CachedViewerCleanupCoordinator.Run.Phase.CLOSING, run.phase)
        assertTrue(f.owner.unavailable)
        native.failed()
        // The queued success callback precedes the failure notification, so
        // publication must inspect the actual receipt rather than its queue.
        f.main.drain()
        f.assertPoisoned()
        assertEquals(1, run.feeds.size)
        assertSame(route, run.routes.single()); assertSame(helper, run.helpers.single().retained)
        assertFalse(native.confirmed)
    }

    @Test fun asynchronousNativeConsumersKeepHelpersAndRoutesUntilBothPlayersRelease() {
        val f = Fixture(); val run = f.begin(); f.route(run); f.helper(run)
        val network = Queue()
        val receipts = (0..1).map { slot ->
            NativeCleanupReceipt().also { receipt ->
                receipt.own(10) { f.events.add("network:$slot") }
                receipt.own(30, ownerThread = true) { f.events.add("player:$slot") }
                f.owner.feed(run, slot, AutoCloseable {
                    receipt.closeAsync(network, f.main, { f.owner.feedCleanupChanged(run) }, { fail("Unexpected failure") })
                }, pending = { receipt.pending }) { receipt.confirmed }
            }
        }
        f.owner.proxyWillChange(run); f.owner.end(run); f.finish()
        assertTrue(receipts.all { it.pending }); assertFalse(f.owner.failed)
        assertFalse("destroy" in f.events); assertFalse("clear" in f.events)
        network.drain()
        assertFalse("destroy" in f.events); assertFalse(f.events.any { it.startsWith("player:") })
        f.main.tasks.removeFirst().run()
        assertFalse("destroy" in f.events); assertNull(f.owner.begin(FailureReporter.NONE))
        f.main.drain()
        assertTrue(receipts.all { it.confirmed }); assertEquals(1, f.events.count { it == "destroy" })
        assertFalse("close:route" in f.events)
        f.acknowledgments.single()(); f.finish()
        assertEquals(1, f.events.count { it == "close:route" }); assertFalse(f.owner.unavailable)
    }

    @Test fun asynchronousTransportFailureStillReleasesPlayerButRetainsRouteOwnership() {
        val f = Fixture(); val run = f.begin(); val route = f.route(run); f.helper(run)
        val network = Queue(); var failed = false; var released = false
        val receipt = NativeCleanupReceipt { failed = true }
        receipt.own(10) { throw IOException("Synthetic disconnect failure") }
        receipt.own(30, ownerThread = true) { released = true }
        f.owner.feed(run, 0, AutoCloseable {
            receipt.closeAsync(network, f.main, { f.owner.feedCleanupChanged(run) }, {})
        }, pending = { receipt.pending }, knownFailed = { failed }) { receipt.confirmed }
        f.owner.proxyWillChange(run); f.owner.end(run)
        network.drain(); f.main.drain(); f.finish()
        assertTrue(released); assertFalse(receipt.pending); assertFalse(receipt.confirmed)
        f.assertPoisoned(); assertSame(route, run.routes.single())
        assertFalse("clear" in f.events); assertFalse("close:route" in f.events)
    }

    @Test fun knownFailureWithPendingWorkerStopsGroupImmediatelyAndStartsCleanupDeadline() {
        val f = Fixture(); val run = f.begin(); f.route(run); f.helper(run)
        var siblingCloses = 0
        f.owner.feed(run, 0, AutoCloseable { /* Already closed session returns Unit. */ },
            pending = { true }, knownFailed = { true }) { false }
        f.owner.feed(run, 1, AutoCloseable { siblingCloses++ }) { true }
        assertFalse(f.owner.closeFeed(run, 0))
        assertEquals(CachedViewerCleanupCoordinator.Run.Phase.ENDING, run.phase)
        assertFalse(f.owner.current(run)); assertEquals(1, siblingCloses)
        assertTrue(f.timers.any { it.first == 20_000L })
        assertFalse("clear" in f.events); f.finish()
        assertFalse(f.events.any { it.startsWith("close:") })
        f.timeout(20_000); f.assertPoisoned(); assertEquals(2, run.feeds.size)
    }
    @Test fun failureArrivingDuringBenignPendingCleanupStopsPreviouslyHealthySiblingNow() {
        val f = Fixture(); val run = f.begin(); f.route(run); f.helper(run)
        var failed = false; var siblingCloses = 0
        f.owner.feed(run, 0, AutoCloseable {}, pending = { true }, knownFailed = { failed }) { false }
        f.owner.feed(run, 1, AutoCloseable { siblingCloses++ }) { true }
        assertTrue(f.owner.closeFeed(run, 0)); assertTrue(f.owner.current(run))
        assertEquals(0, siblingCloses); assertTrue(f.timers.isEmpty())
        failed = true; f.owner.feedCleanupChanged(run)
        assertEquals(CachedViewerCleanupCoordinator.Run.Phase.ENDING, run.phase)
        assertEquals(1, siblingCloses); assertTrue(f.timers.any { it.first == 20_000L })
        assertFalse("clear" in f.events); f.finish()
        assertFalse(f.events.any { it.startsWith("close:") })
        f.timeout(20_000); f.assertPoisoned()
    }

    @Test fun unattemptedConsumerFailureNotificationStopsGroupBeforeGenericFeedFailureCallback() {
        val f = Fixture(); val run = f.begin(); f.route(run); f.helper(run)
        var failed = false; var consumerCloses = 0; var siblingCloses = 0
        f.owner.feed(run, 0, AutoCloseable { consumerCloses++ }, pending = { true }, knownFailed = { failed }) { false }
        f.owner.feed(run, 1, AutoCloseable { siblingCloses++ }) { true }
        assertFalse(checkNotNull(run.feeds[0]).attempted)
        failed = true; f.owner.feedCleanupChanged(run)
        assertEquals(CachedViewerCleanupCoordinator.Run.Phase.ENDING, run.phase)
        assertEquals(1, consumerCloses); assertEquals(1, siblingCloses)
        assertTrue(f.timers.any { it.first == 20_000L }); assertFalse("clear" in f.events)
        f.finish(); assertFalse(f.events.any { it.startsWith("close:") })
        f.timeout(20_000); f.assertPoisoned()
    }

}
