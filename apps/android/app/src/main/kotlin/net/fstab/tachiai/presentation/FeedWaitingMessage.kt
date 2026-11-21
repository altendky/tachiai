package net.fstab.tachiai.presentation

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// A waiting feed's pane text (#587): one brief line, then when it was last
// checked. Times use the device's zone and locale. A start time is the
// provider's schedule, shown as given; it is not a promise the show is live.
internal fun feedWaitingMessage(kind: FeedWaitingKind, startEpochMs: Long?, lastCheckedEpochMs: Long?,
    nowEpochMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    fun clock(epochMs: Long) = time.format(Instant.ofEpochMilli(epochMs).atZone(zone))
    // Another local day names the weekday, e.g. "Sat 9:30 PM".
    fun moment(epochMs: Long, preposition: String): String {
        val then = Instant.ofEpochMilli(epochMs).atZone(zone)
        return if (then.toLocalDate() == Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate())
            "$preposition ${clock(epochMs)}"
        else "${DateTimeFormatter.ofPattern("EEE", locale).format(then)} ${clock(epochMs)}"
    }
    val line = when {
        startEpochMs == null -> if (kind == FeedWaitingKind.CHANNEL) "Waiting for this channel to go live."
            else "Waiting for this broadcast to start."
        startEpochMs > nowEpochMs ->
            "Not live yet. Starts ${moment(startEpochMs, "at")} (in ${waitingRemaining(startEpochMs - nowEpochMs)})."
        else -> "Not live yet. Should have started ${moment(startEpochMs, "at")}."
    }
    return lastCheckedEpochMs?.let { "$line\nLast checked ${clock(it)}" } ?: line
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
