package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.provider.catalog.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AbemaMyListProjectionTest {
    @Test fun typedJoinsPreserveReferenceOrderExactIdentityAndNoPlaybackClaims() {
        val page = parseAbemaMyListResponse("""{
            "mylist":[
                {"contentId":"same","contentType":"CONTENT_TYPE_SERIES"},
                {"contentId":"same","contentType":"CONTENT_TYPE_PROGRAM"},
                {"contentId":"slot-1","contentType":"CONTENT_TYPE_SLOT","slotGroupId":"group-1","slotIds":["slot-1"]},
                {"contentId":"group-1","contentType":"CONTENT_TYPE_SLOT_GROUP"},
                {"contentId":"event-1","contentType":"CONTENT_TYPE_LIVE_EVENT"}
            ],"dataSet":{
                "programs":[{"id":"same","title":"Episode","terms":[],"mediaStatus":{"unknown":true}}],
                "series":[{"id":"same","title":"Series"}],
                "slots":[{"id":"slot-1","title":"Future slot","channelId":"sumo","startAt":9999999999}],
                "slotGroups":[{"id":"group-1","title":"Group"}],
                "liveEvents":[{"id":"event-1","title":"Event","alias":"different-alias","realTime":{"startAt":9999999999}}]
            },"paging":{"next":"opaque+/=continuation"},"position":12345
        }""")
        assertEquals(listOf("series", "episode", "slot", "slot_group", "live_event"), page.entries.map { it.resource.kind })
        assertEquals(listOf("same", "same", "sumo/slot-1", "group-1", "event-1"), page.entries.map { it.resource.identity })
        assertEquals(listOf(CatalogIntent.COLLECTION, CatalogIntent.VIDEO, CatalogIntent.BROADCAST,
            CatalogIntent.COLLECTION, CatalogIntent.BROADCAST), page.entries.map { it.resource.intent })
        assertTrue(page.entries.all { it.availability == CatalogAvailability.UNKNOWN && it.scheduledStartEpochMs == null })
        assertEquals("opaque+/=continuation", page.providerNext)
        assertFalse(page.toString().contains("continuation"))
    }

    @Test fun emptyCollectionIsDistinctFromMissingReferencesOrUnjoinedDataset() {
        assertTrue(parseAbemaMyListResponse("""{"mylist":[],"dataSet":{},"paging":{"next":""}}""").entries.isEmpty())
        assertTrue(parseAbemaMyListResponse("""{"mylist":[]}""").entries.isEmpty())
        rejected("""{"dataSet":{}}""")
        rejected("""{"mylist":[{"contentId":"one","contentType":"CONTENT_TYPE_PROGRAM"}]}""")
        rejected("""{"mylist":[{"contentId":"one","contentType":"CONTENT_TYPE_PROGRAM"}],"dataSet":{"series":[{"id":"one","title":"Wrong type"}]}}""")
        rejected("""{"mylist":[{"contentId":"one","contentType":"CONTENT_TYPE_UNKNOWN"}],"dataSet":{}}""")
        rejected("""{"mylist":[{"contentId":"one","contentType":"CONTENT_TYPE_CHANNEL"}],"dataSet":{}}""")
    }

    @Test fun duplicateDatasetIdentityCannotSubstituteMetadataButRepeatedReferencesDeduplicate() {
        val root = fixture()
        val reference = root.getJSONArray("mylist").getJSONObject(0)
        root.getJSONArray("mylist").put(JSONObject(reference.toString()))
        assertEquals(1, parseAbemaMyListResponse(root.toString()).entries.size)
        val rows = root.getJSONObject("dataSet").getJSONArray("programs")
        rows.put(JSONObject().put("id", "one").put("title", "Substituted"))
        rejected(root.toString())
    }

    @Test fun strictJsonRejectsDuplicateEscapedKeysRelaxedSyntaxTrailingAndUnicode() {
        listOf(
            """{"mylist":[],"my\u006cist":[],"dataSet":{}}""",
            """{"mylist":[],"ignored":{"key":1,"key":2}}""",
            """{'mylist':[]}""", """{"mylist":[],}""", """{"mylist":[]}null""",
            """{"mylist":[],"ignored":"\ud800"}""", """{"mylist":[],"ignored":01}""",
            """{"mylist":[],"ignored":[,]}""",
        ).forEach(::rejected)
        val valid = fixture().also { it.getJSONObject("dataSet").getJSONArray("programs").getJSONObject(0)
            .put("title", " Unicode \uD83D\uDE00 ") }
        assertEquals("Unicode \uD83D\uDE00", parseAbemaMyListResponse(valid.toString()).entries.single().title)
        listOf("", " ", "bad\u200dtitle", "bad\u0000title", "x".repeat(161)).forEach { title ->
            val changed = fixture()
            changed.getJSONObject("dataSet").getJSONArray("programs").getJSONObject(0).put("title", title)
            rejected(changed.toString())
        }
    }

    @Test fun responseBoundsApplyToReferencesDatasetsIgnoredValuesDepthAndUtf8Bytes() {
        val tooMany = fixture()
        repeat(20) { tooMany.getJSONArray("mylist").put(JSONObject().put("contentId", "one").put("contentType", "CONTENT_TYPE_PROGRAM")) }
        assertEquals(AbemaCollectionFailure.RESPONSE_LIMIT, rejected(tooMany.toString()))
        val dataset = fixture()
        val rows = JSONArray()
        repeat(501) { rows.put(JSONObject().put("id", "p-$it").put("title", "Title")) }
        dataset.getJSONObject("dataSet").put("programs", rows)
        assertEquals(AbemaCollectionFailure.RESPONSE_LIMIT, rejected(dataset.toString()))
        rejected("{\"mylist\":[],\"ignored\":" + "[".repeat(33) + "0" + "]".repeat(33) + "}")
        rejected("{\"mylist\":[],\"ignored\":[" + List(16_001) { "0" }.joinToString(",") + "]}")
        assertEquals(AbemaCollectionFailure.RESPONSE_LIMIT,
            rejected("{\"mylist\":[],\"ignored\":\"" + "あ".repeat(ABEMA_MY_LIST_RESPONSE_LIMIT / 3) + "\"}"))
    }

    @Test fun continuationAndTransportRequestAreBoundedFixedReadOnlyAndRedacted() {
        val root = fixture().put("paging", JSONObject().put("next", "next+/=&limit=100"))
        val page = parseAbemaMyListResponse(root.toString())
        val uri = abemaMyListRequestUri(page.providerNext)
        assertEquals("https", uri.scheme); assertEquals("api.p-c3-e.abema-tv.com", uri.host)
        assertEquals("/v1/mylist", uri.path)
        assertEquals("limit=20&order=updated_at_desc&withDataSet=true&include=liveEvent&next=next%2B%2F%3D%26limit%3D100", uri.rawQuery)
        listOf("\n", "x".repeat(2049), "next\u200b").forEach {
            rejected(fixture().put("paging", JSONObject().put("next", it)).toString())
        }
        assertEquals("AbemaMyListResponse(redacted)", AbemaMyListResponse(200, page).toString())
        try { AbemaMyListResponse(401, page); fail("Non-200 metadata must be rejected") } catch (_: IllegalArgumentException) { }
    }

    private fun fixture() = JSONObject().put("mylist", JSONArray().put(JSONObject()
        .put("contentId", "one").put("contentType", "CONTENT_TYPE_PROGRAM")))
        .put("dataSet", JSONObject().put("programs", JSONArray().put(JSONObject().put("id", "one").put("title", "Episode"))))
    private fun rejected(body: String): AbemaCollectionFailure {
        try { parseAbemaMyListResponse(body); fail("Malformed projection must fail closed") }
        catch (error: AbemaCollectionException) {
            assertNull(error.cause)
            assertEquals(error.category.name, error.message)
            return error.category
        }
        error("Unreachable")
    }
}
