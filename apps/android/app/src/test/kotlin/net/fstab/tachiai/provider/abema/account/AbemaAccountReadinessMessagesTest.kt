package net.fstab.tachiai.provider.abema.account

import net.fstab.tachiai.presentation.PrototypeFailureReason
import org.junit.Assert.*
import org.junit.Test

class AbemaAccountReadinessMessagesTest {
    private fun message(result: AbemaAccountReadinessResult) = abemaAccountReadinessMessage(result)

    @Test fun everyAccountOutcomeMapsToOneSharedState() {
        // Ready proceeds without a message.
        assertNull(message(AbemaAccountReadinessResult.READY))
        // Renewing is the same text every screen shows while a renewal runs.
        assertEquals(ABEMA_ACCOUNT_RENEWING_MESSAGE, message(AbemaAccountReadinessResult.HANDOFF))
        // Needs you and the storage problem point to the account in Providers.
        for (result in listOf(AbemaAccountReadinessResult.INTERACTION_REQUIRED, AbemaAccountReadinessResult.FAILURE,
            AbemaAccountReadinessResult.STORAGE_FAILURE))
            assertTrue(checkNotNull(message(result)).contains("Open ABEMA account in Providers"))
        assertTrue(checkNotNull(message(AbemaAccountReadinessResult.INTERACTION_REQUIRED)).contains("choose the saved account"))
        assertTrue(checkNotNull(message(AbemaAccountReadinessResult.STORAGE_FAILURE)).contains("could not be read or updated"))
        // Temporarily unavailable only after retries are exhausted; retry, not Providers.
        val temporary = checkNotNull(message(AbemaAccountReadinessResult.TEMPORARY_FAILURE))
        assertTrue(temporary.contains("temporarily unavailable"))
        assertFalse(temporary.contains("Providers"))
        // Each outcome keeps its own distinct, non-blank text.
        val shown = AbemaAccountReadinessResult.entries.mapNotNull(::message)
        assertEquals(AbemaAccountReadinessResult.entries.size - 1, shown.distinct().size)
        assertTrue(shown.all { it.isNotBlank() })
        assertNotEquals(ABEMA_ACCOUNT_CHECKING_MESSAGE, ABEMA_ACCOUNT_RENEWING_MESSAGE)
    }

    // #783: a poisoned account instance says what provider details and Manage
    // streams say, where their Restart is offered.
    @Test fun anUnconfirmedAccountCleanupUsesTheProvidersRestartNotice() {
        assertEquals(net.fstab.tachiai.feature.connections.PROVIDER_CLEANUP_RESTART_MESSAGE,
            message(AbemaAccountReadinessResult.CLEANUP_UNCONFIRMED))
    }

    @Test fun theFeedsUnresolvedAccountFailurePointsToTheSameProvidersPlace() {
        val feed = PrototypeFailureReason.ABEMA_ACCOUNT_UNAVAILABLE.message
        assertTrue(feed.endsWith("Open ABEMA account in Providers to check the connection."))
        assertTrue(checkNotNull(message(AbemaAccountReadinessResult.FAILURE))
            .endsWith("Open ABEMA account in Providers to check the connection."))
    }
}
