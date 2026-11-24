package net.fstab.tachiai.presentation

// A feed's scrub previews (#673), supplied by its provider adapter. Both
// functions run on a worker and may use the network through the feed's route.
// `key` names the item for caching; it is not logged.
internal class ScrubPreviewSource(
    val key: String,
    // The item's sheets, or null when it has none.
    val resolve: () -> ScrubSpriteSheets?,
    // One sheet's encoded image, or null. Only addresses from resolve are passed.
    val fetch: (String) -> ByteArray?,
) {
    override fun toString() = "ScrubPreviewSource(redacted)"
}
