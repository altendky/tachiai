package net.fstab.tachiai.provider.abema.account

import org.junit.Assert.*
import org.junit.Test

// #783: a lease session's end in the ordinary process waits for a cancelled
// validation to finish, and the client waits longer still for its CLOSED.
class AbemaAccountSessionEndTest {
    private class Clock { var now = 1_000L }

    @Test fun aCancelledValidationFinishingAfterTheOldFiveSecondBoundStillConfirms() {
        val clock = Clock(); var closes = 0
        val result = awaitAbemaSessionEnd(AbemaAccountAccessIpc.END_MS, { clock.now }, { clock.now += 10 },
            { closes++ }, idle = { clock.now >= 1_000L + 6_500L }, confirmed = { clock.now >= 1_000L + 7_000L })
        assertEquals(AbemaSessionEnd.CONFIRMED, result)
        assertTrue("An attachment created late is closed again on every pass", closes > 600)
    }

    @Test fun workOrAttachmentThatNeverFinishesIsUnconfirmedAtTheBound() {
        val clock = Clock()
        assertEquals(AbemaSessionEnd.JOBS_RUNNING, awaitAbemaSessionEnd(500, { clock.now }, { clock.now += 10 }, {},
            idle = { false }, confirmed = { true }))
        assertEquals(AbemaSessionEnd.ATTACHMENT_UNCONFIRMED, awaitAbemaSessionEnd(500, { clock.now }, { clock.now += 10 }, {},
            idle = { true }, confirmed = { false }))
    }

    @Test fun aBackwardClockOrInterruptionNeverConfirms() {
        val clock = Clock()
        assertEquals(AbemaSessionEnd.CLOCK, awaitAbemaSessionEnd(500, { clock.now }, { clock.now -= 10 }, {},
            idle = { false }, confirmed = { false }))
        assertEquals(AbemaSessionEnd.INTERRUPTED, awaitAbemaSessionEnd(500, { clock.now }, { throw InterruptedException() }, {},
            idle = { true }, confirmed = { false }))
        assertTrue(Thread.interrupted())
    }

    @Test fun theClientWaitsLongerThanTheServiceAndBothFitTheViewerCleanupDeadline() {
        assertTrue(AbemaAccountAccessIpc.CLOSE_REPLY_MS > AbemaAccountAccessIpc.END_MS)
        // CachedViewerCleanupCoordinator poisons a run that has not finished in 20 s.
        assertTrue(AbemaAccountAccessIpc.CLOSE_REPLY_MS < 20_000L)
    }

    @Test fun theEndLineCarriesOnlyAClosedResultAndMilliseconds() {
        assertEquals("owner=SESSION_END result=JOBS_RUNNING waitedMs=10000", abemaSessionEndLine(AbemaSessionEnd.JOBS_RUNNING, 10_000))
        assertEquals("owner=SESSION_END result=REJECTED waitedMs=-1", abemaSessionEndLine(AbemaSessionEnd.REJECTED, -1))
    }
}
