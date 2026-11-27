package net.fstab.tachiai.platform.diagnostics

import org.junit.Assert.*
import org.junit.Test

class OperationStatsTest {
    @Test fun drainsClosedCountsAndDurationsOncePerInterval() {
        val stats = OperationStats("owner=CHECK_STATS", intervalMs = 1_000)
        assertNull(stats.drain(5_000))
        stats.time("check", 12, 10_000)
        stats.time("check", 30, 10_100)
        stats.count("refused", 10_200)
        stats.time("check", -5, 10_300)
        assertNull(stats.drain(10_999))
        assertEquals("owner=CHECK_STATS windowMs=1000 refused=1 check=3 checkMs=42 checkMaxMs=30", stats.drain(11_000))
        assertNull(stats.drain(20_000))
        stats.count("dispatched", 30_000)
        assertEquals("owner=CHECK_STATS windowMs=1500 dispatched=1", stats.drain(31_500))
    }

    @Test fun peaksKeepTheWindowMaximumAndForcedDrainEmitsEarly() {
        val stats = OperationStats("journal=FAILURE_WRITER", intervalMs = 30_000)
        assertNull(stats.drain(0, force = true))
        stats.count("queued", 1_000)
        stats.peak("queueDepth", 3, 1_000)
        stats.peak("queueDepth", 7, 1_100)
        stats.peak("queueDepth", 2, 1_200)
        stats.time("flush", 40, 1_300)
        assertNull(stats.drain(2_000))
        assertEquals("journal=FAILURE_WRITER windowMs=1000 queued=1 queueDepth=7 flush=1 flushMs=40 flushMaxMs=40",
            stats.drain(2_000, force = true))
        assertNull(stats.drain(40_000, force = true))
        assertThrows(IllegalArgumentException::class.java) { stats.peak("queue depth", 1, 0) }
    }

    @Test fun onlyClosedNamesAreAccepted() {
        for (name in listOf("", "owner=", "owner=check", "Owner=CHECK", "owner=CHECK user"))
            assertThrows(IllegalArgumentException::class.java) { OperationStats(name) }
        val stats = OperationStats("store=PROVIDER_INSTANCES")
        for (event in listOf("", "Check", "check=1", "check host", "x".repeat(33)))
            assertThrows(IllegalArgumentException::class.java) { stats.count(event, 0) }
    }
}
