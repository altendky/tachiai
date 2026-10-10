package net.fstab.tachiai.provider.twitch.catalog

import java.time.Instant
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

    private val scheduleNow = Instant.parse("2026-10-10T00:00:00Z").toEpochMilli()
    private fun time(deltaMs: Long) = Instant.ofEpochMilli(scheduleNow + deltaMs).toString()
    private fun segment(startMs: Long, endMs: Long = startMs + 1_000, canceled: Any? = null): Map<String, Any?> =
        mapOf("start_time" to time(startMs), "end_time" to time(endMs), "canceled_until" to canceled)
    private fun schedule(vararg segments: Map<String, Any?>, vacation: Any? = null, owner: Any? = "123") =
        TwitchHelixResponse(200, mapOf("data" to mapOf("broadcaster_id" to owner,
            "segments" to segments.toList(), "vacation" to vacation), "pagination" to emptyMap<String, Any?>()))
    private fun scheduled(response: TwitchHelixResponse, now: Long = scheduleNow) = parseTwitchScheduleStart(response, "123", now)

    @Test fun ScheduleSelectsMinimumEligibleStartWithoutAssumingRowOrder() {
        val response = schedule(segment(20_000), segment(5_000), segment(10_000), segment(5_000))
        assertEquals(scheduleNow + 5_000, scheduled(response))
        // Unneeded segment metadata cannot supply a source identity or alter the date.
        assertEquals(scheduleNow + 5_000, scheduled(schedule(segment(5_000) + mapOf(
            "id" to "opaque-schedule-id", "title" to "Different broadcast", "video_id" to "999"))))
    }

    @Test fun ScheduleUsesFreshClockAndExcludesPastAndExactNow() {
        assertNull(scheduled(schedule(segment(-1_000), segment(0))))
        val response = schedule(segment(1), segment(2_000))
        assertEquals(scheduleNow + 1, scheduled(response))
        assertEquals(scheduleNow + 2_000, scheduled(response, scheduleNow + 1))
        assertNull(scheduled(response, scheduleNow + 2_000))
    }

    @Test fun AnyWellFormedCancellationSuppressesOccurrenceRegardlessOfCutoff() {
        for (cancellation in listOf(time(-10_000), time(1_000), time(6_000), time(20_000))) {
            assertNull(scheduled(schedule(segment(5_000, 6_000, cancellation))))
        }
        assertEquals(scheduleNow + 10_000, scheduled(schedule(segment(5_000, canceled = time(6_000)), segment(10_000))))
        listOf<Any>(false, 0, "", "private", "2026-10-10T00:00:60Z").forEach {
            assertInvalid { scheduled(schedule(segment(5_000, canceled = it))) }
        }
    }

    @Test fun VacationSuppressesEveryIntervalOverlapButNotTouchingBoundaries() {
        val vacation = mapOf("start_time" to time(10_000), "end_time" to time(20_000))
        for ((start, end) in listOf(5_000L to 15_000L, 15_000L to 25_000L, 12_000L to 18_000L,
            5_000L to 25_000L, 10_000L to 20_000L)) {
            assertNull(scheduled(schedule(segment(start, end), vacation = vacation)))
        }
        assertEquals(scheduleNow + 5_000, scheduled(schedule(segment(5_000, 10_000), vacation = vacation)))
        assertEquals(scheduleNow + 20_000, scheduled(schedule(segment(20_000, 25_000), vacation = vacation)))
        assertEquals(scheduleNow + 25_000, scheduled(schedule(segment(12_000, 18_000), segment(25_000), vacation = vacation)))
    }

    @Test fun NoScheduleAndValidEmptyOrFilteredPageAreAbsentContext() {
        assertNull(scheduled(TwitchHelixResponse(404, mapOf("data" to "private error"))))
        assertNull(scheduled(schedule()))
        val empty = schedule()
        assertNull(scheduled(TwitchHelixResponse(200, empty.fields - "pagination")))
        assertNull(scheduled(TwitchHelixResponse(200, empty.fields + ("pagination" to mapOf("cursor" to "fixture-more")))))
        assertNull(scheduled(schedule(segment(1_000, canceled = time(2_000)))))
    }

    @Test fun ScheduleRejectsOtherStatusesRatherThanClaimingNoSchedule() {
        for (status in listOf(201, 400, 401, 403, 429, 500, 503)) {
            assertInvalid { scheduled(TwitchHelixResponse(status)) }
        }
    }

    @Test fun ScheduleRequiresExactCanonicalBroadcasterAndBoundedClock() {
        listOf<Any?>(null, 123, "00123", "124", "private", "1".repeat(33)).forEach {
            assertInvalid { scheduled(schedule(segment(1_000), owner = it)) }
        }
        assertInvalid { parseTwitchScheduleStart(schedule(), "00123", scheduleNow) }
        assertInvalid { scheduled(schedule(), -1) }
        assertInvalid { scheduled(schedule(), 253402300800000L) }
    }

    @Test fun ScheduleRequiresExplicitNullableFieldsAndIncreasingVacationBounds() {
        val data = schedule(segment(1_000)).fields["data"] as Map<*, *>
        assertInvalid { scheduled(TwitchHelixResponse(200, mapOf("data" to data - "vacation"))) }
        assertInvalid { scheduled(schedule(segment(1_000) - "canceled_until")) }
        for (vacation in listOf(false, "private", emptyMap<String, Any?>(),
            mapOf("start_time" to time(1_000)), mapOf("start_time" to time(1_000), "end_time" to null),
            mapOf("start_time" to time(1_000), "end_time" to time(1_000)),
            mapOf("start_time" to time(2_000), "end_time" to time(1_000)))) {
            assertInvalid { scheduled(schedule(vacation = vacation)) }
        }
        assertEquals(scheduleNow + 1_000, scheduled(schedule(segment(1_000), vacation = null)))
    }

    @Test fun ScheduleRejectsWrongShapesAndValidatesRowsAfterAnEligibleOccurrence() {
        for (data in listOf(null, emptyList<Any>(), "private", mapOf("broadcaster_id" to "123", "vacation" to null),
            mapOf("broadcaster_id" to "123", "vacation" to null, "segments" to "private"),
            mapOf("broadcaster_id" to "123", "vacation" to null, "segments" to listOf(null)),
            mapOf("broadcaster_id" to "123", "vacation" to null, "segments" to listOf(mapOf(1 to "private"))))) {
            assertInvalid { scheduled(TwitchHelixResponse(200, mapOf("data" to data))) }
        }
        assertInvalid { scheduled(schedule(segment(1_000), segment(10_000, 9_000))) }
        assertInvalid { scheduled(schedule(segment(1_000), segment(10_000) + ("start_time" to "private"))) }
    }

    @Test fun ScheduleTimestampsRequireStrictCalendarAndBoundedRfc3339Shape() {
        val invalid = listOf<Any?>(null, 123, "2026-10-10", "2026-10-10T00:00Z", "2026-10-10T00:00:01",
            "2026-02-30T00:00:01Z", "2026-13-10T00:00:01Z", "2026-10-10T24:00:01Z",
            "2026-10-10T00:60:01Z", "2026-10-10T00:00:60Z", "2026-10-10T00:00:01+24:00",
            "2026-10-10T00:00:01.1234567890Z", "+10000-10-10T00:00:01Z", "1969-12-31T23:59:59Z",
            "9999-12-31T23:59:59-01:00", " 2026-10-10T00:00:01Z", "2026-10-10T00:00:01Z\n")
        invalid.forEach { value ->
            assertInvalid { scheduled(schedule(segment(1_000) + ("start_time" to value))) }
            assertInvalid { scheduled(schedule(segment(1_000) + ("end_time" to value))) }
        }
        assertInvalid { scheduled(schedule(segment(1_000, 1_000))) }
    }

    @Test fun ScheduleNormalizesOffsetsAndTruncatesFractionalPrecisionToMilliseconds() {
        val offset = segment(1_000) + mapOf("start_time" to "2026-10-10T09:00:01+09:00",
            "end_time" to "2026-10-10T09:00:02+09:00")
        assertEquals(scheduleNow + 1_000, scheduled(schedule(offset)))
        val fractional = segment(1_000) + mapOf("start_time" to "2026-10-10T00:00:00.001999999Z",
            "end_time" to "2026-10-10T00:00:01.000000001Z")
        assertEquals(scheduleNow + 1, scheduled(schedule(fractional)))
        assertNull(scheduled(schedule(fractional), scheduleNow + 1))
    }

    @Test fun ScheduleBoundsPageAndValidatesUnfollowedTopLevelPagination() {
        assertEquals(scheduleNow + 1_000, scheduled(schedule(*Array(25) { segment(1_000 + it * 1_000L) })))
        assertInvalid { scheduled(schedule(*Array(26) { segment(1_000) })) }
        val response = schedule(segment(1_000))
        for (pagination in listOf(null, "private", mapOf("cursor" to null), mapOf("cursor" to 1),
            mapOf("cursor" to ""), mapOf("cursor" to "private\n"), mapOf("cursor" to "a".repeat(2049)))) {
            assertInvalid { scheduled(TwitchHelixResponse(200, response.fields + ("pagination" to pagination))) }
        }
        assertEquals(scheduleNow + 1_000, scheduled(TwitchHelixResponse(200,
            response.fields + ("pagination" to mapOf("cursor" to "fixture-more")))))
    }

    @Test fun ScheduleObjectDoesNotRelaxExistingArrayEndpointContracts() {
        assertInvalid { parseTwitchUsers(schedule(segment(1_000))) }
        assertInvalid { parseTwitchVideos(schedule(segment(1_000))) }
        assertInvalid { scheduled(response(user())) }
    }

    private fun assertInvalid(action: () -> Unit) {
        val error = assertThrows(TwitchHelixException::class.java) { action() }
        assertEquals(TwitchHelixFailure.INVALID_RESPONSE, error.failure)
        assertNull(error.cause); assertFalse(error.toString().contains("private"))
    }
}
