package net.fstab.tachiai.provider.twitch

import net.fstab.tachiai.presentation.PrototypeFailureReason
import net.fstab.tachiai.presentation.PrototypeFeedFailure
import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class TwitchNotLiveFailureTest {
    private val resource = CatalogResource(ProviderId("twitch"), "broadcaster", "123", CatalogIntent.CHANNEL)
    private fun entry(availability: CatalogAvailability, start: Long? = null) =
        CatalogResult.Value(CatalogEntry(resource, "Fixture", availability, scheduledStartEpochMs = start))

    @Test fun laterChecksInAWaitingPeriodMakeNoStatusRequest() {
        var calls = 0
        val failure = twitchNotLiveFailure(lookup = false) { calls++; entry(CatalogAvailability.OFFLINE) }
        assertEquals(PrototypeFeedFailure(PrototypeFailureReason.NOT_LIVE_YET), failure)
        assertEquals(0, calls)
    }

    @Test fun anOfflineChannelWaitsWithItsPublishedStart() {
        assertEquals(PrototypeFeedFailure(PrototypeFailureReason.NOT_LIVE_YET, scheduledStartEpochMs = 1_800_000_600_000),
            twitchNotLiveFailure(true) { entry(CatalogAvailability.OFFLINE, 1_800_000_600_000) })
        assertEquals(PrototypeFeedFailure(PrototypeFailureReason.NOT_LIVE_YET),
            twitchNotLiveFailure(true) { entry(CatalogAvailability.OFFLINE) })
        // Went live between the two requests: the next check will start it.
        assertEquals(PrototypeFeedFailure(PrototypeFailureReason.NOT_LIVE_YET),
            twitchNotLiveFailure(true) { entry(CatalogAvailability.LIVE) })
    }

    @Test fun aMissingBroadcasterKeepsTodaysFailure() {
        assertEquals(PrototypeFeedFailure(PrototypeFailureReason.MEDIA_NOT_FOUND),
            twitchNotLiveFailure(true) { entry(CatalogAvailability.UNAVAILABLE) })
    }

    @Test fun aFailedStatusRequestStillWaitsWithoutAStart() {
        for (reason in CatalogFailure.entries)
            assertEquals(PrototypeFeedFailure(PrototypeFailureReason.NOT_LIVE_YET),
                twitchNotLiveFailure(true) { CatalogResult.Failure(reason) })
    }
}
