package net.fstab.tachiai.presentation

// Scrub previews (#673): a provider's timeline images are sprite sheets, each a
// grid of equal tiles, one tile per interval, filled row by row. The provider
// adapter supplies the layout and each sheet's address; the viewer shows the
// target tile while scrubbing. Never a data class: no generated toString.
internal class ScrubSpriteSheets(
    val intervalMs: Long,
    val columns: Int,
    val rows: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    // Tiles in all, or null when the sheets run to the item's end.
    val tileCount: Int?,
    private val sheet: (Int) -> String?,
) {
    init {
        require(intervalMs > 0 && columns in 1..64 && rows in 1..64 && tileWidth in 1..2048 &&
            tileHeight in 1..2048 && (tileCount == null || tileCount > 0))
    }
    val tilesPerSheet: Int get() = columns * rows

    // The tile index at a position, clamped to the item and the known tiles.
    fun tileIndexAt(positionMs: Long, durationMs: Long): Long? {
        if (durationMs <= 0) return null
        val index = positionMs.coerceIn(0, durationMs - 1) / intervalMs
        return tileCount?.let { minOf(index, it - 1L) } ?: index
    }

    fun sheetAt(positionMs: Long, durationMs: Long): Int? =
        tileIndexAt(positionMs, durationMs)?.let { (it / tilesPerSheet).toIntOrNullBounded() }

    // The last sheet the item can use.
    fun lastSheet(durationMs: Long): Int? = sheetAt(durationMs - 1, durationMs)

    fun sheetUrl(index: Int): String? = if (index < 0) null else runCatching { sheet(index) }.getOrNull()

    fun tileAt(positionMs: Long, durationMs: Long): ScrubTile? {
        val index = tileIndexAt(positionMs, durationMs) ?: return null
        val sheetIndex = (index / tilesPerSheet).toIntOrNullBounded() ?: return null
        val within = (index % tilesPerSheet).toInt()
        val url = sheetUrl(sheetIndex) ?: return null
        return ScrubTile(url, sheetIndex, within % columns, within / columns)
    }
}

private fun Long.toIntOrNullBounded(): Int? = if (this in 0..Int.MAX_VALUE.toLong()) toInt() else null

// One tile: its sheet and grid cell. Pixel bounds follow the decoded sheet.
internal class ScrubTile(val sheetUrl: String, val sheet: Int, val column: Int, val row: Int) {
    override fun toString() = "ScrubTile(sheet=$sheet, column=$column, row=$row)"
}

internal data class ScrubTileBounds(val left: Int, val top: Int, val width: Int, val height: Int)

// A tile's pixels in a decoded sheet. Sheets drawn at another scale than the
// declared tile size are scaled by width. A tile past a short final sheet, or
// outside the image, has no bounds: that position shows no preview.
internal fun scrubTileBounds(sheets: ScrubSpriteSheets, tile: ScrubTile, imageWidth: Int, imageHeight: Int): ScrubTileBounds? {
    if (imageWidth <= 0 || imageHeight <= 0) return null
    val scale = imageWidth.toDouble() / (sheets.columns * sheets.tileWidth)
    val width = (sheets.tileWidth * scale).toInt()
    val height = (sheets.tileHeight * scale).toInt()
    if (width <= 0 || height <= 0) return null
    val left = (tile.column * sheets.tileWidth * scale).toInt()
    val top = (tile.row * sheets.tileHeight * scale).toInt()
    if (left + width > imageWidth || top + height > imageHeight) return null
    return ScrubTileBounds(left, top, width, height)
}

// Sheets to fetch for a scrub target, nearest first: the target's sheet, then
// its neighbours within the item. Never the whole item.
internal fun scrubSheetsToFetch(sheets: ScrubSpriteSheets, positionMs: Long, durationMs: Long): List<Int> {
    val target = sheets.sheetAt(positionMs, durationMs) ?: return emptyList()
    val last = sheets.lastSheet(durationMs) ?: target
    return listOf(target, target + 1, target - 1).filter { it in 0..last }
}
