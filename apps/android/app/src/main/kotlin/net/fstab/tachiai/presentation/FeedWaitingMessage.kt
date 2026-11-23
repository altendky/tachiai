package net.fstab.tachiai.presentation

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// A program from a waiting channel's schedule (#560): the one scheduled now,
// or the next one. Display only; it is not a promise the channel is live.
internal data class FeedWaitingProgram(val title: String, val startEpochMs: Long, val endEpochMs: Long, val now: Boolean) {
    override fun toString() = "FeedWaitingProgram(redacted)"
}

// A waiting feed's pane text (#587): one brief line, a waiting channel's
// scheduled program when known (#560), then when it was last checked. Times
// use the device's zone and locale. A start time is the provider's schedule,
// shown as given; it is not a promise the show is live.
internal fun feedWaitingMessage(kind: FeedWaitingKind, startEpochMs: Long?, lastCheckedEpochMs: Long?,
    nowEpochMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault(),
    program: FeedWaitingProgram? = null, notOpen: Boolean = false): String {
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    fun clock(epochMs: Long) = time.format(Instant.ofEpochMilli(epochMs).atZone(zone))
    fun sameDay(epochMs: Long) = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate() ==
        Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
    fun weekday(epochMs: Long) = DateTimeFormatter.ofPattern("EEE", locale).format(Instant.ofEpochMilli(epochMs).atZone(zone))
    // Another local day names the weekday, e.g. "Sat 9:30 PM".
    fun moment(epochMs: Long, preposition: String): String =
        if (sameDay(epochMs)) "$preposition ${clock(epochMs)}" else "${weekday(epochMs)} ${clock(epochMs)}"
    val line = when {
        startEpochMs == null -> if (kind != FeedWaitingKind.CHANNEL) "Waiting for this broadcast to start."
            // The provider isn't opening the channel for viewing (#632).
            else if (notOpen) "This channel isn't open for viewing right now. Waiting for it to open."
            else "Waiting for this channel to go live."
        startEpochMs > nowEpochMs ->
            "Not live yet. Starts ${moment(startEpochMs, "at")} (in ${waitingRemaining(startEpochMs - nowEpochMs)})."
        else -> "Not live yet. Should have started ${moment(startEpochMs, "at")}."
    }
    val scheduled = program?.takeIf { kind == FeedWaitingKind.CHANNEL }?.let {
        if (it.now) "Scheduled now: ${it.title} (until ${if (sameDay(it.endEpochMs)) "" else weekday(it.endEpochMs) + " "}${clock(it.endEpochMs)})."
        else if (it.startEpochMs > nowEpochMs)
            "Next: ${it.title} ${moment(it.startEpochMs, "at")} (in ${waitingRemaining(it.startEpochMs - nowEpochMs)})."
        else null
    }
    val lines = listOfNotNull(line, scheduled, lastCheckedEpochMs?.let { "Last checked ${clock(it)}" })
    return lines.joinToString("\n")
}

// Seconds under a minute, minutes under an hour, hours and minutes under a
// day, then whole hours. Rounded up, so it never reads "in 0 s" early.
internal fun waitingRemaining(remainingMs: Long): String {
    val seconds = (remainingMs + 999) / 1000
    if (seconds < 60) return "$seconds s"
    val minutes = (seconds + 59) / 60
    if (minutes < 60) return "$minutes min"
    val hours = minutes / 60
    val rest = minutes % 60
    if (hours < 24) return if (rest == 0L) "$hours h" else "$hours h $rest min"
    return "${(minutes + 59) / 60} h"
}
