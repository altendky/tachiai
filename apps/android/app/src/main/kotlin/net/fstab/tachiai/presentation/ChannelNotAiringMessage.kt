package net.fstab.tachiai.presentation

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// An off-air refusal looks identical after every Retry (#557), so it names when
// it was last checked. Seconds make a quick repeat visibly different. The time
// uses the device's zone and locale; it is not a provider schedule claim.
internal fun channelNotAiringMessage(failure: PrototypeFeedFailure, checkedAtEpochMs: Long,
    zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withLocale(locale)
        .format(Instant.ofEpochMilli(checkedAtEpochMs).atZone(zone))
    return "${failure.viewerMessage}\nLast checked at $time."
}
