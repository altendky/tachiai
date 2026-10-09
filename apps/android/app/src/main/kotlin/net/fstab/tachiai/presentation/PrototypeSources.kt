package net.fstab.tachiai.presentation

internal enum class PrototypeService(val title: String) { ABEMA("ABEMA"), TWITCH("Twitch") }
internal enum class PrototypePlaybackKind { LIVE, REPLAY }

// A small initial catalogue, not a restriction that a service has one channel.
// Public identities only; provider adapters own resolution and authenticated data.
internal enum class PrototypeSource(
    val service: PrototypeService,
    val kind: PrototypePlaybackKind,
    val title: String,
    val resourceId: String? = null,
) {
    ABEMA_LIVE(PrototypeService.ABEMA, PrototypePlaybackKind.LIVE, "ABEMA · News · Live"),
    ABEMA_REPLAY(PrototypeService.ABEMA, PrototypePlaybackKind.REPLAY, "ABEMA · Sumo · Replay"),
    TWITCH_LIVE(PrototypeService.TWITCH, PrototypePlaybackKind.LIVE, "Twitch · Izgonnabemei · Live", "izgonnabemei"),
    TWITCH_CHILLHOP_LIVE(PrototypeService.TWITCH, PrototypePlaybackKind.LIVE, "Twitch · Chillhop Radio · Live", "chillhopradio"),
    TWITCH_VIRTUAL_JAPAN_LIVE(PrototypeService.TWITCH, PrototypePlaybackKind.LIVE, "Twitch · Virtual Japan · Live", "virtualjapan"),
    TWITCH_REPLAY(PrototypeService.TWITCH, PrototypePlaybackKind.REPLAY, "Twitch · Rocket League · Replay", "2080217716");

    val optionTitle: String get() = title.removePrefix("${service.title} · ")
    fun slotLabel(slot: String) = "$slot · $title"
}

internal data class PrototypeSelection(
    val a: PrototypeSource = PrototypeSource.ABEMA_LIVE,
    val b: PrototypeSource = PrototypeSource.TWITCH_LIVE,
    val aInstanceId: String = defaultProviderInstanceId(a.service),
    val bInstanceId: String = defaultProviderInstanceId(b.service),
) {
    init { require(validProviderInstanceId(aInstanceId) && validProviderInstanceId(bInstanceId)) }
    // Duplicate source choices are intentional: each slot owns a separate host.
    val sources get() = listOf(a, b)
    val feeds get() = listOf(PrototypeFeedChoice(a, aInstanceId), PrototypeFeedChoice(b, bInstanceId))
}

internal enum class PrototypeSlot { A, B }

// An incomplete picker draft never reaches playback preparation.
internal data class PrototypeSourceAssignments(
    val a: PrototypeSource? = PrototypeSource.ABEMA_LIVE,
    val b: PrototypeSource? = PrototypeSource.TWITCH_LIVE,
) {
    fun assign(slot: PrototypeSlot, source: PrototypeSource, checked: Boolean): PrototypeSourceAssignments {
        val previous = if (slot == PrototypeSlot.A) a else b
        val next = if (checked) source else previous?.takeUnless { it == source }
        return if (slot == PrototypeSlot.A) copy(a = next) else copy(b = next)
    }

    fun selectionOrNull(): PrototypeSelection? =
        if (a != null && b != null) PrototypeSelection(a, b) else null
}
