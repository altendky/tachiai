package net.fstab.tachiai.provider.abema

import net.fstab.tachiai.presentation.ProviderId
import net.fstab.tachiai.provider.catalog.CatalogAvailability
import net.fstab.tachiai.provider.catalog.CatalogIntent
import net.fstab.tachiai.provider.catalog.CatalogResource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AbemaChannelMetadataTest {
    private fun channel(id: Any? = "sumo", name: Any? = "相撲") = JSONObject()
        .put("id", id ?: JSONObject.NULL).put("name", name ?: JSONObject.NULL)
    private fun body(vararg channels: Any?) = JSONObject().put("channels", JSONArray(channels.toList())).toString()
    private fun failure(input: String, category: AbemaChannelFailure = AbemaChannelFailure.INVALID_RESPONSE) {
        val error = assertThrows(AbemaChannelException::class.java) { parseAbemaChannelResponse(input) }
        assertEquals(category, error.category)
        assertEquals(category.name, error.message)
        assertNull(error.cause)
        assertEquals(0, error.suppressed.size)
        assertEquals(0, error.stackTrace.size)
    }

    @Test fun identityAndNameAreTheOnlyRetainedFieldsRegardlessOfPlaybackClaims() {
        val row = channel().put("viewingOpened", true)
            .put("status", JSONObject().put("drm", true))
            .put("playback", JSONObject().put("dash", "https://private.example.test/PRIVATE_SENTINEL"))
            .put("broadcastRegionPolicy", "PRIVATE_SENTINEL")
        val entries = parseAbemaChannelResponse(body(row))
        val entry = entries.single()
        assertEquals(CatalogResource(ProviderId("abema"), "channel", "sumo", CatalogIntent.CHANNEL), entry.resource)
        assertEquals("相撲", entry.title)
        assertEquals(CatalogAvailability.UNKNOWN, entry.availability)
        assertNull(entry.scheduledStartEpochMs)
        assertFalse(entries.toString().contains("PRIVATE_SENTINEL"))
        assertFalse(AbemaChannelResponse(200, entries).toString().contains("sumo"))
        assertThrows(UnsupportedOperationException::class.java) { (entries as MutableList<*>).clear() }
    }

    @Test fun emptySnapshotAndIgnoredWellFormedStructuresRemainValid() {
        assertTrue(parseAbemaChannelResponse("{\"channels\":[]}").isEmpty())
        val extra = "{\"channels\":[],\"extra\":[true,false,null,-12.5e+2,{\"text\":\"[]{}\\\"\\\\\"}]}"
        assertTrue(parseAbemaChannelResponse(extra).isEmpty())
        assertTrue(parseAbemaChannelResponse(" \n{\"channels\":[]}\t\r").isEmpty())
    }

    @Test fun validNamesNormalizeBoundarySpacesAndPreserveUnicodeAndIdentityCase() {
        val entries = parseAbemaChannelResponse(body(channel("sumo", "  相撲 🚀  "), channel("Sumo", "Other")))
        assertEquals(listOf("sumo", "Sumo"), entries.map { it.resource.identity })
        assertEquals("相撲 🚀", entries.first().title)
        assertEquals("a".repeat(160), parseAbemaChannelResponse(body(channel("a".repeat(64), "a".repeat(160)))).single().title)
    }

    @Test fun missingWrongRootArrayAndEntryTypesFailRatherThanBecomeEmptySuccess() {
        listOf("", "null", "[]", "{}", "{\"channels\":null}", "{\"channels\":{}}",
            "{\"channels\":\"empty\"}", body(JSONObject()), body("row"), body(3), body(JSONObject.NULL),
            body(channel(123)), body(channel(null)), body(channel(name = 123)), body(channel(name = null)),
            body(channel(), channel("second", false))).forEach { failure(it) }
    }

    @Test fun invalidIdsAndDuplicateIdentityRejectTheWholeSnapshot() {
        listOf("", "_sumo", "-sumo", "sumo/slot", "sumo.m3u8", "sumo?token=x", "相撲", "su mo",
            "sumo\n", "a".repeat(65)).forEach { failure(body(channel(it))) }
        failure(body(channel(), channel("sumo", "Renamed")))
    }

    @Test fun invalidDisplayNamesCannotHideControlsThroughTrimming() {
        listOf("", "   ", "a".repeat(161), "\nNews\n", "Name\t", "\u0000News", "News\u007f",
            "News\u200b", "News\u202e").forEach { failure(body(channel(name = it))) }
    }

    @Test fun snapshotCountIsBoundedWithoutSilentlyDroppingExtraChannels() {
        val accepted = (0 until ABEMA_CHANNEL_ENTRY_LIMIT).map { channel("channel$it", "Channel $it") }
        assertEquals(500, parseAbemaChannelResponse(body(*accepted.toTypedArray())).size)
        failure(body(*(accepted + channel("extra", "Extra")).toTypedArray()), AbemaChannelFailure.RESPONSE_LIMIT)
    }

    @Test fun byteLimitIncludesIgnoredFieldsAndMultibyteUtf8() {
        val prefix = "{\"channels\":[],\"ignored\":\""
        val suffix = "\"}"
        val exact = prefix + "a".repeat(ABEMA_CHANNEL_RESPONSE_LIMIT - prefix.length - suffix.length) + suffix
        assertTrue(parseAbemaChannelResponse(exact).isEmpty())
        failure(exact + " ", AbemaChannelFailure.RESPONSE_LIMIT)
        val multibyte = prefix + "相".repeat(ABEMA_CHANNEL_RESPONSE_LIMIT / 3) + suffix
        assertTrue(multibyte.length < ABEMA_CHANNEL_RESPONSE_LIMIT)
        failure(multibyte, AbemaChannelFailure.RESPONSE_LIMIT)
    }

    @Test fun pathologicalNestingAndWideIgnoredDataAreRefusedBeforeOrgJsonRecursion() {
        val nested = "{\"channels\":[],\"ignored\":" + "[".repeat(2000) + "0" + "]".repeat(2000) + "}"
        failure(nested)
        val shallow = "{\"channels\":[],\"ignored\":" + "[".repeat(20) + "0" + "]".repeat(20) + "}"
        assertTrue(parseAbemaChannelResponse(shallow).isEmpty())
        failure("{\"channels\":[],\"ignored\":[" + List(17_000) { "0" }.joinToString(",") + "]}")
    }

    @Test fun ambiguousKeysRelaxedJsonAndTrailingPayloadAreRejected() {
        listOf("{channels:[]}", "{'channels':[]}", "{\"channels\":[]}//comment",
            "{\"channels\":[],}", "{\"channels\":[]}{}", "{\"channels\":[]};",
            "{\"channels\":[],\"extra\":NaN}", "{\"channels\":[],\"extra\":Infinity}",
            "{\"channels\":[],\"extra\":01}", "{\"channels\":[],\"extra\":.5}",
            "{\"channels\":[],\"extra\":[,]}", "{\"channels\":[],\"channels\":[]}",
            "{\"channels\":[{\"id\":\"sumo\",\"name\":\"One\",\"name\":\"Two\"}]}",
            "{\"channels\":[],\"\\u0063hannels\":[]}").forEach { failure(it) }
    }

    @Test fun unpairedSurrogatesAndBadEscapesAreRejectedEvenInIgnoredFields() {
        listOf("{\"channels\":[],\"extra\":\"\\ud800\"}", "{\"channels\":[],\"extra\":\"\\udc00\"}",
            "{\"channels\":[],\"extra\":\"\\uZZZZ\"}", "{\"channels\":[],\"extra\":\"\\q\"}",
            "{\"channels\":[],\"extra\":\"\ud800\"}").forEach { failure(it) }
        val valid = "{\"channels\":[{\"id\":\"space\",\"name\":\"Rocket \\ud83d\\ude80\"}]}"
        assertEquals("Rocket 🚀", parseAbemaChannelResponse(valid).single().title)
    }

    @Test fun responseCopiesCallerListAndRedactsEveryStatus() {
        val entries = parseAbemaChannelResponse(body(channel())).toMutableList()
        val response = AbemaChannelResponse(200, entries)
        entries.clear()
        assertEquals(1, response.entries.size)
        assertThrows(UnsupportedOperationException::class.java) { (response.entries as MutableList<*>).clear() }
        assertEquals("AbemaChannelResponse(redacted)", AbemaChannelResponse(429, retryAtEpochMs = 123).toString())
        assertThrows(IllegalArgumentException::class.java) { AbemaChannelResponse(401, response.entries) }
        assertThrows(IllegalArgumentException::class.java) { AbemaChannelResponse(200, retryAtEpochMs = 123) }
    }
}
