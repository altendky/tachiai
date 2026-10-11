package net.fstab.tachiai.provider.abema

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.feature.connections.*
import net.fstab.tachiai.feature.diagnostic.AbemaGuestBootstrapActivity
import net.fstab.tachiai.platform.network.connectionProfileStore
import net.fstab.tachiai.platform.storage.PRIVATE_SECRET_LIMIT
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.catalog.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

// Test APK only. The opt-in gate precedes saved state, UI and all WebView APIs.
// One known unfiltered Series page; no guessed Season, Search, continuation,
// account capability, Add, playback, provider mutation or production DTO change.
class AbemaSeasonCandidateDeviceTest {
    @Test fun optInOneKnownSeriesPageObservesOnlyAnExplicitReturnedSeasonParent() {
        verifiedProbeInstrumentation("tachiaiReadOnlyAbemaSeasonCandidate")
        val phases = HarnessPhases()
        try { observe(phases) } catch (_: Throwable) { failHarness(phases) }
    }

    @Test fun optInOnlyAdoptsAndDestroysTheIdleGuestActivity() {
        val instrumentation = verifiedProbeInstrumentation("tachiaiReadOnlyAbemaSeasonCandidateHarness")
        val phases = HarnessPhases(); val active = AtomicBoolean()
        val foreground = IdleForeground(instrumentation, active)
        val started = SystemClock.elapsedRealtime()
        try {
            try {
                phases.at(HarnessPhase.ACTIVITY_LAUNCH) { foreground.launch(instrumentation.targetContext) }
                phases.at(HarnessPhase.ACTIVITY_ADOPTION) { foreground.adopt() }
                phases.at(HarnessPhase.FOREGROUND_CHECK) { check(active.get()) }
            } finally {
                phases.at(HarnessPhase.ACTIVITY_DESTRUCTION) { foreground.finishConfirmed() }
            }
            phases.at(HarnessPhase.RESULT_CHECK) {
                check(!active.get() && SystemClock.elapsedRealtime() - started in 0 until 12_000)
            }
            phases.current.set(HarnessPhase.COMPLETE)
            Log.i("TachiaiAbemaSeasonCandidateProbe", "outcome=HARNESS_READY phase=COMPLETE")
        } catch (_: Throwable) { failHarness(phases) }
        // The Activity performs its existing initialization reads. This method
        // performs no fixture store/record/route operations or runtime start.
    }

    private fun verifiedProbeInstrumentation(flag: String): Instrumentation {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString(flag) == "true")
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        assumeTrue(Application.getProcessName() == "${instrumentation.targetContext.packageName}:abema_guest_bootstrap")
        val avd = try {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("getprop ro.boot.qemu.avd_name")).use {
                val bytes = ByteArray(129); var size = 0
                while (size < bytes.size) { val count = it.read(bytes, size, bytes.size - size); if (count < 0) break; size += count }
                size <= 128 && String(bytes, 0, size, Charsets.UTF_8).trim() == "tachiai-dev"
            }
        } catch (_: Throwable) { false }
        assumeTrue("Requires verified tachiai-dev guest process", avd)
        return instrumentation
    }

    private fun failHarness(phases: HarnessPhases): Nothing {
        val phase = phases.failure.get() ?: phases.current.get()
        Log.i("TachiaiAbemaSeasonCandidateProbe", "outcome=HARNESS_FAILED phase=${phase.name}")
        throw AssertionError("Public Season candidate observation failed closed checks at ${phase.name}")
    }

    @Test fun syntheticProofIsOptionalStrictAndStrippedBeforeTheUnchangedSeriesDecoder() {
        fun shape(seriesExact: Boolean = true, seasonValid: Boolean = true, parent: String = "MATCH") = JSONObject()
            .put("seriesExact", seriesExact).put("seasonValid", seasonValid).put("seasonParent", parent)
        fun projection(proof: Any? = null, absent: Any? = null, diagnostic: Any = shape()) = JSONObject().put("seriesId", SERIES).put("version", "fixture-version")
            .put("programs", JSONArray().put(JSONObject().put("id", "fixture-program").put("title", "Fixture episode")
                .put(SHAPE_FIELD, diagnostic).apply { if (proof != null) put(PROOF_FIELD, proof); if (absent != null) put(ABSENT_PROOF_FIELD, absent) }))
        fun proof() = JSONObject().put("seriesId", SERIES).put("seasonId", "fixture-season")
            .put("seasonSeriesId", SERIES).put("programId", "fixture-program")
        val valid = stripProjection(JSONObject.quote(projection(proof()).toString()))!!
        assertEquals(PublicSeasonProof(SERIES, "fixture-season", "fixture-program"), valid.proof)
        assertNull(valid.absentProof)
        val text = JSONTokener(valid.raw).nextValue() as String
        assertFalse(text.contains(PROOF_FIELD))
        assertFalse(text.contains(SHAPE_FIELD))
        assertFalse(text.contains(ABSENT_PROOF_FIELD))
        assertEquals(ShapeCounts(1, 1, 1, 0, 1, 0, 0, 1, 0), valid.shapes)
        assertNotNull(decodeAbemaSeriesProjection(text, AbemaSeriesPageRequest(SERIES)))
        for (invalid in listOf(proof().put("seriesId", "other-series"), proof().put("seasonSeriesId", "other-series"),
            proof().put("programId", "other-program"), proof().put("seasonId", 12), proof().put("seasonId", "bad/id"),
            proof().put("extra", true), "fixture-season", JSONObject.NULL)) {
            val stripped = stripProjection(JSONObject.quote(projection(invalid).toString()))!!
            assertNull(stripped.proof)
            assertNotNull(decodeAbemaSeriesProjection(JSONTokener(stripped.raw).nextValue() as String, AbemaSeriesPageRequest(SERIES)))
        }
        assertNull(stripProjection(JSONObject.quote(projection().toString()))!!.proof)
        assertNull(stripProjection(JSONObject.quote(projection(proof()).put("seriesId", "other-series").toString())))
        assertNull(stripProjection("{}"))
        val single = projection(proof()).toString()
        for (duplicate in listOf(single.replaceFirst("\"seriesId\":", "\"seriesId\":\"other-series\",\"seriesId\":"),
            single.replaceFirst("\"seriesId\":", "\"series\\u0049d\":\"other-series\",\"seriesId\":"))) {
            assertNull(stripProjection(JSONObject.quote(duplicate)))
        }
        for ((diagnostic, expected) in listOf(
            shape(parent = "ABSENT") to ShapeCounts(1, 1, 1, 1, 0, 0, 1, 0, 0),
            shape(parent = "INVALID") to ShapeCounts(1, 1, 1, 0, 0, 1, 0, 0, 1),
            shape(seasonValid = false, parent = "ABSENT") to ShapeCounts(1, 1, 0, 1, 0, 0, 0, 0, 0),
            shape(seriesExact = false, seasonValid = false, parent = "INVALID") to ShapeCounts(1, 0, 0, 0, 0, 1, 0, 0, 0))) {
            val source = projection().apply { getJSONArray("programs").getJSONObject(0).put(SHAPE_FIELD, diagnostic) }
            val stripped = stripProjection(JSONObject.quote(source.toString()))!!
            assertEquals(expected, stripped.shapes)
            assertNull(stripped.proof)
            assertNotNull(decodeAbemaSeriesProjection(JSONTokener(stripped.raw).nextValue() as String, AbemaSeriesPageRequest(SERIES)))
        }
        for (invalid in listOf(shape().put("seriesExact", "true"), shape().put("seasonValid", 1),
            shape(parent = "CONFLICTING"), shape().put("extra", true), "MATCH", JSONObject.NULL)) {
            val source = projection().apply { getJSONArray("programs").getJSONObject(0).put(SHAPE_FIELD, invalid) }
            assertNull(stripProjection(JSONObject.quote(source.toString())))
        }
        val missingShape = projection().apply { getJSONArray("programs").getJSONObject(0).remove(SHAPE_FIELD) }
        assertNull(stripProjection(JSONObject.quote(missingShape.toString())))
        val mixed = projection(diagnostic = shape(parent = "ABSENT")).apply {
            getJSONArray("programs").put(JSONObject().put("id", "other-program").put("title", "Other fixture episode")
                .put(SHAPE_FIELD, shape()))
        }
        val mixedResult = stripProjection(JSONObject.quote(mixed.toString()))!!
        assertEquals(ShapeCounts(2, 2, 2, 1, 1, 0, 1, 1, 0), mixedResult.shapes)
        assertNotNull(decodeAbemaSeriesProjection(JSONTokener(mixedResult.raw).nextValue() as String, AbemaSeriesPageRequest(SERIES)))
        val bounded = projection().apply {
            val rows = getJSONArray("programs")
            for (index in 1 until 20) rows.put(JSONObject().put("id", "fixture-program-$index").put("title", "Fixture episode")
                .put(SHAPE_FIELD, shape()))
        }
        assertEquals(ShapeCounts(20, 20, 20, 0, 20, 0, 0, 20, 0), stripProjection(JSONObject.quote(bounded.toString()))!!.shapes)
        bounded.getJSONArray("programs").put(JSONObject().put("id", "over-bound").put("title", "Fixture episode").put(SHAPE_FIELD, shape()))
        assertNull(stripProjection(JSONObject.quote(bounded.toString())))
        assertNull(stripProjection(JSONObject.quote(single.replaceFirst("\"seasonParent\":", "\"seasonParent\":\"ABSENT\",\"seasonParent\":"))))
        fun absentProof() = JSONObject().put("seriesId", SERIES).put("seasonId", "fixture-season").put("programId", "fixture-program")
        val absent = stripProjection(JSONObject.quote(projection(absent = absentProof(), diagnostic = shape(parent = "ABSENT")).toString()))!!
        assertNull(absent.proof)
        assertEquals(PublicSeasonProof(SERIES, "fixture-season", "fixture-program"), absent.absentProof)
        assertFalse((JSONTokener(absent.raw).nextValue() as String).contains(ABSENT_PROOF_FIELD))
        assertNotNull(decodeAbemaSeriesProjection(JSONTokener(absent.raw).nextValue() as String, AbemaSeriesPageRequest(SERIES)))
        for (invalid in listOf(absentProof().put("seriesId", "other-series"), absentProof().put("seasonId", "bad/id"),
            absentProof().put("programId", "other-program"), absentProof().put("seasonId", 12), absentProof().put("extra", true),
            absentProof().put("seasonSeriesId", JSONObject.NULL), absentProof().put("seasonSeriesId", ""),
            absentProof().put("seasonSeriesId", "other-series"), JSONObject.NULL, "fixture-season")) {
            assertNull(stripProjection(JSONObject.quote(projection(absent = invalid, diagnostic = shape(parent = "ABSENT")).toString())))
        }
        for (invalidShape in listOf(shape(parent = "MATCH"), shape(parent = "INVALID"), shape(seasonValid = false, parent = "ABSENT"),
            shape(seriesExact = false, parent = "ABSENT"))) {
            assertNull(stripProjection(JSONObject.quote(projection(absent = absentProof(), diagnostic = invalidShape).toString())))
        }
        assertNull(stripProjection(JSONObject.quote(projection(proof(), absentProof(), shape(parent = "ABSENT")).toString())))
    }

    private fun observe(phases: HarnessPhases) {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val instanceId = defaultProviderInstanceId(PrototypeService.ABEMA)
        fun readOwner() = readAbemaCatalogRouteOwner(instanceId,
            { providerInstanceStore(context).read { legacyProviderSettings(context) } },
            { connectionProfileStore(context).selected(it) })
        val before = phases.at(HarnessPhase.PROTECTED_CAPTURE) { encryptedRecords(context.noBackupFilesDir) }
        val owner = phases.at(HarnessPhase.OWNER_CHECK) {
            readOwner().also { check(it.profile != null && it.instance.setup.route?.mode == SourceRouteMode.SAVED_CONNECTION) }
        }
        phases.at(HarnessPhase.PROFILE_SETUP) { instrumentation.runOnMainSync { configureAbemaGuestBootstrapProfile(context) } }
        val active = AtomicBoolean(); val foreground = IdleForeground(instrumentation, active)
        val environment = CandidateEnvironment(AndroidAbemaGuestEnvironment(context))
        val probe = Probe(environment)
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val result = AtomicReference<AbemaGuestRuntimeResult?>(); val completed = CountDownLatch(1)
        var admitted = false; val started = SystemClock.elapsedRealtime()
        try {
            phases.at(HarnessPhase.ACTIVITY_LAUNCH) { foreground.launch(context) }
            val activity = phases.at(HarnessPhase.ACTIVITY_ADOPTION) { foreground.adopt() }
            phases.at(HarnessPhase.FOREGROUND_CHECK) {
                var idle = false
                instrumentation.runOnMainSync { idle = !AbemaGuestRuntimeProcess.get(activity).unavailable }
                check(idle && active.get() && owner.sameOwnership(readOwner()))
            }
            phases.at(HarnessPhase.RUNTIME_ADMISSION) {
                check(retained.compareAndSet(null, probe))
                val request = AbemaGuestRuntimeRequest(instanceId, AbemaGuestBootstrapOperation.FETCH_SERIES,
                    nonce = nonce, expectedOwnerProof = owner.proof(), deadlineElapsedMs = SystemClock.elapsedRealtime() + 45_000,
                    seriesRequest = AbemaSeriesPageRequest(SERIES), canRemainActive = active::get)
                instrumentation.runOnMainSync {
                    admitted = probe.runtime.start(request) { value -> result.set(value); completed.countDown() }
                }
                check(admitted)
            }
            phases.at(HarnessPhase.RUNTIME_WAIT) { check(completed.await(65, TimeUnit.SECONDS)) }
        } finally {
            active.set(false)
            try {
                phases.at(HarnessPhase.RUNTIME_CLEANUP) {
                    instrumentation.runOnMainSync { probe.runtime.cancel(nonce) }
                    if (admitted && completed.count != 0L) completed.await(5, TimeUnit.SECONDS)
                }
            } finally {
                try { phases.at(HarnessPhase.ACTIVITY_DESTRUCTION) { foreground.finishConfirmed() } } finally {
                    // A failed/held teardown keeps the actual coordinator, route,
                    // view and cleanup executors strongly owned for this process.
                    if (!probe.runtime.unavailable) {
                        probe.worker.shutdown(); probe.cleanup.shutdown()
                        retained.compareAndSet(probe, null)
                    }
                }
            }
        }
        val after = phases.at(HarnessPhase.PROTECTED_POSTCHECK) { encryptedRecords(context.noBackupFilesDir) }
        val unchanged = before.keys == after.keys && before.all { (name, digest) -> after[name]?.let { MessageDigest.isEqual(digest, it) } == true }
        val current = phases.at(HarnessPhase.OWNER_POSTCHECK) { owner.sameOwnership(readOwner()) }
        val value = result.get(); val page = value?.seriesPage
        val cleanupConfirmed = admitted && completed.count == 0L && !probe.runtime.unavailable
        val success = value?.category == "EXACT_SERIES_PAGE" && page != null && page.seriesId == SERIES && page.seasonId == null &&
            page.entries.size <= 20 && page.entries.map { it.resource }.distinct().size == page.entries.size && page.entries.all {
                it.resource.providerId == ProviderId("abema") && it.resource.kind == "episode" && it.resource.intent == CatalogIntent.VIDEO &&
                    validAbemaSeriesId(it.resource.identity) && safeAbemaSeriesText(it.title, 160) &&
                    it.availability == CatalogAvailability.UNKNOWN && it.scheduledStartEpochMs == null
            }
        val candidate = environment.candidate.getAndSet(null).takeIf {
            cleanupConfirmed && unchanged && current && success && page.entries.any { entry -> entry.resource.identity == it?.programId }
        }
        val absentCandidate = environment.absentCandidate.getAndSet(null).takeIf {
            cleanupConfirmed && unchanged && current && success && page.entries.any { entry -> entry.resource.identity == it?.programId }
        }
        val shapes = environment.shapes.getAndSet(null).takeIf {
            cleanupConfirmed && unchanged && current && success && it?.rows == page.entries.size
        }
        val outcome = when {
            !cleanupConfirmed -> "CLEANUP_FAILED"; !unchanged -> "STORES_CHANGED"; !current -> "OWNER_CHANGED"
            success -> "EXACT_SERIES_PAGE"; else -> "INVALID_RESPONSE"
        }
        Log.i("TachiaiAbemaSeasonCandidateProbe", "outcome=$outcome count=${page?.entries?.size ?: 0} " +
            "candidateSeries=${candidate?.seriesId ?: "NONE"} candidateSeason=${candidate?.seasonId ?: "NONE"} " +
            "candidateEvidence=${if (candidate != null) PublicSeasonEvidence.EXPLICIT_PARENT_FIELD.name else PublicSeasonEvidence.NONE.name} " +
            "absentCandidateSeries=${absentCandidate?.seriesId ?: "NONE"} absentCandidateSeason=${absentCandidate?.seasonId ?: "NONE"} " +
            "absentCandidateEvidence=${if (absentCandidate != null) PublicSeasonEvidence.SAME_PROGRAM_ABSENT_PARENT_FIELD.name else PublicSeasonEvidence.NONE.name} " +
            "sdk=${Build.VERSION.SDK_INT} routeMode=${owner.instance.setup.route!!.mode.name}" +
            (shapes?.let { " shapeRows=${it.rows} seriesExact=${it.seriesExact} seasonValid=${it.seasonValid} " +
                "seasonParentAbsent=${it.parentAbsent} seasonParentMatch=${it.parentMatch} seasonParentInvalid=${it.parentInvalid} " +
                "validSeasonParentAbsent=${it.validParentAbsent} validSeasonParentMatch=${it.validParentMatch} validSeasonParentInvalid=${it.validParentInvalid}" }
                ?: " shape=NONE"))
        phases.at(HarnessPhase.RESULT_CHECK) {
            assertTrue("Public candidate observation exceeded bounded budgets", SystemClock.elapsedRealtime() - started in 0 until 70_000)
            assertTrue("Public candidate observation changed protected records", unchanged)
            assertTrue("Public candidate observation changed captured owner", current)
            assertTrue("Public candidate observation did not confirm cleanup", cleanupConfirmed)
            assertTrue("Public candidate observation did not return exact normalized metadata", success)
            assertNotNull("Public candidate observation did not return closed shape counts", shapes)
        }
        phases.current.set(HarnessPhase.COMPLETE)
        // A valid Series page without a valid Season proof is honest absence,
        // never acceptance of a fabricated parent or an automatic retry.
    }

    private enum class HarnessPhase {
        PROTECTED_CAPTURE, OWNER_CHECK, PROFILE_SETUP, ACTIVITY_LAUNCH, ACTIVITY_ADOPTION, FOREGROUND_CHECK,
        RUNTIME_ADMISSION, RUNTIME_WAIT, RUNTIME_CLEANUP, ACTIVITY_DESTRUCTION, PROTECTED_POSTCHECK,
        OWNER_POSTCHECK, RESULT_CHECK, COMPLETE,
    }

    private class HarnessPhases {
        val current = AtomicReference(HarnessPhase.PROTECTED_CAPTURE)
        val failure = AtomicReference<HarnessPhase?>()
        fun <T> at(phase: HarnessPhase, action: () -> T): T {
            current.set(phase)
            try { return action() } catch (error: Throwable) { failure.compareAndSet(null, phase); throw error }
        }
    }

    // The diagnostic Activity deliberately clears its Intent in onCreate, so
    // ActivityScenario cannot identify it by the original launch Intent.
    // Observe only its exact class and finish only instances seen after launch.
    private class IdleForeground(private val instrumentation: Instrumentation, private val active: AtomicBoolean) {
        private val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
        private val owned = linkedSetOf<AbemaGuestBootstrapActivity>()
        private var registered = false
        private var launched = false
        private val callback = ActivityLifecycleCallback { activity, stage ->
            if (activity.javaClass == AbemaGuestBootstrapActivity::class.java) {
                val exact = activity as AbemaGuestBootstrapActivity
                owned.add(exact)
                if (stage in setOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED))
                    exact.window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                if (owned.size != 1 || stage in setOf(Stage.PAUSED, Stage.STOPPED, Stage.DESTROYED)) active.set(false)
            }
        }
        private fun alive() = Stage.values().filter { it != Stage.DESTROYED }.flatMap { lifecycle.getActivitiesInStage(it) }
            .filter { it.javaClass == AbemaGuestBootstrapActivity::class.java }.distinct()
        private fun onMain(action: () -> Unit) {
            var succeeded = false
            instrumentation.runOnMainSync { try { action(); succeeded = true } catch (_: Throwable) { } }
            check(succeeded)
        }

        fun launch(context: Context) = onMain {
            check(alive().isEmpty())
            lifecycle.addLifecycleCallback(callback); registered = true
            launched = true
            context.startActivity(Intent(context, AbemaGuestBootstrapActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        }

        fun adopt(): AbemaGuestBootstrapActivity {
            val started = SystemClock.elapsedRealtime(); var adopted: AbemaGuestBootstrapActivity? = null
            while (adopted == null && SystemClock.elapsedRealtime() - started in 0 until 5000) {
                onMain {
                    val instances = alive()
                    check(owned.size <= 1 && instances.size <= 1)
                    val resumed = lifecycle.getActivitiesInStage(Stage.RESUMED)
                        .filter { it.javaClass == AbemaGuestBootstrapActivity::class.java }
                    val exact = resumed.singleOrNull() as? AbemaGuestBootstrapActivity
                    if (exact != null && owned.singleOrNull() === exact && instances.singleOrNull() === exact &&
                        !exact.isFinishing && !exact.isDestroyed && exact.hasWindowFocus()) {
                        exact.window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                        active.set(true); adopted = exact
                    }
                }
                if (adopted == null) Thread.sleep(25)
            }
            return checkNotNull(adopted)
        }

        fun finishConfirmed() {
            active.set(false)
            if (!registered) return
            try {
                val started = SystemClock.elapsedRealtime(); var confirmed = false
                while (!confirmed && SystemClock.elapsedRealtime() - started in 0 until 5000) {
                    onMain {
                        owned.toList().forEach { if (!it.isDestroyed && !it.isFinishing) it.finish() }
                        confirmed = (!launched || owned.isNotEmpty()) && owned.all { it.isDestroyed } &&
                            alive().none { living -> owned.any { it === living } }
                    }
                    if (!confirmed) Thread.sleep(25)
                }
                check(confirmed)
            } finally {
                onMain { lifecycle.removeLifecycleCallback(callback); registered = false }
            }
        }
    }

    private class Probe(environment: CandidateEnvironment) {
        val worker = Executors.newSingleThreadExecutor()
        val cleanup = Executors.newFixedThreadPool(2)
        val runtime = AbemaGuestRuntimeCoordinator(environment, AndroidAbemaGuestMain(), worker, cleanup, SystemClock::elapsedRealtime)
    }

    private class CandidateEnvironment(private val base: AbemaGuestRuntimeEnvironment) : AbemaGuestRuntimeEnvironment by base {
        val candidate = AtomicReference<PublicSeasonProof?>()
        val absentCandidate = AtomicReference<PublicSeasonProof?>()
        val shapes = AtomicReference<ShapeCounts?>()
        override fun prepare(owner: AbemaGuestRuntimeOwner, request: AbemaGuestRuntimeRequest,
            preparation: AbemaGuestRuntimePreparation, canRequest: () -> Boolean): Map<String, ByteArray> {
            check(request.operation == AbemaGuestBootstrapOperation.FETCH_SERIES && request.seriesRequest?.let {
                it.seriesId == SERIES && it.offset == 0 && it.version == null && it.seasonId == null
            } == true)
            val files = base.prepare(owner, request, preparation, canRequest)
            val source = files.getValue(CONTROL_PATH).toString(Charsets.UTF_8)
            check(source.length <= 128 * 1024 && source.split(PROJECTION).size == 2)
            return files + (CONTROL_PATH to source.replace(PROJECTION, TEST_PROJECTION).toByteArray(Charsets.UTF_8))
        }
        override fun newView(request: AbemaGuestRuntimeRequest, preparation: AbemaGuestRuntimePreparation,
            files: Map<String, ByteArray>, canRequest: () -> Boolean, currentView: (AbemaGuestRuntimeView) -> Boolean,
            failed: (AbemaGuestRuntimeView, String) -> Unit): AbemaGuestRuntimeView {
            lateinit var wrapped: AbemaGuestRuntimeView
            val actual = base.newView(request, preparation, files, canRequest,
                { currentView(wrapped) }, { _, category -> failed(wrapped, category) })
            wrapped = object : AbemaGuestRuntimeView by actual {
                override fun metadata(callback: (String?) -> Unit) = actual.metadata { raw ->
                    val stripped = stripProjection(raw)
                    candidate.set(stripped?.proof)
                    absentCandidate.set(stripped?.absentProof)
                    shapes.set(stripped?.shapes)
                    callback(stripped?.raw)
                }
            }
            return wrapped
        }
    }

    private data class PublicSeasonProof(val seriesId: String, val seasonId: String, val programId: String)
    private enum class PublicSeasonEvidence { NONE, EXPLICIT_PARENT_FIELD, SAME_PROGRAM_ABSENT_PARENT_FIELD }
    private data class ShapeCounts(val rows: Int, val seriesExact: Int, val seasonValid: Int,
        val parentAbsent: Int, val parentMatch: Int, val parentInvalid: Int,
        val validParentAbsent: Int, val validParentMatch: Int, val validParentInvalid: Int)
    private class StrippedProjection(val raw: String, val proof: PublicSeasonProof?, val absentProof: PublicSeasonProof?, val shapes: ShapeCounts)

    private fun encryptedRecords(directory: File): Map<String, ByteArray> {
        val started = SystemClock.elapsedRealtime()
        fun checkTime() = check(SystemClock.elapsedRealtime() - started in 0 until 5000)
        check(directory.isDirectory && directory.canRead() && !Files.isSymbolicLink(directory.toPath()))
        val files = checkNotNull(directory.listFiles()).filter { it.name.endsWith(".enc") || it.name.endsWith(".enc.bak") || it.name.endsWith(".enc.new") }
        check(files.size in 1..64); checkTime()
        return files.associate { file ->
            checkTime(); check(file.isFile && file.canRead() && !Files.isSymbolicLink(file.toPath()) && file.length() in 1..(PRIVATE_SECRET_LIMIT + 29).toLong())
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192); var size = 0
                try { while (true) {
                    checkTime(); val count = input.read(buffer); if (count < 0) break
                    checkTime(); size += count; check(size <= PRIVATE_SECRET_LIMIT + 29); digest.update(buffer, 0, count)
                } } finally { buffer.fill(0) }
            }
            checkTime(); file.name to digest.digest()
        }
    }

    companion object {
        private const val SERIES = "394-72"
        private const val CONTROL_PATH = "/_tachiai/guest/control.js"
        private const val PROOF_FIELD = "tachiaiTestPublicSeasonParent"
        private const val ABSENT_PROOF_FIELD = "tachiaiTestPublicSeasonAbsentParent"
        private const val SHAPE_FIELD = "tachiaiTestPublicSeasonShape"
        private const val PROJECTION = "return Object.freeze({ id: item.id, title: item.episode.title.trim() });"
        private val retained = AtomicReference<Probe?>()
        private val TEST_PROJECTION = """
            const row = { id: item.id, title: item.episode.title.trim() };
            const parent = item.season;
            const seriesExact = !!(item.series && typeof item.series === 'object' && !Array.isArray(item.series) &&
              typeof item.series.id === 'string' && item.series.id === seriesRequest.seriesId);
            const seasonObject = !!(parent && typeof parent === 'object' && !Array.isArray(parent));
            const seasonValid = !!(seasonObject && typeof parent.id === 'string' &&
              /^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(parent.id));
            const seasonParent = seasonObject ? (Object.prototype.hasOwnProperty.call(parent, 'seriesId') ?
              (seriesExact && typeof parent.seriesId === 'string' && parent.seriesId === item.series.id ? 'MATCH' : 'INVALID') : 'ABSENT') :
              (parent === undefined || parent === null ? 'ABSENT' : 'INVALID');
            row.$SHAPE_FIELD = { seriesExact, seasonValid, seasonParent };
            if (item.series && typeof item.series === 'object' && !Array.isArray(item.series) &&
                typeof item.series.id === 'string' && item.series.id === seriesRequest.seriesId &&
                parent && typeof parent === 'object' && !Array.isArray(parent) &&
                typeof parent.id === 'string' && /^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(parent.id) &&
                typeof parent.seriesId === 'string' && parent.seriesId === item.series.id &&
                item.series.id.length + parent.id.length + 1 <= 256) {
              row.$PROOF_FIELD = { seriesId: item.series.id, seasonId: parent.id,
                seasonSeriesId: parent.seriesId, programId: item.id };
            }
            if (seriesExact && seasonValid && !Object.prototype.hasOwnProperty.call(parent, 'seriesId') &&
                item.series.id.length + parent.id.length + 1 <= 256) {
              row.$ABSENT_PROOF_FIELD = { seriesId: item.series.id, seasonId: parent.id, programId: item.id };
            }
            return Object.freeze(row);
        """.trimIndent()

        private fun stripProjection(raw: String?): StrippedProjection? = runCatching {
            val encoded = raw?.takeIf { it.length <= 65536 } ?: return null
            checkAbemaCollectionJson(encoded)
            val outer = JSONTokener(encoded)
            val text = outer.nextValue() as? String ?: return null
            if (outer.nextClean() != '\u0000' || text.toByteArray(Charsets.UTF_8).size > 65536) return null
            checkAbemaCollectionJson(text)
            val inner = JSONTokener(text); val value = inner.nextValue() as? JSONObject ?: return null
            if (inner.nextClean() != '\u0000' || value.keys().asSequence().toSet() != setOf("seriesId", "version", "programs") ||
                value.opt("seriesId") != SERIES) return null
            val rows = value.opt("programs") as? JSONArray ?: return null
            if (rows.length() > 20) return null
            var candidate: PublicSeasonProof? = null
            var absentCandidate: PublicSeasonProof? = null
            var seriesExact = 0; var seasonValid = 0; var parentAbsent = 0; var parentMatch = 0; var parentInvalid = 0
            var validParentAbsent = 0; var validParentMatch = 0; var validParentInvalid = 0
            for (index in 0 until rows.length()) {
                val row = rows.opt(index) as? JSONObject ?: return null
                val keys = row.keys().asSequence().toSet()
                if (keys != setOf("id", "title", SHAPE_FIELD) && keys != setOf("id", "title", SHAPE_FIELD, PROOF_FIELD) &&
                    keys != setOf("id", "title", SHAPE_FIELD, ABSENT_PROOF_FIELD)) return null
                val shape = row.remove(SHAPE_FIELD) as? JSONObject ?: return null
                if (shape.keys().asSequence().toSet() != setOf("seriesExact", "seasonValid", "seasonParent")) return null
                val exact = shape.opt("seriesExact") as? Boolean ?: return null
                val valid = shape.opt("seasonValid") as? Boolean ?: return null
                val parent = shape.opt("seasonParent") as? String ?: return null
                if (exact) seriesExact++
                if (valid) seasonValid++
                when (parent) {
                    "ABSENT" -> { parentAbsent++; if (valid) validParentAbsent++ }
                    "MATCH" -> { parentMatch++; if (valid) validParentMatch++ }
                    "INVALID" -> { parentInvalid++; if (valid) validParentInvalid++ }
                    else -> return null
                }
                if (keys.contains(ABSENT_PROOF_FIELD)) {
                    val absent = row.remove(ABSENT_PROOF_FIELD) as? JSONObject ?: return null
                    if (!exact || !valid || parent != "ABSENT" ||
                        absent.keys().asSequence().toSet() != setOf("seriesId", "seasonId", "programId")) return null
                    val series = absent.opt("seriesId") as? String ?: return null
                    val season = absent.opt("seasonId") as? String ?: return null
                    val program = absent.opt("programId") as? String ?: return null
                    if (series != SERIES || !validAbemaSeriesId(season) || series.length + season.length + 1 > 256 ||
                        !validAbemaSeriesId(program) || program != row.opt("id")) return null
                    if (absentCandidate == null) absentCandidate = PublicSeasonProof(series, season, program)
                }
                val extra = row.remove(PROOF_FIELD) as? JSONObject
                if (extra != null && extra.keys().asSequence().toSet() == setOf("seriesId", "seasonId", "seasonSeriesId", "programId")) {
                    val series = extra.opt("seriesId") as? String
                    val season = extra.opt("seasonId") as? String
                    val program = extra.opt("programId") as? String
                    if (series == SERIES && extra.opt("seasonSeriesId") == SERIES && season != null && validAbemaSeriesId(season) &&
                        series.length + season.length + 1 <= 256 && program != null && validAbemaSeriesId(program) && program == row.opt("id")) {
                        if (candidate == null) candidate = PublicSeasonProof(series, season, program)
                    }
                }
            }
            StrippedProjection(JSONObject.quote(value.toString()), candidate, absentCandidate, ShapeCounts(rows.length(), seriesExact, seasonValid,
                parentAbsent, parentMatch, parentInvalid, validParentAbsent, validParentMatch, validParentInvalid))
        }.getOrNull()
    }
}
