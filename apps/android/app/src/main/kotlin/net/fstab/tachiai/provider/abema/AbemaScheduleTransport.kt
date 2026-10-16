package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.provider.catalog.*

// Local presentation/IPC bounds, not provider limits or a complete timetable.
internal const val ABEMA_SCHEDULE_PREFIX_LIMIT = 501
internal const val ABEMA_SCHEDULE_PAGE_LIMIT = 100
internal const val ABEMA_SCHEDULE_PROJECTION_BYTES = 256 * 1024

internal data class AbemaScheduleRequest(val channelId: String? = null) {
    init { require(channelId == null || AbemaSlotRequest.validId(channelId)) }
    override fun toString() = "AbemaScheduleRequest(redacted)"
}

internal class AbemaScheduleSnapshot(entries: List<CatalogEntry>, val truncated: Boolean = false,
    val request: AbemaScheduleRequest = AbemaScheduleRequest()) {
    val entries: List<CatalogEntry> = entries.toList()
    init {
        require(this.entries.size <= ABEMA_SCHEDULE_PREFIX_LIMIT)
        require(!truncated || this.entries.size == ABEMA_SCHEDULE_PREFIX_LIMIT)
        require(this.entries.map { it.resource }.distinct().size == this.entries.size)
        require(this.entries.map { AbemaSlotRequest.from(it.resource)?.slotId }.distinct().size == this.entries.size)
        require(this.entries.all { AbemaSlotRequest.from(it.resource) != null &&
            (request.channelId == null || AbemaSlotRequest.from(it.resource)?.channelId == request.channelId) &&
            safeAbemaSeriesText(it.title, 160) && it.availability == CatalogAvailability.UNKNOWN &&
            it.scheduledStartEpochMs == null })
    }
    override fun toString() = "AbemaScheduleSnapshot(redacted)"
}

internal interface AbemaScheduleTransport : AutoCloseable {
    fun fetch(request: AbemaScheduleRequest = AbemaScheduleRequest()): CatalogResult<AbemaScheduleSnapshot>
    fun cancelActiveRequest()
}
