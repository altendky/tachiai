package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.provider.catalog.*

// Local presentation/IPC bounds, not provider limits or a complete timetable.
internal const val ABEMA_SCHEDULE_PREFIX_LIMIT = 501
internal const val ABEMA_SCHEDULE_PAGE_LIMIT = 100
internal const val ABEMA_SCHEDULE_PROJECTION_BYTES = 256 * 1024
// Epoch seconds; the same ceiling as a scheduled start in milliseconds.
internal const val ABEMA_SCHEDULE_MAX_EPOCH_SECONDS = 99_999_999_999L

internal data class AbemaScheduleRequest(val channelId: String? = null) {
    init { require(channelId == null || AbemaSlotRequest.validId(channelId)) }
    override fun toString() = "AbemaScheduleRequest(redacted)"
}

// One slot's scheduled times in epoch seconds (#560). Only a channel-filtered
// snapshot carries them, for that channel's next-program line.
internal data class AbemaSlotTimes(val startEpochSeconds: Long, val endEpochSeconds: Long) {
    init {
        require(startEpochSeconds >= 1 && endEpochSeconds <= ABEMA_SCHEDULE_MAX_EPOCH_SECONDS &&
            endEpochSeconds - startEpochSeconds in 1..MAX_CATALOG_DURATION_SECONDS)
    }
}

internal class AbemaScheduleSnapshot(entries: List<CatalogEntry>, val truncated: Boolean = false,
    val request: AbemaScheduleRequest = AbemaScheduleRequest(), times: List<AbemaSlotTimes?>? = null) {
    val entries: List<CatalogEntry> = entries.toList()
    // Parallel to entries for a channel-filtered snapshot (null where unknown);
    // always empty for the browsable timetable. Entries never carry the times.
    val times: List<AbemaSlotTimes?> = times?.toList()
        ?: if (request.channelId == null) emptyList() else List(this.entries.size) { null }
    init {
        require(this.entries.size <= ABEMA_SCHEDULE_PREFIX_LIMIT)
        require(!truncated || this.entries.size == ABEMA_SCHEDULE_PREFIX_LIMIT)
        require(this.entries.map { it.resource }.distinct().size == this.entries.size)
        require(this.entries.map { AbemaSlotRequest.from(it.resource)?.slotId }.distinct().size == this.entries.size)
        require(this.entries.all { AbemaSlotRequest.from(it.resource) != null &&
            (request.channelId == null || AbemaSlotRequest.from(it.resource)?.channelId == request.channelId) &&
            safeAbemaSeriesText(it.title, 160) && it.availability == CatalogAvailability.UNKNOWN &&
            it.scheduledStartEpochMs == null })
        require(if (request.channelId == null) this.times.isEmpty() else this.times.size == this.entries.size)
    }
    override fun toString() = "AbemaScheduleSnapshot(redacted)"
}

internal interface AbemaScheduleTransport : AutoCloseable {
    fun fetch(request: AbemaScheduleRequest = AbemaScheduleRequest()): CatalogResult<AbemaScheduleSnapshot>
    fun cancelActiveRequest()
}
