package net.fstab.tachiai.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrubPreviewsTest {
    // ABEMA-like: unbounded 10×6 sheets of 192×108, 10 s per tile.
    private val open = ScrubSpriteSheets(10_000, 10, 6, 192, 108, null) { "sheet-$it" }
    // Twitch-like: 5×10 sheets, 405 s per tile, 200 tiles in four sheets.
    private val counted = ScrubSpriteSheets(405_000, 5, 10, 220, 124, 200) { index ->
        listOf("a", "b", "c", "d").getOrNull(index)
    }

    @Test fun positionMapsToSheetAndCell() {
        val first = checkNotNull(open.tileAt(0, 3_600_000))
        assertEquals(listOf("sheet-0", 0, 0, 0), listOf(first.sheetUrl, first.sheet, first.column, first.row))
        // 9:59 is the last tile of the first sheet; 10:00 starts the second.
        val last = checkNotNull(open.tileAt(599_999, 3_600_000))
        assertEquals(listOf(0, 9, 5), listOf(last.sheet, last.column, last.row))
        val next = checkNotNull(open.tileAt(600_000, 3_600_000))
        assertEquals(listOf("sheet-1", 1, 0, 0), listOf(next.sheetUrl, next.sheet, next.column, next.row))
        // 1:42:10 → tile 613 → sheet 10, tile 13 → column 3, row 1.
        val deep = checkNotNull(open.tileAt(6_130_000, 12_240_000))
        assertEquals(listOf(10, 3, 1), listOf(deep.sheet, deep.column, deep.row))
    }

    @Test fun positionsClampToTheItemAndKnownTiles() {
        assertEquals(0, checkNotNull(open.tileAt(-5_000, 60_000)).sheet)
        val end = checkNotNull(open.tileAt(Long.MAX_VALUE, 1_200_000))
        assertEquals(listOf(1, 9, 5), listOf(end.sheet, end.column, end.row))
        // Past the last counted tile shows the last one.
        val beyond = checkNotNull(counted.tileAt(90_000_000, 100_000_000))
        assertEquals(listOf("d", 3, 4, 9), listOf(beyond.sheetUrl, beyond.sheet, beyond.column, beyond.row))
        assertEquals(1, checkNotNull(counted.tileAt(405_000L * 51, 100_000_000)).sheet)
    }

    @Test fun missingDataMeansNoTile() {
        assertNull(open.tileAt(0, 0))
        assertNull(open.tileAt(0, -1))
        val short = ScrubSpriteSheets(405_000, 5, 10, 220, 124, 200) { if (it == 0) "a" else null }
        assertNull(short.tileAt(405_000L * 60, 100_000_000))
        val throwing = ScrubSpriteSheets(10_000, 10, 6, 192, 108, null) { error("no sheet") }
        assertNull(throwing.tileAt(0, 60_000))
    }

    @Test fun tileBoundsFollowTheDecodedSheet() {
        val tile = checkNotNull(open.tileAt(6_130_000, 12_240_000))
        assertEquals(ScrubTileBounds(576, 108, 192, 108), scrubTileBounds(open, tile, 1920, 648))
        // A half-size sheet scales the cell.
        assertEquals(ScrubTileBounds(288, 54, 96, 54), scrubTileBounds(open, tile, 960, 324))
        // The final sheet can be shorter: rows it lacks have no preview.
        val lastRow = checkNotNull(open.tileAt(590_000, 3_600_000))
        assertNull(scrubTileBounds(open, lastRow, 1920, 324))
        assertEquals(ScrubTileBounds(0, 216, 192, 108),
            scrubTileBounds(open, checkNotNull(open.tileAt(200_000, 3_600_000)), 1920, 324))
        assertNull(scrubTileBounds(open, tile, 0, 0))
    }

    @Test fun fetchesTargetSheetThenNeighboursWithinTheItem() {
        assertEquals(listOf(3, 4, 2), scrubSheetsToFetch(open, 2_100_000, 12_240_000))
        assertEquals(listOf(0, 1), scrubSheetsToFetch(open, 0, 12_240_000))
        // 3:24:00 uses sheets 0–20.
        assertEquals(listOf(20, 19), scrubSheetsToFetch(open, 12_239_000, 12_240_000))
        assertEquals(listOf(0), scrubSheetsToFetch(open, 0, 300_000))
        assertEquals(listOf(3, 2), scrubSheetsToFetch(counted, 90_000_000, 100_000_000))
        assertEquals(emptyList<Int>(), scrubSheetsToFetch(open, 0, 0))
    }
}
