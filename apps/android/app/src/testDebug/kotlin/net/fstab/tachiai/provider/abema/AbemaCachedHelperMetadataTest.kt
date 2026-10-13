package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.platform.diagnostics.FailureCategory
import org.junit.Assert.*
import org.junit.Test

class AbemaCachedHelperMetadataTest {
    @Test fun slotRefusalPhasesRemainClosedAndCannotPublishPreparation() {
        for (phase in listOf("ACCOUNT", "SLOT_REGION", "SLOT_FREE", "SLOT_RESTRICTIONS", "SLOT_WINDOW")) {
            val value = checkNotNull(parseAbemaCachedHelperMetadata(metadata("UNKNOWN", 200, phase)))
            assertEquals(phase, value.phase)
            val evidence = AbemaCachedHelperEvidence()
            assertTrue(evidence.observe(value))
            assertFalse(evidence.permitsPreparation("READY", value))
            assertFalse(evidence.permitsPreparation("READY", checkNotNull(parseAbemaCachedHelperMetadata(metadata()))))
        }
    }

    private fun metadata(failure: String? = "NONE", http: Int = 200, phase: String = "READY") =
        "{\"phase\":\"$phase\",\"modules\":50,\"lastModule\":24968,\"http\":$http" +
            (failure?.let { ",\"failure\":\"$it\"" } ?: "") + "}"
    private fun callback(value: String) = "\"" + value.replace("\"", "\\\"") + "\""

    @Test fun canonicalOldAndNewProjectionsRemainBoundedAndTyped() {
        for (failure in AbemaCachedHelperFailure.entries) {
            val http = when (failure) {
                AbemaCachedHelperFailure.FETCH_REJECTED_BEFORE_HTTP -> 0
                AbemaCachedHelperFailure.HTTP_REFUSED -> 403
                else -> 200
            }
            val text = metadata(failure.name, http)
            val result = checkNotNull(parseAbemaCachedHelperMetadata(text))
            assertEquals(failure, result.failure)
            assertEquals(result, parseAbemaCachedHelperMetadataCallback(callback(text)))
            assertEquals("phase=READY modules=50 lastModule=24968 http=$http failure=${failure.name}", result.closedSummary())
        }
        val legacy = checkNotNull(parseAbemaCachedHelperMetadata(metadata(null, 403, "GUEST")))
        assertEquals(AbemaCachedHelperFailure.NONE, legacy.failure)
        assertEquals(403, legacy.http)
        assertEquals(legacy, parseAbemaCachedHelperMetadataCallback(callback(metadata(null, 403, "GUEST"))))
    }

    @Test fun duplicatesExtrasCoercionsUnknownsAndNoncanonicalEncodingsRefuseWithoutEvidence() {
        val valid = metadata()
        for (text in listOf(valid + "x", " " + valid, valid.replace("50", "\"50\""), valid.replace("50", "50.0"),
            valid.replace("50", "5e1"), valid.replace("50", "050"), valid.replace("50", "true"),
            valid.replace("50", "null"), valid.replace("50", "-1"), valid.replace("50", "4097"),
            valid.replace("24968", "1000001"), valid.replace("200", "99"), valid.replace("200", "600"),
            valid.replace("READY", "PRIVATE_TOKEN"), valid.replace("NONE", "PRIVATE_SECRET"),
            valid.replace("READY", "R\\u0045ADY"), valid.dropLast(1) + ",\"http\":200}",
            valid.dropLast(1) + ",\"url\":\"https://private.invalid/?token=secret\"}",
            valid.replace("\"modules\":50,\"lastModule\":24968", "\"lastModule\":24968,\"modules\":50"),
            "x".repeat(513))) assertNull(text, parseAbemaCachedHelperMetadata(text))
        for (raw in listOf(valid, "null", callback(valid) + " trailing", callback(valid).replace("READY", "R\\u0045ADY"),
            callback(valid).replace("\\\"", "\\u0022"), "x".repeat(1025)))
            assertNull(parseAbemaCachedHelperMetadataCallback(raw))
    }

    @Test fun impossibleReturnedStatusCategoryCombinationsRefuse() {
        for ((failure, statuses) in listOf("NONE" to listOf(201, 403), "FETCH_REJECTED_BEFORE_HTTP" to listOf(200, 403),
            "HTTP_REFUSED" to listOf(0, 200), "RESPONSE_VALIDATION_REFUSED" to listOf(0, 403),
            "TIMER_EXPIRED" to listOf(403), "OWNED_CANCELLED" to listOf(403), "UNKNOWN" to listOf(403)))
            for (http in statuses) assertNull(parseAbemaCachedHelperMetadata(metadata(failure, http)))
    }

    @Test fun firstProvedFailureSurvivesStaleReadyAndCleanupAndFeedsRemainIndependent() {
        val first = AbemaCachedHelperEvidence(); val second = AbemaCachedHelperEvidence()
        val ready = checkNotNull(parseAbemaCachedHelperMetadata(metadata()))
        val failed = checkNotNull(parseAbemaCachedHelperMetadata(metadata("TIMER_EXPIRED", 0, "GUEST")))
        assertTrue(first.permitsPreparation("READY", ready))
        assertFalse(first.permitsPreparation("WAITING_SOURCE", ready))
        assertFalse(first.permitsPreparation("READY", null))
        assertTrue(first.observe(failed))
        assertFalse(first.permitsPreparation("READY", failed))
        assertFalse(first.observe(ready)); assertFalse(first.permitsPreparation("READY", ready))
        assertFalse(first.observe(checkNotNull(parseAbemaCachedHelperMetadata(metadata("OWNED_CANCELLED", 0, "GUEST")))))
        assertEquals(AbemaCachedHelperFailure.TIMER_EXPIRED, first.firstFailure)
        assertTrue(second.permitsPreparation("READY", ready))
        assertFalse(first.permitsPreparation("READY", checkNotNull(parseAbemaCachedHelperMetadata(metadata(null, 403)))))
    }

    @Test fun everyClosedHelperFailureMapsExplicitlyWithoutProviderText() {
        val expected = listOf(FailureCategory.NONE, FailureCategory.ABEMA_CACHED_TIMER_EXPIRED,
            FailureCategory.ABEMA_CACHED_OWNED_CANCELLED, FailureCategory.ABEMA_CACHED_FETCH_REJECTED_BEFORE_HTTP,
            FailureCategory.ABEMA_CACHED_HTTP_REFUSED, FailureCategory.ABEMA_CACHED_RESPONSE_VALIDATION_REFUSED,
            FailureCategory.ABEMA_CACHED_UNKNOWN)
        assertEquals(expected, AbemaCachedHelperFailure.entries.map { it.diagnosticCategory() })
    }

    @Test fun localOwnerCancellationIsTypedStickyAndCannotAdmitALateReadyWithoutPostCloseReads() {
        val evidence = AbemaCachedHelperEvidence()
        assertFalse(evidence.remember(AbemaCachedHelperFailure.NONE))
        assertTrue(evidence.remember(AbemaCachedHelperFailure.OWNED_CANCELLED))
        assertFalse(evidence.observe(checkNotNull(parseAbemaCachedHelperMetadata(metadata("TIMER_EXPIRED", 0, "GUEST")))))
        assertFalse(evidence.permitsPreparation("READY", checkNotNull(parseAbemaCachedHelperMetadata(metadata()))))
        assertEquals(AbemaCachedHelperFailure.OWNED_CANCELLED, evidence.firstFailure)
    }
}
