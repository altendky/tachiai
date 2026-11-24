package net.fstab.tachiai.provider.twitch

import java.net.URI
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchSeekPreviewsTest {
    private val id = "2934444575"
    private val info = "https://d1m7jfoe9zdc1j.cloudfront.net/d3670efdec98ccf43201_midnightsumo_322501133147_1795138014/storyboards/$id-info.json"
    private fun gql(url: Any?) = JSONObject().put("data", JSONObject().put("video", JSONObject().put("seekPreviewsURL", url ?: JSONObject.NULL))).toString()
    // The shape observed on 2026-11-24, with synthetic directory names.
    private val storyboard = """[{"count":200,"width":160,"rows":40,"images":["$id-low-0.jpg"],"interval":405,"quality":"low","cols":5,"height":90},""" +
        """{"count":200,"width":220,"rows":10,"images":["$id-high-0.jpg","$id-high-1.jpg","$id-high-2.jpg","$id-high-3.jpg"],"interval":405,"quality":"high","cols":5,"height":124}]"""

    @Test fun requestNamesOnlyTheVideo() {
        val body = JSONObject(String(twitchSeekPreviewsBody(id)))
        assertEquals(id, body.getJSONObject("variables").getString("id"))
        assertTrue(body.getString("query").contains("seekPreviewsURL"))
    }

    @Test fun storyboardAddressIsBoundToTheVideo() {
        assertEquals(URI(info), parseTwitchSeekPreviewsUrl(200, gql(info), id))
        for (refused in listOf(null, "", info.replace(id, "1"), info.replace("https:", "http:"),
            info.replace("d1m7jfoe9zdc1j.cloudfront.net", "example.com"), "$info?x=1", "$info#x",
            info.replace("d1m7jfoe9zdc1j.cloudfront.net", "d1m7jfoe9zdc1j.cloudfront.net:8443"),
            info.replace("/storyboards/", "/other/"), info.replace("d3670efdec98ccf43201", "a/.."))) {
            assertNull(refused, parseTwitchSeekPreviewsUrl(200, gql(refused), id))
        }
        assertNull(parseTwitchSeekPreviewsUrl(400, gql(info), id))
        assertNull(parseTwitchSeekPreviewsUrl(200, """{"data":{"video":null}}""", id))
        assertNull(parseTwitchSeekPreviewsUrl(200, """{"errors":[{"message":"x"}],"data":null}""", id))
        assertNull(parseTwitchSeekPreviewsUrl(200, "not json", id))
    }

    @Test fun storyboardPrefersHighSheetsAndMapsPositions() {
        val sheets = checkNotNull(parseTwitchStoryboard(URI(info), storyboard, id))
        assertEquals(405_000L, sheets.intervalMs)
        assertEquals(listOf(5, 10, 220, 124, 200),
            listOf(sheets.columns, sheets.rows, sheets.tileWidth, sheets.tileHeight, sheets.tileCount))
        // 6:45:00 → tile 60 → second sheet, tile 10 → column 0, row 2.
        val tile = checkNotNull(sheets.tileAt(24_300_000, 80_940_000))
        assertEquals(info.replace("$id-info.json", "$id-high-1.jpg"), tile.sheetUrl)
        assertEquals(listOf(0, 2), listOf(tile.column, tile.row))
        assertTrue(allowedTwitchStoryboardSprite(URI(info), id, tile.sheetUrl))
        assertFalse(allowedTwitchStoryboardSprite(URI(info), id, info.replace("$id-info.json", "other-high-1.jpg")))
        assertFalse(allowedTwitchStoryboardSprite(URI(info), id, "https://example.com/$id-high-1.jpg"))
        val lowOnly = checkNotNull(parseTwitchStoryboard(URI(info), storyboard.replace("\"high\"", "\"other\""), id))
        assertEquals(160, lowOnly.tileWidth)
    }

    @Test fun unexpectedStoryboardsMeanNoPreviews() {
        for (body in listOf("", "{}", "[]", "not json",
            storyboard.replace("$id-high-1.jpg", "../x.jpg").replace("$id-low-0.jpg", "../y.jpg"),
            storyboard.replace("\"interval\":405", "\"interval\":0"),
            storyboard.replace("\"cols\":5", "\"cols\":0"))) {
            assertNull(body, parseTwitchStoryboard(URI(info), body, id))
        }
        assertNull(twitchScrubPreviewSource("not-a-video") { error("no network") })
    }
}
