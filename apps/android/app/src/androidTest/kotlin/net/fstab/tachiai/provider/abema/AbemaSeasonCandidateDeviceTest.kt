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
// One known unfiltered Series page, exact Slot or public Schedule; no guessed identities, continuation,
// account capability, Add, playback, provider mutation or production DTO change.
class AbemaSeasonCandidateDeviceTest {
    @Test fun optInOneKnownSeriesPageObservesOnlyAnExplicitReturnedSeasonParent() {
        verifiedProbeInstrumentation("tachiaiReadOnlyAbemaSeasonCandidate")
        val phases = HarnessPhases()
        try { observe(phases) } catch (_: Throwable) { failHarness(phases) }
    }

    @Test fun optInOneKnownSlotObservesOnlyClosedSourceAndWindowShapes() {
        verifiedProbeInstrumentation("tachiaiReadOnlyAbemaSlotShape")
        val phases = HarnessPhases()
        try { observe(phases, Observation.SLOT_SHAPE) } catch (_: Throwable) { failHarness(phases, Observation.SLOT_SHAPE) }
    }

    @Test fun optInOneScheduleObservesOnlyClosedRawTimeAndFreeFlagCounts() {
        verifiedProbeInstrumentation("tachiaiReadOnlyAbemaScheduleShape")
        val phases = HarnessPhases()
        try { observe(phases, Observation.SCHEDULE_SHAPE) } catch (_: Throwable) { failHarness(phases, Observation.SCHEDULE_SHAPE) }
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

    private fun failHarness(phases: HarnessPhases, observation: Observation = Observation.SERIES_CANDIDATE): Nothing {
        val phase = phases.failure.get() ?: phases.current.get()
        Log.i(observation.tag, "outcome=HARNESS_FAILED phase=${phase.name}")
        throw AssertionError("Public metadata observation failed closed checks at ${phase.name}")
    }

    @Test fun syntheticSlotShapesAreClosedAndStrippedWithoutChangingThePublicProjection() {
        fun marker() = JSONObject().apply {
            SLOT_FIELDS.keys.forEach { put(it, SlotShape.ABSENT.name) }
            SLOT_PARENTS.values.flatten().forEach { put(it, SlotShape.PARENT_UNAVAILABLE.name) }
        }
        fun projection(shapes: Any = marker(), id: Any = SLOT_ID, channel: Any = SLOT_CHANNEL) = JSONObject()
            .put("id", id).put("channelId", channel).put("title", "Fixture broadcast").put(SLOT_SHAPE_FIELD, shapes)
        val closed = marker().put("playback", "OBJECT").put("playbackDash", "STRING").put("playbackArin", "EMPTY_STRING")
            .put("flags", "OBJECT").put("paused", "FALSE").put("timeshift", "TRUE").put("timeshiftFree", "NULL")
            .put("dashFlag", "ABSENT").put("chasePlay", "ABSENT")
            .put("mark", "EMPTY_OBJECT").put("drm", "ABSENT").put("payperview", "NULL")
            .put("startAt", "ZERO").put("endAt", "POSITIVE_SAFE_INTEGER").put("timeshiftEndAt", "OTHER_NUMBER")
            .put("broadcastRegionPolicies", "OBJECT").put("linearRegion", "JAPAN_ONLY").put("timeshiftRegion", "OVERSEAS_ONLY")
            .put("chasePlayFeatureAuthorityIds", "NONEMPTY_ARRAY")
        val result = stripSlotProjection(JSONObject.quote(projection(closed).toString()))!!
        val expected = JSONObject().put("id", SLOT_ID).put("channelId", SLOT_CHANNEL).put("title", "Fixture broadcast")
        assertEquals(expected.toString(), JSONTokener(result.raw).nextValue())
        assertEquals(SlotShape.ZERO, result.shapes.fields.getValue("startAt"))
        assertEquals(SlotShape.NULL, result.shapes.fields.getValue("timeshiftFree"))
        assertEquals(SlotShape.PARENT_UNAVAILABLE, result.shapes.fields.getValue("chaseDash"))
        assertTrue(result.shapes.render().split(' ').size == SLOT_FIELDS.size)
        assertFalse(result.raw.contains(SLOT_SHAPE_FIELD))
        for (parent in listOf("ABSENT", "NULL", "INVALID")) {
            val source = marker().put("playback", parent)
            assertNotNull(stripSlotProjection(JSONObject.quote(projection(source).toString())))
            source.put("playbackDash", "STRING")
            assertNull(stripSlotProjection(JSONObject.quote(projection(source).toString())))
        }
        for (invalid in listOf(marker().put("playbackDash", "TRUE"), marker().put("paused", true),
            marker().put("startAt", 0), marker().put("linearRegion", "https://synthetic.invalid/"),
            marker().put("unexpected", "STRING"), marker().apply { remove("endAt") },
            marker().put("flags", "EMPTY_OBJECT").put("paused", "TRUE"),
            marker().put("flags", "OBJECT"), JSONObject.NULL, "OBJECT")) {
            assertNull(stripSlotProjection(JSONObject.quote(projection(invalid).toString())))
        }
        assertNull(stripSlotProjection(JSONObject.quote(projection(id = "other-slot").toString())))
        assertNull(stripSlotProjection(JSONObject.quote(projection(channel = "other-channel").toString())))
        assertNull(stripSlotProjection(JSONObject.quote(projection(id = 12).toString())))
        assertNull(stripSlotProjection(JSONObject.quote(projection().put("extra", "STRING").toString())))
        assertNull(stripSlotProjection("{}"))
        val single = projection().toString()
        for (duplicate in listOf(single.replaceFirst("\"playback\":", "\"playback\":\"NULL\",\"playback\":"),
            single.replaceFirst("\"playback\":", "\"play\\u0062ack\":\"NULL\",\"playback\":"))) {
            assertNull(stripSlotProjection(JSONObject.quote(duplicate)))
        }
        assertNull(stripSlotProjection(JSONObject.quote(single) + "{}"))
        assertNull(stripSlotProjection(JSONObject.quote(" ".repeat(4097))))
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

    private fun observe(phases: HarnessPhases, observation: Observation = Observation.SERIES_CANDIDATE) {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val instanceId = defaultProviderInstanceId(PrototypeService.ABEMA)
        val schedule = observation == Observation.SCHEDULE_SHAPE
        fun stableRecords() { if (schedule) requireStableRecords(context.noBackupFilesDir) }
        fun readOwner(): AbemaCatalogRouteOwner {
            stableRecords()
            return readAbemaCatalogRouteOwner(instanceId,
                { providerInstanceStore(context).read { legacyProviderSettings(context) } },
                { connectionProfileStore(context).selected(it) }).also { stableRecords() }
        }
        val before = phases.at(HarnessPhase.PROTECTED_CAPTURE) { encryptedRecords(context.noBackupFilesDir, schedule) }
        val owner = phases.at(HarnessPhase.OWNER_CHECK) {
            readOwner().also { check(it.profile != null && it.instance.setup.route?.mode == SourceRouteMode.SAVED_CONNECTION) }
        }
        phases.at(HarnessPhase.PROFILE_SETUP) { instrumentation.runOnMainSync { configureAbemaGuestBootstrapProfile(context) } }
        val active = AtomicBoolean(); val foreground = IdleForeground(instrumentation, active)
        val environment = CandidateEnvironment(AndroidAbemaGuestEnvironment(context), observation, ::stableRecords)
        val probe = Probe(environment)
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val result = AtomicReference<AbemaGuestRuntimeResult?>(); val completed = CountDownLatch(1)
        var admitted = false; var activityFinished = false; val started = SystemClock.elapsedRealtime()
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
                val request = AbemaGuestRuntimeRequest(instanceId, observation.operation,
                    nonce = nonce, expectedOwnerProof = owner.proof(), deadlineElapsedMs = SystemClock.elapsedRealtime() + 45_000,
                    seriesRequest = if (observation == Observation.SERIES_CANDIDATE) AbemaSeriesPageRequest(SERIES) else null,
                    slotRequest = if (observation == Observation.SLOT_SHAPE) AbemaSlotRequest(SLOT_CHANNEL, SLOT_ID) else null,
                    canRemainActive = active::get)
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
                try { phases.at(HarnessPhase.ACTIVITY_DESTRUCTION) { foreground.finishConfirmed(); activityFinished = true } } finally {
                    // A failed/held teardown keeps the actual coordinator, route,
                    // view and cleanup executors strongly owned for this process.
                    if (!probe.runtime.unavailable && (!schedule || activityFinished)) {
                        probe.worker.shutdown(); probe.cleanup.shutdown()
                        retained.compareAndSet(probe, null)
                    } else if (schedule) {
                        retained.compareAndSet(null, probe)
                    }
                }
            }
        }
        // The new Schedule observation attempts both postchecks even when one
        // fails. It assumes an idle device; preflight is not an external-writer lock.
        val after = if (schedule) runCatching { phases.at(HarnessPhase.PROTECTED_POSTCHECK) {
            encryptedRecords(context.noBackupFilesDir, true)
        } }.getOrNull() else phases.at(HarnessPhase.PROTECTED_POSTCHECK) { encryptedRecords(context.noBackupFilesDir) }
        val current = if (schedule) runCatching { phases.at(HarnessPhase.OWNER_POSTCHECK) {
            owner.sameOwnership(readOwner())
        } }.getOrDefault(false) else phases.at(HarnessPhase.OWNER_POSTCHECK) { owner.sameOwnership(readOwner()) }
        val unchanged = after != null && before.keys == after.keys && before.all { (name, digest) -> after[name]?.let { MessageDigest.isEqual(digest, it) } == true }
        if (schedule) { before.values.forEach { it.fill(0) }; after?.values?.forEach { it.fill(0) } }
        val value = result.get()
        val cleanupConfirmed = admitted && completed.count == 0L && !probe.runtime.unavailable && (!schedule || activityFinished)
        if (schedule) {
            val snapshot = value?.scheduleSnapshot
            val success = value?.category == "SCHEDULE_SNAPSHOT" && snapshot != null && snapshot.entries.size <= 501 &&
                snapshot.entries.all { AbemaSlotRequest.from(it.resource) != null &&
                    it.availability == CatalogAvailability.UNKNOWN && it.scheduledStartEpochMs == null }
            val bounded = SystemClock.elapsedRealtime() - started in 0 until 70_000
            val counts = environment.scheduleShapes.getAndSet(null).takeIf {
                cleanupConfirmed && unchanged && current && success && bounded && it != null && it.projectedRows == snapshot.entries.size &&
                    it.truncated == snapshot.truncated
            }
            val outcome = when {
                !cleanupConfirmed -> "CLEANUP_FAILED"; !unchanged -> "STORES_CHANGED"; !current -> "OWNER_CHANGED"
                !bounded -> "BUDGET_REFUSED"; success && counts != null -> "SCHEDULE_SHAPES"; else -> "INVALID_RESPONSE"
            }
            Log.i(observation.tag, "outcome=$outcome sdk=${Build.VERSION.SDK_INT} routeMode=${owner.instance.setup.route!!.mode.name}" +
                (counts?.let { " ${it.header()}" } ?: " shape=NONE"))
            counts?.let {
                // Fixed labels and validated counts only; bounded separate lines
                // avoid Android truncating a combined category report.
                Log.i(observation.tag, "scope=ALL ${it.all.render()}")
                Log.i(observation.tag, "scope=PROJECTED ${it.projected.render()}")
                Log.i(observation.tag, "scope=DISPLAYED ${it.displayed.render()}")
            }
            phases.at(HarnessPhase.RESULT_CHECK) {
                assertTrue("Schedule shape observation exceeded bounded budgets", bounded)
                assertTrue("Schedule shape observation changed protected records", unchanged)
                assertTrue("Schedule shape observation changed captured owner", current)
                assertTrue("Schedule shape observation did not confirm cleanup", cleanupConfirmed)
                assertTrue("Schedule shape observation did not return normal metadata", success)
                assertNotNull("Schedule shape observation did not return closed coherent counts", counts)
            }
            phases.current.set(HarnessPhase.COMPLETE)
            return
        }
        if (observation == Observation.SLOT_SHAPE) {
            val entry = value?.entry
            val success = value?.category == "EXACT_SLOT_METADATA" && entry != null &&
                entry.resource == AbemaSlotRequest(SLOT_CHANNEL, SLOT_ID).resource && safeAbemaSeriesText(entry.title, 160) &&
                entry.availability == CatalogAvailability.UNKNOWN && entry.scheduledStartEpochMs == null
            val shapes = environment.slotShapes.getAndSet(null).takeIf { cleanupConfirmed && unchanged && current && success }
            val outcome = when {
                !cleanupConfirmed -> "CLEANUP_FAILED"; !unchanged -> "STORES_CHANGED"; !current -> "OWNER_CHANGED"
                success && shapes != null -> "EXACT_SLOT_METADATA"; else -> "INVALID_RESPONSE"
            }
            Log.i(observation.tag, "outcome=$outcome" + (shapes?.let { " ${it.render()}" } ?: " shape=NONE"))
            phases.at(HarnessPhase.RESULT_CHECK) {
                assertTrue("Public Slot observation exceeded bounded budgets", SystemClock.elapsedRealtime() - started in 0 until 70_000)
                assertTrue("Public Slot observation changed protected records", unchanged)
                assertTrue("Public Slot observation changed captured owner", current)
                assertTrue("Public Slot observation did not confirm cleanup", cleanupConfirmed)
                assertTrue("Public Slot observation did not return exact normalized metadata", success)
                assertNotNull("Public Slot observation did not return closed shape categories", shapes)
            }
            phases.current.set(HarnessPhase.COMPLETE)
            return
        }
        val page = value?.seriesPage
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

    private enum class Observation(val operation: AbemaGuestBootstrapOperation, val tag: String) {
        SERIES_CANDIDATE(AbemaGuestBootstrapOperation.FETCH_SERIES, "TachiaiAbemaSeasonCandidateProbe"),
        SLOT_SHAPE(AbemaGuestBootstrapOperation.FETCH_SLOT, "TachiaiAbemaSlotShapeProbe"),
        SCHEDULE_SHAPE(AbemaGuestBootstrapOperation.FETCH_SCHEDULE, "TachiaiAbemaScheduleShapeProbe"),
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

    private class CandidateEnvironment(private val base: AbemaGuestRuntimeEnvironment, private val observation: Observation,
        private val stableRecords: () -> Unit = {}) : AbemaGuestRuntimeEnvironment by base {
        val candidate = AtomicReference<PublicSeasonProof?>()
        val absentCandidate = AtomicReference<PublicSeasonProof?>()
        val shapes = AtomicReference<ShapeCounts?>()
        val slotShapes = AtomicReference<SlotShapes?>()
        val scheduleShapes = AtomicReference<AbemaScheduleShapeObservation.Counts?>()
        private val scheduleWallMs = if (observation == Observation.SCHEDULE_SHAPE) System.currentTimeMillis() else null
        override fun readOwner(instanceId: String): AbemaGuestRuntimeOwner {
            stableRecords(); return base.readOwner(instanceId).also { stableRecords() }
        }
        override fun sameOwner(owner: AbemaGuestRuntimeOwner): Boolean {
            stableRecords(); return base.sameOwner(owner).also { stableRecords() }
        }
        override fun prepare(owner: AbemaGuestRuntimeOwner, request: AbemaGuestRuntimeRequest,
            preparation: AbemaGuestRuntimePreparation, canRequest: () -> Boolean): Map<String, ByteArray> {
            check(request.operation == observation.operation && when (observation) {
                Observation.SERIES_CANDIDATE -> request.seriesRequest?.let {
                    it.seriesId == SERIES && it.offset == 0 && it.version == null && it.seasonId == null
                } == true
                Observation.SLOT_SHAPE -> request.slotRequest?.let { it.channelId == SLOT_CHANNEL && it.slotId == SLOT_ID } == true
                Observation.SCHEDULE_SHAPE -> request.seriesRequest == null && request.slotRequest == null && request.searchRequest == null && request.episodeId == null
            })
            val files = base.prepare(owner, request, preparation, canRequest)
            val source = files.getValue(CONTROL_PATH).toString(Charsets.UTF_8)
            val anchor = when (observation) {
                Observation.SERIES_CANDIDATE -> PROJECTION; Observation.SLOT_SHAPE -> SLOT_PROJECTION
                Observation.SCHEDULE_SHAPE -> AbemaScheduleShapeObservation.ANCHOR
            }
            val projection = when (observation) {
                Observation.SERIES_CANDIDATE -> TEST_PROJECTION; Observation.SLOT_SHAPE -> TEST_SLOT_PROJECTION
                Observation.SCHEDULE_SHAPE -> AbemaScheduleShapeObservation.projection(checkNotNull(scheduleWallMs))
            }
            check(source.length <= 128 * 1024 && source.split(anchor).size == 2)
            return files + (CONTROL_PATH to source.replace(anchor, projection).toByteArray(Charsets.UTF_8))
        }
        override fun newView(request: AbemaGuestRuntimeRequest, preparation: AbemaGuestRuntimePreparation,
            files: Map<String, ByteArray>, canRequest: () -> Boolean, currentView: (AbemaGuestRuntimeView) -> Boolean,
            failed: (AbemaGuestRuntimeView, String) -> Unit): AbemaGuestRuntimeView {
            lateinit var wrapped: AbemaGuestRuntimeView
            val actual = base.newView(request, preparation, files, canRequest,
                { currentView(wrapped) }, { _, category -> failed(wrapped, category) })
            wrapped = object : AbemaGuestRuntimeView by actual {
                override fun metadata(callback: (String?) -> Unit) = actual.metadata { raw ->
                    if (observation == Observation.SERIES_CANDIDATE) {
                        val stripped = stripProjection(raw)
                        candidate.set(stripped?.proof)
                        absentCandidate.set(stripped?.absentProof)
                        shapes.set(stripped?.shapes)
                        callback(stripped?.raw)
                    } else if (observation == Observation.SLOT_SHAPE) {
                        val stripped = stripSlotProjection(raw)
                        slotShapes.set(stripped?.shapes)
                        callback(stripped?.raw)
                    } else {
                        val stripped = AbemaScheduleShapeObservation.strip(raw)
                        scheduleShapes.set(stripped?.counts)
                        callback(stripped?.raw)
                    }
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
    private enum class SlotShape {
        ABSENT, NULL, EMPTY_OBJECT, OBJECT, INVALID, EMPTY_STRING, STRING, PARENT_UNAVAILABLE,
        TRUE, FALSE, ZERO, POSITIVE_SAFE_INTEGER, OTHER_NUMBER, ALLOW_ALL, JAPAN_ONLY, OVERSEAS_ONLY,
        OTHER_ENUM, EMPTY_ARRAY, NONEMPTY_ARRAY,
    }
    private class SlotShapes(val fields: Map<String, SlotShape>) {
        fun render() = SLOT_FIELDS.keys.joinToString(" ") { "$it=${fields.getValue(it).name}" }
    }
    private class StrippedSlotProjection(val raw: String, val shapes: SlotShapes)

    private fun requireStableRecords(directory: File) {
        check(directory.isDirectory && directory.canRead() && !Files.isSymbolicLink(directory.toPath()))
        check(checkNotNull(directory.listFiles()).none { it.name.endsWith(".enc.bak") || it.name.endsWith(".enc.new") })
    }

    private fun encryptedRecords(directory: File, stable: Boolean = false): Map<String, ByteArray> {
        val started = SystemClock.elapsedRealtime()
        fun checkTime() = check(SystemClock.elapsedRealtime() - started in 0 until 5000)
        check(directory.isDirectory && directory.canRead() && !Files.isSymbolicLink(directory.toPath()))
        if (stable) requireStableRecords(directory)
        val files = checkNotNull(directory.listFiles()).filter { it.name.endsWith(".enc") || it.name.endsWith(".enc.bak") || it.name.endsWith(".enc.new") }
        check(files.size in 1..64); checkTime()
        val captured = linkedMapOf<String, ByteArray>()
        try {
            for (file in files) {
                checkTime(); check(file.isFile && file.canRead() && !Files.isSymbolicLink(file.toPath()) && file.length() in 1..(PRIVATE_SECRET_LIMIT + 29).toLong())
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192); var size = 0
                    try { while (true) {
                        checkTime(); val count = input.read(buffer); if (count < 0) break
                        checkTime(); size += count; check(size <= PRIVATE_SECRET_LIMIT + 29); digest.update(buffer, 0, count)
                    } } finally { buffer.fill(0) }
                }
                checkTime(); captured[file.name] = digest.digest()
            }
            if (stable) requireStableRecords(directory)
            return captured
        } catch (error: Throwable) {
            if (stable) captured.values.forEach { it.fill(0) }
            throw error
        }
    }

    companion object {
        private const val SERIES = "394-72"
        private const val SLOT_CHANNEL = "abema-anime"
        private const val SLOT_ID = "9FYQvU4hBaZZy1"
        private const val CONTROL_PATH = "/_tachiai/guest/control.js"
        private const val PROOF_FIELD = "tachiaiTestPublicSeasonParent"
        private const val ABSENT_PROOF_FIELD = "tachiaiTestPublicSeasonAbsentParent"
        private const val SHAPE_FIELD = "tachiaiTestPublicSeasonShape"
        private const val SLOT_SHAPE_FIELD = "tachiaiTestSlotShape"
        private const val PROJECTION = "return Object.freeze({ id: item.id, title: item.episode.title.trim() });"
        private const val SLOT_PROJECTION = "return Object.freeze({ id: value.id, channelId: value.channelId, title: value.title });"
        private val retained = AtomicReference<Probe?>()
        private val SLOT_FIELDS = buildMap<String, Set<SlotShape>> {
            fun fields(names: String, vararg categories: SlotShape) {
                val allowed = setOf(SlotShape.ABSENT, SlotShape.NULL, SlotShape.INVALID, *categories)
                names.split(' ').forEach { put(it, allowed) }
            }
            fields("playback chasePlayback flags mark broadcastRegionPolicies payperview", SlotShape.EMPTY_OBJECT, SlotShape.OBJECT)
            fields("playbackDash playbackArin chaseDash chaseArin", SlotShape.EMPTY_STRING, SlotShape.STRING, SlotShape.PARENT_UNAVAILABLE)
            fields("paused timeshift timeshiftFree dashFlag chasePlay drm", SlotShape.TRUE, SlotShape.FALSE, SlotShape.PARENT_UNAVAILABLE)
            fields("startAt endAt timeshiftEndAt timeshiftFreeEndAt", SlotShape.ZERO, SlotShape.POSITIVE_SAFE_INTEGER, SlotShape.OTHER_NUMBER)
            fields("linearRegion timeshiftRegion", SlotShape.ALLOW_ALL, SlotShape.JAPAN_ONLY, SlotShape.OVERSEAS_ONLY, SlotShape.OTHER_ENUM, SlotShape.PARENT_UNAVAILABLE)
            fields("chasePlayFeatureAuthorityIds", SlotShape.EMPTY_ARRAY, SlotShape.NONEMPTY_ARRAY)
        }
        private val SLOT_PARENTS = mapOf("playback" to listOf("playbackDash", "playbackArin"),
            "chasePlayback" to listOf("chaseDash", "chaseArin"), "flags" to listOf("paused", "timeshift", "timeshiftFree", "dashFlag", "chasePlay"),
            "mark" to listOf("drm"), "broadcastRegionPolicies" to listOf("linearRegion", "timeshiftRegion"))
        // Shape only: no playlist, ARIN, timestamp, authority or account value
        // leaves this app-owned callback. NULL/ZERO are not access decisions.
        private val TEST_SLOT_PROJECTION = """
            const record = object => !!object && typeof object === 'object' && !Array.isArray(object);
            const shape = (object, key, kind) => {
              if (!record(object)) return 'PARENT_UNAVAILABLE';
              if (!Object.prototype.hasOwnProperty.call(object, key)) return 'ABSENT';
              const item = object[key];
              if (item === null) return 'NULL';
              if (kind === 'record') return record(item) ? (Object.keys(item).length ? 'OBJECT' : 'EMPTY_OBJECT') : 'INVALID';
              if (kind === 'string') return typeof item === 'string' ? (item.length ? 'STRING' : 'EMPTY_STRING') : 'INVALID';
              if (kind === 'boolean') return item === true ? 'TRUE' : item === false ? 'FALSE' : 'INVALID';
              if (kind === 'number') return typeof item !== 'number' || !Number.isFinite(item) ? 'INVALID' :
                item === 0 ? 'ZERO' : Number.isSafeInteger(item) && item > 0 ? 'POSITIVE_SAFE_INTEGER' : 'OTHER_NUMBER';
              if (kind === 'array') return Array.isArray(item) ? (item.length ? 'NONEMPTY_ARRAY' : 'EMPTY_ARRAY') : 'INVALID';
              if (kind === 'region') {
                if (item === 0 || item === 'BROADCAST_REGION_ALLOW_ALL') return 'ALLOW_ALL';
                if (item === 1 || item === 'BROADCAST_REGION_ALLOW_ONLY_JAPAN') return 'JAPAN_ONLY';
                if (item === 2 || item === 'BROADCAST_REGION_ALLOW_ONLY_OVERSEAS') return 'OVERSEAS_ONLY';
                return typeof item === 'number' || typeof item === 'string' ? 'OTHER_ENUM' : 'INVALID';
              }
              throw new Error();
            };
            const shapes = {};
            for (const key of ['playback', 'chasePlayback', 'flags', 'mark', 'broadcastRegionPolicies', 'payperview'])
              shapes[key] = shape(value, key, 'record');
            for (const [name, parent, key, kind] of [
              ['playbackDash', value.playback, 'dash', 'string'], ['playbackArin', value.playback, 'arin', 'string'],
              ['chaseDash', value.chasePlayback, 'dash', 'string'], ['chaseArin', value.chasePlayback, 'arin', 'string'],
              ['paused', value.flags, 'paused', 'boolean'], ['timeshift', value.flags, 'timeshift', 'boolean'],
              ['timeshiftFree', value.flags, 'timeshiftFree', 'boolean'], ['dashFlag', value.flags, 'dash', 'boolean'],
              ['chasePlay', value.flags, 'chasePlay', 'boolean'], ['drm', value.mark, 'drm', 'boolean'],
              ['linearRegion', value.broadcastRegionPolicies, 'linear', 'region'],
              ['timeshiftRegion', value.broadcastRegionPolicies, 'timeshift', 'region']]) shapes[name] = shape(parent, key, kind);
            for (const key of ['startAt', 'endAt', 'timeshiftEndAt', 'timeshiftFreeEndAt']) shapes[key] = shape(value, key, 'number');
            shapes.chasePlayFeatureAuthorityIds = shape(value, 'chasePlayFeatureAuthorityIds', 'array');
            return Object.freeze({ id: value.id, channelId: value.channelId, title: value.title,
              $SLOT_SHAPE_FIELD: Object.freeze(shapes) });
        """.trimIndent()
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

        private fun stripSlotProjection(raw: String?): StrippedSlotProjection? = runCatching {
            val encoded = raw?.takeIf { it.length <= 4096 } ?: return null
            checkAbemaCollectionJson(encoded)
            val outer = JSONTokener(encoded); val text = outer.nextValue() as? String ?: return null
            if (outer.nextClean() != '\u0000' || text.toByteArray(Charsets.UTF_8).size > 4096) return null
            checkAbemaCollectionJson(text)
            val inner = JSONTokener(text); val value = inner.nextValue() as? JSONObject ?: return null
            if (inner.nextClean() != '\u0000' || value.keys().asSequence().toSet() != setOf("id", "channelId", "title", SLOT_SHAPE_FIELD) ||
                value.opt("id") != SLOT_ID || value.opt("channelId") != SLOT_CHANNEL) return null
            val marker = value.remove(SLOT_SHAPE_FIELD) as? JSONObject ?: return null
            if (marker.keys().asSequence().toSet() != SLOT_FIELDS.keys) return null
            val fields = SLOT_FIELDS.mapValues { (name, allowed) ->
                val category = SlotShape.valueOf(marker.opt(name) as? String ?: return null)
                if (category !in allowed) return null
                category
            }
            for ((parent, children) in SLOT_PARENTS) {
                val shape = fields.getValue(parent)
                if (children.any { name -> when (shape) {
                    SlotShape.ABSENT, SlotShape.NULL, SlotShape.INVALID -> fields.getValue(name) != SlotShape.PARENT_UNAVAILABLE
                    SlotShape.EMPTY_OBJECT -> fields.getValue(name) != SlotShape.ABSENT
                    SlotShape.OBJECT -> fields.getValue(name) == SlotShape.PARENT_UNAVAILABLE
                    else -> true
                } }) return null
            }
            StrippedSlotProjection(JSONObject.quote(value.toString()), SlotShapes(fields))
        }.getOrNull()
    }
}
