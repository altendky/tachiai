package net.fstab.tachiai.provider.abema

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class AbemaChannelCatalogTest {
    private val id = "12345678-1234-1234-1234-123456789abc"
    private fun entry(name: String) = CatalogEntry(CatalogResource(ProviderId("abema"), "channel", name, CatalogIntent.CHANNEL),
        "Channel $name")
    private class Transport(private val response: () -> AbemaChannelResponse) : AbemaChannelTransport {
        var closed = false
        override fun fetch() = response()
        override fun close() { closed = true }
    }
    private fun value(result: CatalogResult<CatalogPage>) = (result as CatalogResult.Value).value
    private fun failure(reason: CatalogFailure, result: CatalogResult<*>) {
        assertEquals(reason, (result as CatalogResult.Failure).reason)
    }

    @Test fun snapshotPagesAreBoundedQueryOwnedAndDoNotRefetch() {
        var calls = 0
        val entries = (1..55).map { entry("channel$it") }
        val catalog = AbemaChannelCatalog(id, { gate -> Transport {
            assertTrue(gate()); calls++; AbemaChannelResponse(200, entries)
        } })
        catalog.use {
            val first = value(it.browse(CatalogQuery()))
            assertEquals(entries.take(20), first.entries)
            assertNotNull(first.nextCursor)
            failure(CatalogFailure.INVALID_INPUT, it.browse(CatalogQuery(search = "other", cursor = first.nextCursor)))
            val second = value(it.browse(CatalogQuery(cursor = first.nextCursor)))
            assertEquals(entries.drop(20).take(20), second.entries)
            failure(CatalogFailure.INVALID_INPUT, it.browse(CatalogQuery(cursor = first.nextCursor)))
            val last = value(it.browse(CatalogQuery(cursor = second.nextCursor)))
            assertEquals(entries.drop(40), last.entries)
            assertNull(last.nextCursor)
            assertEquals(1, calls)
            val nextQuery = value(it.browse(CatalogQuery(search = "CHANNEL5")))
            assertEquals(entries.filter { it.resource.identity.startsWith("channel5") }, nextQuery.entries)
            assertEquals(2, calls)
        }
    }

    @Test fun channelUrlAbsenceIsConfigurableButBareIdAndRefreshDoNotGuess() {
        var calls = 0
        val channel = entry("abema-news")
        AbemaChannelCatalog(id, { Transport { calls++; AbemaChannelResponse(200, listOf(channel)) } }).use {
            assertEquals(channel, (it.lookup("abema-news") as CatalogResult.Value).value)
            assertEquals(channel, (it.lookup("https://abema.tv/channels/abema-news") as CatalogResult.Value).value)
            val absent = (it.lookup("https://abema.tv/channels/sumo") as CatalogResult.Value).value
            assertEquals("sumo", absent.resource.identity)
            assertEquals(CatalogAvailability.UNKNOWN, absent.availability)
            assertEquals("ABEMA channel · sumo", absent.title)
            failure(CatalogFailure.NOT_FOUND, it.lookup("sumo"))
            failure(CatalogFailure.NOT_FOUND, it.refresh(absent.resource))
            failure(CatalogFailure.NOT_VERIFIED, it.resolve(channel.resource))
            assertEquals(5, calls)
        }
    }

    @Test fun failedOrInvalidProviderResponsesNeverBecomePublicLinkSuccess() {
        val invalid = entry("abema-news").copy(availability = CatalogAvailability.LIVE)
        val responses = listOf(AbemaChannelResponse(500), AbemaChannelResponse(403),
            AbemaChannelResponse(200, listOf(invalid)), AbemaChannelResponse(200, listOf(entry("a"), entry("a"))))
        responses.forEach { response ->
            AbemaChannelCatalog(id, { Transport { response } }).use {
                failure(CatalogFailure.TEMPORARY, it.lookup("https://abema.tv/channels/sumo"))
            }
        }
        AbemaChannelCatalog(id, { Transport { throw IOException("private response detail") } }).use {
            failure(CatalogFailure.TEMPORARY, it.lookup("https://abema.tv/channels/sumo"))
        }
    }

    @Test fun queryReplacementLookupAndUnavailableCollectionsDiscardOldContinuations() {
        var calls = 0
        val entries = (1..30).map { entry("channel$it") }
        AbemaChannelCatalog(id, { Transport { calls++; AbemaChannelResponse(200, entries) } }).use {
            val original = value(it.browse(CatalogQuery())).nextCursor
            assertEquals(CatalogFailure.INVALID_INPUT, (it.browse(CatalogQuery(search = " ")) as CatalogResult.Failure).reason)
            failure(CatalogFailure.INVALID_INPUT, it.browse(CatalogQuery(cursor = original)))
            val beforeLookup = value(it.browse(CatalogQuery())).nextCursor
            it.lookup("channel1")
            failure(CatalogFailure.INVALID_INPUT, it.browse(CatalogQuery(cursor = beforeLookup)))
            val beforeHistory = value(it.browse(CatalogQuery())).nextCursor
            val requests = calls
            failure(CatalogFailure.NOT_VERIFIED, it.browse(CatalogQuery(collectionId = "history")))
            failure(CatalogFailure.NOT_VERIFIED, it.browse(CatalogQuery(collectionId = "my_list")))
            assertEquals(requests, calls)
            failure(CatalogFailure.INVALID_INPUT, it.browse(CatalogQuery(cursor = beforeHistory)))
        }
    }

    @Test fun queuedLookupAndInvalidFreshQueryDiscardTheEarlierInFlightSnapshot() {
        for (lookup in listOf(true, false)) {
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            var calls = 0
            var first: CatalogResult<CatalogPage>? = null
            var replacement: CatalogResult<*>? = null
            val catalog = AbemaChannelCatalog(id, { Transport {
                calls++
                if (calls == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                AbemaChannelResponse(200, (1..30).map { entry("channel$it") })
            } })
            val original = thread { first = catalog.browse(CatalogQuery()) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val next = thread { replacement = if (lookup) catalog.lookup("channel1") else catalog.browse(CatalogQuery(search = " ")) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (next.state != Thread.State.BLOCKED && next.isAlive && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED, next.state)
            release.countDown(); original.join(5000); next.join(5000)
            assertFalse(original.isAlive); assertFalse(next.isAlive)
            if (lookup) assertTrue(replacement is CatalogResult.Value)
            else failure(CatalogFailure.INVALID_INPUT, checkNotNull(replacement))
            val before = calls
            failure(CatalogFailure.INVALID_INPUT, catalog.browse(CatalogQuery(cursor = value(checkNotNull(first)).nextCursor)))
            assertEquals(before, calls)
            catalog.close()
        }
    }

    @Test fun ownerLossAndTransportCleanupFailureSuppressMetadataPublication() {
        var current = true
        val catalog = AbemaChannelCatalog(id, { Transport {
            current = false; AbemaChannelResponse(200, listOf(entry("news")))
        } }, canPublish = { current })
        catalog.use { failure(CatalogFailure.ACCESS_REQUIRED, it.browse(CatalogQuery())) }
        val cleanup = AbemaChannelCatalog(id, { object : AbemaChannelTransport {
            override fun fetch() = AbemaChannelResponse(200, listOf(entry("news")))
            override fun close() { throw IOException("private cleanup") }
        } })
        cleanup.use { failure(CatalogFailure.TEMPORARY, it.browse(CatalogQuery())) }
    }

    @Test fun closeSignalsActiveWorkWithoutWaitingForOperationAndRejectsLateResults() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        var result: CatalogResult<CatalogPage>? = null
        val catalog = AbemaChannelCatalog(id, { object : AbemaChannelTransport {
            override fun fetch(): AbemaChannelResponse {
                entered.countDown(); check(released.await(5, TimeUnit.SECONDS))
                return AbemaChannelResponse(200, listOf(entry("news")))
            }
            override fun close() { released.countDown() }
        } })
        val worker = thread { result = catalog.browse(CatalogQuery()) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        catalog.close()
        worker.join(5000)
        assertFalse(worker.isAlive)
        failure(CatalogFailure.ACCESS_REQUIRED, checkNotNull(result))
        assertEquals(CatalogAccess.NOT_VERIFIED, catalog.capabilities().browse)
    }

    @Test fun rateLimitHasBoundedRetryWithoutAutomaticSecondRequest() {
        var calls = 0
        AbemaChannelCatalog(id, { Transport { calls++; AbemaChannelResponse(429, retryAtEpochMs = Long.MAX_VALUE) } },
            wallMs = { 1000L }).use {
            val result = it.browse(CatalogQuery()) as CatalogResult.Failure
            assertEquals(CatalogFailure.RATE_LIMITED, result.reason)
            assertEquals(86_401_000L, result.retryAtEpochMs)
            assertEquals(1, calls)
        }
    }
}
