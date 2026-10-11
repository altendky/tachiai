package net.fstab.tachiai.provider.abema

import java.util.concurrent.Executor
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AbemaGuestRuntimeCoordinatorTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    private val proof = "a".repeat(64)
    private val nonce = "1".repeat(32)
    private class Work : Executor {
        val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { pending.addLast(command) }
        fun one(): Boolean = pending.removeFirstOrNull()?.let { it.run(); true } ?: false
    }
    private class Main : AbemaGuestRuntimeMain {
        var now = 1_000L
        private class Task(val token: Any, val at: Long, val action: () -> Unit)
        private val pending = mutableListOf<Task>()
        override fun checkThread() = Unit
        override fun post(token: Any, action: () -> Unit) { pending += Task(token, now, action) }
        override fun delay(token: Any, millis: Long, action: () -> Unit) { pending += Task(token, now + millis, action) }
        override fun remove(token: Any) { pending.removeAll { it.token === token } }
        fun one(): Boolean {
            val task = pending.firstOrNull { it.at <= now } ?: return false
            pending.remove(task); task.action(); return true
        }
    }
    private inner class Fixture {
        val main = Main(); val worker = Work(); val cleanup = Work()
        val events = mutableListOf<String>()
        val results = mutableListOf<AbemaGuestRuntimeResult>()
        var foreground = true
        var matching = true
        var ownerProof = proof
        var destroy: () -> Boolean = { true }
        var holdStatus = false
        var holdClear = false
        var onClear: () -> Unit = {}
        var clearCallback: (() -> Unit)? = null
        var reads = 0
        var metadataTitle = "Exact episode"
        val views = mutableListOf<View>()
        inner class View(val request: AbemaGuestRuntimeRequest,
            val currentView: (AbemaGuestRuntimeView) -> Boolean,
            val failed: (AbemaGuestRuntimeView, String) -> Unit) : AbemaGuestRuntimeView {
            var statusCallback: ((String?) -> Unit)? = null
            override fun open() { events += "open" }
            override fun status(callback: (String?) -> Unit) {
                if (holdStatus) statusCallback = callback
                else callback(statusValue())
            }
            fun statusValue() = JSONObject.quote(JSONObject().put("state", "EXACT_EPISODE_METADATA").put("failure", "NONE").toString())
            override fun metadata(callback: (String?) -> Unit) {
                events += "projection"
                callback(JSONObject.quote(JSONObject().put("id", request.episodeId).put("title", metadataTitle).toString()))
            }
            override fun destroy(): Boolean { events += "destroy"; return this@Fixture.destroy.invoke() }
        }
        val environment = object : AbemaGuestRuntimeEnvironment {
            override fun readOwner(instanceId: String): AbemaGuestRuntimeOwner {
                assertEquals(id, instanceId); reads++; events += "read-owner"
                return object : AbemaGuestRuntimeOwner { override val proof = ownerProof }
            }
            override fun sameOwner(owner: AbemaGuestRuntimeOwner): Boolean {
                reads++; events += "check-owner"; return matching && owner.proof == ownerProof
            }
            override fun newPreparation() = object : AbemaGuestRuntimePreparation {
                override fun cancel() { events += "cancel" }
                override fun cancelTransport() { events += "cancel-download" }
                override val cleanupConfirmed = true
                override fun close() { events += "close-backend" }
            }
            override fun prepare(owner: AbemaGuestRuntimeOwner, request: AbemaGuestRuntimeRequest,
                preparation: AbemaGuestRuntimePreparation, canRequest: () -> Boolean): Map<String, ByteArray> {
                assertTrue(canRequest()); events += "prepare"; return emptyMap()
            }
            override fun install(preparation: AbemaGuestRuntimePreparation, callback: (Boolean) -> Unit) {
                events += "install"; callback(true)
            }
            override fun newView(request: AbemaGuestRuntimeRequest, preparation: AbemaGuestRuntimePreparation,
                files: Map<String, ByteArray>, canRequest: () -> Boolean,
                currentView: (AbemaGuestRuntimeView) -> Boolean,
                failed: (AbemaGuestRuntimeView, String) -> Unit): AbemaGuestRuntimeView {
                return View(request, currentView, failed).also { views += it }
            }
            override fun clear(callback: () -> Unit) {
                events += "clear-dispatched"; onClear()
                if (holdClear) clearCallback = callback else { events += "clear-confirmed"; callback() }
            }
            override fun report(error: Throwable) { events += "safe-failure" }
        }
        val runtime = AbemaGuestRuntimeCoordinator(environment, main, worker, cleanup) { main.now }
        fun request(selectedNonce: String = nonce) = AbemaGuestRuntimeRequest(id, AbemaGuestBootstrapOperation.FETCH_EPISODE,
            "fixture-episode", selectedNonce, proof, main.now + 45_000) { foreground }
        fun start(selectedNonce: String = nonce) = runtime.start(request(selectedNonce), completed = results::add)
        fun pump() {
            repeat(100) {
                val didWork = worker.one()
                val didCleanup = cleanup.one()
                val didMain = main.one()
                if (!didWork && !didCleanup && !didMain) return
            }
            error("Fixture did not settle")
        }
    }

    @Test fun exactProjectionPublishesOnlyAfterDestroyClearBackendCloseAndFinalOwnerRead() {
        val f = Fixture(); assertTrue(f.start()); f.pump()
        assertEquals("EXACT_EPISODE_METADATA", f.results.single().category)
        assertEquals("fixture-episode", f.results.single().entry?.resource?.identity)
        assertEquals("Exact episode", f.results.single().entry?.title)
        assertTrue(f.events.indexOf("destroy") < f.events.indexOf("clear-dispatched"))
        assertTrue(f.events.indexOf("clear-confirmed") < f.events.indexOf("close-backend"))
        assertTrue(f.events.lastIndexOf("check-owner") > f.events.indexOf("close-backend"))
        assertFalse(f.runtime.unavailable)
    }

    @Test fun capturedOwnerProofMismatchRefusesBeforeAnyPreparationOrProviderDispatch() {
        val f = Fixture(); f.ownerProof = "b".repeat(64)
        assertTrue(f.start()); f.pump()
        assertFalse(f.events.contains("prepare")); assertFalse(f.events.contains("install")); assertTrue(f.views.isEmpty())
        assertNull(f.results.single().entry); assertFalse(f.runtime.unavailable)
    }

    @Test fun nativeProjectionRejectsSupplementaryFormatCharactersButAcceptsVisibleSupplementaryText() {
        for (title in listOf("Hidden \uDB40\uDC01", "Hidden \uDB40\uDC20")) {
            val f = Fixture(); f.metadataTitle = title
            assertTrue(f.start()); f.pump()
            assertEquals("GUEST_EPISODE_RESPONSE", f.results.single().category)
            assertNull(f.results.single().entry); assertFalse(f.runtime.unavailable)
        }
        val f = Fixture(); f.metadataTitle = "Visible \uD83D\uDE00"
        assertTrue(f.start()); f.pump()
        assertEquals("EXACT_EPISODE_METADATA", f.results.single().category)
        assertEquals(f.metadataTitle, f.results.single().entry?.title)
    }

    @Test fun metadataCandidateExpiringDuringConfirmedCleanupCannotPublish() {
        val f = Fixture(); f.holdStatus = true; f.holdClear = true
        assertTrue(f.start()); f.pump()
        f.main.now += 44_500
        f.views.single().let { it.statusCallback?.invoke(it.statusValue()) }; f.pump()
        assertTrue(f.events.contains("destroy")); assertTrue(f.events.contains("clear-dispatched"))
        assertTrue(f.results.isEmpty())
        f.main.now += 501
        f.clearCallback?.invoke(); f.pump()
        assertEquals("CANCELLED", f.results.single().category)
        assertNull(f.results.single().entry); assertFalse(f.runtime.unavailable)
    }

    @Test fun finalOwnerChangeSuppressesPreviouslyValidMetadata() {
        val f = Fixture(); f.onClear = { f.matching = false }
        assertTrue(f.start()); f.pump()
        assertEquals("OWNER_CHANGED", f.results.single().category); assertNull(f.results.single().entry)
        assertTrue(f.events.contains("close-backend"))
    }

    @Test fun destructionRefusalExceptionAndErrorNeverClearOrCloseAndPoisonSharedAdmission() {
        for (failure in listOf<() -> Boolean>({ false }, { throw IllegalStateException("fixture") }, { throw AssertionError("fixture") })) {
            val f = Fixture(); f.destroy = failure
            assertTrue(f.start()); f.pump()
            assertEquals("CLEANUP_FAILED", f.results.single().category)
            assertFalse(f.events.contains("clear-dispatched")); assertFalse(f.events.contains("close-backend"))
            assertTrue(f.runtime.unavailable); assertFalse(f.start("2".repeat(32)))
        }
    }

    @Test fun uncertainClearRetainsBackendAndBlocksActivityOrServiceReplacement() {
        val f = Fixture(); f.holdClear = true
        assertTrue(f.start()); f.pump()
        assertTrue(f.runtime.unavailable); assertFalse(f.events.contains("close-backend"))
        assertFalse(f.start("2".repeat(32)))
        f.main.now += 20_001; f.pump()
        assertEquals("CLEANUP_FAILED", f.results.single().category)
        assertFalse(f.events.contains("close-backend")); assertFalse(f.start("2".repeat(32)))
        f.clearCallback?.invoke(); f.pump()
        assertEquals(1, f.results.size); assertTrue(f.runtime.unavailable)
    }

    @Test fun cancelDuringPreparationCannotCloseBackendBeforeConfirmedClear() {
        val f = Fixture(); assertTrue(f.start()); assertTrue(f.worker.one())
        f.runtime.cancel(nonce); f.pump()
        assertFalse(f.events.contains("open")); assertFalse(f.events.contains("install"))
        assertTrue(f.events.indexOf("clear-confirmed") < f.events.indexOf("close-backend"))
        assertEquals("CANCELLED", f.results.single().category)
    }

    @Test fun oldViewCallbackAndCompletionCannotCancelLaterAttempt() {
        val f = Fixture(); f.holdStatus = true; assertTrue(f.start()); f.pump()
        val old = f.views.single(); f.runtime.cancel(nonce); f.pump()
        assertTrue(f.start("2".repeat(32))); f.pump()
        val reads = f.reads
        old.failed(old, "NAVIGATION_REFUSED"); old.statusCallback?.invoke(old.statusValue()); f.pump()
        assertEquals(reads, f.reads); assertEquals(1, f.results.size)
        assertTrue(f.runtime.unavailable); assertFalse(old.currentView(old))
        f.runtime.cancel("2".repeat(32)); f.pump(); assertEquals(2, f.results.size)
    }

    @Test fun lostForegroundPulseRejectsLateProjectionWithoutMainOwnerReads() {
        val f = Fixture(); f.holdStatus = true; assertTrue(f.start()); f.pump()
        val view = f.views.single(); val before = f.reads
        f.foreground = false; f.main.now += 101
        assertTrue(f.main.one()); assertEquals(before, f.reads)
        view.statusCallback?.invoke(view.statusValue()); f.pump()
        assertEquals("CANCELLED", f.results.single().category); assertNull(f.results.single().entry)
    }
}
