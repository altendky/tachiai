package net.fstab.tachiai.presentation

import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class FeedAiringLookupTest {
    private val epoch = 1_800_000_000_000L
    private fun program(title: String, start: Long, end: Long) = CatalogScheduledProgram(title, start, end)
    private fun value(current: CatalogScheduledProgram? = null, next: CatalogScheduledProgram? = null) =
        CatalogResult.Value(CatalogAiring(current, next))
    private val next = program("Next", epoch + 29 * 60_000, epoch + 89 * 60_000)

    @Test fun theFirstRefreshLooksUpAndTheAnswerAppearsWhenItArrives() {
        val lookup = FeedAiringLookup()
        assertNull(lookup.program(epoch))
        val token = checkNotNull(lookup.begin(0, epoch))
        // One lookup at a time; the pane stays plain meanwhile.
        assertNull(lookup.begin(120_000, epoch + 120_000)); assertNull(lookup.program(epoch))
        assertTrue(lookup.complete(token, value(next = next), 30_000, epoch + 30_000))
        assertEquals(FeedWaitingProgram("Next", next.startEpochMs, next.endEpochMs, now = false), lookup.program(epoch + 30_000))
        assertEquals(next.startEpochMs, lookup.start(epoch + 30_000))
        // Once the next program's start has passed it is no longer shown as next.
        assertNull(lookup.program(next.startEpochMs))
    }

    @Test fun aSupersededOrDuplicateResultIsDropped() {
        val lookup = FeedAiringLookup()
        val token = checkNotNull(lookup.begin(0, epoch))
        assertFalse(lookup.complete(FeedAiringToken(), value(next = next), 1_000, epoch + 1_000))
        assertNull(lookup.program(epoch + 1_000))
        assertTrue(lookup.complete(token, value(), 1_000, epoch + 1_000))
        assertFalse(lookup.complete(token, value(next = next), 2_000, epoch + 2_000))
        assertNull(lookup.program(epoch + 2_000))
    }

    @Test fun anAnswerIsReusedUntilStaleOrItsBoundaryPasses() {
        val lookup = FeedAiringLookup()
        lookup.complete(checkNotNull(lookup.begin(0, epoch)), value(next = next), 0, epoch)
        // Waiting checks every minute or 15 s do not repeat the lookup.
        for (t in listOf(15_000L, 60_000L, 600_000L, 28 * 60_000L)) assertNull(t.toString(), lookup.begin(t, epoch + t))
        // The next program's start has passed: ask again, at the next refresh.
        val after = 29 * 60_000L
        val token = checkNotNull(lookup.begin(after, epoch + after))
        val now = program("Next", next.startEpochMs, next.endEpochMs)
        lookup.complete(token, value(current = now), after, epoch + after)
        assertEquals(FeedWaitingProgram("Next", now.startEpochMs, now.endEpochMs, now = true), lookup.program(epoch + after))
        assertEquals(now.startEpochMs, lookup.start(epoch + after))
        // Nothing upcoming and no boundary: only staleness asks again.
        val empty = FeedAiringLookup()
        empty.complete(checkNotNull(empty.begin(0, epoch)), value(), 0, epoch)
        assertNull(empty.begin(FeedAiringLookup.FRESH_MS - 1, epoch + FeedAiringLookup.FRESH_MS - 1))
        assertNotNull(empty.begin(FeedAiringLookup.FRESH_MS, epoch + FeedAiringLookup.FRESH_MS))
    }

    @Test fun lookupsNeverRunMoreThanOnceAMinute() {
        val lookup = FeedAiringLookup()
        // A start seconds away passes before the next refresh.
        val soon = program("Soon", epoch + 5_000, epoch + 60_000)
        lookup.complete(checkNotNull(lookup.begin(0, epoch)), value(next = soon), 1_000, epoch + 1_000)
        assertNull(lookup.begin(15_000, epoch + 15_000))
        assertNull(lookup.begin(59_999, epoch + 59_999))
        assertNotNull(lookup.begin(60_000, epoch + 60_000))
    }

    @Test fun failuresKeepThePlainPaneAndBackOff() {
        val lookup = FeedAiringLookup()
        lookup.complete(checkNotNull(lookup.begin(0, epoch)), CatalogResult.Failure(CatalogFailure.TEMPORARY), 45_000, epoch + 45_000)
        assertNull(lookup.program(epoch + 45_000))
        val first = 45_000 + FeedAiringLookup.FAILURE_MS
        assertNull(lookup.begin(first - 1, epoch + first - 1))
        lookup.complete(checkNotNull(lookup.begin(first, epoch + first)), CatalogResult.Failure(CatalogFailure.ACCESS_REQUIRED), first, epoch + first)
        val second = first + 2 * FeedAiringLookup.FAILURE_MS
        assertNull(lookup.begin(second - 1, epoch + second - 1))
        assertNotNull(lookup.begin(second, epoch + second))
        // A rate limit waits at least until its retry time.
        val limited = FeedAiringLookup()
        limited.complete(checkNotNull(limited.begin(0, epoch)), CatalogResult.Failure(CatalogFailure.RATE_LIMITED, epoch + 20 * 60_000), 0, epoch)
        assertNull(limited.begin(20 * 60_000 - 1, epoch + 20 * 60_000 - 1))
        assertNotNull(limited.begin(20 * 60_000, epoch + 20 * 60_000))
        // A provider without schedules is never asked again.
        val unsupported = FeedAiringLookup()
        unsupported.complete(checkNotNull(unsupported.begin(0, epoch)), CatalogResult.Failure(CatalogFailure.UNSUPPORTED), 0, epoch)
        assertNull(unsupported.begin(10 * FeedAiringLookup.FRESH_MS, epoch + 10 * FeedAiringLookup.FRESH_MS))
    }

    @Test fun aFailedRefreshKeepsAStillValidAnswerOnlyWhileItApplies() {
        val lookup = FeedAiringLookup()
        val current = program("Now", epoch - 60_000, epoch + 40 * 60_000)
        lookup.complete(checkNotNull(lookup.begin(0, epoch)), value(current = current), 0, epoch)
        val stale = FeedAiringLookup.FRESH_MS
        lookup.complete(checkNotNull(lookup.begin(stale, epoch + stale)), CatalogResult.Failure(CatalogFailure.TEMPORARY), stale, epoch + stale)
        assertEquals("Now", lookup.program(epoch + stale)?.title)
        assertNull(lookup.program(current.endEpochMs))
    }

    @Test fun theStartClosestToNowTimesTheChecks() {
        val lookup = FeedAiringLookup()
        assertNull(lookup.start(epoch))
        val current = program("Now", epoch - 60_000, epoch + 30 * 60_000)
        val following = program("Later", epoch + 30 * 60_000, epoch + 90 * 60_000)
        lookup.complete(checkNotNull(lookup.begin(0, epoch)), value(current, following), 0, epoch)
        assertEquals(current.startEpochMs, lookup.start(epoch))
        assertEquals(following.startEpochMs, lookup.start(epoch + 20 * 60_000))
    }
}
