package net.fstab.tachiai.feature.connections

import net.fstab.tachiai.platform.network.ProviderStartupCleanupAdmission
import org.junit.Assert.*
import org.junit.Test

class ProviderCleanupNoticeTest {
    @Test fun unconfirmedCleanupOffersRestartRecovery() {
        val notice = checkNotNull(providerCleanupNotice(ProviderStartupCleanupAdmission.CLEANUP_UNCONFIRMED))
        assertEquals(ProviderCleanupAction.RESTART, notice.action)
        assertEquals("Connection cleanup could not be confirmed. Restart Tachiai before using this provider again. " +
            "Saved app data will be kept.", notice.message)
        assertEquals("Connection cleanup unconfirmed", notice.status)
        assertFalse(notice.message.contains("Retry"))
    }

    @Test fun deadlineKeepsRetryShortly() {
        val notice = checkNotNull(providerCleanupNotice(ProviderStartupCleanupAdmission.DEADLINE))
        assertEquals(ProviderCleanupAction.RETRY, notice.action)
        assertEquals("The previous connection has not finished closing. Retry shortly.", notice.message)
        assertEquals("Waiting for connection cleanup", notice.status)
    }

    @Test fun admittedOrCancelledWaitsShowNoNotice() {
        assertNull(providerCleanupNotice(ProviderStartupCleanupAdmission.READY))
        assertNull(providerCleanupNotice(ProviderStartupCleanupAdmission.CANCELLED))
    }
}
