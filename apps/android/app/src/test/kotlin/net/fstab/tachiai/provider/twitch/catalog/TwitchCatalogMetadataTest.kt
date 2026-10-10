package net.fstab.tachiai.provider.twitch.catalog

import org.junit.Assert.*
import org.junit.Test

class TwitchCatalogMetadataTest {
    private fun response(vararg rows: Map<String, Any?>, pagination: Map<String, Any?> = emptyMap()) =
        TwitchHelixResponse(200, mapOf("data" to rows.toList(), "pagination" to pagination))
    private fun user(id: String = "123", login: String = "sumo") = mapOf("id" to id, "login" to login, "display_name" to "Sumo")
    private fun video(id: String = "456") = mapOf("id" to id, "user_id" to "123", "title" to "Day one", "type" to "archive")

    @Test fun FollowingContainsOfflineCapableIdentitiesWithoutGuessingLiveState() {
        val following = parseTwitchFollowing(response(mapOf("broadcaster_id" to "123", "broadcaster_login" to "sumo", "broadcaster_name" to "Sumo")))
        val broadcaster = following.items.single()
        assertEquals("123", broadcaster.id); assertEquals("sumo", broadcaster.login); assertNull(broadcaster.live)
        assertEquals("123", parseTwitchUsers(response(user())).items.single().id)
        val renamed = parseTwitchUsers(response(user(login = "renamed"))).items.single()
        val recycled = parseTwitchUsers(response(user(id = "999"))).items.single()
        assertEquals(broadcaster.id, renamed.id); assertNotEquals(broadcaster.id, recycled.id)
    }

    @Test fun SearchAndStreamMetadataKeepBroadcastIdentitySeparateFromChannelIdentity() {
        val offline = parseTwitchSearch(response(mapOf("id" to "123", "broadcaster_login" to "sumo",
            "display_name" to "Sumo", "is_live" to false))).items.single()
        assertEquals(false, offline.live)
        val stream = parseTwitchStreams(response(mapOf("id" to "789", "user_id" to "123", "user_login" to "sumo",
            "user_name" to "Sumo", "type" to "live"))).items.single()
        assertEquals("789", stream.broadcastId); assertEquals(offline.id, stream.broadcaster.id)
        assertEquals(true, stream.broadcaster.live)
    }

    @Test fun PublishedVideoIdentityDoesNotBecomeItsBroadcasterOrAnotherReplay() {
        val videos = parseTwitchVideos(response(video(), video("457"))).items
        assertEquals(listOf("456", "457"), videos.map { it.id })
        assertEquals(listOf("123", "123"), videos.map { it.broadcasterId })
        assertEquals("Day one", videos.first().title)
        assertTrue(parseTwitchVideos(response()).items.isEmpty()) // Missing/deleted exact video is a valid empty response.
        listOf("archive", "highlight", "upload").forEach { type ->
            assertEquals("456", parseTwitchVideos(response(video() + ("type" to type))).items.single().id)
        }
    }

    @Test fun EmptyContinuationAndDuplicateRowsArePreservedForBoundedAdapterPaging() {
        val cursor = "fixture-cursor/&+=?"
        val empty = parseTwitchFollowing(response(pagination = mapOf("cursor" to cursor)))
        assertTrue(empty.items.isEmpty()); assertEquals(cursor, empty.nextCursor)
        val repeated = parseTwitchUsers(response(user(), user()))
        assertEquals(listOf("123", "123"), repeated.items.map { it.id })
        assertFalse(empty.toString().contains(cursor)); assertFalse(repeated.toString().contains("sumo"))
        val mutable = mutableListOf(repeated.items.first())
        val copy = TwitchHelixPage(mutable)
        mutable.clear(); assertEquals(1, copy.items.size)
    }

    @Test fun WrongTypesInvalidIdsAndUnboundedRowsOrCursorsFailWithoutRawDetails() {
        val invalidRows = listOf(user() - "id", user() + ("id" to 123), user() + ("id" to "00123"),
            user() + ("id" to "a".repeat(33)), user() + ("login" to "Sumo"), user() + ("display_name" to " private "),
            user() + ("display_name" to "private\n"), user() + ("display_name" to "private\u200b"),
            user() + ("display_name" to "private".repeat(30)))
        invalidRows.forEach { assertInvalid { parseTwitchUsers(response(it)) } }
        listOf(null, "private", 123, List(101) { user() }, listOf("private"), listOf(null)).forEach { rows ->
            assertInvalid { parseTwitchUsers(TwitchHelixResponse(200, mapOf("data" to rows))) }
        }
        listOf(null, "private", mapOf("cursor" to null), mapOf("cursor" to 1), mapOf("cursor" to ""),
            mapOf("cursor" to "a".repeat(2049)), mapOf("cursor" to "private\n")).forEach { pagination ->
            assertInvalid { parseTwitchUsers(TwitchHelixResponse(200, mapOf("data" to emptyList<Any>(), "pagination" to pagination))) }
        }
        assertInvalid { parseTwitchSearch(response(mapOf("id" to "123", "broadcaster_login" to "sumo", "display_name" to "Sumo", "is_live" to "false"))) }
        assertInvalid { parseTwitchVideos(response(video() + ("type" to "private"))) }
        assertInvalid { parseTwitchStreams(response(mapOf("id" to "789", "user_id" to "123", "user_login" to "sumo", "user_name" to "Sumo", "type" to ""))) }
        assertInvalid { parseTwitchUsers(TwitchHelixResponse(401)) }
    }

    @Test fun AllWorkerModelsAndResponsesHaveRedactedDescriptions() {
        val broadcaster = TwitchCatalogBroadcaster("987654321", "fixturealias", "Fixture title", true)
        listOf(broadcaster, TwitchCatalogLiveStream("876543210", broadcaster), TwitchCatalogVideo("765432109", "987654321", "Fixture title"),
            TwitchHelixResponse(200, mapOf("private" to "fixture-private"))).forEach { value ->
            listOf("987654321", "fixturealias", "Fixture title", "fixture-private").forEach { assertFalse(value.toString().contains(it)) }
        }
    }

    private fun assertInvalid(action: () -> Unit) {
        val error = assertThrows(TwitchHelixException::class.java) { action() }
        assertEquals(TwitchHelixFailure.INVALID_RESPONSE, error.failure)
        assertNull(error.cause); assertFalse(error.toString().contains("private"))
    }
}
