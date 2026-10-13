package net.fstab.tachiai.provider.abema

import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test

// Test APK only: a lead from the displayed prefix, never entitlement or a
// playback result. The production decoder receives only its normal projection.
internal object AbemaScheduleCatchupCandidateObservation {
    const val MARKER = "tachiaiTestCatchupCandidate"
    const val ANCHOR = AbemaScheduleShapeObservation.ANCHOR
    private const val MARKER_BYTES = 1024
    val TEST_SCHEDULE_PROJECTION = """
        const tachiaiCatchup = (() => {
          const clock = __TACHIAI_TEST_CATCHUP_NOW_MS__;
          if (!Number.isSafeInteger(clock) || clock < 0 || clock > 8640000000000000) throw new Error();
          const record = value => !!value && typeof value === 'object' && !Array.isArray(value);
          const seconds = value => Number.isSafeInteger(value) && value > 0 && value < 100000000000;
          // Test-only lead headroom for route/bootstrap preparation. Production
          // playback independently retains its fresh five-minute window guard.
          const future = value => seconds(value) && value * 1000 > clock + 600000;
          let eligibleRows = 0, candidate = null;
          for (const row of reply.slots.slice(0, 500)) {
            if (!record(row.flags) || row.flags.timeshift !== true || row.flags.timeshiftFree !== true ||
              !seconds(row.startAt) || !seconds(row.endAt) || row.startAt >= row.endAt || row.endAt * 1000 > clock ||
              !future(row.timeshiftEndAt) ||
              Object.hasOwn(row, 'timeshiftFreeEndAt') && row.timeshiftFreeEndAt !== 0 && !future(row.timeshiftFreeEndAt)) continue;
            eligibleRows++;
            if (candidate === null) candidate = Object.freeze({ channelId: row.channelId, slotId: row.id });
          }
          return Object.freeze({ version: 1, eligibleRows, candidate });
        })();
        return Object.freeze({ slots, truncated: reply.slots.length > slots.length,
          tachiaiTestCatchupCandidate: tachiaiCatchup });
    """.trimIndent()

    fun projection(wallMs: Long): String {
        require(wallMs in 0..8_640_000_000_000_000L)
        return TEST_SCHEDULE_PROJECTION.replace("__TACHIAI_TEST_CATCHUP_NOW_MS__", wallMs.toString())
    }
    class Stripped(val raw: String, val eligibleRows: Int, val candidate: AbemaSlotRequest?) {
        override fun toString() = "AbemaScheduleCatchupCandidateObservation.Stripped(redacted)"
    }
    private fun keys(value: JSONObject, expected: Set<String>) = check(value.keys().asSequence().toSet() == expected)
    private fun integer(value: Any?, range: IntRange): Int {
        val number = when (value) { is Int -> value.toLong(); is Long -> value; else -> error("Count refused") }
        check(number in range.first.toLong()..range.last.toLong()); return number.toInt()
    }
    fun strip(raw: String?): Stripped? = runCatching {
        val maximum = ABEMA_SCHEDULE_PROJECTION_BYTES + MARKER_BYTES
        val encoded = raw?.takeIf { it.length <= maximum * 2 } ?: return null
        checkAbemaCollectionJson(encoded)
        val outer = JSONTokener(encoded); val text = outer.nextValue() as? String ?: return null
        check(outer.nextClean() == '\u0000' && text.toByteArray(Charsets.UTF_8).size <= maximum)
        checkAbemaCollectionJson(text)
        val inner = JSONTokener(text); val value = inner.nextValue() as? JSONObject ?: return null
        check(inner.nextClean() == '\u0000'); keys(value, setOf("slots", "truncated", MARKER))
        val marker = value.remove(MARKER) as? JSONObject ?: return null
        check(marker.toString().toByteArray(Charsets.UTF_8).size <= MARKER_BYTES)
        keys(marker, setOf("version", "eligibleRows", "candidate"))
        check(integer(marker.opt("version"), 1..1) == 1)
        val count = integer(marker.opt("eligibleRows"), 0..500)
        val normal = value.toString()
        val snapshot = decodeAbemaScheduleProjection(normal) ?: return null
        val displayed = snapshot.entries.take(500)
        check(count <= displayed.size)
        val candidate = if (marker.opt("candidate") === JSONObject.NULL) {
            check(count == 0); null
        } else {
            val lead = marker.opt("candidate") as? JSONObject ?: return null
            keys(lead, setOf("channelId", "slotId"))
            val channel = lead.opt("channelId") as? String ?: return null
            val slot = lead.opt("slotId") as? String ?: return null
            if (!AbemaSlotRequest.validId(channel) || !AbemaSlotRequest.validId(slot)) return null
            val request = AbemaSlotRequest(channel, slot)
            val index = displayed.indexOfFirst { it.resource == request.resource }
            // FIRST lead: rows preceding it cannot contribute to the count.
            check(index >= 0 && count in 1..(displayed.size - index))
            request
        }
        Stripped(JSONObject.quote(normal), count, candidate)
    }.getOrNull()
}

class AbemaScheduleCatchupCandidateObservationTest {
    private fun projection(rows: Int = 1, count: Int = 1, candidateIndex: Int? = 0): JSONObject = JSONObject()
        .put("slots", org.json.JSONArray().apply { repeat(rows) { put(JSONObject().put("id", "slot-$it")
            .put("channelId", "channel-A").put("title", "Fixture")) } })
        .put("truncated", false).put(AbemaScheduleCatchupCandidateObservation.MARKER, JSONObject().put("version", 1)
            .put("eligibleRows", count).put("candidate", candidateIndex?.let { JSONObject().put("channelId", "channel-A")
                .put("slotId", "slot-$it") } ?: JSONObject.NULL))
    private fun strip(value: JSONObject) = AbemaScheduleCatchupCandidateObservation.strip(JSONObject.quote(value.toString()))
    @Test fun stripsOneLeadOrHonestAbsenceWithoutChangingNormalProjection() {
        for (rows in listOf(0, 1, 500, 501)) {
            val value = projection(rows, if (rows == 0) 0 else 1, if (rows == 0) null else 0)
            val result = checkNotNull(strip(value))
            assertEquals(if (rows == 0) 0 else 1, result.eligibleRows)
            assertEquals(if (rows == 0) null else "channel-A/slot-0", result.candidate?.resource?.identity)
            assertFalse(result.raw.contains(AbemaScheduleCatchupCandidateObservation.MARKER))
            val decoded = checkNotNull(decodeAbemaScheduleProjection(JSONTokener(result.raw).nextValue() as String))
            assertEquals(rows, decoded.entries.size); assertFalse(decoded.truncated)
            assertFalse(result.toString().contains("channel-A"))
        }
        assertNotNull(strip(projection(3, 2, 1)))
    }
    @Test fun rejectsUnknownTypesImpossibleCountsMissingLeadAndOutsideDisplayedPrefix() {
        for (value in listOf(projection(0), projection(1, 0), projection(1, 1, null), projection(1, 2),
            projection(501, 1, 500), projection(3, 3, 1), projection(502, 1, 0))) assertNull(strip(value))
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("extra", true) }, { it.put("version", 2) }, { it.put("eligibleRows", 1.5) },
            { it.put("eligibleRows", -1) }, { it.put("eligibleRows", 501) }, { it.remove("candidate") },
            { it.put("candidate", "private") }, { it.put("candidate", org.json.JSONArray()) },
            { it.getJSONObject("candidate").put("extra", true) },
            { it.getJSONObject("candidate").put("channelId", "other") },
            { it.getJSONObject("candidate").put("slotId", "bad/id") },
            { it.getJSONObject("candidate").put("slotId", "A".repeat(65)) },
        )
        for (mutate in mutations) { val value = projection(); mutate(value.getJSONObject(AbemaScheduleCatchupCandidateObservation.MARKER)); assertNull(strip(value)) }
        assertNull(strip(projection().put("extra", true)))
    }
    @Test fun rejectsLiteralEscapedDuplicatesTrailingInputAndMalformedNormalRows() {
        val valid = projection().toString()
        for (text in listOf(valid + " trailing", valid.replace("\"version\":1", "\"version\":1,\"version\":1"),
            valid.replace("\"version\":1", "\"version\":1,\"ver\\u0073ion\":1"),
            valid.replace("\"eligibleRows\":1", "\"eligibleRows\":1,\"eligibleRows\":1"),
            valid.replace("\"candidate\":", "\"candidate\":null,\"candidate\":"),
            valid.replace("\"slotId\":", "\"slotId\":\"slot-0\",\"slot\\u0049d\":"),
            valid.replace("\"slots\":", "\"slots\":[],\"slots\":")))
            assertNull(AbemaScheduleCatchupCandidateObservation.strip(JSONObject.quote(text)))
        assertNull(AbemaScheduleCatchupCandidateObservation.strip(JSONObject.quote(valid) + " trailing"))
        assertNull(strip(projection().apply { getJSONArray("slots").getJSONObject(0).put("id", "wrong") }))
        assertNull(strip(projection().apply { getJSONArray("slots").getJSONObject(0).put("title", " bad ") }))
    }
    @Test fun capturedClockBoundsAreExplicitAndHaveNoProviderSideEffects() {
        for (clock in listOf(0L, 8_640_000_000_000_000L))
            assertFalse(AbemaScheduleCatchupCandidateObservation.projection(clock).contains("__TACHIAI_TEST_CATCHUP_NOW_MS__"))
        for (clock in listOf(-1L, 8_640_000_000_000_001L))
            assertThrows(IllegalArgumentException::class.java) { AbemaScheduleCatchupCandidateObservation.projection(clock) }
    }
}
