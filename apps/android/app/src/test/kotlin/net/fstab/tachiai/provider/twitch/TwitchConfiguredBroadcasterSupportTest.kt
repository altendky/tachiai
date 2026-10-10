package net.fstab.tachiai.provider.twitch

import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class TwitchConfiguredBroadcasterSupportTest {
    private fun resource(id: String) = CatalogResource(ProviderId("twitch"), "broadcaster", id, CatalogIntent.CHANNEL)

    @Test fun canonicalBroadcasterIdsUseCatalogBoundRatherThanNativeVideoOrLoginBound() {
        listOf("1", "123456789", "9".repeat(21), "9".repeat(32)).forEach {
            assertTrue(supportedConfiguredTwitchBroadcaster(resource(it)))
        }
        listOf("0", "01", "0".repeat(32), "1".repeat(33), "savedchannel", "123.4", "123/4", "123-4").forEach {
            assertFalse(supportedConfiguredTwitchBroadcaster(resource(it)))
        }
    }

    @Test fun onlyExactProviderKindAndOngoingChannelIntentAreAdmitted() {
        val exact = resource("123")
        listOf(exact.copy(providerId = ProviderId("abema")), exact.copy(kind = "channel"),
            exact.copy(kind = "video"), exact.copy(intent = CatalogIntent.VIDEO),
            exact.copy(intent = CatalogIntent.BROADCAST), exact.copy(intent = CatalogIntent.COLLECTION)).forEach {
            assertFalse(supportedConfiguredTwitchBroadcaster(it))
        }
    }
}
