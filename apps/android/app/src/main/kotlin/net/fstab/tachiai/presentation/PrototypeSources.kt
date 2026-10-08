package net.fstab.tachiai.presentation

internal enum class PrototypeService { ABEMA, TWITCH }
internal enum class PrototypePlaybackKind { LIVE, REPLAY }

// A small initial catalogue, not a restriction that a service has one channel.
// Public identities only; provider adapters own resolution and authenticated data.
internal enum class PrototypeSource(
    val service: PrototypeService,
    val kind: PrototypePlaybackKind,
    val title: String,
) {
    ABEMA_LIVE(PrototypeService.ABEMA, PrototypePlaybackKind.LIVE, "ABEMA · News · Live"),
    ABEMA_REPLAY(PrototypeService.ABEMA, PrototypePlaybackKind.REPLAY, "ABEMA · Sumo · Replay"),
    TWITCH_LIVE(PrototypeService.TWITCH, PrototypePlaybackKind.LIVE, "Twitch · Izgonnabemei · Live"),
    TWITCH_REPLAY(PrototypeService.TWITCH, PrototypePlaybackKind.REPLAY, "Twitch · Rocket League · Replay");

    fun slotLabel(slot: String) = "$slot · $title"
}

internal data class PrototypeSelection(
    val a: PrototypeSource = PrototypeSource.ABEMA_LIVE,
    val b: PrototypeSource = PrototypeSource.TWITCH_LIVE,
) {
    // Duplicate source choices are intentional: each slot owns a separate host.
    val sources get() = listOf(a, b)
}
