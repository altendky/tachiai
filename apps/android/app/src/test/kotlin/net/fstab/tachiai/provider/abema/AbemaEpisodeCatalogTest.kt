package net.fstab.tachiai.provider.abema

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class AbemaEpisodeCatalogTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    private fun resource(identity: String = "394-72_s10_p8510") =
        CatalogResource(ProviderId("abema"), "episode", identity, CatalogIntent.VIDEO)
    private fun entry(resource: CatalogResource, title: String = "Published episode") = CatalogEntry(resource, title)
    private fun failure(reason: CatalogFailure, result: CatalogResult<*>) =
        assertEquals(reason, (result as CatalogResult.Failure).reason)
    private fun noChannels(): AbemaChannelTransport = object : AbemaChannelTransport {
        override fun fetch(): AbemaChannelResponse = error("Episode refresh must not fetch channels")
        override fun close() = Unit
    }
    private class Episode(private val reply: (CatalogResource) -> CatalogResult<CatalogEntry>,
        private val cleanup: () -> Unit = {}) : AbemaEpisodeTransport {
        override fun fetch(resource: CatalogResource) = reply(resource)
        override fun cancelActiveRequest() = Unit
        override fun close() = cleanup()
    }

    @Test fun arbitraryTypedEpisodesUseTheirExactResourceAndOnlyNormalizedUnknownMetadata() {
        val requested = mutableListOf<CatalogResource>()
        var closes = 0
        val catalog = AbemaChannelCatalog(id, { noChannels() }, episodeTransportFactory = { gate ->
            Episode({ request ->
                assertTrue(gate()); requested.add(request)
                CatalogResult.Value(entry(request, "Fixture \uD83D\uDE00"))
            }, { closes++ })
        })
        catalog.use {
            for (request in listOf(resource(), resource("different-series_s2_p9"), resource("x".repeat(128)))) {
                val result = (it.refresh(request) as CatalogResult.Value).value
                assertEquals(request, result.resource)
                assertEquals("Fixture \uD83D\uDE00", result.title)
                assertEquals(CatalogAvailability.UNKNOWN, result.availability)
                assertNull(result.scheduledStartEpochMs)
                failure(CatalogFailure.NOT_VERIFIED, it.resolve(request))
            }
            assertEquals(listOf(resource(), resource("different-series_s2_p9"), resource("x".repeat(128))), requested)
            assertEquals(3, closes)
        }
    }

    @Test fun malformedTypedIdsKindsAndUnavailableEpisodeSeamNeverDispatch() {
        var calls = 0
        AbemaChannelCatalog(id, { noChannels() }, episodeTransportFactory = {
            calls++; Episode({ CatalogResult.Value(entry(it)) })
        }).use { catalog ->
            for (invalid in listOf(resource("x".repeat(129)), resource("a/b"), resource("a.b"),
                resource().copy(intent = CatalogIntent.CHANNEL), resource().copy(providerId = ProviderId("twitch"))))
                failure(CatalogFailure.INVALID_INPUT, catalog.refresh(invalid))
            for (unsupported in listOf(resource().copy(kind = "series", intent = CatalogIntent.COLLECTION),
                resource().copy(kind = "slot", intent = CatalogIntent.BROADCAST)))
                failure(CatalogFailure.NOT_VERIFIED, catalog.refresh(unsupported))
            assertEquals(0, calls)
        }
        AbemaChannelCatalog(id, { noChannels() }).use {
            failure(CatalogFailure.NOT_VERIFIED, it.refresh(resource()))
        }
    }

    @Test fun wrongIdentityTypeStatusScheduleAndUnsafeUnicodeCannotBecomeMetadata() {
        val request = resource()
        for (invalid in listOf(
            entry(resource("another-episode")),
            entry(request.copy(kind = "channel", intent = CatalogIntent.CHANNEL)),
            entry(request.copy(providerId = ProviderId("twitch"))),
            entry(request.copy(intent = CatalogIntent.BROADCAST)),
            entry(request).copy(availability = CatalogAvailability.AVAILABLE),
            entry(request).copy(scheduledStartEpochMs = 0),
            entry(request, "Unpaired \uD800"), entry(request, "Unpaired \uDC00"),
            entry(request, "Hidden \uDB40\uDC01"),
        )) AbemaChannelCatalog(id, { noChannels() }, episodeTransportFactory = {
            Episode({ CatalogResult.Value(invalid) })
        }).use { failure(CatalogFailure.TEMPORARY, it.refresh(request)) }
    }

    @Test fun transportFailuresCannotClaimAnAccountAndRateLimitRetryIsBoundedWithoutRetrying() {
        for (reason in CatalogFailure.entries) {
            var calls = 0
            AbemaChannelCatalog(id, { noChannels() }, wallMs = { 1000L }, episodeTransportFactory = {
                Episode({ calls++; CatalogResult.Failure(reason) })
            }).use {
                val result = it.refresh(resource()) as CatalogResult.Failure
                assertEquals(when (reason) {
                    CatalogFailure.NOT_FOUND, CatalogFailure.NOT_VERIFIED, CatalogFailure.RATE_LIMITED, CatalogFailure.TEMPORARY -> reason
                    else -> CatalogFailure.TEMPORARY
                }, result.reason)
                assertEquals(if (reason == CatalogFailure.RATE_LIMITED) 61_000L else null, result.retryAtEpochMs)
                assertEquals(1, calls)
            }
        }
        for ((now, supplied, expected) in listOf(
            Triple(1000L, Long.MAX_VALUE, 86_401_000L), Triple(1000L, 0L, 1000L),
            Triple(Long.MAX_VALUE - 1, Long.MAX_VALUE, Long.MAX_VALUE), Triple(-1L, null, 60_000L),
        )) AbemaChannelCatalog(id, { noChannels() }, wallMs = { now }, episodeTransportFactory = {
            Episode({ CatalogResult.Failure(CatalogFailure.RATE_LIMITED, supplied) })
        }).use {
            val result = it.refresh(resource()) as CatalogResult.Failure
            assertEquals(CatalogFailure.RATE_LIMITED, result.reason)
            assertEquals(expected, result.retryAtEpochMs)
        }
    }

    @Test fun ownerLossBeforeFactoryDuringFetchAndCleanupIsTemporaryAndSuppressesLateMetadata() {
        for (stage in listOf("before", "factory", "fetch", "cleanup")) {
            var current = stage != "before"
            var factories = 0
            var fetches = 0
            AbemaChannelCatalog(id, { noChannels() }, canPublish = { current }, episodeTransportFactory = { gate ->
                factories++
                if (stage == "factory") current = false
                Episode({ request ->
                    assertTrue(gate()); fetches++
                    if (stage == "fetch") current = false
                    CatalogResult.Value(entry(request))
                }, { if (stage == "cleanup") current = false })
            }).use {
                failure(CatalogFailure.TEMPORARY, it.refresh(resource()))
                assertEquals(if (stage == "before") 0 else 1, factories)
                assertEquals(if (stage in listOf("before", "factory")) 0 else 1, fetches)
            }
        }
        AbemaChannelCatalog(id, { noChannels() }, episodeTransportFactory = {
            Episode({ throw IOException("PRIVATE_PROVIDER_DETAILS") })
        }).use {
            val result = it.refresh(resource())
            failure(CatalogFailure.TEMPORARY, result)
            assertFalse(result.toString().contains("PRIVATE_PROVIDER_DETAILS"))
        }
    }

    @Test fun completedMetadataWaitsForCleanupAndFailedCleanupBlocksFutureAdmission() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val result = AtomicReference<CatalogResult<CatalogEntry>?>()
        val catalog = AbemaChannelCatalog(id, { noChannels() }, episodeTransportFactory = {
            Episode({ CatalogResult.Value(entry(it)) }, {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            })
        })
        val worker = thread { result.set(catalog.refresh(resource())) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); assertNull(result.get()); assertTrue(worker.isAlive)
            release.countDown(); worker.join(5000); assertFalse(worker.isAlive)
            assertEquals(entry(resource()), (result.get() as CatalogResult.Value).value)
        } finally { release.countDown(); worker.join(5000); catalog.close() }

        var calls = 0
        AbemaChannelCatalog(id, { noChannels() }, episodeTransportFactory = {
            calls++
            Episode({ CatalogResult.Value(entry(it)) }, { throw IOException("PRIVATE_CLEANUP_DETAILS") })
        }).use {
            failure(CatalogFailure.TEMPORARY, it.refresh(resource()))
            assertEquals(CatalogAccess.NOT_VERIFIED, it.capabilities().refresh)
            failure(CatalogFailure.TEMPORARY, it.refresh(resource("next-episode")))
            assertEquals(1, calls)
        }
    }

    @Test fun closeCancelsActiveEpisodeWithoutWaitingForOperationLockAndRejectsItsLateValue() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val finishedClose = CountDownLatch(1)
        val cancels = AtomicInteger()
        val result = AtomicReference<CatalogResult<CatalogEntry>?>()
        val catalog = AbemaChannelCatalog(id, { noChannels() }, episodeTransportFactory = { gate ->
            object : AbemaEpisodeTransport {
                override fun fetch(resource: CatalogResource): CatalogResult<CatalogEntry> {
                    assertTrue(gate()); entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                    assertFalse(gate()); return CatalogResult.Value(entry(resource))
                }
                override fun cancelActiveRequest() { cancels.incrementAndGet(); release.countDown() }
                override fun close() = Unit
            }
        })
        val worker = thread { result.set(catalog.refresh(resource())) }
        var closer: Thread? = null
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            closer = thread { try { catalog.close() } finally { finishedClose.countDown() } }
            assertTrue("Close must not wait for the blocked refresh lock", finishedClose.await(1, TimeUnit.SECONDS))
            worker.join(5000); assertFalse(worker.isAlive)
            assertEquals(1, cancels.get()); failure(CatalogFailure.TEMPORARY, checkNotNull(result.get()))
        } finally { release.countDown(); worker.join(5000); closer?.join(5000); catalog.close() }
    }

    @Test fun episodeRefreshSuccessFailureAndInvalidInputDiscardChannelContinuations() {
        for (reply in listOf<CatalogResult<CatalogEntry>?>(CatalogResult.Value(entry(resource())),
            CatalogResult.Failure(CatalogFailure.TEMPORARY), null)) {
            var channelCalls = 0
            var episodeCalls = 0
            val channels = (1..30).map { CatalogEntry(CatalogResource(ProviderId("abema"), "channel", "channel$it",
                CatalogIntent.CHANNEL), "Channel $it") }
            AbemaChannelCatalog(id, { object : AbemaChannelTransport {
                override fun fetch(): AbemaChannelResponse { channelCalls++; return AbemaChannelResponse(200, channels) }
                override fun close() = Unit
            } }, episodeTransportFactory = {
                Episode({ episodeCalls++; checkNotNull(reply) })
            }).use { catalog ->
                val first = (catalog.browse(CatalogQuery()) as CatalogResult.Value).value
                assertNotNull(first.nextCursor)
                val refreshed = catalog.refresh(if (reply == null) resource("invalid/path") else resource())
                if (reply == null) failure(CatalogFailure.INVALID_INPUT, refreshed) else assertEquals(reply, refreshed)
                failure(CatalogFailure.INVALID_INPUT, catalog.browse(CatalogQuery(cursor = first.nextCursor)))
                assertEquals(1, channelCalls); assertEquals(if (reply == null) 0 else 1, episodeCalls)
            }
        }
    }
}
