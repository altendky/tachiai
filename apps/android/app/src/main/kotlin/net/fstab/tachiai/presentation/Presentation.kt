package net.fstab.tachiai.presentation

@JvmInline
value class PaneId(val value: String)

@JvmInline
value class ProviderId(val value: String)

@JvmInline
value class ResourceLocator(val value: String)

enum class PlaybackKind {
    LIVE,
    REPLAY,
    UNKNOWN,
}

data class PaneSpec(
    val id: PaneId,
    val providerId: ProviderId,
    val resource: ResourceLocator,
    val playbackKind: PlaybackKind,
    val initiallyMuted: Boolean,
)

data class Presentation(
    val panes: List<PaneSpec>,
) {
    init {
        require(panes.isNotEmpty()) { "A presentation must contain at least one pane." }
        require(panes.map(PaneSpec::id).distinct().size == panes.size) {
            "Pane identifiers must be unique."
        }
    }
}
