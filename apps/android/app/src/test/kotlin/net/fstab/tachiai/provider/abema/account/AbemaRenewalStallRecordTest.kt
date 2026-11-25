package net.fstab.tachiai.provider.abema.account

import net.fstab.tachiai.provider.abema.account.AbemaRenewalStallRecord.Admission
import net.fstab.tachiai.provider.abema.account.AbemaRenewalStallRecord.Kind
import net.fstab.tachiai.provider.abema.account.AbemaRenewalStallRecord.Page
import org.junit.Assert.*
import org.junit.Test

class AbemaRenewalStallRecordTest {
    @Test fun aDeadlineRecordsWhatTheCommittedPageWasWaitingFor() {
        var now = 1_000L
        val record = AbemaRenewalStallRecord { now }
        record.started()
        record.admission(Kind.MAIN, Admission.ADMITTED)
        record.page(Page.STARTED)
        now = 2_000
        record.page(Page.COMMITTED)
        record.admission(Kind.OTHER, Admission.ADMITTED)
        record.admission(Kind.OTHER, Admission.POLICY_REFUSED)
        record.error(Kind.OTHER)
        record.admission(Kind.GATEWAY, Admission.ADMITTED)
        record.admission(Kind.REGION, Admission.ADMITTED)
        now = 3_000
        record.regionStarted()
        now = 93_500
        assertEquals("lateMs=2500 sinceProgressMs=91500 page=COMMITTED pendingMain=0 pendingRegion=1 " +
            "admitted=main:1,gateway:1,region:1,other:1 policyRefused=main:0,gateway:0,region:0,other:1 " +
            "inactiveRefused=main:0,gateway:0,region:0,other:0 errors=main:0,gateway:0,region:0,other:1",
            record.logFields(91_000))
    }

    @Test fun aMainFrameIsPendingUntilItCommitsOrFails() {
        var now = 0L
        val record = AbemaRenewalStallRecord { now }
        assertTrue(record.logFields(0).contains("sinceProgressMs=-1 page=NONE pendingMain=0"))
        record.admission(Kind.MAIN, Admission.ADMITTED)
        now = 30_000
        assertTrue(record.logFields(30_000).startsWith("lateMs=0 sinceProgressMs=30000 page=NONE pendingMain=1 "))
        record.error(Kind.MAIN)
        assertTrue(record.logFields(30_000).contains("pendingMain=0"))
        record.admission(Kind.MAIN, Admission.INACTIVE_REFUSED)
        record.regionEnded() // An unmatched end never goes negative.
        // A new main-frame load starts over.
        record.page(Page.COMMITTED)
        record.page(Page.STARTED)
        record.admission(Kind.MAIN, Admission.ADMITTED)
        assertTrue(record.logFields(30_000).contains("page=STARTED pendingMain=1"))
        assertTrue(record.logFields(30_000).contains("pendingRegion=0"))
        assertTrue(record.logFields(30_000).contains("inactiveRefused=main:1,"))
    }

    @Test fun theLineHoldsOnlyClosedNamesAndCounts() {
        val record = AbemaRenewalStallRecord { error("clock unavailable") }
        record.admission(Kind.REGION, Admission.ADMITTED)
        val line = record.logFields(5)
        assertTrue(line.startsWith("lateMs=0 sinceProgressMs=-1 "))
        assertTrue(line.split(' ').all { it.matches(Regex("[A-Za-z]+=[A-Za-z0-9:,_-]+")) })
        assertFalse(line.contains('/') || line.contains('.') || line.contains('?'))
    }

    @Test fun lateness() {
        assertEquals(0, abemaRenewalLateMs(10_000, 9_000))
        assertEquals(122_000, abemaRenewalLateMs(10_000, 132_000))
        assertEquals(0, abemaRenewalLateMs(10_000, -1))
    }
}
