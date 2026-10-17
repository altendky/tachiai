package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.provider.catalog.*
import org.junit.Assert.*
import org.junit.Test

class AbemaHistoryProjectionTest {
    private fun page(type: String = "2", id: String = "episode", title: String = "Episode",
        paging: String = "", extra: String = "") =
        """{"histories":[{"type":$type,"sourceId":"$id"}],"dataSet":{"vods":[{"id":"$id","title":"$title"}]},"ignored":null$extra$paging}"""
    private fun rejected(body: String, category: AbemaCollectionFailure = AbemaCollectionFailure.INVALID_RESPONSE) {
        val error = assertThrows(AbemaCollectionException::class.java) { parseAbemaHistoryResponse(body) }
        assertEquals(category, error.category); assertEquals(category.name, error.message)
        assertNull(error.cause); assertEquals(0, error.stackTrace.size)
    }

    @Test fun allNumericAndSymbolicKindsJoinTheirOwnDatasetAndKeepExactParentAndProviderOrder() {
        for ((slot, vod, event) in listOf(Triple("1", "2", "3"), Triple("\"TIMESHIFT\"", "\"VOD\"", "\"LIVE_EVENT\""))) {
            val result = parseAbemaHistoryResponse("""{"histories":[
                {"type":$event,"sourceId":"shared"},{"type":$slot,"sourceId":"shared"},
                {"type":$vod,"sourceId":"shared"},{"type":$event,"sourceId":"shared"}],
                "dataSet":{"timeshifts":[{"id":"shared","channelId":"sumo","title":"Broadcast"}],
                "vods":[{"id":"shared","title":"Replay"}],"liveEvents":[{"id":"shared","title":"Event"}]},
                "paging":{"next":"private-next+/=&"}}""")
            assertEquals(AbemaCollectionKind.HISTORY, result.kind)
            assertEquals(listOf("live_event", "slot", "episode"), result.entries.map { it.resource.kind })
            assertEquals(listOf("shared", "sumo/shared", "shared"), result.entries.map { it.resource.identity })
            assertEquals(listOf(CatalogIntent.BROADCAST, CatalogIntent.BROADCAST, CatalogIntent.VIDEO), result.entries.map { it.resource.intent })
            assertEquals(listOf("Event", "Broadcast", "Replay"), result.entries.map { it.title })
            assertTrue(result.entries.all { it.availability == CatalogAvailability.UNKNOWN && it.scheduledStartEpochMs == null })
            assertEquals("private-next+/=&", result.providerNext)
            assertFalse(result.toString().contains("private-next")); assertFalse(result.toString().contains("Replay"))
        }
    }

    @Test fun emptyHistoryIsExplicitAndNullableDatasetAndPagingHaveClosedShapes() {
        for (body in listOf("""{"histories":[]}""", """{"histories":[],"dataSet":null,"paging":null}""",
            """{"histories":[],"dataSet":{},"paging":{}}""", """{"histories":[],"paging":{"next":""}}""")) {
            val result = parseAbemaHistoryResponse(body)
            assertTrue(result.entries.isEmpty()); assertNull(result.providerNext); assertEquals(AbemaCollectionKind.HISTORY, result.kind)
        }
        for (body in listOf("{}", """{"mylist":[]}""", """{"histories":{}}""",
            """{"histories":[],"dataSet":[]}""", """{"histories":[],"paging":[]}""",
            """{"histories":[],"paging":{"next":null}}""", """{"histories":[],"paging":{"next":1}}""")) rejected(body)
    }

    @Test fun explicitNullHistoryAndDatasetArraysAcceptTheObservedTerminalPage() {
        for (histories in listOf("null", "[]")) {
            val result = parseAbemaHistoryResponse("""{"histories":$histories,
                "dataSet":{"timeshifts":null,"vods":null,"liveEvents":null},"paging":{}}""")
            assertEquals(AbemaCollectionKind.HISTORY, result.kind)
            assertTrue(result.entries.isEmpty()); assertNull(result.providerNext)
        }
        val withoutDataset = parseAbemaHistoryResponse("""{"histories":null}""")
        assertTrue(withoutDataset.entries.isEmpty()); assertNull(withoutDataset.providerNext)
    }

    @Test fun nonemptyHistoryStillJoinsItsExactKindWhenUnusedDatasetsAreNull() {
        val kinds = listOf(Triple(1, "timeshifts", "slot"), Triple(2, "vods", "episode"), Triple(3, "liveEvents", "live_event"))
        for ((type, selected, kind) in kinds) {
            val parent = if (kind == "slot") ",\"channelId\":\"sumo\"" else ""
            val datasets = kinds.joinToString(",") { (_, array, _) ->
                if (array == selected) """"$array":[{"id":"same","title":"Selected"$parent}]"""
                else """"$array":null"""
            }
            val result = parseAbemaHistoryResponse("""{"histories":[{"type":$type,"sourceId":"same"}],"dataSet":{$datasets}}""")
            val entry = result.entries.single()
            assertEquals(kind, entry.resource.kind)
            assertEquals(if (kind == "slot") "sumo/same" else "same", entry.resource.identity)
            assertEquals(if (kind == "episode") CatalogIntent.VIDEO else CatalogIntent.BROADCAST, entry.resource.intent)
            assertEquals("Selected", entry.title)
            assertEquals(CatalogAvailability.UNKNOWN, entry.availability); assertNull(entry.scheduledStartEpochMs)
            assertNull(result.providerNext)
            rejected("""{"histories":[{"type":$type,"sourceId":"same"}],
                "dataSet":{"timeshifts":null,"vods":null,"liveEvents":null}}""")
        }
        rejected("""{"histories":[{"type":2,"sourceId":"same"}],"dataSet":{"vods":null,
            "timeshifts":[{"id":"same","channelId":"sumo","title":"Different kind"}],"liveEvents":null}}""")
    }

    @Test fun nullNormalizationDoesNotAcceptAbsentReferencesMalformedArraysOrInvalidPaging() {
        for (body in listOf("{}", """{"error":{"code":403},"dataSet":{}}""",
            """{"dataSet":{"timeshifts":null,"vods":null,"liveEvents":null},"paging":{}}""")) rejected(body)
        for (histories in listOf("{}", "\"null\"", "0", "true", "\"[]\""))
            rejected("""{"histories":$histories,"dataSet":{}}""")
        for (value in listOf("[]", "0", "true", "\"null\"")) rejected("""{"histories":null,"dataSet":$value}""")
        for (array in listOf("timeshifts", "vods", "liveEvents")) {
            for (value in listOf("{}", "0", "true", "\"[]\""))
                rejected("""{"histories":null,"dataSet":{"$array":$value}}""")
        }
        for (paging in listOf("[]", "true", "{\"next\":null}", "{\"next\":1}"))
            rejected("""{"histories":null,"dataSet":{"vods":null},"paging":$paging}""")
        for (body in listOf("""{"histories":null,"histories":[]}""",
            """{"histories":null,"dataSet":{"vods":null,"vods":[]}}""",
            """{"histories":null,"ignored":"\ud800"}""")) rejected(body)
    }

    @Test fun nullHistoryStillValidatesDatasetIdentityAndAggregateAndResponseBounds() {
        val items = List(500) { """{"id":"ep$it","title":"Replay"}""" }.joinToString(",")
        val bounded = """{"histories":null,"dataSet":{"timeshifts":null,"vods":[$items],"liveEvents":null}}"""
        assertTrue(parseAbemaHistoryResponse(bounded).entries.isEmpty())
        rejected("""{"histories":null,"dataSet":{"timeshifts":null,"vods":[$items],
            "liveEvents":[{"id":"event","title":"Event"}]}}""", AbemaCollectionFailure.RESPONSE_LIMIT)
        rejected("""{"histories":null,"dataSet":{"vods":[{"id":"same","title":"One"},{"id":"same","title":"Two"}]}}""")
        rejected("""{"histories":null,"dataSet":{"timeshifts":[{"id":"slot","title":"No parent"}],"vods":null}}""")
        val ref = """{"type":2,"sourceId":"ep"}"""
        rejected("""{"histories":[${List(21) { ref }.joinToString(",")}],
            "dataSet":{"timeshifts":null,"vods":[{"id":"ep","title":"Replay"}],"liveEvents":null}}""", AbemaCollectionFailure.RESPONSE_LIMIT)
        rejected("""{"histories":null,"ignored":"${"雪".repeat(ABEMA_MY_LIST_RESPONSE_LIMIT / 3)}"}""", AbemaCollectionFailure.RESPONSE_LIMIT)
    }

    @Test fun unknownTypesNumericStringsAndMissingOrAmbiguousJoinsRefuseWholePage() {
        for (wire in listOf("0", "4", "-1", "1.5", "\"UNKNOWN\"", "\"UNRECOGNIZED\"", "\"2\"", "true", "null", "{}", "[]"))
            rejected(page(type = wire))
        for (body in listOf("""{"histories":[{"type":2,"sourceId":"missing"}],"dataSet":{"vods":[]}}""",
            """{"histories":[{"type":2,"sourceId":"episode"}]}""",
            """{"histories":[{"type":2,"sourceId":"same"}],"dataSet":{"timeshifts":[{"id":"same","channelId":"news","title":"Wrong kind"}]}}""",
            """{"histories":[{"type":1,"sourceId":"same"}],"dataSet":{"timeshifts":[{"id":"same","channelId":"news","title":"One"},{"id":"same","channelId":"sumo","title":"Two"}]}}""",
            """{"histories":[],"dataSet":{"vods":{}}}""", """{"histories":[null],"dataSet":{}}""")) rejected(body)
    }

    @Test fun projectionIgnoresUnverifiedViewingAndEntitlementFieldsWithoutFabricatingStatus() {
        val result = parseAbemaHistoryResponse("""{"histories":[{"type":2,"sourceId":"ep","position":123,
            "lastViewingAtMs":123456,"completed":true}],"dataSet":{"vods":[{"id":"ep","title":"  Replay 😀  ",
            "endAt":1,"freeEndAt":2,"terms":[{"private":"ignored"}],"playback":{"url":"https://private.invalid"}}]}}""")
        val entry = result.entries.single()
        assertEquals("Replay 😀", entry.title); assertEquals(CatalogAvailability.UNKNOWN, entry.availability)
        assertNull(entry.scheduledStartEpochMs)
        assertFalse(entry.toString().contains("position")); assertFalse(entry.toString().contains("private.invalid"))
    }

    @Test fun strictJsonIncludesIgnoredFieldsAndTitlesAndIdsAreBounded() {
        for (body in listOf(page() + "{}", page().replace("\"ignored\":null", "\"ignored\":null,\"ignored\":null"),
            page(extra = ",\"bad\":\"\\ud800\""), page(extra = ",\"bad\":NaN"), page(title = "\\u0000"),
            page(title = "\\u200e"), page(title = ""), page(title = "x".repeat(161)), page(id = "a/b"),
            page(id = "x".repeat(129)), """{"histories":[{"type":1,"sourceId":"slot"}],"dataSet":{"timeshifts":[{"id":"slot","title":"No parent"}]}}""")) rejected(body)
        rejected(page(extra = ",\"nested\":" + "[".repeat(34) + "0" + "]".repeat(34)), AbemaCollectionFailure.RESPONSE_LIMIT)
        rejected(page(extra = ",\"large\":\"" + "a".repeat(ABEMA_MY_LIST_RESPONSE_LIMIT) + "\""), AbemaCollectionFailure.RESPONSE_LIMIT)
        for (next in listOf("x".repeat(2049), "\\u0000", "\\u200e", "\\ud83d\\ude00")) rejected(page(paging = ",\"paging\":{\"next\":\"$next\"}"))
    }

    @Test fun localRawReferenceAndAggregateDatasetCapsApplyBeforeDeduplication() {
        val ref = """{"type":2,"sourceId":"ep"}"""
        val dataset = """"dataSet":{"vods":[{"id":"ep","title":"Replay"}]}"""
        val twenty = """{"histories":[${List(20) { ref }.joinToString(",") }],$dataset}"""
        assertEquals(1, parseAbemaHistoryResponse(twenty).entries.size)
        rejected("""{"histories":[${List(21) { ref }.joinToString(",") }],$dataset}""", AbemaCollectionFailure.RESPONSE_LIMIT)
        val items = List(500) { """{"id":"ep$it","title":"Replay"}""" }.joinToString(",")
        assertTrue(parseAbemaHistoryResponse("""{"histories":[],"dataSet":{"vods":[$items]}}""").entries.isEmpty())
        rejected("""{"histories":[],"dataSet":{"vods":[$items],"liveEvents":[{"id":"event","title":"Event"}]}}""", AbemaCollectionFailure.RESPONSE_LIMIT)
    }
}
