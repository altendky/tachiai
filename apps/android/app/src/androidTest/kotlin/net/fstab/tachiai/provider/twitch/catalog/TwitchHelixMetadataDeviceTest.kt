package net.fstab.tachiai.provider.twitch.catalog

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TwitchHelixMetadataDeviceTest {
    @Test fun androidJsonProjectionKeepsOnlyNeededPublicMetadataAndPagination() {
        val fields = twitchHelixResponseFields("""{"data":[{"id":"123","login":"fixture_channel","display_name":"Fixture channel","email":"private@example.invalid","profile_image_url":"https://example.invalid/private","description":"private response","unexpected":{"token":"private"}}],"pagination":{"cursor":"fixture-cursor","secret":"private"},"message":"private error"}""")
        val rows = fields["data"] as List<*>
        val row = rows.single() as Map<*, *>
        assertEquals(setOf("id", "login", "display_name"), row.keys)
        assertEquals(setOf("data", "pagination"), fields.keys)
        assertEquals(mapOf("cursor" to "fixture-cursor"), fields["pagination"])
        val users = parseTwitchUsers(TwitchHelixResponse(200, fields))
        assertEquals("123", users.items.single().id)
        assertEquals("fixture-cursor", users.nextCursor)
        assertFalse(users.toString().contains("fixture-cursor"))
        assertTrue(parseTwitchUsers(TwitchHelixResponse(200,
            twitchHelixResponseFields("""{"data":[],"pagination":{}}"""))).items.isEmpty())
    }

    @Test fun malformedAndroidJsonAndUnboundedPaginationFailClosed() {
        for (body in listOf("not-json", """{"data":null}""", """{"data":[null]}""",
            """{"data":[],"pagination":{"cursor":123}}""",
            """{"data":[],"pagination":{"cursor":""}}""")) {
            val error = assertThrows(TwitchHelixException::class.java) { twitchHelixResponseFields(body) }
            assertEquals(TwitchHelixFailure.INVALID_RESPONSE, error.failure)
            assertFalse(error.toString().contains(body))
        }
        val cursor = "x".repeat(2049)
        assertThrows(TwitchHelixException::class.java) {
            twitchHelixResponseFields("""{"data":[],"pagination":{"cursor":"$cursor"}}""")
        }
        val row = """{"id":"123","login":"fixture","display_name":"Fixture"}"""
        assertThrows(TwitchHelixException::class.java) {
            twitchHelixResponseFields("{\"data\":[" + List(101) { row }.joinToString(",") + "]}")
        }
    }

    @Test fun androidScheduleProjectionKeepsOnlyTimingOwnershipAndTopLevelCursor() {
        val fields = twitchHelixResponseFields("""{
            "data":{"broadcaster_id":"123","broadcaster_login":"fixture_private_alias",
                "broadcaster_name":"Private fixture name","email":"private@example.invalid",
                "segments":[{"start_time":"2026-10-10T00:00:01Z","end_time":"2026-10-10T00:00:02Z",
                    "canceled_until":null,"id":"private-segment-id","title":"Private fixture title",
                    "category":{"name":"Private fixture category"},"is_recurring":true,
                    "url":"https://example.invalid/?token=fixture-private","unexpected":{"secret":"private"}}],
                "vacation":null},
            "pagination":{"cursor":"fixture-more","private":"private"},"message":"private error"}
        """)
        assertEquals(setOf("data", "pagination"), fields.keys)
        val data = fields["data"] as Map<*, *>
        assertEquals(setOf("broadcaster_id", "segments", "vacation"), data.keys)
        val row = (data["segments"] as List<*>).single() as Map<*, *>
        assertEquals(setOf("start_time", "end_time", "canceled_until"), row.keys)
        assertTrue(row.containsKey("canceled_until")); assertNull(row["canceled_until"])
        assertTrue(data.containsKey("vacation")); assertNull(data["vacation"])
        assertEquals(mapOf("cursor" to "fixture-more"), fields["pagination"])
        assertFalse(fields.toString().contains("private"))
        val now = java.time.Instant.parse("2026-10-10T00:00:00Z").toEpochMilli()
        assertEquals(now + 1_000, parseTwitchScheduleStart(TwitchHelixResponse(200, fields), "123", now))
        assertInvalid { parseTwitchUsers(TwitchHelixResponse(200, fields)) }
    }

    @Test fun androidScheduleNullableFieldsAndVacationIntervalsDriveEligibility() {
        val fields = twitchHelixResponseFields("""{
            "data":{"broadcaster_id":"123","segments":[
                {"start_time":"2026-10-10T00:00:20Z","end_time":"2026-10-10T00:00:25Z","canceled_until":null},
                {"start_time":"2026-10-10T00:00:05Z","end_time":"2026-10-10T00:00:15Z","canceled_until":null},
                {"start_time":"2026-10-10T00:00:01Z","end_time":"2026-10-10T00:00:02Z",
                    "canceled_until":"2026-10-09T00:00:00Z"}],
                "vacation":{"start_time":"2026-10-10T00:00:10Z","end_time":"2026-10-10T00:00:20Z",
                    "private":"private"}},"pagination":{}}
        """)
        val data = fields["data"] as Map<*, *>
        assertEquals(setOf("start_time", "end_time"), (data["vacation"] as Map<*, *>).keys)
        val now = java.time.Instant.parse("2026-10-10T00:00:00Z").toEpochMilli()
        assertEquals(now + 20_000, parseTwitchScheduleStart(TwitchHelixResponse(200, fields), "123", now))
        assertFalse(fields.toString().contains("private"))
        val empty = twitchHelixResponseFields("""{"data":{"broadcaster_id":"123","segments":[],"vacation":null}}""")
        assertNull(parseTwitchScheduleStart(TwitchHelixResponse(200, empty), "123", now))
    }

    @Test fun androidScheduleMissingNullablesAndWrongNestedShapesFailClosed() {
        val row = """{"start_time":"2026-10-10T00:00:01Z","end_time":"2026-10-10T00:00:02Z","canceled_until":null}"""
        val missingCancellation = """{"start_time":"2026-10-10T00:00:01Z","end_time":"2026-10-10T00:00:02Z"}"""
        val invalid = listOf(
            """{"data":{}}""",
            """{"data":{"broadcaster_id":"123","segments":[]}}""",
            """{"data":{"broadcaster_id":"123","segments":[$missingCancellation],"vacation":null}}""",
            """{"data":{"broadcaster_id":"123","segments":[null],"vacation":null}}""",
            """{"data":{"broadcaster_id":"123","segments":{},"vacation":null}}""",
            """{"data":{"broadcaster_id":123,"segments":[] ,"vacation":null}}""",
            """{"data":{"broadcaster_id":"123","segments":[$row],"vacation":false}}""",
            """{"data":{"broadcaster_id":"123","segments":[$row],"vacation":{"start_time":"2026-10-10T00:00:01Z"}}}""",
            """{"data":{"broadcaster_id":"123","segments":[$row],"vacation":null},"pagination":null}""",
            """{"data":{"broadcaster_id":"123","segments":[$row],"vacation":null},"pagination":{"cursor":null}}""",
        )
        invalid.forEach { body -> assertInvalid { twitchHelixResponseFields(body) } }
        assertInvalid { twitchHelixResponseFields("""{"data":{"broadcaster_id":"123","segments":[$row],"vacation":null},"pagination":{"cursor":"${"x".repeat(2049)}"}}""") }
    }

    @Test fun androidScheduleProjectionStillRequiresParserOwnershipAndTimestampValidation() {
        fun response(start: String, owner: String = "123") = TwitchHelixResponse(200, twitchHelixResponseFields("""{
            "data":{"broadcaster_id":"$owner","segments":[{"start_time":"$start",
                "end_time":"2026-10-10T00:00:02Z","canceled_until":null}],"vacation":null},"pagination":{}}
        """))
        val now = java.time.Instant.parse("2026-10-10T00:00:00Z").toEpochMilli()
        assertInvalid { parseTwitchScheduleStart(response("2026-02-30T00:00:01Z"), "123", now) }
        assertInvalid { parseTwitchScheduleStart(response("2026-10-10T00:00:01Z", "124"), "123", now) }
        assertInvalid { parseTwitchScheduleStart(response("2026-10-10T00:00:02Z"), "123", now) }
        assertEquals(now + 1, parseTwitchScheduleStart(response("2026-10-10T00:00:00.001999999Z"), "123", now))
    }

    @Test fun androidScheduleProjectionBoundsSegmentsAndRequiredText() {
        val row = """{"start_time":"2026-10-10T00:00:01Z","end_time":"2026-10-10T00:00:02Z","canceled_until":null}"""
        fun body(count: Int) = """{"data":{"broadcaster_id":"123","segments":[${List(count) { row }.joinToString(",")}],"vacation":null},"pagination":{}}"""
        assertEquals(25, (((twitchHelixResponseFields(body(25))["data"] as Map<*, *>)["segments"]) as List<*>).size)
        assertInvalid { twitchHelixResponseFields(body(26)) }
        assertInvalid { twitchHelixResponseFields("""{"data":{"broadcaster_id":"${"x".repeat(65)}","segments":[],"vacation":null}}""") }
        assertInvalid { twitchHelixResponseFields(body(1).replace("2026-10-10T00:00:01Z", "x".repeat(65))) }
    }

    private fun assertInvalid(action: () -> Unit) {
        val error = assertThrows(TwitchHelixException::class.java) { action() }
        assertEquals(TwitchHelixFailure.INVALID_RESPONSE, error.failure)
        assertNull(error.cause)
        assertFalse(error.toString().contains("private"))
    }
}
