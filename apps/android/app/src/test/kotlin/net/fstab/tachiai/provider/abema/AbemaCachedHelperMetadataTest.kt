package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.platform.diagnostics.FailureCategory
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AbemaCachedHelperMetadataTest {
    @Test fun slotRefusalPhasesRemainClosedAndCannotPublishPreparation() {
        for (phase in listOf("ACCOUNT", "SLOT_REGION", "SLOT_REGION_PAGE", "SLOT_REGION_CDN",
            "SLOT_FREE", "SLOT_RESTRICTIONS", "SLOT_WINDOW")) {
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

    private val channelPhases = listOf("CHANNEL_REQUEST", "CHANNEL_RESPONSE", "CHANNEL_ENVELOPE_REFUSED",
        "CHANNEL_SELECTION_REFUSED", "CHANNEL_UNAVAILABLE", "CHANNEL_DRM_REFUSED", "CHANNEL_PLAYLIST_REFUSED",
        "CHANNEL_ALTERNATE_REFUSED", "CHANNEL_REGION", "CHANNEL_REGION_PAGE", "CHANNEL_REGION_CDN",
        "CHANNEL_DEVICE", "CHANNEL_CONTEXT_REFUSED")

    @Test fun returnedRegionCauseAndUnfinishedPrefixSurviveNativeTeardownWithoutInventingHelperTimeout() {
        for (returned in listOf(metadata("NONE", 0, "SLOT_REGION_CDN"),
            metadata("HTTP_REFUSED", 403, "SLOT_REGION_CDN"), metadata("TIMER_EXPIRED", 0, "SLOT_REGION_PAGE"))) {
            val evidence = AbemaChannelPreparationEvidence()
            val actual = checkNotNull(parseAbemaCachedHelperMetadata(returned))
            evidence.observe(actual); evidence.seal()
            for (late in listOf(metadata(), metadata("OWNED_CANCELLED", 0, "FAILED")))
                evidence.observe(checkNotNull(parseAbemaCachedHelperMetadata(late)))
            evidence.refuse(); evidence.seal()
            assertSame(actual, evidence.snapshot)
        }
        val absent = AbemaChannelPreparationEvidence(); absent.seal()
        absent.observe(checkNotNull(parseAbemaCachedHelperMetadata(metadata())))
        assertNull(absent.snapshot)
        val invalid = AbemaChannelPreparationEvidence()
        invalid.observe(checkNotNull(parseAbemaCachedHelperMetadata(metadata("NONE", 200, "SLOT_REGION_PAGE"))))
        invalid.refuse(); invalid.seal()
        invalid.observe(checkNotNull(parseAbemaCachedHelperMetadata(metadata())))
        assertNull(invalid.snapshot)
    }

    @Test fun channelPhasesParseCanonicalAndLegacyShapesWithoutChangingPreparationOrHttpGates() {
        for (phase in channelPhases) {
            for ((failure, http) in listOf("NONE" to 0, "NONE" to 200,
                "HTTP_REFUSED" to 403, "RESPONSE_VALIDATION_REFUSED" to 200)) {
                val text = metadata(failure, http, phase)
                val value = checkNotNull(parseAbemaCachedHelperMetadata(text))
                assertEquals(phase, value.phase); assertEquals(http, value.http)
                assertEquals(value, parseAbemaCachedHelperMetadataCallback(callback(text)))
                assertFalse(AbemaCachedHelperEvidence().permitsPreparation("READY", value))
            }
            val legacy = checkNotNull(parseAbemaCachedHelperMetadata(metadata(null, 403, phase)))
            assertEquals(AbemaCachedHelperFailure.NONE, legacy.failure)
            assertEquals(legacy, parseAbemaCachedHelperMetadataCallback(callback(metadata(null, 403, phase))))
            for ((failure, http) in listOf("NONE" to 403, "HTTP_REFUSED" to 200,
                "FETCH_REJECTED_BEFORE_HTTP" to 200, "RESPONSE_VALIDATION_REFUSED" to 0))
                assertNull(parseAbemaCachedHelperMetadata(metadata(failure, http, phase)))
        }
        val ready = checkNotNull(parseAbemaCachedHelperMetadata(metadata()))
        assertTrue(AbemaCachedHelperEvidence().permitsPreparation("READY", ready))
    }

    @Test fun channelMetadataStillRejectsUnknownEscapedDuplicatedExtraRawOrUnboundedFields() {
        val valid = metadata("RESPONSE_VALIDATION_REFUSED", 200, "CHANNEL_ENVELOPE_REFUSED")
        for (text in listOf(valid.replace("CHANNEL_ENVELOPE_REFUSED", "CHANNEL_PRIVATE_TOKEN"),
            valid.replace("CHANNEL_ENVELOPE_REFUSED", "CHANNEL_ENVELOPE_REF\\u0055SED"),
            valid.dropLast(1) + ",\"phase\":\"CHANNEL_RESPONSE\"}",
            valid.dropLast(1) + ",\"body\":\"PRIVATE_PROVIDER_BODY\"}",
            valid.dropLast(1) + ",\"url\":\"https://private.invalid/?token=secret\"}",
            valid.replace("\"http\":200", "\"http\":\"200\""), valid.replace("24968", "1000001"),
            valid.replace("50", "4097"), valid.replace("200", "600"))) {
            assertNull(parseAbemaCachedHelperMetadata(text))
            assertNull(parseAbemaCachedHelperMetadataCallback(callback(text)))
        }
        assertNull(parseAbemaCachedHelperMetadataCallback(callback(valid).replace("\\\"", "\\u0022")))
    }

    @Test fun channelObservationStartsEmptyAndKeepsLatestSafePrefixWithoutPublishingAuthority() {
        val evidence = AbemaChannelPreparationEvidence()
        assertNull(evidence.snapshot)
        val request = checkNotNull(parseAbemaCachedHelperMetadata(metadata("NONE", 0, "CHANNEL_REQUEST")))
        val response = checkNotNull(parseAbemaCachedHelperMetadata(metadata("NONE", 200, "CHANNEL_RESPONSE")))
        evidence.observe(request); assertSame(request, evidence.snapshot)
        evidence.observe(response); assertSame(response, evidence.snapshot)
        assertFalse(AbemaCachedHelperEvidence().permitsPreparation("READY", evidence.snapshot))
        val ready = checkNotNull(parseAbemaCachedHelperMetadata(metadata()))
        evidence.observe(ready); assertSame(ready, evidence.snapshot)
    }

    @Test fun channelFirstFailureFreezesFullSnapshotAcrossOtherFailuresReadyAndCancellation() {
        val evidence = AbemaChannelPreparationEvidence()
        val failed = checkNotNull(parseAbemaCachedHelperMetadata(metadata("HTTP_REFUSED", 403, "CHANNEL_REQUEST")))
        evidence.observe(failed)
        for (text in listOf(metadata(), metadata("NONE", 200, "CHANNEL_RESPONSE"),
            metadata("OWNED_CANCELLED", 0, "FAILED"), metadata("RESPONSE_VALIDATION_REFUSED", 200, "CHANNEL_DRM_REFUSED"))) {
            evidence.observe(checkNotNull(parseAbemaCachedHelperMetadata(text)))
            assertSame(failed, evidence.snapshot)
        }
        val changedCopy = failed.copy(phase = "CHANNEL_RESPONSE", http = 200, failure = AbemaCachedHelperFailure.NONE)
        assertNotEquals(changedCopy, evidence.snapshot)
        assertEquals("phase=CHANNEL_REQUEST modules=50 lastModule=24968 http=403 failure=HTTP_REFUSED",
            checkNotNull(evidence.snapshot).closedSummary())
    }

    @Test fun channelParseRefusalPermanentlyWithholdsPrefixFailureAndFutureSnapshots() {
        for (prefix in listOf<String?>(null, metadata("NONE", 0, "CHANNEL_REQUEST"),
            metadata("RESPONSE_VALIDATION_REFUSED", 200, "CHANNEL_SELECTION_REFUSED"))) {
            val evidence = AbemaChannelPreparationEvidence()
            prefix?.let { evidence.observe(checkNotNull(parseAbemaCachedHelperMetadata(it))) }
            assertNull(parseAbemaCachedHelperMetadata("PRIVATE_PROVIDER_CONTENT"))
            evidence.refuse(); assertNull(evidence.snapshot)
            evidence.observe(checkNotNull(parseAbemaCachedHelperMetadata(metadata())))
            evidence.refuse(); assertNull(evidence.snapshot)
        }
    }

    @Test fun concurrentRefusalCannotBeOverwrittenByLateAlreadyParsedMetadata() {
        val evidence = AbemaChannelPreparationEvidence()
        val ready = checkNotNull(parseAbemaCachedHelperMetadata(metadata()))
        val response = checkNotNull(parseAbemaCachedHelperMetadata(metadata("NONE", 200, "CHANNEL_RESPONSE")))
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val failed = AtomicReference<Throwable?>()
        val writers = listOf(Thread {
            try { check(start.await(2, TimeUnit.SECONDS)); repeat(100) { evidence.observe(response) } }
            catch (error: Throwable) { failed.compareAndSet(null, error) }
            finally { done.countDown() }
        }, Thread {
            try { check(start.await(2, TimeUnit.SECONDS)); evidence.refuse(); repeat(100) { evidence.observe(ready) } }
            catch (error: Throwable) { failed.compareAndSet(null, error) }
            finally { done.countDown() }
        })
        writers.forEach { it.start() }; start.countDown()
        assertTrue(done.await(3, TimeUnit.SECONDS))
        writers.forEach { it.join(1000) }
        assertNull(failed.get())
        assertNull(evidence.snapshot)
    }
}
