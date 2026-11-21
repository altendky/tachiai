package net.fstab.tachiai.presentation

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedWaitingMessageTest {
    private val zone = ZoneId.of("America/New_York")
    private val locale = Locale.US
    // 2026-11-20 21:00 local.
    private val now = Instant.parse("2026-11-21T02:00:00Z").toEpochMilli()
    private fun clock(epochMs: Long) = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        .format(Instant.ofEpochMilli(epochMs).atZone(zone))
    private fun message(kind: FeedWaitingKind, start: Long?, checked: Long? = null) =
        feedWaitingMessage(kind, start, checked, now, zone, locale)

    @Test fun aKnownStartTodayCountsDown() {
        val start = now + 29 * 60_000 + 30_000
        assertEquals("Not live yet. Starts at ${clock(start)} (in 30 min).", message(FeedWaitingKind.CHANNEL, start))
        assertEquals("Not live yet. Starts at ${clock(now + 45_000)} (in 45 s).", message(FeedWaitingKind.OCCURRENCE, now + 45_000))
    }

    @Test fun hoursAndAnotherDayAreNamed() {
        val later = now + (2 * 60 + 5) * 60_000L
        assertEquals("Not live yet. Starts at ${clock(later)} (in 2 h 5 min).", message(FeedWaitingKind.CHANNEL, later))
        // Past local midnight names the weekday.
        val afterMidnight = now + (3 * 60 + 5) * 60_000L
        assertEquals("Not live yet. Starts Sat ${clock(afterMidnight)} (in 3 h 5 min).",
            message(FeedWaitingKind.CHANNEL, afterMidnight))
        val tomorrow = now + 26 * 3_600_000L
        assertEquals("Not live yet. Starts Sat ${clock(tomorrow)} (in 26 h).", message(FeedWaitingKind.CHANNEL, tomorrow))
        val sameDay = now + 2 * 3_600_000L
        assertEquals("Not live yet. Starts at ${clock(sameDay)} (in 2 h).", message(FeedWaitingKind.CHANNEL, sameDay))
    }

    @Test fun aPassedStartAndAnUnknownStartAreExplained() {
        val passed = now - 5 * 60_000
        assertEquals("Not live yet. Should have started at ${clock(passed)}.", message(FeedWaitingKind.OCCURRENCE, passed))
        assertEquals("Waiting for this channel to go live.", message(FeedWaitingKind.CHANNEL, null))
        assertEquals("Waiting for this broadcast to start.", message(FeedWaitingKind.OCCURRENCE, null))
    }

    @Test fun theLastCheckIsOnItsOwnLine() {
        assertEquals("Waiting for this channel to go live.\nLast checked ${clock(now - 60_000)}",
            message(FeedWaitingKind.CHANNEL, null, checked = now - 60_000))
    }

    @Test fun remainingTimeRoundsUp() {
        assertEquals("1 s", waitingRemaining(1))
        assertEquals("59 s", waitingRemaining(59_000))
        assertEquals("1 min", waitingRemaining(60_000))
        assertEquals("2 min", waitingRemaining(61_000))
        assertEquals("59 min", waitingRemaining(59 * 60_000L))
        assertEquals("1 h", waitingRemaining(60 * 60_000L))
        assertEquals("1 h 1 min", waitingRemaining(60 * 60_000L + 1))
        assertEquals("24 h", waitingRemaining(24 * 3_600_000L))
    }
}
