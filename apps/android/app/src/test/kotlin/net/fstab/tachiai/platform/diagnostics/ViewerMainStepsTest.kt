package net.fstab.tachiai.platform.diagnostics

import org.junit.Assert.*
import org.junit.Test

// #769: closed main-thread step lines carry only a fixed name and milliseconds.
class ViewerMainStepsTest {
    @Test fun lineHoldsOnlyTheStepNameAndBoundedMilliseconds() {
        assertEquals("viewer=MAIN_STEP step=playerRelease ms=42", ViewerMainSteps.line("playerRelease", 42))
        assertEquals("viewer=MAIN_STEP step=stop ms=0", ViewerMainSteps.line("stop", -5))
        assertThrows(IllegalArgumentException::class.java) { ViewerMainSteps.line("https://example.invalid/x", 1) }
        assertThrows(IllegalArgumentException::class.java) { ViewerMainSteps.line("Stop step", 1) }
    }

    @Test fun timedStepReturnsItsResultAndRethrows() {
        assertEquals(7, ViewerMainSteps.time("startPlayer") { 7 })
        assertThrows(IllegalStateException::class.java) { ViewerMainSteps.time<Unit>("startPlayer") { error("synthetic") } }
    }
}
