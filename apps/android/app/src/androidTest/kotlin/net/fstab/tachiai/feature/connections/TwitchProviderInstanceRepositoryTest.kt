package net.fstab.tachiai.feature.connections

import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import net.fstab.tachiai.presentation.*
import net.fstab.tachiai.provider.twitch.*
import org.junit.Assert.*
import org.junit.Test

class TwitchProviderInstanceRepositoryTest {
    @Test fun defaultUsesExactHistoricalLocalRepositoryAndNewIdsHaveSeparateSingletonOwners() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val old = AndroidTwitchAuthorization.get(context, TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL)
        assertSame(old, AndroidTwitchAuthorization.forProviderInstance(context, defaultProviderInstanceId(PrototypeService.TWITCH)))
        val id = UUID.randomUUID().toString()
        val created = AndroidTwitchAuthorization.forProviderInstance(context, id)
        assertNotSame(old, created)
        assertSame(created, AndroidTwitchAuthorization.forProviderInstance(context, id))
        assertNotSame(created, AndroidTwitchAuthorization.forProviderInstance(context, UUID.randomUUID().toString()))
        assertEquals(TwitchAuthorizationProfile.PROVIDER_SMART_TV_LOCAL, created.profile)
        // Accessors construct repositories only: no grant read, write or keystore operation.
    }
}
