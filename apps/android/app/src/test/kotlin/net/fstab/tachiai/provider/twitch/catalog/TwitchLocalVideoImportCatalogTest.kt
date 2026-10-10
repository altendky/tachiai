package net.fstab.tachiai.provider.twitch.catalog

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class TwitchLocalVideoImportCatalogTest {
    private class Fixture(
        override val providerId: ProviderId = ProviderId("twitch"),
        override val instanceId: String = "12000000-0000-0000-0000-000000000001",
    ) : ProviderCatalog {
        val capabilityCalls = AtomicInteger()
        val lookupCalls = AtomicInteger()
        val browseCalls = AtomicInteger()
        val refreshCalls = AtomicInteger()
        val resolveCalls = AtomicInteger()
        val closes = AtomicInteger()
        var access = CatalogAccess.AUTHORIZATION_REQUIRED
        var readCapabilities: (() -> CatalogCapabilities)? = null
        var find: (String) -> CatalogResult<CatalogEntry> = { error("Local import must not call connected lookup") }
        val page = CatalogResult.Value(CatalogPage(emptyList(), "opaque-fixture-cursor"))
        val refreshFailure = CatalogResult.Failure(CatalogFailure.NOT_VERIFIED)
        val playbackFailure = CatalogResult.Failure(CatalogFailure.UNSUPPORTED)
        override fun capabilities(): CatalogCapabilities {
            capabilityCalls.incrementAndGet()
            return readCapabilities?.invoke() ?: caps()
        }
        fun caps() = CatalogCapabilities(browse = access, search = access, lookup = access, children = access,
            refresh = access, playback = CatalogAccess.NOT_VERIFIED,
            collections = listOf(CatalogCollection("following", "Following", access)),
            browseTitle = "Live channels", initialCollectionId = "following")
        override fun lookup(input: String): CatalogResult<CatalogEntry> { lookupCalls.incrementAndGet(); return find(input) }
        override fun browse(query: CatalogQuery): CatalogResult<CatalogPage> { browseCalls.incrementAndGet(); return page }
        override fun refresh(resource: CatalogResource): CatalogResult<CatalogEntry> { refreshCalls.incrementAndGet(); return refreshFailure }
        override fun resolve(resource: CatalogResource): CatalogResult<CatalogPlaybackResource> { resolveCalls.incrementAndGet(); return playbackFailure }
        override fun close() { closes.incrementAndGet() }
        fun assertNoOperations() {
            assertEquals(0, lookupCalls.get()); assertEquals(0, browseCalls.get())
            assertEquals(0, refreshCalls.get()); assertEquals(0, resolveCalls.get())
        }
    }
    private fun resource(id: String) = CatalogResource(ProviderId("twitch"), "video", id, CatalogIntent.VIDEO)
    private fun <T> value(result: CatalogResult<T>) = (result as CatalogResult.Value<T>).value
    private fun failure(result: CatalogResult<*>, expected: CatalogFailure) =
        assertEquals(expected, (result as CatalogResult.Failure).reason)
    private fun await(latch: CountDownLatch) { assertTrue("Fixture operation did not reach its boundary", latch.await(2, TimeUnit.SECONDS)) }
    private fun assertDeniedCapabilities(caps: CatalogCapabilities) {
        assertEquals(CatalogAccess.NOT_VERIFIED, caps.browse); assertEquals(CatalogAccess.NOT_VERIFIED, caps.search)
        assertEquals(CatalogAccess.NOT_VERIFIED, caps.lookup); assertEquals(CatalogAccess.NOT_VERIFIED, caps.children)
        assertEquals(CatalogAccess.NOT_VERIFIED, caps.refresh); assertEquals(CatalogAccess.NOT_VERIFIED, caps.playback)
        assertTrue(caps.collections.isEmpty()); assertNull(caps.initialCollectionId)
    }

    @Test fun decoratorRequiresATwitchProviderAndCanonicalInstanceIdentity() {
        assertThrows(IllegalArgumentException::class.java) { TwitchLocalVideoImportCatalog(Fixture(ProviderId("abema"))) }
        assertThrows(IllegalArgumentException::class.java) { TwitchLocalVideoImportCatalog(Fixture(instanceId = "../provider")) }
    }

    @Test fun onlyLookupCapabilityChangesAcrossDisconnectedAndBlockedMetadataStates() {
        val fixture = Fixture(); val catalog = TwitchLocalVideoImportCatalog(fixture)
        listOf(CatalogAccess.AUTHORIZATION_REQUIRED, CatalogAccess.RECONNECT_REQUIRED, CatalogAccess.SCOPE_REQUIRED,
            CatalogAccess.NOT_VERIFIED, CatalogAccess.UNSUPPORTED, CatalogAccess.AVAILABLE).forEach { access ->
            fixture.access = access
            assertEquals(fixture.caps().copy(lookup = CatalogAccess.AVAILABLE), catalog.capabilities())
        }
        assertEquals(fixture.providerId, catalog.providerId); assertEquals(fixture.instanceId, catalog.instanceId)
        fixture.assertNoOperations()
    }

    @Test fun localExactUrlsNormalizeToOneUnknownVideoWithoutCallingConnectedOperations() {
        val fixture = Fixture(); val catalog = TwitchLocalVideoImportCatalog(fixture)
        catalog.capabilities()
        val entries = listOf("https://www.twitch.tv/videos/335921245", "https://twitch.tv/videos/335921245/",
            " https://WWW.TWITCH.TV:443/videos/335921245 ").map { value(catalog.lookup(it)) }
        assertEquals(1, entries.distinct().size)
        assertEquals(resource("335921245"), entries.first().resource)
        assertEquals("Public Twitch video 335921245 (metadata not checked)", entries.first().title)
        assertEquals(CatalogAvailability.UNKNOWN, entries.first().availability)
        assertNull(entries.first().scheduledStartEpochMs)
        fixture.assertNoOperations(); assertEquals(1, fixture.capabilityCalls.get())
    }

    @Test fun localImportRetainsKnownSyntaxWithoutClaimingExistenceOrChangingIntent() {
        val fixture = Fixture(); val catalog = TwitchLocalVideoImportCatalog(fixture)
        catalog.capabilities()
        listOf("1", "9".repeat(32)).forEach { id ->
            val entry = value(catalog.lookup("https://twitch.tv/videos/$id"))
            assertEquals(resource(id), entry.resource); assertEquals(CatalogAvailability.UNKNOWN, entry.availability)
        }
        fixture.assertNoOperations()
    }

    @Test fun localImportRejectsAliasesBareIdsSignedAndAmbiguousUrlsWithoutDelegation() {
        val fixture = Fixture(); val catalog = TwitchLocalVideoImportCatalog(fixture)
        catalog.capabilities()
        listOf("", " ", "335921245", "midnightsumo", "https://twitch.tv/midnightsumo", "https://twitch.tv/335921245",
            "http://twitch.tv/videos/123", "https://twitch.tv.evil.test/videos/123", "https://evil.test/videos/123",
            "https://fixture:private@twitch.tv/videos/123", "https://twitch.tv:444/videos/123",
            "https://twitch.tv/videos/123?token=private-fixture", "https://twitch.tv/videos/123?",
            "https://twitch.tv/videos/123#private-fixture", "https://twitch.tv/videos/123#", "https://twitch.tv/videos/%31",
            "https://twitch.tv/videos/0", "https://twitch.tv/videos/0123", "https://twitch.tv/videos/${"9".repeat(33)}",
            "https://twitch.tv/videos/123//", "https://twitch.tv/videos/123/extra", "https://clips.twitch.tv/Fixture",
            "https://twitch.tv/directory", "https://usher.ttvnw.net/api/vod/123.m3u8",
            "https://twitch.tv/videos/123\n", "https://twitch.tv/videos/123\u200B", "x".repeat(2049))
            .forEach { failure(catalog.lookup(it), CatalogFailure.INVALID_INPUT) }
        fixture.assertNoOperations()
        assertEquals("TwitchLocalVideoImportCatalog(redacted)", catalog.toString())
    }

    @Test fun lookupBeforeCapabilitiesAndFailedCapabilityReadCannotReuseAnEarlierLocalMode() {
        val fixture = Fixture(); val catalog = TwitchLocalVideoImportCatalog(fixture)
        failure(catalog.lookup("https://twitch.tv/videos/123"), CatalogFailure.ACCESS_REQUIRED)
        fixture.assertNoOperations(); assertEquals(0, fixture.capabilityCalls.get())
        catalog.capabilities(); assertEquals(resource("123"), value(catalog.lookup("https://twitch.tv/videos/123")).resource)
        val error = IllegalStateException("Synthetic capabilities failure")
        fixture.readCapabilities = { throw error }
        assertSame(error, assertThrows(IllegalStateException::class.java) { catalog.capabilities() })
        failure(catalog.lookup("https://twitch.tv/videos/123"), CatalogFailure.ACCESS_REQUIRED)
        fixture.assertNoOperations()
    }

    @Test fun connectedLookupPreservesMetadataAndExistingChannelInputs() {
        val fixture = Fixture(); fixture.access = CatalogAccess.AVAILABLE
        val catalog = TwitchLocalVideoImportCatalog(fixture); catalog.capabilities()
        val checked = CatalogResult.Value(CatalogEntry(resource("123"), "Actual fixture title", CatalogAvailability.AVAILABLE))
        fixture.find = { checked }
        assertSame(checked, catalog.lookup("https://twitch.tv/videos/123"))
        val channel = CatalogResult.Value(CatalogEntry(CatalogResource(ProviderId("twitch"), "broadcaster", "456", CatalogIntent.CHANNEL),
            "Checked fixture channel", CatalogAvailability.OFFLINE))
        fixture.find = { input -> assertEquals("neverstarted", input); channel }
        assertSame(channel, catalog.lookup("neverstarted"))
        assertEquals(2, fixture.lookupCalls.get())
    }

    @Test fun everyConnectedFailureAndExceptionIsPreservedWithoutLocalFallback() {
        val fixture = Fixture(); fixture.access = CatalogAccess.AVAILABLE
        val catalog = TwitchLocalVideoImportCatalog(fixture); catalog.capabilities()
        CatalogFailure.entries.forEach { reason ->
            val rejected = CatalogResult.Failure(reason, if (reason == CatalogFailure.RATE_LIMITED) 123_000L else null)
            fixture.find = { rejected }
            assertSame(rejected, catalog.lookup("https://twitch.tv/videos/123"))
        }
        val error = IllegalStateException("Synthetic route failure")
        fixture.find = { throw error }
        assertSame(error, assertThrows(IllegalStateException::class.java) { catalog.lookup("https://twitch.tv/videos/123") })
        assertEquals(CatalogFailure.entries.size + 1, fixture.lookupCalls.get())
    }

    @Test fun capabilityReloadSelectsModeWithoutAutomaticallyRepeatingAFailedLookup() {
        val fixture = Fixture(); fixture.access = CatalogAccess.AVAILABLE
        val catalog = TwitchLocalVideoImportCatalog(fixture); catalog.capabilities()
        fixture.find = { CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED) }
        failure(catalog.lookup("https://twitch.tv/videos/123"), CatalogFailure.ACCESS_REQUIRED)
        fixture.access = CatalogAccess.RECONNECT_REQUIRED
        // Until explicit reload, another lookup still follows connected policy.
        failure(catalog.lookup("https://twitch.tv/videos/123"), CatalogFailure.ACCESS_REQUIRED)
        catalog.capabilities()
        assertEquals(2, fixture.lookupCalls.get())
        assertEquals(CatalogAvailability.UNKNOWN, value(catalog.lookup("https://twitch.tv/videos/123")).availability)
        assertEquals(2, fixture.lookupCalls.get())
        fixture.access = CatalogAccess.AVAILABLE; catalog.capabilities()
        val checked = CatalogResult.Value(CatalogEntry(resource("123"), "Verified fixture video", CatalogAvailability.UNAVAILABLE))
        fixture.find = { checked }
        assertSame(checked, catalog.lookup("https://twitch.tv/videos/123"))
    }

    @Test fun otherOperationsRemainDelegatedWithoutPromotingTheirCapabilities() {
        val fixture = Fixture(); val catalog = TwitchLocalVideoImportCatalog(fixture)
        val caps = catalog.capabilities()
        assertEquals(CatalogAccess.AUTHORIZATION_REQUIRED, caps.browse)
        assertEquals(CatalogAccess.NOT_VERIFIED, caps.playback)
        assertSame(fixture.page, catalog.browse(CatalogQuery(collectionId = "following")))
        assertSame(fixture.refreshFailure, catalog.refresh(resource("123")))
        assertSame(fixture.playbackFailure, catalog.resolve(resource("123")))
        assertEquals(1, fixture.browseCalls.get()); assertEquals(1, fixture.refreshCalls.get()); assertEquals(1, fixture.resolveCalls.get())
    }

    @Test fun ownerLossAndGateExceptionsRequireFreshCapabilitiesBeforeLookup() {
        val fixture = Fixture(); val owner = AtomicBoolean(true)
        val catalog = TwitchLocalVideoImportCatalog(fixture) { owner.get() }
        catalog.capabilities(); owner.set(false)
        failure(catalog.lookup("https://twitch.tv/videos/123"), CatalogFailure.ACCESS_REQUIRED)
        failure(catalog.browse(CatalogQuery()), CatalogFailure.ACCESS_REQUIRED)
        failure(catalog.refresh(resource("123")), CatalogFailure.ACCESS_REQUIRED)
        failure(catalog.resolve(resource("123")), CatalogFailure.ACCESS_REQUIRED)
        assertDeniedCapabilities(catalog.capabilities())
        owner.set(true)
        failure(catalog.lookup("https://twitch.tv/videos/123"), CatalogFailure.ACCESS_REQUIRED)
        catalog.capabilities(); value(catalog.lookup("https://twitch.tv/videos/123"))
        fixture.assertNoOperations(); assertEquals(2, fixture.capabilityCalls.get())
        val failingGate = TwitchLocalVideoImportCatalog(fixture) { throw IllegalStateException("Synthetic owner failure") }
        assertDeniedCapabilities(failingGate.capabilities())
        failure(failingGate.lookup("https://twitch.tv/videos/123"), CatalogFailure.ACCESS_REQUIRED)
        assertEquals(2, fixture.capabilityCalls.get())
    }

    @Test fun closeDoesNotWaitForCapabilityIoAndRejectsTheLateMode() {
        val fixture = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        fixture.readCapabilities = { entered.countDown(); await(release); fixture.caps() }
        val catalog = TwitchLocalVideoImportCatalog(fixture)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val reading = workers.submit<CatalogCapabilities> { catalog.capabilities() }
            await(entered)
            workers.submit { catalog.close() }.get(1, TimeUnit.SECONDS)
            assertEquals(1, fixture.closes.get()); release.countDown()
            assertDeniedCapabilities(reading.get(2, TimeUnit.SECONDS))
            failure(catalog.lookup("https://twitch.tv/videos/123"), CatalogFailure.ACCESS_REQUIRED)
            catalog.close(); assertEquals(1, fixture.closes.get())
        } finally { release.countDown(); workers.shutdownNow() }
    }

    @Test fun closeDoesNotWaitForConnectedLookupAndRejectsItsLateMetadata() {
        val fixture = Fixture(); fixture.access = CatalogAccess.AVAILABLE
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        fixture.find = { entered.countDown(); await(release)
            CatalogResult.Value(CatalogEntry(resource("123"), "Late private fixture title", CatalogAvailability.AVAILABLE)) }
        val catalog = TwitchLocalVideoImportCatalog(fixture); catalog.capabilities()
        val workers = Executors.newFixedThreadPool(2)
        try {
            val reading = workers.submit<CatalogResult<CatalogEntry>> { catalog.lookup("https://twitch.tv/videos/123") }
            await(entered)
            workers.submit { catalog.close() }.get(1, TimeUnit.SECONDS)
            release.countDown(); failure(reading.get(2, TimeUnit.SECONDS), CatalogFailure.ACCESS_REQUIRED)
            fixture.assertClosedOnce()
        } finally { release.countDown(); workers.shutdownNow() }
    }

    @Test fun ownerChangeDuringCapabilitiesOrConnectedLookupRejectsTheResult() {
        val fixture = Fixture(); val owner = AtomicBoolean(true)
        val catalog = TwitchLocalVideoImportCatalog(fixture) { owner.get() }
        fixture.readCapabilities = { owner.set(false); fixture.caps() }
        assertDeniedCapabilities(catalog.capabilities())
        failure(catalog.lookup("https://twitch.tv/videos/123"), CatalogFailure.ACCESS_REQUIRED)
        owner.set(true); fixture.access = CatalogAccess.AVAILABLE; fixture.readCapabilities = null
        catalog.capabilities()
        fixture.find = { owner.set(false); CatalogResult.Value(CatalogEntry(resource("123"), "Stale fixture video")) }
        failure(catalog.lookup("https://twitch.tv/videos/123"), CatalogFailure.ACCESS_REQUIRED)
        assertEquals(1, fixture.lookupCalls.get())
    }

    @Test fun failedExchangeAfterOwnerLossStillClearsAccessInsteadOfPublishingATemporaryFailure() {
        val fixture = Fixture(); val owner = AtomicBoolean(true)
        val catalog = TwitchLocalVideoImportCatalog(fixture) { owner.get() }
        fixture.access = CatalogAccess.AVAILABLE; catalog.capabilities()
        fixture.find = { owner.set(false); throw IllegalStateException("Synthetic interrupted lookup") }
        failure(catalog.lookup("https://twitch.tv/videos/123"), CatalogFailure.ACCESS_REQUIRED)
        owner.set(true)
        fixture.readCapabilities = { owner.set(false); throw IllegalStateException("Synthetic interrupted capabilities") }
        assertDeniedCapabilities(catalog.capabilities())
        assertEquals(1, fixture.lookupCalls.get())
    }

    @Test fun aNewCapabilityReadRejectsAnOlderLookupWithoutSwitchingItsResultToLocalSuccess() {
        val fixture = Fixture(); fixture.access = CatalogAccess.AVAILABLE
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        fixture.find = { entered.countDown(); await(release)
            CatalogResult.Value(CatalogEntry(resource("123"), "Old connected fixture title", CatalogAvailability.AVAILABLE)) }
        val catalog = TwitchLocalVideoImportCatalog(fixture); catalog.capabilities()
        val workers = Executors.newSingleThreadExecutor()
        try {
            val reading = workers.submit<CatalogResult<CatalogEntry>> { catalog.lookup("https://twitch.tv/videos/123") }
            await(entered)
            fixture.access = CatalogAccess.AUTHORIZATION_REQUIRED; catalog.capabilities()
            release.countDown(); failure(reading.get(2, TimeUnit.SECONDS), CatalogFailure.ACCESS_REQUIRED)
            assertEquals(CatalogAvailability.UNKNOWN, value(catalog.lookup("https://twitch.tv/videos/123")).availability)
            assertEquals(1, fixture.lookupCalls.get())
        } finally { release.countDown(); workers.shutdownNow() }
    }

    @Test fun anOlderCapabilityResponseCannotReplaceTheLatestConnectedMode() {
        val fixture = Fixture(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        fixture.readCapabilities = {
            if (fixture.capabilityCalls.get() == 1) {
                entered.countDown(); await(release)
                fixture.caps().copy(lookup = CatalogAccess.AUTHORIZATION_REQUIRED)
            } else fixture.caps().copy(lookup = CatalogAccess.AVAILABLE)
        }
        val catalog = TwitchLocalVideoImportCatalog(fixture)
        val workers = Executors.newSingleThreadExecutor()
        try {
            val older = workers.submit<CatalogCapabilities> { catalog.capabilities() }
            await(entered); catalog.capabilities()
            release.countDown(); assertDeniedCapabilities(older.get(2, TimeUnit.SECONDS))
            val checked = CatalogResult.Value(CatalogEntry(resource("123"), "Latest connected fixture title", CatalogAvailability.AVAILABLE))
            fixture.find = { checked }
            assertSame(checked, catalog.lookup("https://twitch.tv/videos/123"))
            assertEquals(1, fixture.lookupCalls.get())
        } finally { release.countDown(); workers.shutdownNow() }
    }

    @Test fun localPublicationChecksTheOwnerAgainWithoutHoldingTheCloseLock() {
        val fixture = Fixture(); val owner = AtomicBoolean(true)
        val checkPublication = AtomicBoolean(false); val checks = AtomicInteger()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val catalog = TwitchLocalVideoImportCatalog(fixture) {
            if (checkPublication.get() && checks.incrementAndGet() == 2) { entered.countDown(); await(release) }
            owner.get()
        }
        catalog.capabilities(); checkPublication.set(true)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val reading = workers.submit<CatalogResult<CatalogEntry>> { catalog.lookup("https://twitch.tv/videos/123") }
            await(entered); owner.set(false)
            workers.submit { catalog.close() }.get(1, TimeUnit.SECONDS)
            release.countDown(); failure(reading.get(2, TimeUnit.SECONDS), CatalogFailure.ACCESS_REQUIRED)
            fixture.assertNoOperations(); fixture.assertClosedOnce()
        } finally { release.countDown(); workers.shutdownNow() }
    }

    private fun Fixture.assertClosedOnce() = assertEquals(1, closes.get())
}
