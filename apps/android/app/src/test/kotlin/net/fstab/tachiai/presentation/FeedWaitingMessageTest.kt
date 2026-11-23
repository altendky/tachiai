package net.fstab.tachiai.presentation

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private fun program(start: Long, end: Long, now: Boolean = false) = FeedWaitingProgram("大相撲LIVE 13日目", start, end, now)
    private fun scheduled(program: FeedWaitingProgram, checked: Long? = null, kind: FeedWaitingKind = FeedWaitingKind.CHANNEL,
        start: Long? = null, zone: ZoneId = this.zone) =
        feedWaitingMessage(kind, start, checked, now, zone, locale, program)

    @Test fun aWaitingChannelNamesItsNextProgramBeforeTheLastCheck() {
        val start = now + 29 * 60_000L
        assertEquals("Waiting for this channel to go live.\nNext: 大相撲LIVE 13日目 at ${clock(start)} (in 29 min).\n" +
            "Last checked ${clock(now - 60_000)}", scheduled(program(start, start + 3_600_000), checked = now - 60_000))
        assertEquals("Waiting for this channel to go live.\nNext: 大相撲LIVE 13日目 at ${clock(start)} (in 29 min).",
            scheduled(program(start, start + 3_600_000)))
        // Another local day names the weekday; hours and minutes count down.
        val tomorrow = now + (12 * 60 + 30) * 60_000L
        assertEquals("Waiting for this channel to go live.\nNext: 大相撲LIVE 13日目 Sat ${clock(tomorrow)} (in 12 h 30 min).",
            scheduled(program(tomorrow, tomorrow + 60_000)))
    }

    @Test fun theProgramFollowsTheDeviceZoneAndClockSetting() {
        val start = Instant.parse("2026-11-21T02:30:00Z").toEpochMilli()
        val tokyo = ZoneId.of("Asia/Tokyo")
        val tokyoClock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(Instant.ofEpochMilli(start).atZone(tokyo))
        assertTrue(tokyoClock.startsWith("11:30") && tokyoClock.endsWith("AM"))
        assertEquals("Waiting for this channel to go live.\nNext: 大相撲LIVE 13日目 at $tokyoClock (in 30 min).",
            scheduled(program(start, start + 60_000), zone = tokyo))
        val day = feedWaitingMessage(FeedWaitingKind.CHANNEL, null, null, now, tokyo, Locale.UK, program(start, start + 60_000))
        assertEquals("Waiting for this channel to go live.\nNext: 大相撲LIVE 13日目 at 11:30 (in 30 min).", day)
    }

    @Test fun aProgramScheduledNowShowsWhenItEnds() {
        val end = now + 45 * 60_000L
        assertEquals("Waiting for this channel to go live.\nScheduled now: 大相撲LIVE 13日目 (until ${clock(end)}).",
            scheduled(program(now - 60_000, end, now = true)))
        val afterMidnight = now + 4 * 3_600_000L
        assertEquals("Waiting for this channel to go live.\nScheduled now: 大相撲LIVE 13日目 (until Sat ${clock(afterMidnight)}).",
            scheduled(program(now - 60_000, afterMidnight, now = true)))
    }

    @Test fun noProgramLineWithoutAnUpcomingChannelProgram() {
        // A next program whose start has passed, an occurrence, or nothing known keeps the plain pane.
        assertEquals("Waiting for this channel to go live.", scheduled(program(now - 1, now + 60_000)))
        assertEquals("Waiting for this broadcast to start.", scheduled(program(now + 60_000, now + 120_000), kind = FeedWaitingKind.OCCURRENCE))
        assertEquals("Waiting for this channel to go live.\nLast checked ${clock(now)}",
            feedWaitingMessage(FeedWaitingKind.CHANNEL, null, now, now, zone, locale, null))
        // A provider's own start keeps its line; the program is added below it.
        val start = now + 10 * 60_000L
        assertEquals("Not live yet. Starts at ${clock(start)} (in 10 min).\nNext: 大相撲LIVE 13日目 at ${clock(start)} (in 10 min).",
            scheduled(program(start, start + 60_000), start = start))
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

    // #632: a channel the provider isn't opening names that state.
    @Test fun aNotOpenChannelSaysSo() {
        val zone = java.time.ZoneId.of("UTC")
        val text = feedWaitingMessage(FeedWaitingKind.CHANNEL, null, null, 0, zone, java.util.Locale.US, notOpen = true)
        assertEquals("This channel isn't open for viewing right now. Waiting for it to open.", text)
        assertEquals("Waiting for this channel to go live.",
            feedWaitingMessage(FeedWaitingKind.CHANNEL, null, null, 0, zone, java.util.Locale.US))
        assertEquals("Waiting for this broadcast to start.",
            feedWaitingMessage(FeedWaitingKind.OCCURRENCE, null, null, 0, zone, java.util.Locale.US, notOpen = true))
    }
}
