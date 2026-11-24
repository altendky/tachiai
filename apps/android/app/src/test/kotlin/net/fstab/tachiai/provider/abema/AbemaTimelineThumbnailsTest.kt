package net.fstab.tachiai.provider.abema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AbemaTimelineThumbnailsTest {
    private val slotPrefix = "https://image.p-c2-x.abema-tv.com/media/slots/SLOT_ID/RECORDING_1/timeline"
    private val RESIZE = "?width=1280&quality=50"

    @Test fun prefixIsBoundToTheSelectedSlotOrProgram() {
        assertEquals(slotPrefix, abemaTimelineThumbnailPrefix(slotPrefix, AbemaTimelineKind.SLOT, "SLOT_ID"))
        val program = "https://image.p-c2-x.abema-tv.com/media/programs/394-72_s10_p4890/1/timeline"
        assertEquals(program, abemaTimelineThumbnailPrefix(program, AbemaTimelineKind.PROGRAM, "394-72_s10_p4890"))
        for (refused in listOf(null, "",
            slotPrefix.replace("SLOT_ID", "OTHER"),
            slotPrefix.replace("/slots/", "/programs/"),
            slotPrefix.replace("image.p-c2-x.abema-tv.com", "image.example.test"),
            slotPrefix.replace("https:", "http:"),
            "$slotPrefix/",
            "$slotPrefix?x=1",
            slotPrefix.replace("RECORDING_1", "a/b"),
            slotPrefix.replace("RECORDING_1", ".."),
            slotPrefix.removeSuffix("/timeline"))) {
            assertNull(refused, abemaTimelineThumbnailPrefix(refused, AbemaTimelineKind.SLOT, "SLOT_ID"))
        }
        assertNull(abemaTimelineThumbnailPrefix(slotPrefix, AbemaTimelineKind.SLOT, "../x"))
    }

    @Test fun spritesAreOnePerTenMinutes() {
        val sheets = abemaTimelineSheets(slotPrefix)
        val tile = checkNotNull(sheets.tileAt(6_130_000, 12_240_000))
        assertEquals("$slotPrefix/6000.png$RESIZE", tile.sheetUrl)
        assertEquals(listOf(3, 1), listOf(tile.column, tile.row))
        assertEquals("$slotPrefix/0.png$RESIZE", checkNotNull(sheets.tileAt(0, 12_240_000)).sheetUrl)
        assertEquals("$slotPrefix/12000.png$RESIZE", checkNotNull(sheets.tileAt(12_239_000, 12_240_000)).sheetUrl)
    }

    @Test fun onlyThePrefixSpritesAreFetched() {
        assertTrue(allowedAbemaTimelineSprite(slotPrefix, "$slotPrefix/0.png$RESIZE"))
        assertTrue(allowedAbemaTimelineSprite(slotPrefix, "$slotPrefix/12000.png$RESIZE"))
        for (url in listOf("$slotPrefix/600.png", "$slotPrefix/601.png$RESIZE", "$slotPrefix/0600.png$RESIZE",
            "$slotPrefix/600.jpg$RESIZE", "$slotPrefix/600.png?width=1920", "$slotPrefix/../600.png$RESIZE",
            "https://image.p-c2-x.abema-tv.com/other/600.png$RESIZE", "$slotPrefix/-600.png$RESIZE")) {
            assertFalse(url, allowedAbemaTimelineSprite(slotPrefix, url))
        }
    }
}
