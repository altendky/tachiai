package net.fstab.tachiai.provider.abema

import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class AbemaSlotMetadataTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    private fun slot(channel: String = "channel-A", slot: String = "slot-A") = AbemaSlotRequest(channel, slot).resource
    private inner class Fixture {
        var allowed = true; var calls = 0; var closes = 0; var cancels = 0; var snapshotCalls = 0
        var afterClose: () -> Unit = {}
        var closeFailure = false
        var owner: () -> Boolean = { allowed }
        var response: (CatalogResource) -> CatalogResult<CatalogEntry> = { CatalogResult.Value(CatalogEntry(it, "Future slot")) }
        val catalog = AbemaChannelCatalog(id, { object : AbemaChannelTransport {
            override fun fetch(): AbemaChannelResponse { snapshotCalls++; return AbemaChannelResponse(200) }
            override fun close() = Unit
        } }, canPublish = { owner() }, wallMs = { 1000 }, slotTransportFactory = { gate -> object : AbemaSlotTransport {
            override fun fetch(resource: CatalogResource): CatalogResult<CatalogEntry> {
                check(gate()); calls++; return response(resource)
            }
            override fun cancelActiveRequest() { cancels++ }
            override fun close() { closes++; afterClose(); if (closeFailure) throw AssertionError("PRIVATE_CLEANUP") }
        } })
    }
    @Test fun exactTwoSlotsUseOnlySlotTransportAndRemainUnknownWithoutPlaybackResolution() {
        val f = Fixture()
        for (resource in listOf(slot(), slot("channel-B", "slot-B"))) {
            val value = (f.catalog.refresh(resource) as CatalogResult.Value).value
            assertEquals(resource, value.resource); assertEquals(CatalogAvailability.UNKNOWN, value.availability)
            assertNull(value.scheduledStartEpochMs)
            assertEquals(CatalogFailure.NOT_VERIFIED, (f.catalog.resolve(resource) as CatalogResult.Failure).reason)
        }
        assertEquals(2, f.calls); assertEquals(2, f.closes); assertEquals(0, f.snapshotCalls)
        assertEquals(slot(), parseAbemaPublicResource("https://abema.tv/channels/channel-A/slots/slot-A")!!.resource)
    }
    @Test fun wrongProviderKindIntentMalformedAndMissingFactoryCannotDispatch() {
        val f = Fixture()
        for (resource in listOf(CatalogResource(ProviderId("twitch"), "slot", "channel-A/slot-A", CatalogIntent.BROADCAST),
            slot().copy(intent = CatalogIntent.CHANNEL), slot().copy(identity = "slot-A"), slot().copy(identity = "a/b/c"),
            slot().copy(identity = "a/" + "b".repeat(65)))) {
            assertTrue(f.catalog.refresh(resource) is CatalogResult.Failure)
        }
        assertEquals(0, f.calls); assertEquals(0, f.snapshotCalls)
        val absent = AbemaChannelCatalog(id, { error("No channel request") })
        assertEquals(CatalogFailure.NOT_VERIFIED, (absent.refresh(slot()) as CatalogResult.Failure).reason)
        assertNull(AbemaSlotRequest.from(slot().copy(kind = "slot_group")))
    }
    @Test fun wrongIdentityTitleAvailabilityOrScheduleRefuseAndProviderFailuresStayConservative() {
        val f = Fixture()
        for (entry in listOf(CatalogEntry(slot("other"), "Title"),
            CatalogEntry(slot(), "\ud800"), CatalogEntry(slot(), "Title", CatalogAvailability.LIVE),
            CatalogEntry(slot(), "Title", scheduledStartEpochMs = 2000))) {
            f.response = { CatalogResult.Value(entry) }
            assertEquals(CatalogFailure.TEMPORARY, (f.catalog.refresh(slot()) as CatalogResult.Failure).reason)
        }
        for (failure in listOf(CatalogFailure.NOT_FOUND, CatalogFailure.NOT_VERIFIED, CatalogFailure.TEMPORARY)) {
            f.response = { CatalogResult.Failure(failure) }
            assertEquals(failure, (f.catalog.refresh(slot()) as CatalogResult.Failure).reason)
        }
        f.response = { CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED) }
        assertEquals(CatalogFailure.TEMPORARY, (f.catalog.refresh(slot()) as CatalogResult.Failure).reason)
        f.response = { CatalogResult.Failure(CatalogFailure.RATE_LIMITED, Long.MAX_VALUE) }
        assertEquals(86_401_000L, (f.catalog.refresh(slot()) as CatalogResult.Failure).retryAtEpochMs)
        f.response = { throw AssertionError("PRIVATE_FETCH") }
        assertEquals(CatalogFailure.TEMPORARY, (f.catalog.refresh(slot()) as CatalogResult.Failure).reason)
        assertEquals(f.calls, f.closes)
    }
    @Test fun closeAndOwnerLossDuringHeldFinalProtectedReadCannotPublishMetadata() {
        for (close in listOf(false, true)) {
            val f = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
            var checks = 0
            f.afterClose = { f.owner = {
                if (++checks == 2) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                f.allowed
            } }
            val result = AtomicReference<CatalogResult<CatalogEntry>>()
            val worker = Thread { result.set(f.catalog.refresh(slot())) }
            try {
                worker.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
                if (close) f.catalog.close() else f.allowed = false
                // Owner loss is observed by the protected result; closure is
                // additionally caught by the cheap post-read admission check.
                release.countDown(); worker.join(5000); assertFalse(worker.isAlive)
                assertEquals(CatalogFailure.TEMPORARY, (result.get() as CatalogResult.Failure).reason)
            } finally { release.countDown(); worker.join(5000); f.catalog.close() }
        }
    }
    @Test fun uncertainCleanupPoisonsAndCannotBeLaunderedByLaterSuccess() {
        val f = Fixture(); f.closeFailure = true
        assertEquals(CatalogFailure.TEMPORARY, (f.catalog.refresh(slot()) as CatalogResult.Failure).reason)
        f.closeFailure = false
        assertEquals(CatalogFailure.TEMPORARY, (f.catalog.refresh(slot()) as CatalogResult.Failure).reason)
        assertEquals(1, f.calls); assertEquals(CatalogAccess.NOT_VERIFIED, f.catalog.capabilities().refresh)
    }
    private fun startFields() = mapOf("nonce" to "a".repeat(32), "instance" to id, "channel" to "channel-A",
        "slot" to "slot-A", "proof" to "b".repeat(64), "deadline" to 46000L)
    @Test fun closedSlotProtocolRejectsCrossModeStartsResultsExtrasAndChangedParent() {
        val start = AbemaSlotMetadataIpc.decodeStart(startFields(), 1000)!!
        assertEquals(slot(), start.slot.resource)
        assertNull(AbemaEpisodeMetadataIpc.decodeStart(startFields(), 1000))
        val episodeFields = startFields() - "channel" - "slot" + ("episode" to "slot-A")
        assertNotNull(AbemaEpisodeMetadataIpc.decodeStart(episodeFields, 1000))
        assertNull(AbemaSlotMetadataIpc.decodeStart(episodeFields, 1000))
        for (bad in listOf(startFields() + ("slot" to "a".repeat(65)), startFields() + ("deadline" to 46001L),
            startFields() + ("channel" to "../bad"), startFields() + ("url" to "private")))
            assertNull(AbemaSlotMetadataIpc.decodeStart(bad, 1000))
        val success = mapOf("nonce" to start.nonce, "outcome" to "OK", "channel" to "channel-A", "slot" to "slot-A", "title" to "Title")
        assertNotNull(AbemaSlotMetadataIpc.decodeResult(success, start.slot))
        assertNull(AbemaEpisodeMetadataIpc.decodeResult(success))
        assertNull(AbemaSlotMetadataIpc.decodeResult(success, AbemaSlotRequest("other", "slot-A")))
        assertNull(AbemaSlotMetadataIpc.decodeResult(success + ("outcome" to "TEMPORARY"), start.slot))
        assertNull(AbemaSlotMetadataIpc.decodeResult(success + ("title" to "\u202eprivate"), start.slot))
    }
    @Test fun sharedClientFinalProtectedReadCannotOverrideCancelCloseForegroundOrExpiry() {
        for (mode in 0..3) {
            val now = AtomicLong(1000); val open = AtomicBoolean(true); val foreground = AtomicBoolean(true)
            val request = AbemaEpisodeMetadataIpc.Start("a".repeat(32), id, "slot-A", "b".repeat(64), 46000)
            val operation = AbemaEpisodeClientOperation(request, now::get, foreground::get)
            assertTrue(operation.accept(AbemaEpisodeMetadataIpc.Result(request.nonce, "OK", "slot-A", "Title")))
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val result = AtomicReference<AbemaEpisodeMetadataIpc.Result?>()
            val worker = Thread { result.set(operation.workerResult({
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); true
            }, { open.get() && operation.current() })) }
            try {
                worker.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
                when (mode) { 0 -> operation.cancel(); 1 -> open.set(false); 2 -> foreground.set(false); else -> now.set(46000) }
                release.countDown(); worker.join(5000); assertFalse(worker.isAlive); assertNull(result.get())
            } finally { release.countDown(); worker.join(5000) }
        }
    }
    @Test fun exactSlotPolicyRequiresGuestAndSingleStrictPreflightGetWithoutAccountOrOtherQuery() {
        val request = AbemaSlotRequest("channel-A", "slot-A")
        val operation = AbemaGuestBootstrapOperation.FETCH_SLOT
        val uri = URI(request.url()); val admission = AbemaGuestRequestAdmission(operation, slot = request)
        assertFalse(admission.admit(uri, false, "GET"))
        assertTrue(admission.admit(URI(AbemaGuestBootstrapPolicy.GUEST), false, "POST"))
        val cors = mapOf("Origin" to "https://abema.tv", "Access-Control-Request-Method" to "GET", "Access-Control-Request-Headers" to "authorization")
        assertTrue(admission.admit(uri, false, "OPTIONS", cors)); assertFalse(admission.admit(uri, false, "OPTIONS", cors))
        assertTrue(admission.admit(uri, false, "GET")); assertFalse(admission.admit(uri, false, "GET"))
        assertTrue(AbemaGuestBootstrapPolicy.contentSecurityPolicy(operation, slot = request).contains(request.url()))
        for (url in listOf(request.url() + "&division=1", request.url().replace("payperview", "all"),
            request.url().replace("slot-A", "slot-B"), request.url().replace("api.p-c3-e.abema-tv.com", "api.abema.io"),
            AbemaGuestBootstrapPolicy.CHALLENGE, AbemaGuestBootstrapPolicy.EPISODE))
            assertFalse(AbemaGuestBootstrapPolicy.allows(URI(url), false, "GET", operation, slot = request))
        assertTrue(AbemaGuestBootstrapPolicy.corsHeaders("GET") { error("Private headers must not be read") }.isEmpty())
    }
}
