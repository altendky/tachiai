package net.fstab.tachiai.platform.media

import org.junit.Assert.*
import org.junit.Test

// #722: Media3 matches live DASH periods by position. A refresh that loses a
// period after its first one is refused as out of sync, and with no reloads
// left that failed the feed with 2000. Retained periods may only leave from
// the front.
class NativeRetainedDashPeriodsTest {
    private data class P(val id: String?, val startMs: Long)

    private fun retained(previous: List<P>, server: List<P>, retainable: Set<String>) =
        nativeRetainedDashPeriods(previous, server, { it.id }, { it.startMs }) { it in retainable }

    private val programme = P("programme", 0)
    private val ad1 = P("ad1", 100_000)
    private val ad2 = P("ad2", 115_000)
    private val ad3 = P("ad3", 130_000)
    private val next = P("next", 145_000)

    @Test fun `every dropped period stays while all are cached`() {
        assertEquals(listOf(programme, ad1, ad2),
            retained(listOf(programme, ad1, ad2, ad3), listOf(ad3, next), setOf("programme", "ad1", "ad2")))
    }

    @Test fun `a period that is not retainable ends retention before it, never in the middle`() {
        // An ad period with a missed recording between two cached periods.
        assertEquals(listOf(ad2), retained(listOf(programme, ad1, ad2, ad3), listOf(ad3, next),
            setOf("programme", "ad2")))
        // The newest dropped period missing keeps none, though older ones are cached.
        assertEquals(emptyList<P>(), retained(listOf(programme, ad1, ad2, ad3), listOf(ad3, next),
            setOf("programme", "ad1")))
    }

    @Test fun `the result is always a run directly before the providers first period`() {
        val previous = listOf(programme, ad1, ad2, ad3)
        val server = listOf(ad3, next)
        val ids = listOf("programme", "ad1", "ad2")
        for (mask in 0 until (1 shl ids.size)) {
            val retainable = ids.filterIndexed { index, _ -> mask and (1 shl index) != 0 }.toSet()
            val kept = retained(previous, server, retainable)
            val before = previous.filter { it.startMs < server.first().startMs }
            assertEquals("mask=$mask", before.takeLast(kept.size), kept)
            assertTrue(kept.all { it.id in retainable })
        }
    }

    @Test fun `periods without an id or still declared by the provider end retention`() {
        assertEquals(listOf(ad2), retained(listOf(programme, P(null, 50_000), ad2), listOf(ad3),
            setOf("programme", "ad2")))
        // The provider moved a period it still declares: it is not duplicated.
        assertEquals(emptyList<P>(), retained(listOf(programme, P("ad3", 120_000)), listOf(ad3),
            setOf("programme", "ad3")))
    }

    @Test fun `nothing is retained from or before periods the provider still lists`() {
        assertEquals(emptyList<P>(), retained(listOf(ad1, ad2), listOf(ad1, ad2, ad3), setOf("ad1", "ad2")))
        assertEquals(emptyList<P>(), retained(listOf(ad1), emptyList(), setOf("ad1")))
    }

    @Test fun `a refresh is out of sync only when it lost a period after its first one`() {
        val previous = listOf(programme, ad1, ad2).map { it.startMs }
        // Rolling forward, and announcing a new period, are in sync.
        assertFalse(nativeDashPeriodsOutOfSync(previous, programme.startMs, 3))
        assertFalse(nativeDashPeriodsOutOfSync(previous, ad1.startMs, 2))
        assertFalse(nativeDashPeriodsOutOfSync(previous, ad2.startMs, 1))
        assertFalse(nativeDashPeriodsOutOfSync(previous, ad1.startMs, 3))
        assertFalse(nativeDashPeriodsOutOfSync(emptyList(), 0, 1))
        // The provider withdrew an announced ad period, or a retained middle
        // period was skipped.
        assertTrue(nativeDashPeriodsOutOfSync(previous, programme.startMs, 2))
        assertTrue(nativeDashPeriodsOutOfSync(previous, ad1.startMs, 1))
        // A first period that starts earlier removes nothing.
        assertTrue(nativeDashPeriodsOutOfSync(previous, -1, 2))
    }
}
