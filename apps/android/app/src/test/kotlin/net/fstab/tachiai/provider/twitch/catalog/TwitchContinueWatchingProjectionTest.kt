package net.fstab.tachiai.provider.twitch.catalog

import java.time.Instant
import net.fstab.tachiai.provider.catalog.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TwitchContinueWatchingProjectionTest {
    private val now = 2_000_000_000_000L
    private fun edge(id: String = "123", position: String = "95", duration: String = "100",
        updated: String = "\"${Instant.ofEpochMilli(now)}\"", owner: String = "\"42\"", login: String = "\"example\"") =
        """{"history":{"position":$position,"updatedAt":$updated},"node":{"id":"$id","title":"Video $id","lengthSeconds":$duration,"owner":{"id":$owner,"login":$login}}}"""
    private fun body(edges: String = "", user: String = "9000") =
        """{"data":{"currentUser":{"id":"$user","viewedVideos":{"edges":[$edges]}}}}"""
    private fun failure(text: String, expected: TwitchContinueWatchingFailure = TwitchContinueWatchingFailure.INVALID_RESPONSE) {
        assertEquals(expected, assertThrows(TwitchContinueWatchingException::class.java) {
            parseTwitchContinueWatching(text, "9000", now)
        }.category)
    }
    @Test fun sourceFilterIncludesExactlyNinetyFivePercentAndFourteenDayBoundaryInProviderOrder() {
        val boundary = now - 14 * 24 * 60 * 60 * 1000L
        val result = parseTwitchContinueWatching(body(listOf(edge("3", updated = "$boundary"),
            edge("2", position = "95.001"), edge("1", updated = "${boundary - 1}"),
            edge("4", position = "0"), edge("5", duration = "0"), edge("6", position = "1.5")).joinToString(",")), "9000", now)
        assertEquals(listOf("3", "6"), result.entries.map { it.resource.identity })
        assertTrue(result.entries.all { it.resource.kind == "video" && it.resource.intent == CatalogIntent.VIDEO &&
            it.availability == CatalogAvailability.UNKNOWN && it.scheduledStartEpochMs == null })
        assertFalse(result.toString().contains("9000"))
        assertFalse(result.toString().contains("position"))
    }
    @Test fun emptyIsValidOnlyWithExactIntendedAccountAndCompleteCollectionShape() {
        assertTrue(parseTwitchContinueWatching(body(), "9000", now).entries.isEmpty())
        failure(body(user = "9001"), TwitchContinueWatchingFailure.USER_MISMATCH)
        for (text in listOf("""{"data":{"currentUser":null}}""", """{"data":{"currentUser":{"id":"9000"}}}""",
            """{"data":{"currentUser":{"id":"9000","viewedVideos":null}}}""", body().replace("[]", "null"),
            body().dropLast(1) + """, "errors":[{"message":"private detail"}]}""",
            body().dropLast(1) + """, "errors":null}""")) failure(text)
        assertTrue(parseTwitchContinueWatching(body().dropLast(1) + """, "errors":[]}""", "9000", now).entries.isEmpty())
    }
    @Test fun strictTypesIdentityJoinsAndDuplicateMembersRejectMalformedPrivatePages() {
        for (row in listOf(edge(position = "\"95\""), edge(duration = "null"), edge(updated = "1.5"),
            edge(updated = "\"yesterday\""), edge(owner = "42"), edge(login = "\"bad login\""),
            edge(id = "01"), edge(id = "1/2"), edge().replace("\"Video 123\"", "\"\\u0000title\""),
            edge().replace("\"id\":\"123\"", "\"id\":\"123\",\"id\":\"456\""),
            edge().replace("\"title\":\"Video 123\"", "\"title\":\"\\ud800\""))) failure(body(row))
        failure(body("${edge()},${edge()}"))
        failure(body().replace("\"currentUser\":", "\"currentUser\":{},\"currentUser\":"))
        failure(body().replace("\"data\"", "'data'"))
        failure(body() + "{}")
        failure(body(edge(position = "1e309")))
    }
    @Test fun pageWorkIsBoundedBeforePermissivePlatformDecoder() {
        failure(body((1..21).joinToString(",") { edge("$it") }), TwitchContinueWatchingFailure.RESPONSE_LIMIT)
        failure(" ".repeat(TWITCH_CONTINUE_WATCHING_BYTES + 1), TwitchContinueWatchingFailure.RESPONSE_LIMIT)
        failure(body().dropLast(1) + ",\"ignored\":" + "[".repeat(18) + "0" + "]".repeat(18) + "}", TwitchContinueWatchingFailure.RESPONSE_LIMIT)
        failure(body().dropLast(1) + ",\"ignored\":\"" + "x".repeat(4097) + "\"}", TwitchContinueWatchingFailure.RESPONSE_LIMIT)
    }
    @Test fun fixedOperationRequestsOnlyKnownIdentityAndProgressFieldsWithTwentyFirstEdges() {
        val request = JSONObject(String(twitchContinueWatchingRequestBody, Charsets.UTF_8))
        assertEquals("FollowedStreamsContinueWatching", request.getString("operationName"))
        assertEquals(20, request.getJSONObject("variables").getInt("limit"))
        val query = request.getString("query")
        assertTrue(query.contains("currentUser { id viewedVideos(first: \$limit)"))
        assertTrue(query.contains("history { position updatedAt }"))
        assertTrue(query.contains("owner { id login }"))
        for (field in listOf("cursor", "pageInfo", "accessToken", "mutation", "self", "thumbnail")) assertFalse(query.contains(field))
    }
}
