package net.fstab.tachiai.provider.abema.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// #635 step 3: a failed owner re-read is not reported as a changed owner.
class AbemaOwnerMismatchTest {
    @Test fun matchingChangedAndUnreadableOwnersAreDistinct() {
        assertNull(abemaOwnerMismatch { true })
        assertEquals(AbemaRenewalDiagnostic.Reason.OWNER_CHANGED, abemaOwnerMismatch { false })
        assertEquals(AbemaRenewalDiagnostic.Reason.OWNER_CHECK_FAILED, abemaOwnerMismatch { error("store unreadable") })
    }
}
