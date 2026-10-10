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
}
