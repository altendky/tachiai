package net.fstab.tachiai.provider.abema

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.presentation.validProviderInstanceId
import net.fstab.tachiai.provider.catalog.*
import org.json.JSONObject
import org.json.JSONTokener

internal class AbemaGuestRuntimeRequest(
    val instanceId: String,
    val operation: AbemaGuestBootstrapOperation,
    val episodeId: String? = null,
    val nonce: String,
    val expectedOwnerProof: String? = null,
    val deadlineElapsedMs: Long,
    val canRemainActive: () -> Boolean,
) {
    init {
        require(validProviderInstanceId(instanceId))
        require(nonce.matches(Regex("[a-f0-9]{32}")))
        require(expectedOwnerProof == null || expectedOwnerProof.matches(Regex("[a-f0-9]{64}")))
        require(if (operation == AbemaGuestBootstrapOperation.FETCH_EPISODE)
            episodeId != null && AbemaGuestBootstrapPolicy.validEpisodeId(episodeId) && expectedOwnerProof != null else episodeId == null)
        require(deadlineElapsedMs >= 0)
    }
    val policyEpisodeId get() = episodeId ?: AbemaGuestBootstrapPolicy.EPISODE_ID
    override fun toString() = "AbemaGuestRuntimeRequest(redacted)"
}

internal class AbemaGuestRuntimeResult(val category: String, val entry: CatalogEntry? = null) {
    override fun toString() = "AbemaGuestRuntimeResult(status only)"
}

internal interface AbemaGuestRuntimeMain {
    fun checkThread()
    fun post(token: Any, action: () -> Unit)
    fun delay(token: Any, millis: Long, action: () -> Unit)
    fun remove(token: Any)
}
internal interface AbemaGuestRuntimeOwner { val proof: String }
internal interface AbemaGuestRuntimePreparation : AutoCloseable {
    fun cancel()
    fun cancelTransport()
    val cleanupConfirmed: Boolean
}
internal interface AbemaGuestRuntimeView {
    fun open()
    fun status(callback: (String?) -> Unit)
    fun metadata(callback: (String?) -> Unit)
    fun destroy(): Boolean
    val cleanupConfirmed: Boolean get() = true
}
internal interface AbemaGuestRuntimeEnvironment {
    fun readOwner(instanceId: String): AbemaGuestRuntimeOwner
    fun sameOwner(owner: AbemaGuestRuntimeOwner): Boolean
    fun newPreparation(): AbemaGuestRuntimePreparation
    fun prepare(owner: AbemaGuestRuntimeOwner, request: AbemaGuestRuntimeRequest,
        preparation: AbemaGuestRuntimePreparation, canRequest: () -> Boolean): Map<String, ByteArray>
    fun install(preparation: AbemaGuestRuntimePreparation, callback: (Boolean) -> Unit)
    fun newView(request: AbemaGuestRuntimeRequest, preparation: AbemaGuestRuntimePreparation,
        files: Map<String, ByteArray>, canRequest: () -> Boolean,
        currentView: (AbemaGuestRuntimeView) -> Boolean,
        failed: (AbemaGuestRuntimeView, String) -> Unit): AbemaGuestRuntimeView
    fun clear(callback: () -> Unit)
    fun report(error: Throwable)
}

// One coordinator owns the process-wide proxy, admission and poisoned context.
// Only its main queue mutates attempt/view state; owner/disk/backend work stays
// on workers. The environment seams are exercised by orchestration fixtures.
internal class AbemaGuestRuntimeCoordinator(
    private val environment: AbemaGuestRuntimeEnvironment,
    private val main: AbemaGuestRuntimeMain,
    private val worker: Executor,
    private val cleanup: Executor,
    private val clockMs: () -> Long,
) {
    private val admission = AbemaGuestBootstrapLifecycle(clockMs)
    private class Attempt(val run: Long, val request: AbemaGuestRuntimeRequest,
        val preparation: AbemaGuestRuntimePreparation, val progress: (String) -> Unit,
        val completed: (AbemaGuestRuntimeResult) -> Unit) {
        val prepared = CountDownLatch(1)
        val cancellationFinished = CountDownLatch(1)
        val cleanupFailed = AtomicBoolean()
        val retained = AtomicBoolean()
        @Volatile var owner: AbemaGuestRuntimeOwner? = null
        @Volatile var view: AbemaGuestRuntimeView? = null
        var entry: CatalogEntry? = null
    }
    @Volatile private var attempt: Attempt? = null
    private var retainedAttempt: Attempt? = null
    val unavailable get() = admission.unavailable

    private fun current(active: Attempt): Boolean = attempt === active &&
        admission.current(active.run, runCatching(active.request.canRemainActive).getOrDefault(false)) &&
        clockMs() < active.request.deadlineElapsedMs
    private fun runtimeCurrent(active: Attempt) = current(active) && admission.canRunRuntime(active.run, true)
    private fun sameOwner(active: Attempt): Boolean {
        if (!current(active)) return false
        val owner = active.owner ?: return false
        return (active.request.expectedOwnerProof == null || active.request.expectedOwnerProof == owner.proof) &&
            environment.sameOwner(owner) && current(active)
    }

    fun start(request: AbemaGuestRuntimeRequest, progress: (String) -> Unit = {},
        completed: (AbemaGuestRuntimeResult) -> Unit): Boolean {
        main.checkThread()
        val now = clockMs()
        if (request.deadlineElapsedMs <= now || request.deadlineElapsedMs - now > 45_000 ||
            !runCatching(request.canRemainActive).getOrDefault(false)) return false
        val run = admission.begin(true) ?: return false
        val preparation = try { environment.newPreparation() } catch (error: Exception) {
            environment.report(error)
            admission.beginCleanup(run)
            admission.destroyBeforeRouteClear(run, { true }, {})
            admission.finishCleanup(run, true, {}) { completed(AbemaGuestRuntimeResult("PREPARATION_REFUSED")) }
            return true
        }
        val active = Attempt(run, request, preparation, progress, completed)
        attempt = active
        progress("PREPARING")
        main.delay(active, request.deadlineElapsedMs - now) { stop(active, "TIMEOUT") }
        // A lost foreground/death pulse is bounded even when no JS callback is
        // pending. This does not claim instantaneous cross-process observation.
        fun pulse() {
            if (attempt !== active) return
            if (!current(active)) stop(active, "CANCELLED") else main.delay(active, 100, ::pulse)
        }
        main.delay(active, 100, ::pulse)
        worker.execute {
            try {
                active.owner = environment.readOwner(request.instanceId)
                check(sameOwner(active))
                val files = environment.prepare(checkNotNull(active.owner), request, active.preparation) { sameOwner(active) }
                check(sameOwner(active))
                main.post(active) {
                    if (current(active)) guarded(active) { environment.install(active.preparation) { applied ->
                        main.checkThread()
                        if (!applied || !current(active) || !admission.routeApplied(run, true)) stop(active, "ROUTE_REFUSED")
                        else verifyThen(active) {
                            val view = environment.newView(request, active.preparation, files, { sameOwner(active) },
                                { candidate -> active.view === candidate && runtimeCurrent(active) },
                                { candidate, category -> main.post(active) {
                                    if (active.view === candidate && runtimeCurrent(active)) stop(active, category)
                                } })
                            active.view = view
                            view.open(); poll(active)
                        }
                    } }
                }
            } catch (error: Exception) {
                environment.report(error)
                main.post(active) { stop(active, "PREPARATION_REFUSED") }
            } finally {
                // Cancellation alone never closes a backend. Cleanup owns
                // that boundary only after destruction and override clear.
                active.prepared.countDown()
            }
        }
        return true
    }

    private fun verifyThen(active: Attempt, next: () -> Unit) {
        worker.execute {
            val valid = runCatching { sameOwner(active) }.onFailure(environment::report).getOrDefault(false)
            main.post(active) { if (current(active)) { if (valid) guarded(active, next) else stop(active, "OWNER_CHANGED") } }
        }
    }

    private fun guarded(active: Attempt, action: () -> Unit) {
        try { action() } catch (error: Exception) {
            environment.report(error); stop(active, "RUNTIME_REFUSED")
        }
    }

    private fun json(raw: String?, maximum: Int): JSONObject? = runCatching {
        val outer = JSONTokener(raw?.takeIf { it.length <= maximum } ?: return null)
        val text = outer.nextValue() as? String ?: return null
        if (outer.nextClean() != '\u0000') return null
        val inner = JSONTokener(text)
        val value = inner.nextValue() as? JSONObject ?: return null
        value.takeIf { inner.nextClean() == '\u0000' }
    }.getOrNull()

    private fun poll(active: Attempt) {
        if (!runtimeCurrent(active)) return
        val view = active.view ?: return
        view.status { raw ->
            if (!runtimeCurrent(active) || active.view !== view) return@status
            val value = json(raw, 256)?.takeIf { it.length() == 2 }
            val state = value?.opt("state") as? String
            val failure = value?.opt("failure") as? String
            when {
                state == "PREPARED" && failure == "NONE" && active.request.operation == AbemaGuestBootstrapOperation.GUEST_ONLY ->
                    verifyThen(active) { stop(active, "PREPARED") }
                state == "GUEST_PROFILE_BOUND" && failure == "NONE" && active.request.operation == AbemaGuestBootstrapOperation.VERIFY_PROFILE ->
                    verifyThen(active) { stop(active, "GUEST_PROFILE_BOUND") }
                state == "CHALLENGE_VALIDATED_AND_DISCARDED" && failure == "NONE" && active.request.operation == AbemaGuestBootstrapOperation.VERIFY_DEVICE_AUTHORIZATION ->
                    verifyThen(active) { stop(active, "CHALLENGE_VALIDATED_AND_DISCARDED") }
                state == "EXACT_EPISODE_METADATA" && failure == "NONE" && active.request.operation in
                    setOf(AbemaGuestBootstrapOperation.VERIFY_EPISODE, AbemaGuestBootstrapOperation.FETCH_EPISODE) -> {
                    if (active.request.operation == AbemaGuestBootstrapOperation.FETCH_EPISODE) view.metadata { metadata ->
                        if (!runtimeCurrent(active) || active.view !== view) return@metadata
                        val entry = decodeMetadata(active.request, metadata)
                        if (entry == null) stop(active, "GUEST_EPISODE_RESPONSE")
                        else { active.entry = entry; verifyThen(active) { stop(active, "EXACT_EPISODE_METADATA") } }
                    } else verifyThen(active) { stop(active, "EXACT_EPISODE_METADATA") }
                }
                state == "REFUSED" && failure in FAILURES && (failure?.startsWith("CHALLENGE_") != true ||
                    active.request.operation == AbemaGuestBootstrapOperation.VERIFY_DEVICE_AUTHORIZATION) -> stop(active, "GUEST_$failure")
                raw == null || raw == "null" || ((state in setOf("WAITING", "MODULES", "GUEST", "PROFILE", "EPISODE") ||
                    state == "CHALLENGE" && active.request.operation == AbemaGuestBootstrapOperation.VERIFY_DEVICE_AUTHORIZATION) && failure == "NONE") ->
                    main.delay(active, 100) { poll(active) }
                else -> stop(active, "STATUS_REFUSED")
            }
        }
    }

    private fun decodeMetadata(request: AbemaGuestRuntimeRequest, raw: String?): CatalogEntry? = runCatching {
        val value = json(raw, 4096)?.takeIf { it.length() == 2 } ?: return null
        val id = value.opt("id") as? String ?: return null
        val title = value.opt("title") as? String ?: return null
        if (id != request.episodeId || !AbemaGuestBootstrapPolicy.validEpisodeId(id) || !safeEpisodeTitle(title)) return null
        CatalogEntry(CatalogResource(ProviderId("abema"), "episode", id, CatalogIntent.VIDEO), title)
    }.getOrNull()

    fun cancel(nonce: String) {
        main.checkThread()
        attempt?.takeIf { it.request.nonce == nonce }?.let { stop(it, "CANCELLED") }
    }
    private fun stop(active: Attempt, category: String) {
        main.checkThread()
        if (attempt !== active || !admission.beginCleanup(active.run)) return
        attempt = null
        main.remove(active)
        try { active.preparation.cancel() } catch (error: Throwable) {
            active.cleanupFailed.set(true); environment.report(error)
        }
        cleanup.execute {
            try { active.preparation.cancelTransport() } catch (error: Throwable) {
                active.cleanupFailed.set(true); environment.report(error)
            } finally { active.cancellationFinished.countDown() }
        }
        val dispatched = admission.destroyBeforeRouteClear(active.run, {
            val view = active.view
            val destroyed = view?.destroy() ?: true
            if (view?.cleanupConfirmed == false) active.cleanupFailed.set(true)
            if (destroyed) active.view = null else retain(active)
            destroyed
        }) {
            active.progress("ENDING")
            main.delay(active, 20_000) { finish(active, false, category) }
            try { environment.clear {
                cleanup.execute {
                    try {
                        if (!active.prepared.await(15, TimeUnit.SECONDS) ||
                            !active.cancellationFinished.await(5, TimeUnit.SECONDS)) active.cleanupFailed.set(true)
                    } catch (error: Exception) { active.cleanupFailed.set(true); environment.report(error) }
                    try { active.preparation.close() } catch (error: Throwable) {
                        active.cleanupFailed.set(true); environment.report(error)
                    }
                    val confirmed = !active.cleanupFailed.get() && active.preparation.cleanupConfirmed
                    // Protected owner read AFTER confirmed teardown, never on
                    // main. A changed owner suppresses the candidate metadata.
                    val ownerCurrent = if (confirmed) runCatching {
                        val owner = active.owner
                        owner != null && environment.sameOwner(owner) &&
                            (active.request.expectedOwnerProof == null || active.request.expectedOwnerProof == owner.proof)
                    }.onFailure(environment::report).getOrDefault(false) else false
                    main.post(active) { finish(active, confirmed, if (ownerCurrent) category else "OWNER_CHANGED") }
                }
            } } catch (error: Throwable) {
                environment.report(error); finish(active, false, category)
            }
        }
        if (!dispatched) {
            retain(active); main.remove(active)
            active.completed(AbemaGuestRuntimeResult("CLEANUP_FAILED"))
        }
    }
    private fun retain(active: Attempt) {
        active.entry = null
        active.retained.set(true); retainedAttempt = active
    }
    private fun finish(active: Attempt, confirmed: Boolean, category: String) {
        main.checkThread()
        admission.finishCleanup(active.run, confirmed, { main.remove(active) }) { safe ->
            if (!safe) retain(active)
            val admitted = safe && runCatching(active.request.canRemainActive).getOrDefault(false) &&
                clockMs() < active.request.deadlineElapsedMs
            val outcome = if (!safe) "CLEANUP_FAILED" else if (!admitted) "CANCELLED" else category
            val entry = active.entry.takeIf { outcome == "EXACT_EPISODE_METADATA" }
            active.entry = null
            active.completed(AbemaGuestRuntimeResult(outcome, entry))
        }
    }

    companion object {
        private val FAILURES = setOf("HTTP", "RESPONSE", "POLICY", "RUNTIME", "TIMEOUT", "PROFILE_HTTP", "PROFILE_RESPONSE",
            "PROFILE_TIMEOUT", "EPISODE_RESPONSE", "EPISODE_TIMEOUT", "EPISODE_TRANSPORT", "EPISODE_REDIRECT",
            "EPISODE_HTTP_401", "EPISODE_HTTP_403", "EPISODE_HTTP_404", "EPISODE_HTTP_410", "EPISODE_HTTP_429",
            "EPISODE_HTTP_3XX", "EPISODE_HTTP_4XX", "EPISODE_HTTP_5XX", "EPISODE_HTTP_OTHER",
            "CHALLENGE_POLICY", "CHALLENGE_RESPONSE", "CHALLENGE_TIMEOUT", "CHALLENGE_TRANSPORT", "CHALLENGE_REDIRECT",
            "CHALLENGE_HTTP_401", "CHALLENGE_HTTP_403", "CHALLENGE_HTTP_404", "CHALLENGE_HTTP_410", "CHALLENGE_HTTP_429",
            "CHALLENGE_HTTP_3XX", "CHALLENGE_HTTP_4XX", "CHALLENGE_HTTP_5XX", "CHALLENGE_HTTP_OTHER")
        fun safeEpisodeTitle(title: String): Boolean {
            if (title.length !in 1..160 || title != title.trim()) return false
            var at = 0
            while (at < title.length) {
                val character = title[at++]
                val codePoint = if (Character.isHighSurrogate(character)) {
                    if (at == title.length || !Character.isLowSurrogate(title[at])) return false
                    Character.toCodePoint(character, title[at++])
                } else {
                    if (Character.isLowSurrogate(character)) return false
                    character.code
                }
                if (Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT.toInt()) return false
            }
            return true
        }
    }
}
