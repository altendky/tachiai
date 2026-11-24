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
import net.fstab.tachiai.provider.abema.account.AbemaAccountException
import net.fstab.tachiai.provider.abema.account.AbemaAccountFailure
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

// Test APK only. The opt-in gate precedes saved state, UI and all WebView APIs.
// One known Series content-list first page, exact Slot or public Schedule; no guessed identities, continuation,
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

    @Test fun optInOneScheduleSelectsOnlyOneExplicitFreeEndedCatchupLead() {
        verifiedProbeInstrumentation("tachiaiReadOnlyAbemaCatchupCandidate")
        val phases = HarnessPhases()
        try { observe(phases, Observation.CATCHUP_CANDIDATE) }
        catch (_: Throwable) { failHarness(phases, Observation.CATCHUP_CANDIDATE) }
    }

    @Test fun optInOneScheduleSelectsOnlyOneOngoingSlotLead() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("tachiaiReadOnlyAbemaOngoingCandidate") == "true")
        check(listOf("tachiaiReadOnlyAbemaSeasonCandidate", "tachiaiReadOnlyAbemaSlotShape",
            "tachiaiReadOnlyAbemaScheduleShape", "tachiaiReadOnlyAbemaCatchupCandidate",
            "tachiaiReadOnlyAbemaSeasonCandidateHarness").none { args.getString(it) == "true" })
        verifiedProbeInstrumentation("tachiaiReadOnlyAbemaOngoingCandidate")
        val phases = HarnessPhases()
        try { observe(phases, Observation.ONGOING_CANDIDATE) }
        catch (_: Throwable) { failHarness(phases, Observation.ONGOING_CANDIDATE) }
    }

    @Test fun ongoingRuntimeDiagnosticsWhitelistCategoriesAndWithholdEveryFieldWithoutProof() {
        for (category in listOf("SCHEDULE_SNAPSHOT", "GUEST_SCHEDULE_RESPONSE", "GUEST_SCHEDULE_TRANSPORT")) {
            assertEquals("runtimeCategory=$category projectionInvoked=true stripSucceeded=false",
                abemaOngoingRuntimeDiagnostic(category, true, false, true))
        }
        for (unknown in listOf("https://synthetic.invalid/private", "GUEST_SCHEDULE_PRIVATE_VALUE", "SCHEDULE_SNAPSHOT\nprivate")) {
            val summary = abemaOngoingRuntimeDiagnostic(unknown, false, false, true)
            assertEquals("runtimeCategory=OTHER projectionInvoked=false stripSucceeded=false", summary)
            assertFalse(summary.contains(unknown))
        }
        assertEquals("runtimeCategory=UNOBSERVED projectionInvoked=false stripSucceeded=false",
            abemaOngoingRuntimeDiagnostic(null, false, false, true))
        assertEquals("runtimeCategory=WITHHELD projectionInvoked=WITHHELD stripSucceeded=WITHHELD",
            abemaOngoingRuntimeDiagnostic("GUEST_SCHEDULE_RESPONSE", true, true, false))
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

    // #622: the content-list projection names its season itself, so the probe
    // reads the unchanged production page instead of injecting test fields.
    @Test fun syntheticContentListPageNamesItsSeasonForTheUnchangedSeriesDecoder() {
        fun projection(season: String? = "${SERIES}_s10") = JSONObject().put("seriesId", SERIES).apply {
            if (season != null) put("seasonId", season).put("seasonName", "ABEMA大相撲2026")
                .put("groups", JSONArray().put(JSONObject().put("id", "${SERIES}_eg1").put("name", "生中継・見逃し"))
                    .put(JSONObject().put("id", "${SERIES}_eg5").put("name", "ハイライト")))
                .put("groupId", "${SERIES}_eg1")
            else put("groups", JSONArray())
        }.put("viewingTypes", JSONArray().apply { if (season != null) put(1) })
            .put("items", JSONArray().apply { if (season != null) {
                put(JSONObject().put("kind", "slot").put("id", "CQ2AnExAX8s7LT").put("channelId", "sumo")
                    .put("title", "Fixture broadcast").put("durationSeconds", 17100))
                put(JSONObject().put("kind", "episode").put("id", "${SERIES}_s10_p8544").put("title", "Fixture episode"))
            } }).put("more", false)
        val page = decodeAbemaSeriesProjection(projection().toString(), AbemaSeriesPageRequest(SERIES))!!
        assertTrue(validSeriesCandidatePage(page))
        assertEquals(SeriesCandidate("${SERIES}_s10", 2, 1, 1), seriesCandidate(page))
        // A series without seasons is an honest absence, not a fabricated parent.
        val bare = decodeAbemaSeriesProjection(projection(season = null).toString(), AbemaSeriesPageRequest(SERIES))!!
        assertTrue(validSeriesCandidatePage(bare))
        assertEquals(SeriesCandidate(null, 0, 0, 0), seriesCandidate(bare))
        // A season or group outside the requested series never decodes.
        assertNull(decodeAbemaSeriesProjection(projection(season = "other-series_s10").toString(), AbemaSeriesPageRequest(SERIES)))
        assertNull(decodeAbemaSeriesProjection(projection().put("seriesId", "other-series").toString(), AbemaSeriesPageRequest(SERIES)))
        assertNull(decodeAbemaSeriesProjection(projection().put("groupId", "other-series_eg1").toString(), AbemaSeriesPageRequest(SERIES)))
    }

    private fun observe(phases: HarnessPhases, observation: Observation = Observation.SERIES_CANDIDATE) {
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val context = instrumentation.targetContext
        val instanceId = defaultProviderInstanceId(PrototypeService.ABEMA)
        val schedule = observation in setOf(Observation.SCHEDULE_SHAPE, Observation.CATCHUP_CANDIDATE, Observation.ONGOING_CANDIDATE)
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
        phases.at(HarnessPhase.PROFILE_SETUP) { instrumentation.runOnMainSync { configureAbemaMetadataRuntimeProfile(context) } }
        val active = AtomicBoolean(); val foreground = IdleForeground(instrumentation, active)
        val environment = CandidateEnvironment(AndroidAbemaMetadataEnvironment(context), observation, ::stableRecords)
        val probe = Probe(environment)
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val result = AtomicReference<AbemaMetadataRuntimeResult?>(); val completed = CountDownLatch(1)
        var admitted = false; var activityFinished = false; val started = SystemClock.elapsedRealtime()
        try {
            phases.at(HarnessPhase.ACTIVITY_LAUNCH) { foreground.launch(context) }
            val activity = phases.at(HarnessPhase.ACTIVITY_ADOPTION) { foreground.adopt() }
            phases.at(HarnessPhase.FOREGROUND_CHECK) {
                var idle = false
                instrumentation.runOnMainSync { idle = !AbemaMetadataRuntimeProcess.get(activity).unavailable }
                check(idle && active.get() && owner.sameOwnership(readOwner()))
            }
            phases.at(HarnessPhase.RUNTIME_ADMISSION) {
                check(retained.compareAndSet(null, probe))
                val request = AbemaMetadataRuntimeRequest(instanceId, observation.operation,
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
        if (observation in setOf(Observation.CATCHUP_CANDIDATE, Observation.ONGOING_CANDIDATE)) {
            val ongoing = observation == Observation.ONGOING_CANDIDATE
            val snapshot = value?.scheduleSnapshot
            val success = value?.category == "SCHEDULE_SNAPSHOT" && snapshot != null && snapshot.entries.size <= 501 &&
                snapshot.entries.all { AbemaSlotRequest.from(it.resource) != null &&
                    it.availability == CatalogAvailability.UNKNOWN && it.scheduledStartEpochMs == null }
            val bounded = SystemClock.elapsedRealtime() - started in 0 until 70_000
            val observed = if (ongoing) environment.ongoingCandidate.getAndSet(null)?.let {
                ScheduleLead(it.eligibleRows, it.candidate)
            } else environment.catchupCandidate.getAndSet(null)?.let { ScheduleLead(it.eligibleRows, it.candidate) }
            val lead = observed.takeIf {
                cleanupConfirmed && unchanged && current && success && bounded && it != null &&
                    (it.candidate == null || snapshot.entries.take(500).any { entry -> entry.resource == it.candidate.resource })
            }
            val outcome = when {
                !cleanupConfirmed -> "CLEANUP_FAILED"; !unchanged -> "STORES_CHANGED"; !current -> "OWNER_CHANGED"
                !bounded -> "BUDGET_REFUSED"; lead == null -> "INVALID_RESPONSE"
                lead.candidate == null -> if (ongoing) "NO_ONGOING_LEAD" else "NO_CATCHUP_LEAD"
                else -> if (ongoing) "ONGOING_PUBLIC_LEAD" else "EXPLICIT_FREE_ENDED_LEAD"
            }
            val runtimeDiagnostic = if (ongoing) " " + abemaOngoingRuntimeDiagnostic(value?.category,
                environment.ongoingProjectionInvoked.get(), environment.ongoingStripSucceeded.get(),
                cleanupConfirmed && unchanged && current && bounded) else ""
            // This public tuple is a lead only. A later operation independently
            // checks its exact current occurrence/source; no rights are inferred.
            Log.i(observation.tag, "outcome=$outcome eligibleRows=${lead?.eligibleRows ?: 0} " +
                "candidateChannel=${lead?.candidate?.channelId ?: "NONE"} candidateSlot=${lead?.candidate?.slotId ?: "NONE"} " +
                "sdk=${Build.VERSION.SDK_INT} routeMode=${owner.instance.setup.route!!.mode.name} " +
                "accountFailure=${environment.accountFailure.get()?.name ?: "NONE"} " +
                "preparationRefused=${value?.category == "PREPARATION_REFUSED"}$runtimeDiagnostic")
            phases.at(HarnessPhase.RESULT_CHECK) {
                val label = if (ongoing) "Ongoing" else "Catchup"
                assertTrue("$label candidate observation exceeded bounded budgets", bounded)
                assertTrue("$label candidate observation changed protected records", unchanged)
                assertTrue("$label candidate observation changed captured owner", current)
                assertTrue("$label candidate observation did not confirm cleanup", cleanupConfirmed)
                assertTrue("$label candidate observation did not return normal metadata", success)
                assertNotNull("$label candidate observation did not return a closed marker", lead)
            }
            phases.current.set(HarnessPhase.COMPLETE)
            return
        }
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
        val success = value?.category == "EXACT_SERIES_PAGE" && page != null && validSeriesCandidatePage(page)
        val candidate = page?.takeIf { cleanupConfirmed && unchanged && current && success }?.let(::seriesCandidate)
        val outcome = when {
            !cleanupConfirmed -> "CLEANUP_FAILED"; !unchanged -> "STORES_CHANGED"; !current -> "OWNER_CHANGED"
            success -> "EXACT_SERIES_PAGE"; else -> "INVALID_RESPONSE"
        }
        Log.i("TachiaiAbemaSeasonCandidateProbe", "outcome=$outcome count=${page?.entries?.size ?: 0} " +
            "candidateSeries=${if (candidate?.seasonId != null) SERIES else "NONE"} candidateSeason=${candidate?.seasonId ?: "NONE"} " +
            "candidateEvidence=${if (candidate?.seasonId != null) "CONTENTLIST_SEASON" else "NONE"} " +
            "groups=${candidate?.groups ?: 0} episodes=${candidate?.episodes ?: 0} slots=${candidate?.slots ?: 0} " +
            "sdk=${Build.VERSION.SDK_INT} routeMode=${owner.instance.setup.route!!.mode.name}")
        phases.at(HarnessPhase.RESULT_CHECK) {
            assertTrue("Public candidate observation exceeded bounded budgets", SystemClock.elapsedRealtime() - started in 0 until 70_000)
            assertTrue("Public candidate observation changed protected records", unchanged)
            assertTrue("Public candidate observation changed captured owner", current)
            assertTrue("Public candidate observation did not confirm cleanup", cleanupConfirmed)
            assertTrue("Public candidate observation did not return exact normalized metadata", success)
            assertNotNull("Public candidate observation did not return a closed summary", candidate)
        }
        phases.current.set(HarnessPhase.COMPLETE)
        // A valid Series page without a valid Season proof is honest absence,
        // never acceptance of a fabricated parent or an automatic retry.
    }

    private enum class Observation(val operation: AbemaMetadataRuntimeOperation, val tag: String) {
        SERIES_CANDIDATE(AbemaMetadataRuntimeOperation.FETCH_SERIES, "TachiaiAbemaSeasonCandidateProbe"),
        SLOT_SHAPE(AbemaMetadataRuntimeOperation.FETCH_SLOT, "TachiaiAbemaSlotShapeProbe"),
        SCHEDULE_SHAPE(AbemaMetadataRuntimeOperation.FETCH_SCHEDULE, "TachiaiAbemaScheduleShapeProbe"),
        CATCHUP_CANDIDATE(AbemaMetadataRuntimeOperation.FETCH_SCHEDULE, "TachiaiAbemaCatchupCandidateProbe"),
        ONGOING_CANDIDATE(AbemaMetadataRuntimeOperation.FETCH_SCHEDULE, "TachiaiAbemaOngoingCandidateProbe"),
    }

    private class ScheduleLead(val eligibleRows: Int, val candidate: AbemaSlotRequest?) {
        override fun toString() = "ScheduleLead(redacted)"
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
        val runtime = AbemaMetadataRuntimeCoordinator(environment, AndroidAbemaMetadataMain(), worker, cleanup, SystemClock::elapsedRealtime)
    }

    private class CandidateEnvironment(private val base: AbemaMetadataRuntimeEnvironment, private val observation: Observation,
        private val stableRecords: () -> Unit = {}) : AbemaMetadataRuntimeEnvironment by base {
        val slotShapes = AtomicReference<SlotShapes?>()
        val scheduleShapes = AtomicReference<AbemaScheduleShapeObservation.Counts?>()
        val catchupCandidate = AtomicReference<AbemaScheduleCatchupCandidateObservation.Stripped?>()
        val ongoingCandidate = AtomicReference<AbemaScheduleOngoingCandidateObservation.Stripped?>()
        val ongoingProjectionInvoked = AtomicBoolean()
        val ongoingStripSucceeded = AtomicBoolean()
        val accountFailure = AtomicReference<AbemaAccountFailure?>()
        private val scheduleWallMs = if (observation in setOf(Observation.SCHEDULE_SHAPE, Observation.CATCHUP_CANDIDATE, Observation.ONGOING_CANDIDATE))
            System.currentTimeMillis() else null
        override fun report(error: Throwable) {
            if (observation in setOf(Observation.CATCHUP_CANDIDATE, Observation.ONGOING_CANDIDATE) && error is AbemaAccountException)
                accountFailure.compareAndSet(null, error.category)
            base.report(error)
        }
        override fun readOwner(instanceId: String): AbemaMetadataRuntimeOwner {
            stableRecords(); return base.readOwner(instanceId).also { stableRecords() }
        }
        override fun sameOwner(owner: AbemaMetadataRuntimeOwner): Boolean {
            stableRecords(); return base.sameOwner(owner).also { stableRecords() }
        }
        override fun prepare(owner: AbemaMetadataRuntimeOwner, request: AbemaMetadataRuntimeRequest,
            preparation: AbemaMetadataRuntimePreparation, canRequest: () -> Boolean): Map<String, ByteArray> {
            check(request.operation == observation.operation && when (observation) {
                Observation.SERIES_CANDIDATE -> request.seriesRequest?.let {
                    it.seriesId == SERIES && it.offset == 0 && it.groupId == null && it.seasonId == null
                } == true
                Observation.SLOT_SHAPE -> request.slotRequest?.let { it.channelId == SLOT_CHANNEL && it.slotId == SLOT_ID } == true
                Observation.SCHEDULE_SHAPE -> request.seriesRequest == null && request.slotRequest == null && request.searchRequest == null && request.episodeId == null
                Observation.CATCHUP_CANDIDATE -> request.seriesRequest == null && request.slotRequest == null && request.searchRequest == null && request.episodeId == null
                Observation.ONGOING_CANDIDATE -> request.seriesRequest == null && request.slotRequest == null && request.searchRequest == null && request.episodeId == null
            })
            val files = base.prepare(owner, request, preparation, canRequest)
            // The Series page is observed through the unchanged production projection.
            if (observation == Observation.SERIES_CANDIDATE) return files
            val source = files.getValue(CONTROL_PATH).toString(Charsets.UTF_8)
            val anchor = when (observation) {
                Observation.SERIES_CANDIDATE -> error("unreachable"); Observation.SLOT_SHAPE -> SLOT_PROJECTION
                Observation.SCHEDULE_SHAPE -> AbemaScheduleShapeObservation.ANCHOR
                Observation.CATCHUP_CANDIDATE -> AbemaScheduleCatchupCandidateObservation.ANCHOR
                Observation.ONGOING_CANDIDATE -> AbemaScheduleOngoingCandidateObservation.ANCHOR
            }
            val projection = when (observation) {
                Observation.SERIES_CANDIDATE -> error("unreachable"); Observation.SLOT_SHAPE -> TEST_SLOT_PROJECTION
                Observation.SCHEDULE_SHAPE -> AbemaScheduleShapeObservation.projection(checkNotNull(scheduleWallMs))
                Observation.CATCHUP_CANDIDATE -> AbemaScheduleCatchupCandidateObservation.projection(checkNotNull(scheduleWallMs))
                Observation.ONGOING_CANDIDATE -> AbemaScheduleOngoingCandidateObservation.projection(checkNotNull(scheduleWallMs))
            }
            check(source.length <= 128 * 1024 && source.split(anchor).size == 2)
            return files + (CONTROL_PATH to source.replace(anchor, projection).toByteArray(Charsets.UTF_8))
        }
        override fun newView(request: AbemaMetadataRuntimeRequest, preparation: AbemaMetadataRuntimePreparation,
            files: Map<String, ByteArray>, canRequest: () -> Boolean, currentView: (AbemaMetadataRuntimeView) -> Boolean,
            failed: (AbemaMetadataRuntimeView, String) -> Unit): AbemaMetadataRuntimeView {
            lateinit var wrapped: AbemaMetadataRuntimeView
            val actual = base.newView(request, preparation, files, canRequest,
                { currentView(wrapped) }, { _, category -> failed(wrapped, category) })
            wrapped = object : AbemaMetadataRuntimeView by actual {
                override fun metadata(callback: (String?) -> Unit) = actual.metadata { raw ->
                    if (observation == Observation.SERIES_CANDIDATE) {
                        callback(raw)
                    } else if (observation == Observation.SLOT_SHAPE) {
                        val stripped = stripSlotProjection(raw)
                        slotShapes.set(stripped?.shapes)
                        callback(stripped?.raw)
                    } else if (observation == Observation.SCHEDULE_SHAPE) {
                        val stripped = AbemaScheduleShapeObservation.strip(raw)
                        scheduleShapes.set(stripped?.counts)
                        callback(stripped?.raw)
                    } else if (observation == Observation.CATCHUP_CANDIDATE) {
                        val stripped = AbemaScheduleCatchupCandidateObservation.strip(raw)
                        catchupCandidate.set(stripped)
                        callback(stripped?.raw)
                    } else {
                        ongoingProjectionInvoked.set(true)
                        val stripped = AbemaScheduleOngoingCandidateObservation.strip(raw)
                        ongoingStripSucceeded.set(stripped != null)
                        ongoingCandidate.set(stripped)
                        callback(stripped?.raw)
                    }
                }
            }
            return wrapped
        }
    }

    // Public identities and closed counts only; titles never leave the page.
    private data class SeriesCandidate(val seasonId: String?, val groups: Int, val episodes: Int, val slots: Int)
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
        private const val SLOT_SHAPE_FIELD = "tachiaiTestSlotShape"
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
        private fun validSeriesCandidatePage(page: AbemaSeriesPage) = page.seriesId == SERIES && page.groups != null &&
            page.entries.size <= 20 && page.entries.map { it.resource }.distinct().size == page.entries.size && page.entries.all {
                (it.resource.kind == "episode" && it.resource.intent == CatalogIntent.VIDEO && validAbemaSeriesId(it.resource.identity) ||
                    AbemaSlotRequest.from(it.resource) != null) && safeAbemaSeriesText(it.title, 160) &&
                    it.availability == CatalogAvailability.UNKNOWN && it.scheduledStartEpochMs == null
            }
        // The season the content list itself names for this series, if any.
        private fun seriesCandidate(page: AbemaSeriesPage) = SeriesCandidate(page.seasonId, page.groups.orEmpty().size,
            page.entries.count { it.resource.kind == "episode" }, page.entries.count { it.resource.kind == "slot" })

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

// Exact runtime outcomes only; provider strings and unknown future categories
// cannot become diagnostic text. Publication proof is independent of success.
internal fun abemaOngoingRuntimeDiagnostic(category: String?, projectionInvoked: Boolean,
    stripSucceeded: Boolean, mayPublish: Boolean): String {
    if (!mayPublish) return "runtimeCategory=WITHHELD projectionInvoked=WITHHELD stripSucceeded=WITHHELD"
    val allowed = setOf("SCHEDULE_SNAPSHOT", "PREPARATION_REFUSED", "RUNTIME_REFUSED", "ROUTE_REFUSED",
        "OWNER_CHANGED", "TIMEOUT", "CANCELLED", "CLEANUP_FAILED", "STATUS_REFUSED", "NAVIGATION_REFUSED",
        "RESOURCE_REFUSED", "TLS_REFUSED", "RENDERER_GONE", "GUEST_HTTP", "GUEST_RESPONSE", "GUEST_POLICY",
        "GUEST_RUNTIME", "GUEST_TIMEOUT", "GUEST_SCHEDULE_RESPONSE", "GUEST_SCHEDULE_TIMEOUT",
        "GUEST_SCHEDULE_TRANSPORT", "GUEST_SCHEDULE_REDIRECT", "GUEST_SCHEDULE_HTTP_401", "GUEST_SCHEDULE_HTTP_403",
        "GUEST_SCHEDULE_HTTP_404", "GUEST_SCHEDULE_HTTP_410", "GUEST_SCHEDULE_HTTP_429", "GUEST_SCHEDULE_HTTP_3XX",
        "GUEST_SCHEDULE_HTTP_4XX", "GUEST_SCHEDULE_HTTP_5XX", "GUEST_SCHEDULE_HTTP_OTHER")
    val closed = if (category == null) "UNOBSERVED" else category.takeIf { it in allowed } ?: "OTHER"
    return "runtimeCategory=$closed projectionInvoked=$projectionInvoked stripSucceeded=$stripSucceeded"
}
