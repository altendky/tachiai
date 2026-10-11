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
    private inner class Fixture(private val operation: AbemaGuestBootstrapOperation = AbemaGuestBootstrapOperation.FETCH_EPISODE) {
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
        var onClose: () -> Unit = {}
        var clearCallback: (() -> Unit)? = null
        var reads = 0
        var metadataTitle = "Exact episode"
        var statusState: String? = null
        var statusFailure = "NONE"
        var statusOverride: JSONObject? = null
        var linkOverride: String? = null
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
            fun statusValue(): String {
                val state = statusState ?: when (operation) {
                    AbemaGuestBootstrapOperation.VERIFY_DEVICE_AUTHORIZATION -> "CHALLENGE_VALIDATED_AND_DISCARDED"
                    AbemaGuestBootstrapOperation.OBSERVE_DEVICE_TOKEN -> AbemaDeviceTokenObservation.STATE
                    AbemaGuestBootstrapOperation.PREPARE_ACCOUNT_LINK -> "ACCOUNT_LINK_READY"
                    else -> "EXACT_EPISODE_METADATA"
                }
                val value = statusOverride ?: JSONObject().put("state", state).put("failure", statusFailure).apply {
                    if (state == AbemaDeviceTokenObservation.STATE) put("http", "HTTP_400").put("schema", "FLAT_CODE_MESSAGE_REASON")
                        .put("code", "AUTHORIZATION_PENDING").put("reason", "ERROR_REASON_UNSPECIFIED")
                }
                return JSONObject.quote(value.toString())
            }
            override fun metadata(callback: (String?) -> Unit) {
                events += "projection"
                callback(JSONObject.quote(JSONObject().put("id", request.episodeId).put("title", metadataTitle).toString()))
            }
            override fun accountLink(callback: (String?) -> Unit) {
                events += "private-projection"
                callback(JSONObject.quote(linkOverride ?: JSONObject().put("guestId", "guest").put("guestBearer", "PRIVATE_BEARER")
                    .put("clientId", "guest").put("deviceCode", "PRIVATE_CODE")
                    .put("tokenBody", "{\"client_id\":\"guest\",\"device_code\":\"PRIVATE_CODE\",\"grant_type\":\"urn:ietf:params:oauth:grant-type:device_code\",\"device_type\":3}")
                    .put("verificationUri", "https://abema.tv/account/transfer?code=PRIVATE_CODE").put("expiresIn", 300).toString()))
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
                override fun close() { events += "close-backend"; onClose() }
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
        fun request(selectedNonce: String = nonce) = AbemaGuestRuntimeRequest(id, operation,
            if (operation == AbemaGuestBootstrapOperation.FETCH_EPISODE) "fixture-episode" else null,
            selectedNonce, proof, main.now + 45_000) { foreground }
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

    @Test fun privateAccountChallengePublishesOnlyAfterConfirmedCleanupAndFinalOwnerCheck() {
        val f = Fixture(AbemaGuestBootstrapOperation.PREPARE_ACCOUNT_LINK); f.holdClear = true
        assertTrue(f.start()); f.pump(); assertTrue(f.results.isEmpty()); assertTrue(f.events.contains("destroy"))
        f.clearCallback?.invoke(); f.pump()
        assertEquals("ACCOUNT_LINK_READY", f.results.single().category); assertNotNull(f.results.single().accountChallenge)
        assertTrue(f.events.lastIndexOf("check-owner") > f.events.indexOf("close-backend")); assertNull(f.results.single().entry)
        assertFalse(f.results.single().toString().contains("PRIVATE"))
    }
    @Test fun privateChallengeNeverPublishesAfterOwnerLossCleanupFailureOrExpiry() {
        for (loss in 0..2) {
            val f = Fixture(AbemaGuestBootstrapOperation.PREPARE_ACCOUNT_LINK); f.holdClear = true
            assertTrue(f.start()); f.pump()
            when (loss) { 0 -> f.matching = false; 1 -> f.onClose = { error("PRIVATE CLOSE") }; 2 -> f.main.now += 300001 }
            f.clearCallback?.invoke(); f.pump(); assertNull(f.results.single().accountChallenge)
            if (loss == 1) assertTrue(f.runtime.unavailable)
        }
    }
    @Test fun privateChallengeRejectsDuplicateBindingOrWrongStatusOperation() {
        val malformed = Fixture(AbemaGuestBootstrapOperation.PREPARE_ACCOUNT_LINK)
        malformed.linkOverride = "{\"guestId\":\"guest\",\"guestId\":\"other\"}"
        assertTrue(malformed.start()); malformed.pump(); assertNull(malformed.results.single().accountChallenge)
        assertEquals("GUEST_CHALLENGE_RESPONSE", malformed.results.single().category)
        val wrong = Fixture(); wrong.statusState = "ACCOUNT_LINK_READY"
        assertTrue(wrong.start()); wrong.pump(); assertEquals("STATUS_REFUSED", wrong.results.single().category); assertNull(wrong.results.single().accountChallenge)
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

    @Test fun challengeFixedOutcomePublishesOnlyAfterFinalOwnerCheckAndConfirmedCleanupWithoutAnyNativeProjection() {
        val f = Fixture(AbemaGuestBootstrapOperation.VERIFY_DEVICE_AUTHORIZATION)
        assertTrue(f.start()); f.pump()
        assertEquals("CHALLENGE_VALIDATED_AND_DISCARDED", f.results.single().category)
        assertNull(f.results.single().entry); assertFalse(f.events.contains("projection"))
        assertTrue(f.events.indexOf("destroy") < f.events.indexOf("clear-dispatched"))
        assertTrue(f.events.indexOf("clear-confirmed") < f.events.indexOf("close-backend"))
        assertTrue(f.events.lastIndexOf("check-owner") > f.events.indexOf("close-backend"))
        assertFalse(f.runtime.unavailable)
        assertEquals("AbemaGuestRuntimeResult(status only)", f.results.single().toString())
    }

    @Test fun challengeOwnerOrForegroundLossSuppressesCandidateAndLateCallbacks() {
        val owner = Fixture(AbemaGuestBootstrapOperation.VERIFY_DEVICE_AUTHORIZATION)
        owner.onClear = { owner.matching = false }
        assertTrue(owner.start()); owner.pump()
        assertEquals("OWNER_CHANGED", owner.results.single().category); assertNull(owner.results.single().entry)

        val f = Fixture(AbemaGuestBootstrapOperation.VERIFY_DEVICE_AUTHORIZATION)
        f.holdStatus = true; assertTrue(f.start()); f.pump()
        val old = f.views.single(); val reads = f.reads
        f.foreground = false; f.main.now += 101; assertTrue(f.main.one()); assertEquals(reads, f.reads)
        old.statusCallback?.invoke(old.statusValue()); f.pump()
        assertEquals("CANCELLED", f.results.single().category); assertNull(f.results.single().entry)
        f.foreground = true; assertTrue(f.start("2".repeat(32))); f.pump()
        val after = f.reads
        old.statusCallback?.invoke(old.statusValue()); old.failed(old, "NAVIGATION_REFUSED"); f.pump()
        assertEquals(after, f.reads); assertEquals(1, f.results.size); assertTrue(f.runtime.unavailable)
        f.runtime.cancel("2".repeat(32)); f.pump(); assertEquals(2, f.results.size)
        assertTrue(f.results.all { it.category == "CANCELLED" && it.entry == null })
    }

    @Test fun challengeOriginalDeadlineAndClosedFailureCategoriesNeverPublishAConnectionOrPrivateResult() {
        val f = Fixture(AbemaGuestBootstrapOperation.VERIFY_DEVICE_AUTHORIZATION)
        f.holdStatus = true; f.holdClear = true; assertTrue(f.start()); f.pump()
        f.main.now += 44_500; f.views.single().let { it.statusCallback?.invoke(it.statusValue()) }; f.pump()
        assertTrue(f.results.isEmpty()); assertTrue(f.events.contains("destroy"))
        f.main.now += 501; f.clearCallback?.invoke(); f.pump()
        assertEquals("CANCELLED", f.results.single().category); assertNull(f.results.single().entry)

        for (failure in listOf("CHALLENGE_POLICY", "CHALLENGE_RESPONSE", "CHALLENGE_TIMEOUT", "CHALLENGE_TRANSPORT", "CHALLENGE_REDIRECT",
            "CHALLENGE_HTTP_401", "CHALLENGE_HTTP_403", "CHALLENGE_HTTP_404", "CHALLENGE_HTTP_410", "CHALLENGE_HTTP_429",
            "CHALLENGE_HTTP_3XX", "CHALLENGE_HTTP_4XX", "CHALLENGE_HTTP_5XX", "CHALLENGE_HTTP_OTHER")) {
            val refused = Fixture(AbemaGuestBootstrapOperation.VERIFY_DEVICE_AUTHORIZATION)
            refused.statusState = "REFUSED"; refused.statusFailure = failure
            assertTrue(refused.start()); refused.pump()
            assertEquals("GUEST_$failure", refused.results.single().category); assertNull(refused.results.single().entry)
            assertFalse(refused.events.contains("projection")); assertFalse(refused.runtime.unavailable)
        }
        val unexpected = Fixture(AbemaGuestBootstrapOperation.VERIFY_DEVICE_AUTHORIZATION)
        unexpected.statusState = "REFUSED"; unexpected.statusFailure = "CHALLENGE_PRIVATE_418"
        assertTrue(unexpected.start()); unexpected.pump(); assertEquals("STATUS_REFUSED", unexpected.results.single().category)
        for ((state, failure) in listOf("CHALLENGE" to "NONE", "CHALLENGE_VALIDATED_AND_DISCARDED" to "NONE",
            "REFUSED" to "CHALLENGE_HTTP_401")) {
            val old = Fixture(); old.statusState = state; old.statusFailure = failure
            assertTrue(old.start()); old.pump(); assertEquals("STATUS_REFUSED", old.results.single().category)
        }
    }

    @Test fun challengeDestructionAndCleanupFailurePoisonSharedAdmissionAcrossDiagnosticAndEpisodeRequests() {
        val cases = listOf<() -> Boolean>({ false }, { throw IllegalStateException("fixture") }, { throw AssertionError("fixture") })
        for (destroy in cases) {
            val f = Fixture(AbemaGuestBootstrapOperation.VERIFY_DEVICE_AUTHORIZATION); f.destroy = destroy
            assertTrue(f.start()); f.pump()
            assertEquals("CLEANUP_FAILED", f.results.single().category); assertNull(f.results.single().entry)
            assertFalse(f.events.contains("clear-dispatched")); assertFalse(f.events.contains("close-backend"))
            assertTrue(f.runtime.unavailable); assertFalse(f.start("2".repeat(32)))
            val episode = AbemaGuestRuntimeRequest(id, AbemaGuestBootstrapOperation.FETCH_EPISODE, "fixture-episode",
                "3".repeat(32), proof, f.main.now + 45_000) { true }
            assertFalse(f.runtime.start(episode, completed = f.results::add))
        }
        val failed = Fixture(AbemaGuestBootstrapOperation.VERIFY_DEVICE_AUTHORIZATION)
        failed.onClose = { throw IllegalStateException("fixture") }
        assertTrue(failed.start()); failed.pump()
        assertEquals("CLEANUP_FAILED", failed.results.single().category); assertNull(failed.results.single().entry)
        assertTrue(failed.runtime.unavailable); assertFalse(failed.start("2".repeat(32)))

        val uncertain = Fixture(AbemaGuestBootstrapOperation.VERIFY_DEVICE_AUTHORIZATION); uncertain.holdClear = true
        assertTrue(uncertain.start()); uncertain.pump(); uncertain.main.now += 20_001; uncertain.pump()
        assertEquals("CLEANUP_FAILED", uncertain.results.single().category); assertTrue(uncertain.runtime.unavailable)
        assertFalse(uncertain.events.contains("close-backend")); assertFalse(uncertain.start("2".repeat(32)))
        uncertain.clearCallback?.invoke(); uncertain.pump(); assertEquals(1, uncertain.results.size)
    }

    @Test fun tokenObservationIsOperationSpecificStatusOnlyAndPublishedAfterOwnerAndCompleteCleanup() {
        val f = Fixture(AbemaGuestBootstrapOperation.OBSERVE_DEVICE_TOKEN); f.holdClear = true
        assertTrue(f.start()); f.pump()
        assertTrue(f.results.isEmpty()); assertTrue(f.runtime.unavailable)
        assertTrue(f.events.indexOf("destroy") < f.events.indexOf("clear-dispatched"))
        assertFalse(f.events.contains("close-backend")); assertFalse(f.events.contains("projection"))
        f.clearCallback?.invoke(); f.pump()
        assertEquals("TOKEN_OBSERVATION_HTTP_400_FLAT_CODE_MESSAGE_REASON_CODE_AUTHORIZATION_PENDING_REASON_ERROR_REASON_UNSPECIFIED",
            f.results.single().category)
        assertNull(f.results.single().entry); assertFalse(f.events.contains("projection"))
        assertTrue(f.events.lastIndexOf("check-owner") > f.events.indexOf("close-backend"))
        assertFalse(f.runtime.unavailable)
    }

    @Test fun tokenOwnerForegroundAndOriginalDeadlineLossSuppressSafeCandidateAndEveryLateCallback() {
        val owner = Fixture(AbemaGuestBootstrapOperation.OBSERVE_DEVICE_TOKEN)
        owner.onClear = { owner.matching = false }; assertTrue(owner.start()); owner.pump()
        assertEquals("OWNER_CHANGED", owner.results.single().category); assertNull(owner.results.single().entry)
        val f = Fixture(AbemaGuestBootstrapOperation.OBSERVE_DEVICE_TOKEN); f.holdStatus = true
        assertTrue(f.start()); f.pump(); val view = f.views.single()
        f.foreground = false; f.main.now += 101; assertTrue(f.main.one())
        view.statusCallback?.invoke(view.statusValue()); f.pump()
        assertEquals("CANCELLED", f.results.single().category); assertNull(f.results.single().entry)
        f.foreground = true; assertTrue(f.start("2".repeat(32))); f.pump()
        val reads = f.reads
        view.statusCallback?.invoke(view.statusValue()); view.failed(view, "RESOURCE_REFUSED"); f.pump()
        assertEquals(reads, f.reads); assertEquals(1, f.results.size)
        f.runtime.cancel("2".repeat(32)); f.pump(); assertEquals("CANCELLED", f.results.last().category)

        val deadline = Fixture(AbemaGuestBootstrapOperation.OBSERVE_DEVICE_TOKEN)
        deadline.holdStatus = true; deadline.holdClear = true; assertTrue(deadline.start()); deadline.pump()
        deadline.main.now += 44_500; deadline.views.single().let { it.statusCallback?.invoke(it.statusValue()) }; deadline.pump()
        assertTrue(deadline.results.isEmpty()); deadline.main.now += 501; deadline.clearCallback?.invoke(); deadline.pump()
        assertEquals("CANCELLED", deadline.results.single().category)
    }

    @Test fun nativeTokenBoundaryRejectsExtraPrivateFieldsUnknownValuesAndImpossibleSuccessSchemaAcrossOperations() {
        fun observation() = JSONObject().put("state", AbemaDeviceTokenObservation.STATE).put("failure", "NONE")
            .put("http", "HTTP_400").put("schema", "FLAT_CODE_MESSAGE_REASON").put("code", "AUTHORIZATION_PENDING")
            .put("reason", "ERROR_REASON_UNSPECIFIED")
        for (invalid in listOf(observation().put("message", "PRIVATE_MESSAGE"), observation().put("code", "PRIVATE_CODE"),
            observation().put("http", "HTTP_418_PRIVATE"), observation().put("http", "HTTP_2XX_UNEXPECTED"),
            observation().put("reason", 0), observation().apply { remove("schema") })) {
            val f = Fixture(AbemaGuestBootstrapOperation.OBSERVE_DEVICE_TOKEN); f.statusOverride = invalid
            assertTrue(f.start()); f.pump(); assertEquals("STATUS_REFUSED", f.results.single().category)
            assertNull(f.results.single().entry); assertFalse(f.results.single().category.contains("PRIVATE"))
        }
        for (operation in listOf(AbemaGuestBootstrapOperation.GUEST_ONLY, AbemaGuestBootstrapOperation.VERIFY_PROFILE,
            AbemaGuestBootstrapOperation.VERIFY_DEVICE_AUTHORIZATION, AbemaGuestBootstrapOperation.FETCH_EPISODE)) {
            val f = Fixture(operation); f.statusOverride = observation()
            assertTrue(f.start()); f.pump(); assertEquals("STATUS_REFUSED", f.results.single().category)
        }
        val success = Fixture(AbemaGuestBootstrapOperation.OBSERVE_DEVICE_TOKEN)
        success.statusOverride = observation().put("http", "HTTP_2XX_UNEXPECTED").put("schema", "NOT_READ")
            .put("code", "NOT_OBSERVED").put("reason", "NOT_OBSERVED")
        assertTrue(success.start()); success.pump()
        assertEquals("TOKEN_OBSERVATION_HTTP_2XX_UNEXPECTED_NOT_READ_CODE_NOT_OBSERVED_REASON_NOT_OBSERVED", success.results.single().category)
        assertNull(success.results.single().entry); assertFalse(success.events.contains("projection"))
    }

    @Test fun tokenClosedFailuresNeverBecomeAChallengeOrAccountResultAndOtherOperationsRefuseThem() {
        for (failure in listOf("TOKEN_POLICY", "TOKEN_RESPONSE", "TOKEN_TIMEOUT", "TOKEN_TRANSPORT", "TOKEN_REDIRECT", "TOKEN_CHALLENGE_EXPIRED")) {
            val f = Fixture(AbemaGuestBootstrapOperation.OBSERVE_DEVICE_TOKEN)
            f.statusState = "REFUSED"; f.statusFailure = failure
            assertTrue(f.start()); f.pump(); assertEquals("GUEST_$failure", f.results.single().category)
            assertNull(f.results.single().entry); assertFalse(f.events.contains("projection"))
            val old = Fixture(AbemaGuestBootstrapOperation.VERIFY_DEVICE_AUTHORIZATION)
            old.statusState = "REFUSED"; old.statusFailure = failure
            assertTrue(old.start()); old.pump(); assertEquals("STATUS_REFUSED", old.results.single().category)
        }
        val unknown = Fixture(AbemaGuestBootstrapOperation.OBSERVE_DEVICE_TOKEN)
        unknown.statusState = "REFUSED"; unknown.statusFailure = "TOKEN_PRIVATE_ERROR"
        assertTrue(unknown.start()); unknown.pump(); assertEquals("STATUS_REFUSED", unknown.results.single().category)
    }

    @Test fun tokenCleanupFailureRetainsAndPoisonsAdmissionIncludingEpisodeAndChallengeRequests() {
        for (destroy in listOf<() -> Boolean>({ false }, { throw IllegalStateException("fixture") }, { throw AssertionError("fixture") })) {
            val f = Fixture(AbemaGuestBootstrapOperation.OBSERVE_DEVICE_TOKEN); f.destroy = destroy
            assertTrue(f.start()); f.pump(); assertEquals("CLEANUP_FAILED", f.results.single().category)
            assertNull(f.results.single().entry); assertFalse(f.events.contains("clear-dispatched")); assertFalse(f.events.contains("close-backend"))
            assertTrue(f.runtime.unavailable); assertFalse(f.start("2".repeat(32)))
            val episode = AbemaGuestRuntimeRequest(id, AbemaGuestBootstrapOperation.FETCH_EPISODE, "fixture-episode",
                "3".repeat(32), proof, f.main.now + 45_000) { true }
            assertFalse(f.runtime.start(episode, completed = f.results::add))
            val challenge = AbemaGuestRuntimeRequest(id, AbemaGuestBootstrapOperation.VERIFY_DEVICE_AUTHORIZATION,
                nonce = "4".repeat(32), deadlineElapsedMs = f.main.now + 45_000, canRemainActive = { true })
            assertFalse(f.runtime.start(challenge, completed = f.results::add))
        }
        val failed = Fixture(AbemaGuestBootstrapOperation.OBSERVE_DEVICE_TOKEN)
        failed.onClose = { throw IllegalStateException("fixture") }; assertTrue(failed.start()); failed.pump()
        assertEquals("CLEANUP_FAILED", failed.results.single().category); assertTrue(failed.runtime.unavailable)
        val uncertain = Fixture(AbemaGuestBootstrapOperation.OBSERVE_DEVICE_TOKEN); uncertain.holdClear = true
        assertTrue(uncertain.start()); uncertain.pump(); uncertain.main.now += 20_001; uncertain.pump()
        assertEquals("CLEANUP_FAILED", uncertain.results.single().category); assertFalse(uncertain.events.contains("close-backend"))
        uncertain.clearCallback?.invoke(); uncertain.pump(); assertEquals(1, uncertain.results.size)
    }
}
